package com.imdevops.smalix

import com.imdevops.smalix.core.model.ActivityInfo
import com.imdevops.smalix.core.model.ApkAnalysis
import com.imdevops.smalix.core.model.AnalysisRequest
import com.imdevops.smalix.core.model.DexInfo
import com.imdevops.smalix.core.model.InspectionResult
import com.imdevops.smalix.core.model.InspectionSummary
import com.imdevops.smalix.core.model.ManifestDraft
import com.imdevops.smalix.core.model.ManifestDraftMetadata
import com.imdevops.smalix.core.model.ManifestDraftValidationStatus
import com.imdevops.smalix.core.model.ManifestEdit
import com.imdevops.smalix.core.model.ManifestEditResult
import com.imdevops.smalix.core.model.ManifestInfo
import com.imdevops.smalix.core.model.ManifestValidation
import com.imdevops.smalix.core.model.Project
import com.imdevops.smalix.core.model.ProjectAnalysisStatus
import com.imdevops.smalix.core.model.ProjectId
import com.imdevops.smalix.core.model.RelativePath
import com.imdevops.smalix.core.model.ValidatedApk
import com.imdevops.smalix.core.model.Workspace
import com.imdevops.smalix.engine.api.ApkAnalyzer
import com.imdevops.smalix.engine.api.DexDetector
import com.imdevops.smalix.engine.api.ManifestDraftEditor
import com.imdevops.smalix.engine.api.ManifestDraftRepository
import com.imdevops.smalix.engine.api.ManifestDraftStore
import com.imdevops.smalix.engine.api.ManifestDraftValidator
import com.imdevops.smalix.engine.api.ManifestParser
import com.imdevops.smalix.engine.api.ProjectRepository
import com.imdevops.smalix.engine.api.WorkspaceStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant

class ManifestDraftUseCaseTest {
    @Test
    fun `creates a valid staged draft from a matching inspected original`() = runBlocking {
        val project = project()
        val drafts = MemoryDraftStore()
        val metadata = MemoryMetadataRepository()
        val useCase = useCase(project, drafts, metadata)

        val result = useCase.openOrCreate(project.id, Instant.MAX)

        val ready = result as DraftSessionResult.Ready
        assertEquals(project.id, ready.draft.projectId)
        assertEquals("sample", ready.draft.packageName)
        assertEquals(ready.draft, drafts.draft)
        assertEquals(ready.draft.id, metadata.metadata?.draftId)
        assertEquals(ManifestDraftValidationStatus.VALID, metadata.metadata?.validationStatus)
    }

    @Test
    fun `does not persist an invalid edit but returns its validation`() = runBlocking {
        val project = project()
        val drafts = MemoryDraftStore()
        val metadata = MemoryMetadataRepository()
        val useCase = useCase(project, drafts, metadata)
        val initial = (useCase.openOrCreate(project.id, Instant.MAX) as DraftSessionResult.Ready).draft

        val result = useCase.edit(project.id, ManifestEdit.SetVersionCode(0))

        val rejected = result as DraftEditResult.Rejected
        assertEquals(initial.copy(revision = 1, versionCode = 0), rejected.draft)
        assertEquals(false, rejected.validation.isValid)
        assertEquals(initial, drafts.draft)
        assertEquals(0, metadata.metadata?.revision)
    }

    @Test
    fun `saves valid edit and discards both staged representations`() = runBlocking {
        val project = project()
        val drafts = MemoryDraftStore()
        val metadata = MemoryMetadataRepository()
        val useCase = useCase(project, drafts, metadata)
        useCase.openOrCreate(project.id, Instant.MAX)

        val edited = useCase.edit(project.id, ManifestEdit.SetVersionName("2.0")) as DraftEditResult.Saved
        assertEquals(1, edited.draft.revision)
        assertEquals("2.0", drafts.draft?.versionName)
        assertEquals(1, metadata.metadata?.revision)

        assertEquals(DraftDiscardResult.Discarded, useCase.discard(project.id))
        assertNull(drafts.draft)
        assertNull(metadata.metadata)
    }

    @Test
    fun `refuses to create a draft when reinspection sha differs`() = runBlocking {
        val project = project()
        val useCase = useCase(project, MemoryDraftStore(), MemoryMetadataRepository(), sha = "b".repeat(64))

        assertEquals(
            DraftSessionResult.StaleDraft("Original APK SHA-256 changed; re-inspect before creating a draft."),
            useCase.openOrCreate(project.id, Instant.MAX),
        )
    }

    @Test
    fun `editing without a staged draft does not create one`() = runBlocking {
        val project = project()
        val drafts = MemoryDraftStore()
        val metadata = MemoryMetadataRepository()

        val result = useCase(project, drafts, metadata).edit(project.id, ManifestEdit.SetVersionName("2.0"))

        assertEquals(DraftEditResult.Unavailable(DraftSessionResult.StaleDraft("No staged manifest draft exists.")), result)
        assertNull(drafts.draft)
        assertNull(metadata.metadata)
    }

    private fun useCase(project: Project, drafts: MemoryDraftStore, metadata: MemoryMetadataRepository, sha: String = "a".repeat(64)) = ManifestDraftUseCase(
        projectRepository = object : ProjectRepository {
            override suspend fun create(project: Project) = Unit
            override suspend fun find(projectId: ProjectId): Project? = project.takeIf { it.id == projectId }
            override suspend fun list(): List<Project> = listOf(project)
            override suspend fun update(project: Project) = Unit
            override suspend fun delete(projectId: ProjectId) = Unit
        },
        workspaceStore = workspaceStore(project.id), draftStore = drafts, draftRepository = metadata,
        inspectionUseCase = inspectionUseCase(project.id, sha), editor = editor(), validator = validator(),
        now = { Instant.ofEpochMilli(1_000) },
    )

    private fun inspectionUseCase(projectId: ProjectId, sha: String) = InspectionUseCase(
        object : ApkAnalyzer { override suspend fun analyze(request: AnalysisRequest) = InspectionResult.Success(ApkAnalysis(ValidatedApk(Path.of("original.apk"), sha, 3, emptyList()), emptySet(), emptyList())) },
        object : ManifestParser { override suspend fun parse(apk: ValidatedApk) = InspectionResult.Success(ManifestInfo("sample", "1.0", 1, 26, 35, null, listOf(ActivityInfo("sample.MainActivity", true, true)), emptyList())) },
        object : DexDetector { override suspend fun detect(apk: ValidatedApk) = InspectionResult.Success(DexInfo(emptyList())) },
    )

    private fun editor() = object : ManifestDraftEditor {
        override fun apply(draft: ManifestDraft, edit: ManifestEdit): ManifestEditResult = when (edit) {
            is ManifestEdit.SetVersionCode -> ManifestEditResult(draft.copy(revision = draft.revision + 1, versionCode = edit.value), validator().validate(draft.copy(revision = draft.revision + 1, versionCode = edit.value)))
            is ManifestEdit.SetVersionName -> ManifestEditResult(draft.copy(revision = draft.revision + 1, versionName = edit.value), ManifestValidation(emptyList()))
            else -> error("unused")
        }
    }

    private fun validator() = object : ManifestDraftValidator {
        override fun validate(draft: ManifestDraft) = if (draft.versionCode == 0L) {
            ManifestValidation(listOf(com.imdevops.smalix.core.model.ManifestIssue(com.imdevops.smalix.core.model.ManifestIssueSeverity.ERROR, "versionCode", "Version code must be positive.")))
        } else ManifestValidation(emptyList())
    }

    private fun workspaceStore(projectId: ProjectId) = object : WorkspaceStore {
        private val workspace = Workspace(projectId, Path.of("project"), Path.of("project/original.apk"), Path.of("project/work"), Path.of("project/logs"))
        override suspend fun create(projectId: ProjectId) = error("unused")
        override suspend fun open(projectId: ProjectId) = workspace
        override suspend fun copyOriginal(sourceApk: Path, workspace: Workspace) = Unit
        override fun originalApk(workspace: Workspace) = workspace.originalApk
        override suspend fun delete(workspace: Workspace) = Unit
    }

    private fun project() = Project(ProjectId.random(), "Sample", Instant.EPOCH, null, RelativePath.of("projects/sample"), RelativePath.of("projects/sample/original.apk"), "a".repeat(64), "sample.apk", 3, ProjectAnalysisStatus.ANALYZED, null)

    private class MemoryDraftStore : ManifestDraftStore {
        var draft: ManifestDraft? = null
        override suspend fun load(workspace: Workspace) = draft
        override suspend fun save(workspace: Workspace, draft: ManifestDraft) { this.draft = draft }
        override suspend fun discard(workspace: Workspace) { draft = null }
    }

    private class MemoryMetadataRepository : ManifestDraftRepository {
        var metadata: ManifestDraftMetadata? = null
        override suspend fun findDraft(projectId: ProjectId) = metadata
        override suspend fun save(metadata: ManifestDraftMetadata) { this.metadata = metadata }
        override suspend fun deleteManifestDraft(projectId: ProjectId) { metadata = null }
    }
}
