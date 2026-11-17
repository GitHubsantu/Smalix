package com.imdevops.smalix

import com.imdevops.smalix.core.model.AnalysisFailure
import com.imdevops.smalix.core.model.InspectionResult
import com.imdevops.smalix.core.model.ManifestDraft
import com.imdevops.smalix.core.model.ManifestDraftMetadata
import com.imdevops.smalix.core.model.ManifestDraftValidationStatus
import com.imdevops.smalix.core.model.ManifestEdit
import com.imdevops.smalix.core.model.ManifestValidation
import com.imdevops.smalix.core.model.Project
import com.imdevops.smalix.core.model.ProjectAnalysisStatus
import com.imdevops.smalix.core.model.ProjectId
import com.imdevops.smalix.core.model.RelativePath
import com.imdevops.smalix.core.model.Workspace
import com.imdevops.smalix.core.model.ZipSafetyLimits
import com.imdevops.smalix.engine.api.ManifestDraftEditor
import com.imdevops.smalix.engine.api.ManifestDraftRepository
import com.imdevops.smalix.engine.api.ManifestDraftStore
import com.imdevops.smalix.engine.api.ManifestDraftValidator
import com.imdevops.smalix.engine.api.ProjectRepository
import com.imdevops.smalix.engine.api.WorkspaceStore
import java.time.Instant

sealed interface DraftSessionResult {
    data class Ready(val draft: ManifestDraft, val validation: ManifestValidation) : DraftSessionResult
    data class InspectionFailed(val error: AnalysisFailure) : DraftSessionResult
    data object ProjectNotFound : DraftSessionResult
    data object ProjectNotReady : DraftSessionResult
    data class WorkspaceUnavailable(val message: String) : DraftSessionResult
    data class StaleDraft(val message: String) : DraftSessionResult
}

sealed interface DraftEditResult {
    data class Saved(val draft: ManifestDraft, val validation: ManifestValidation) : DraftEditResult
    data class Rejected(val draft: ManifestDraft, val validation: ManifestValidation) : DraftEditResult
    data class Unavailable(val result: DraftSessionResult) : DraftEditResult
}

sealed interface DraftDiscardResult {
    data object Discarded : DraftDiscardResult
    data class Unavailable(val result: DraftSessionResult) : DraftDiscardResult
}

/** Coordinates staged manifest data only; it never writes or replaces an APK. */
class ManifestDraftUseCase(
    private val projectRepository: ProjectRepository,
    private val workspaceStore: WorkspaceStore,
    private val draftStore: ManifestDraftStore,
    private val draftRepository: ManifestDraftRepository,
    private val inspectionUseCase: InspectionUseCase,
    private val editor: ManifestDraftEditor,
    private val validator: ManifestDraftValidator,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun openOrCreate(
        projectId: ProjectId,
        deadline: Instant,
        limits: ZipSafetyLimits = ZipSafetyLimits(),
    ): DraftSessionResult {
        val project = projectRepository.find(projectId) ?: return DraftSessionResult.ProjectNotFound
        val originalSha256 = project.originalSha256
        if (project.analysisStatus != ProjectAnalysisStatus.ANALYZED || originalSha256 == null) {
            return DraftSessionResult.ProjectNotReady
        }
        val workspace = openWorkspace(projectId) ?: return DraftSessionResult.WorkspaceUnavailable("Project workspace is unavailable.")
        val stored = try {
            draftStore.load(workspace)
        } catch (error: IllegalArgumentException) {
            return DraftSessionResult.StaleDraft(error.message ?: "Manifest draft is unavailable.")
        }
        val metadata = draftRepository.findDraft(projectId)
        if (stored != null || metadata != null) {
            return validateStoredDraft(project, stored, metadata)
        }

        return when (val inspection = inspectionUseCase.inspect(
            com.imdevops.smalix.core.model.AnalysisRequest(projectId, workspaceStore.originalApk(workspace), deadline, limits),
        )) {
            is InspectionResult.Failure -> DraftSessionResult.InspectionFailed(inspection.error)
            is InspectionResult.Success -> {
                if (inspection.value.apk.archive.sha256 != originalSha256) {
                    DraftSessionResult.StaleDraft("Original APK SHA-256 changed; re-inspect before creating a draft.")
                } else {
                    val draft = ManifestDraft.from(projectId, originalSha256, inspection.value.manifest)
                    val validation = validator.validate(draft)
                    if (!validation.isValid) DraftSessionResult.StaleDraft("Parsed manifest draft is invalid.")
                    else save(project, workspace, draft, validation)
                }
            }
        }
    }

    suspend fun edit(projectId: ProjectId, edit: ManifestEdit): DraftEditResult {
        val ready = openExisting(projectId) ?: return DraftEditResult.Unavailable(openOrCreateUnavailable(projectId))
        val result = editor.apply(ready.draft, edit)
        return if (result.validation.isValid) {
            when (val saved = save(ready.project, ready.workspace, result.draft, result.validation)) {
                is DraftSessionResult.Ready -> DraftEditResult.Saved(saved.draft, saved.validation)
                else -> DraftEditResult.Unavailable(saved)
            }
        } else {
            DraftEditResult.Rejected(result.draft, result.validation)
        }
    }

    suspend fun discard(projectId: ProjectId): DraftDiscardResult {
        val ready = openExisting(projectId) ?: return DraftDiscardResult.Unavailable(openOrCreateUnavailable(projectId))
        draftStore.discard(ready.workspace)
        draftRepository.deleteManifestDraft(projectId)
        return DraftDiscardResult.Discarded
    }

    private suspend fun openExisting(projectId: ProjectId): ExistingDraft? {
        val project = projectRepository.find(projectId) ?: return null
        if (project.analysisStatus != ProjectAnalysisStatus.ANALYZED || project.originalSha256 == null) return null
        val workspace = openWorkspace(projectId) ?: return null
        val draft = try { draftStore.load(workspace) } catch (_: IllegalArgumentException) { return null }
        val metadata = draftRepository.findDraft(projectId)
        return if (draft != null && metadata != null && matches(project, draft, metadata)) ExistingDraft(project, workspace, draft) else null
    }

    private suspend fun openOrCreateUnavailable(projectId: ProjectId): DraftSessionResult {
        val project = projectRepository.find(projectId) ?: return DraftSessionResult.ProjectNotFound
        if (project.analysisStatus != ProjectAnalysisStatus.ANALYZED || project.originalSha256 == null) {
            return DraftSessionResult.ProjectNotReady
        }
        val workspace = openWorkspace(projectId) ?: return DraftSessionResult.WorkspaceUnavailable("Project workspace is unavailable.")
        val draft = try { draftStore.load(workspace) } catch (error: IllegalArgumentException) {
            return DraftSessionResult.StaleDraft(error.message ?: "Manifest draft is unavailable.")
        }
        val metadata = draftRepository.findDraft(projectId)
        return if (draft == null && metadata == null) {
            DraftSessionResult.StaleDraft("No staged manifest draft exists.")
        } else {
            validateStoredDraft(project, draft, metadata)
        }
    }

    private suspend fun validateStoredDraft(
        project: Project,
        draft: ManifestDraft?,
        metadata: ManifestDraftMetadata?,
    ): DraftSessionResult = if (draft != null && metadata != null && matches(project, draft, metadata)) {
        DraftSessionResult.Ready(draft, validator.validate(draft))
    } else {
        DraftSessionResult.StaleDraft("Manifest draft metadata does not match the inspected original APK.")
    }

    private fun matches(project: Project, draft: ManifestDraft, metadata: ManifestDraftMetadata): Boolean =
        draft.projectId == project.id && draft.originalApkSha256 == project.originalSha256 &&
            draft.id == metadata.draftId && draft.revision == metadata.revision &&
            metadata.projectId == project.id && metadata.originalApkSha256 == project.originalSha256

    private suspend fun save(
        project: Project,
        workspace: Workspace,
        draft: ManifestDraft,
        validation: ManifestValidation,
    ): DraftSessionResult {
        draftStore.save(workspace, draft)
        val timestamp = now()
        draftRepository.save(
            ManifestDraftMetadata(
                projectId = project.id,
                draftId = draft.id,
                originalApkSha256 = draft.originalApkSha256,
                relativePath = draftPath(project),
                revision = draft.revision,
                createdAt = draftRepository.findDraft(project.id)?.createdAt ?: timestamp,
                updatedAt = timestamp,
                validationStatus = if (validation.isValid) ManifestDraftValidationStatus.VALID else ManifestDraftValidationStatus.INVALID,
            ),
        )
        return DraftSessionResult.Ready(draft, validation)
    }

    private suspend fun openWorkspace(projectId: ProjectId): Workspace? = try {
        workspaceStore.open(projectId)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun draftPath(project: Project): RelativePath =
        RelativePath.of("${project.workspaceRelativePath.value}/work/manifest-draft.json")

    private data class ExistingDraft(val project: Project, val workspace: Workspace, val draft: ManifestDraft)
}
