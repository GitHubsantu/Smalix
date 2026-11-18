package com.imdevops.smalix

import com.imdevops.smalix.core.model.AnalysisFailure
import com.imdevops.smalix.core.model.AnalysisRequest
import com.imdevops.smalix.core.model.InspectionResult
import com.imdevops.smalix.core.model.ProjectAnalysisStatus
import com.imdevops.smalix.core.model.ProjectId
import com.imdevops.smalix.core.model.RelativePath
import com.imdevops.smalix.core.model.SmaliDraft
import com.imdevops.smalix.core.model.SmaliDraftMetadata
import com.imdevops.smalix.core.model.SmaliDraftStatus
import com.imdevops.smalix.core.model.SmaliValidation
import com.imdevops.smalix.core.model.Workspace
import com.imdevops.smalix.core.model.ZipSafetyLimits
import com.imdevops.smalix.engine.api.ProjectRepository
import com.imdevops.smalix.engine.api.SmaliDraftRepository
import com.imdevops.smalix.engine.api.SmaliDraftStore
import com.imdevops.smalix.engine.api.SmaliDraftValidator
import com.imdevops.smalix.engine.api.WorkspaceStore
import java.time.Instant
import java.util.UUID

sealed interface SmaliDraftSessionResult {
    data class Ready(val draft: SmaliDraft, val validation: SmaliValidation) : SmaliDraftSessionResult
    data class InspectionFailed(val error: AnalysisFailure) : SmaliDraftSessionResult
    data object ProjectNotFound : SmaliDraftSessionResult
    data object ProjectNotReady : SmaliDraftSessionResult
    data class WorkspaceUnavailable(val message: String) : SmaliDraftSessionResult
    data class StaleDraft(val message: String) : SmaliDraftSessionResult
}

sealed interface SmaliDraftSaveResult {
    data class Saved(val draft: SmaliDraft, val validation: SmaliValidation) : SmaliDraftSaveResult
    data class Rejected(val validation: SmaliValidation) : SmaliDraftSaveResult
    data class Unavailable(val result: SmaliDraftSessionResult) : SmaliDraftSaveResult
}

/** Coordinates staged Smali text only. It never writes DEX entries or an APK. */
class SmaliDraftUseCase(
    private val projects: ProjectRepository,
    private val workspaces: WorkspaceStore,
    private val drafts: SmaliDraftStore,
    private val metadata: SmaliDraftRepository,
    private val inspection: InspectionUseCase,
    private val validator: SmaliDraftValidator,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun openOrCreate(
        projectId: ProjectId,
        proposed: SmaliDraft,
        deadline: Instant,
        limits: ZipSafetyLimits = ZipSafetyLimits(),
    ): SmaliDraftSessionResult {
        val project = projects.find(projectId) ?: return SmaliDraftSessionResult.ProjectNotFound
        val sha = project.originalSha256 ?: return SmaliDraftSessionResult.ProjectNotReady
        if (project.analysisStatus != ProjectAnalysisStatus.ANALYZED || proposed.projectId != projectId ||
            !proposed.originalApkSha256.equals(sha, ignoreCase = true)
        ) return SmaliDraftSessionResult.StaleDraft("Smali draft does not match the inspected original APK.")
        val workspace = openWorkspace(projectId) ?: return SmaliDraftSessionResult.WorkspaceUnavailable("Project workspace is unavailable.")
        when (val result = inspection.inspect(AnalysisRequest(projectId, workspaces.originalApk(workspace), deadline, limits))) {
            is InspectionResult.Failure -> return SmaliDraftSessionResult.InspectionFailed(result.error)
            is InspectionResult.Success -> if (!result.value.apk.archive.sha256.equals(sha, ignoreCase = true)) {
                return SmaliDraftSessionResult.StaleDraft("Original APK SHA-256 changed; re-inspect before opening a Smali draft.")
            }
        }
        openExisting(projectId, workspace, sha, proposed.id)?.let { return it }
        val textDraft = try { drafts.load(workspace, proposed.id, sha) } catch (error: IllegalArgumentException) {
            return SmaliDraftSessionResult.StaleDraft(error.message ?: "Staged Smali draft is unavailable.")
        }
        if (textDraft != null || metadata.findSmaliDraft(proposed.id) != null) {
            return SmaliDraftSessionResult.StaleDraft("Smali draft metadata does not match the inspected original APK.")
        }
        return save(workspace, proposed)
    }

    suspend fun discard(projectId: ProjectId, draftId: UUID): SmaliDraftSessionResult {
        val project = projects.find(projectId) ?: return SmaliDraftSessionResult.ProjectNotFound
        val sha = project.originalSha256 ?: return SmaliDraftSessionResult.ProjectNotReady
        val workspace = openWorkspace(projectId) ?: return SmaliDraftSessionResult.WorkspaceUnavailable("Project workspace is unavailable.")
        val ready = openExisting(projectId, workspace, sha, draftId)
            ?: return SmaliDraftSessionResult.StaleDraft("No matching staged Smali draft exists.")
        drafts.discard(workspace, ready.draft.id)
        metadata.deleteSmaliDraft(ready.draft.id)
        return ready
    }

    suspend fun saveText(
        projectId: ProjectId,
        draftId: UUID,
        text: String,
        deadline: Instant,
        limits: ZipSafetyLimits = ZipSafetyLimits(),
    ): SmaliDraftSaveResult {
        val project = projects.find(projectId) ?: return SmaliDraftSaveResult.Unavailable(SmaliDraftSessionResult.ProjectNotFound)
        val sha = project.originalSha256 ?: return SmaliDraftSaveResult.Unavailable(SmaliDraftSessionResult.ProjectNotReady)
        if (project.analysisStatus != ProjectAnalysisStatus.ANALYZED) return SmaliDraftSaveResult.Unavailable(SmaliDraftSessionResult.ProjectNotReady)
        val workspace = openWorkspace(projectId) ?: return SmaliDraftSaveResult.Unavailable(SmaliDraftSessionResult.WorkspaceUnavailable("Project workspace is unavailable."))
        when (val result = inspection.inspect(AnalysisRequest(projectId, workspaces.originalApk(workspace), deadline, limits))) {
            is InspectionResult.Failure -> return SmaliDraftSaveResult.Unavailable(SmaliDraftSessionResult.InspectionFailed(result.error))
            is InspectionResult.Success -> if (!result.value.apk.archive.sha256.equals(sha, ignoreCase = true)) {
                return SmaliDraftSaveResult.Unavailable(SmaliDraftSessionResult.StaleDraft("Original APK SHA-256 changed; re-inspect before saving a Smali draft."))
            }
        }
        val current = openExisting(projectId, workspace, sha, draftId)
            ?: return SmaliDraftSaveResult.Unavailable(SmaliDraftSessionResult.StaleDraft("No matching staged Smali draft exists."))
        val revised = current.draft.copy(revision = current.draft.revision + 1, text = text)
        val validation = validator.validate(revised)
        if (!validation.isValid) return SmaliDraftSaveResult.Rejected(validation)
        return try {
            save(workspace, revised)
            SmaliDraftSaveResult.Saved(revised, validation)
        } catch (error: Exception) {
            SmaliDraftSaveResult.Unavailable(SmaliDraftSessionResult.StaleDraft("Staged text was saved but metadata could not be reconciled: ${error.message ?: "unknown error"}"))
        }
    }

    private suspend fun openExisting(projectId: ProjectId, workspace: Workspace, sha: String, draftId: UUID): SmaliDraftSessionResult.Ready? {
        val draft = try { drafts.load(workspace, draftId, sha) } catch (_: IllegalArgumentException) { return null }
        val stored = metadata.findSmaliDraft(draftId)
        return if (draft != null && stored != null && stored.projectId == projectId && stored.draftId == draft.id &&
            stored.originalApkSha256.equals(sha, ignoreCase = true) && stored.revision == draft.revision &&
            stored.relativePath == draftPath(projectId, draftId)
        ) SmaliDraftSessionResult.Ready(draft, validator.validate(draft)) else null
    }

    private suspend fun save(workspace: Workspace, draft: SmaliDraft): SmaliDraftSessionResult.Ready {
        val validation = validator.validate(draft)
        drafts.save(workspace, draft)
        val timestamp = now()
        val existing = metadata.findSmaliDraft(draft.id)
        metadata.saveSmaliDraft(
            SmaliDraftMetadata(draft.id, draft.projectId, draft.originalApkSha256, draftPath(draft.projectId, draft.id),
                draft.revision, existing?.createdAt ?: timestamp, timestamp,
                if (validation.isValid) SmaliDraftStatus.VALID else SmaliDraftStatus.INVALID),
        )
        return SmaliDraftSessionResult.Ready(draft, validation)
    }

    private suspend fun openWorkspace(projectId: ProjectId): Workspace? = try { workspaces.open(projectId) } catch (_: IllegalArgumentException) { null }
    private fun draftPath(projectId: ProjectId, draftId: UUID) = RelativePath.of("projects/${projectId.value}/work/dex/$draftId.smali")
}
