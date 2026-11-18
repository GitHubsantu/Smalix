package com.imdevops.smalix

import com.imdevops.smalix.core.model.AnalysisFailure
import com.imdevops.smalix.core.model.AnalysisRequest
import com.imdevops.smalix.core.model.InspectionResult
import com.imdevops.smalix.core.model.ProjectAnalysisStatus
import com.imdevops.smalix.core.model.ProjectId
import com.imdevops.smalix.core.model.RelativePath
import com.imdevops.smalix.core.model.ResourceDraft
import com.imdevops.smalix.core.model.ResourceDraftMetadata
import com.imdevops.smalix.core.model.ResourceDraftStatus
import com.imdevops.smalix.core.model.ResourceDraftValidation
import com.imdevops.smalix.core.model.Workspace
import com.imdevops.smalix.core.model.ZipSafetyLimits
import com.imdevops.smalix.engine.api.ProjectRepository
import com.imdevops.smalix.engine.api.ResourceDraftRepository
import com.imdevops.smalix.engine.api.ResourceDraftStore
import com.imdevops.smalix.engine.api.ResourceDraftValidator
import com.imdevops.smalix.engine.api.WorkspaceStore
import java.time.Instant
import java.util.UUID

sealed interface ResourceDraftSessionResult {
    data class Ready(val draft: ResourceDraft, val validation: ResourceDraftValidation) : ResourceDraftSessionResult
    data class InspectionFailed(val error: AnalysisFailure) : ResourceDraftSessionResult
    data object ProjectNotFound : ResourceDraftSessionResult
    data object ProjectNotReady : ResourceDraftSessionResult
    data class WorkspaceUnavailable(val message: String) : ResourceDraftSessionResult
    data class StaleDraft(val message: String) : ResourceDraftSessionResult
}

sealed interface ResourceDraftSaveResult {
    data class Saved(val draft: ResourceDraft, val validation: ResourceDraftValidation) : ResourceDraftSaveResult
    data class Rejected(val validation: ResourceDraftValidation) : ResourceDraftSaveResult
    data class Unavailable(val result: ResourceDraftSessionResult) : ResourceDraftSaveResult
}

/** Coordinates staged resource text only. It never writes resources.arsc or APK bytes. */
class ResourceDraftUseCase(
    private val projects: ProjectRepository, private val workspaces: WorkspaceStore, private val drafts: ResourceDraftStore,
    private val metadata: ResourceDraftRepository, private val inspection: InspectionUseCase, private val validator: ResourceDraftValidator,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun openOrCreate(projectId: ProjectId, proposed: ResourceDraft, deadline: Instant, limits: ZipSafetyLimits = ZipSafetyLimits()): ResourceDraftSessionResult {
        val project = projects.find(projectId) ?: return ResourceDraftSessionResult.ProjectNotFound
        val sha = project.originalSha256 ?: return ResourceDraftSessionResult.ProjectNotReady
        if (project.analysisStatus != ProjectAnalysisStatus.ANALYZED || proposed.projectId != projectId || !proposed.originalApkSha256.equals(sha, true)) return stale("Resource draft does not match the inspected original APK.")
        val workspace = workspace(projectId) ?: return ResourceDraftSessionResult.WorkspaceUnavailable("Project workspace is unavailable.")
        when (val result = inspection.inspect(AnalysisRequest(projectId, workspaces.originalApk(workspace), deadline, limits))) {
            is InspectionResult.Failure -> return ResourceDraftSessionResult.InspectionFailed(result.error)
            is InspectionResult.Success -> if (!result.value.apk.archive.sha256.equals(sha, true)) return stale("Original APK SHA-256 changed; re-inspect before opening a resource draft.")
        }
        existing(projectId, workspace, sha, proposed.id)?.let { return it }
        val text = try { drafts.load(workspace, proposed.id, sha) } catch (e: IllegalArgumentException) { return stale(e.message ?: "Staged resource draft is unavailable.") }
        if (text != null || metadata.findResourceDraft(proposed.id) != null) return stale("Resource draft metadata does not match the inspected original APK.")
        return save(workspace, proposed)
    }

    suspend fun saveValue(projectId: ProjectId, draftId: UUID, value: String, deadline: Instant, limits: ZipSafetyLimits = ZipSafetyLimits()): ResourceDraftSaveResult {
        val project = projects.find(projectId) ?: return ResourceDraftSaveResult.Unavailable(ResourceDraftSessionResult.ProjectNotFound)
        val sha = project.originalSha256 ?: return ResourceDraftSaveResult.Unavailable(ResourceDraftSessionResult.ProjectNotReady)
        if (project.analysisStatus != ProjectAnalysisStatus.ANALYZED) return ResourceDraftSaveResult.Unavailable(ResourceDraftSessionResult.ProjectNotReady)
        val workspace = workspace(projectId) ?: return ResourceDraftSaveResult.Unavailable(ResourceDraftSessionResult.WorkspaceUnavailable("Project workspace is unavailable."))
        when (val result = inspection.inspect(AnalysisRequest(projectId, workspaces.originalApk(workspace), deadline, limits))) {
            is InspectionResult.Failure -> return ResourceDraftSaveResult.Unavailable(ResourceDraftSessionResult.InspectionFailed(result.error))
            is InspectionResult.Success -> if (!result.value.apk.archive.sha256.equals(sha, true)) return ResourceDraftSaveResult.Unavailable(stale("Original APK SHA-256 changed; re-inspect before saving a resource draft."))
        }
        val current = existing(projectId, workspace, sha, draftId) ?: return ResourceDraftSaveResult.Unavailable(stale("No matching staged resource draft exists."))
        val revised = current.draft.copy(revision = current.draft.revision + 1, proposedValue = value); val validation = validator.validate(revised)
        if (!validation.isValid) return ResourceDraftSaveResult.Rejected(validation)
        return ResourceDraftSaveResult.Saved(save(workspace, revised).draft, validation)
    }

    suspend fun discard(projectId: ProjectId, draftId: UUID): ResourceDraftSessionResult {
        val project = projects.find(projectId) ?: return ResourceDraftSessionResult.ProjectNotFound; val sha = project.originalSha256 ?: return ResourceDraftSessionResult.ProjectNotReady
        val workspace = workspace(projectId) ?: return ResourceDraftSessionResult.WorkspaceUnavailable("Project workspace is unavailable.")
        val ready = existing(projectId, workspace, sha, draftId) ?: return stale("No matching staged resource draft exists.")
        drafts.discard(workspace, draftId); metadata.deleteResourceDraft(draftId); return ready
    }

    private suspend fun existing(projectId: ProjectId, workspace: Workspace, sha: String, draftId: UUID): ResourceDraftSessionResult.Ready? {
        val draft = try { drafts.load(workspace, draftId, sha) } catch (_: IllegalArgumentException) { return null }; val stored = metadata.findResourceDraft(draftId)
        return if (draft != null && stored != null && stored.projectId == projectId && stored.draftId == draftId && stored.originalApkSha256.equals(sha, true) && stored.revision == draft.revision && stored.relativePath == path(projectId, draftId)) ResourceDraftSessionResult.Ready(draft, validator.validate(draft)) else null
    }
    private suspend fun save(workspace: Workspace, draft: ResourceDraft): ResourceDraftSessionResult.Ready {
        val validation = validator.validate(draft); drafts.save(workspace, draft); val time = now(); val old = metadata.findResourceDraft(draft.id)
        metadata.saveResourceDraft(ResourceDraftMetadata(draft.id, draft.projectId, draft.originalApkSha256, path(draft.projectId, draft.id), draft.revision, old?.createdAt ?: time, time, if (validation.isValid) ResourceDraftStatus.VALID else ResourceDraftStatus.INVALID))
        return ResourceDraftSessionResult.Ready(draft, validation)
    }
    private suspend fun workspace(projectId: ProjectId) = try { workspaces.open(projectId) } catch (_: IllegalArgumentException) { null }
    private fun path(projectId: ProjectId, draftId: UUID) = RelativePath.of("projects/${projectId.value}/work/resources/$draftId.json")
    private fun stale(message: String) = ResourceDraftSessionResult.StaleDraft(message)
}
