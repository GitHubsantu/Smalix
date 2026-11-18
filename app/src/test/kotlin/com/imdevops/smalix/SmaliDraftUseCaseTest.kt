package com.imdevops.smalix

import com.imdevops.smalix.core.model.ApkAnalysis
import com.imdevops.smalix.core.model.AnalysisRequest
import com.imdevops.smalix.core.model.DexInfo
import com.imdevops.smalix.core.model.InspectionResult
import com.imdevops.smalix.core.model.ManifestInfo
import com.imdevops.smalix.core.model.Project
import com.imdevops.smalix.core.model.ProjectAnalysisStatus
import com.imdevops.smalix.core.model.ProjectId
import com.imdevops.smalix.core.model.RelativePath
import com.imdevops.smalix.core.model.SmaliDraft
import com.imdevops.smalix.core.model.SmaliDraftMetadata
import com.imdevops.smalix.core.model.SmaliDraftStatus
import com.imdevops.smalix.core.model.SmaliValidation
import com.imdevops.smalix.core.model.ValidatedApk
import com.imdevops.smalix.core.model.Workspace
import com.imdevops.smalix.engine.api.ApkAnalyzer
import com.imdevops.smalix.engine.api.DexDetector
import com.imdevops.smalix.engine.api.ManifestParser
import com.imdevops.smalix.engine.api.ProjectRepository
import com.imdevops.smalix.engine.api.SmaliDraftRepository
import com.imdevops.smalix.engine.api.SmaliDraftStore
import com.imdevops.smalix.engine.api.SmaliDraftValidator
import com.imdevops.smalix.engine.api.WorkspaceStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

class SmaliDraftUseCaseTest {
    @Test fun `creates after matching reinspection then reopens and discards both representations`() = runBlocking {
        val project = project(); val texts = Texts(); val metadata = Metadata(); val useCase = useCase(project, texts, metadata)
        val proposed = draft(project.id)
        assertEquals(proposed, (useCase.openOrCreate(project.id, proposed, Instant.MAX) as SmaliDraftSessionResult.Ready).draft)
        assertEquals(proposed.id, metadata.value?.draftId)
        assertEquals(proposed, (useCase.openOrCreate(project.id, proposed, Instant.MAX) as SmaliDraftSessionResult.Ready).draft)
        assertEquals(proposed, (useCase.discard(project.id, proposed.id) as SmaliDraftSessionResult.Ready).draft)
        assertNull(texts.value); assertNull(metadata.value)
    }

    @Test fun `rejects changed original and inconsistent staged metadata without overwriting text`() = runBlocking {
        val project = project(); val proposed = draft(project.id); val texts = Texts(proposed)
        val inconsistent = Metadata(SmaliDraftMetadata(proposed.id, project.id, "a".repeat(64), RelativePath.of("projects/${project.id.value}/work/dex/wrong.smali"), 0, Instant.EPOCH, Instant.EPOCH, SmaliDraftStatus.VALID))
        val stale = useCase(project, texts, inconsistent).openOrCreate(project.id, proposed, Instant.MAX)
        assertEquals(SmaliDraftSessionResult.StaleDraft("Smali draft metadata does not match the inspected original APK."), stale)
        assertEquals(proposed, texts.value)
        val changed = useCase(project, Texts(), Metadata(), sha = "b".repeat(64)).openOrCreate(project.id, proposed, Instant.MAX)
        assertEquals(SmaliDraftSessionResult.StaleDraft("Original APK SHA-256 changed; re-inspect before opening a Smali draft."), changed)
    }

    @Test fun `saves a new revision and preserves old text when validation rejects it`() = runBlocking {
        val project = project(); val texts = Texts(); val metadata = Metadata(); val useCase = useCase(project, texts, metadata)
        val initial = draft(project.id)
        useCase.openOrCreate(project.id, initial, Instant.MAX)

        val saved = useCase.saveText(project.id, initial.id, ".method public m()V\n.end method", Instant.MAX) as SmaliDraftSaveResult.Saved
        assertEquals(1, saved.draft.revision)
        assertEquals(saved.draft, texts.value)
        val rejected = useCase.saveText(project.id, initial.id, "bad", Instant.MAX) as SmaliDraftSaveResult.Rejected
        assertEquals(false, rejected.validation.isValid)
        assertEquals(saved.draft, texts.value)
    }

    private fun useCase(project: Project, texts: Texts, metadata: Metadata, sha: String = "a".repeat(64)) = SmaliDraftUseCase(
        projects = object : ProjectRepository { override suspend fun create(project: Project) = Unit; override suspend fun find(projectId: ProjectId) = project.takeIf { it.id == projectId }; override suspend fun list() = listOf(project); override suspend fun update(project: Project) = Unit; override suspend fun delete(projectId: ProjectId) = Unit },
        workspaces = object : WorkspaceStore { val workspace = Workspace(project.id, Path.of("p"), Path.of("p/original.apk"), Path.of("p/work"), Path.of("p/logs")); override suspend fun create(projectId: ProjectId) = error("unused"); override suspend fun open(projectId: ProjectId) = workspace; override suspend fun copyOriginal(sourceApk: Path, workspace: Workspace) = Unit; override fun originalApk(workspace: Workspace) = workspace.originalApk; override suspend fun delete(workspace: Workspace) = Unit },
        drafts = texts, metadata = metadata,
        inspection = InspectionUseCase(object : ApkAnalyzer { override suspend fun analyze(request: AnalysisRequest) = InspectionResult.Success(ApkAnalysis(ValidatedApk(Path.of("p/original.apk"), sha, 1, emptyList()), emptySet(), emptyList())) }, object : ManifestParser { override suspend fun parse(apk: ValidatedApk) = InspectionResult.Success(ManifestInfo("p", null, null, null, null, null, emptyList(), emptyList())) }, object : DexDetector { override suspend fun detect(apk: ValidatedApk) = InspectionResult.Success(DexInfo(emptyList())) }),
        validator = object : SmaliDraftValidator { override fun validate(draft: SmaliDraft) = if (draft.text == "bad") SmaliValidation(listOf("invalid")) else SmaliValidation(emptyList()) }, now = { Instant.EPOCH },
    )

    private fun project() = Project(ProjectId.random(), "P", Instant.EPOCH, null, RelativePath.of("projects/p"), RelativePath.of("projects/p/original.apk"), "a".repeat(64), "p.apk", 1, ProjectAnalysisStatus.ANALYZED, null)
    private fun draft(projectId: ProjectId) = SmaliDraft(UUID.randomUUID(), projectId, "a".repeat(64), "classes.dex", "Lp/C;", "m", "()V", 0, ".method m()V\n.end method")
    private class Texts(var value: SmaliDraft? = null) : SmaliDraftStore { override suspend fun load(workspace: Workspace, draftId: UUID, expectedOriginalApkSha256: String) = value; override suspend fun save(workspace: Workspace, draft: SmaliDraft) { value = draft }; override suspend fun discard(workspace: Workspace, draftId: UUID) { value = null } }
    private class Metadata(var value: SmaliDraftMetadata? = null) : SmaliDraftRepository { override suspend fun findSmaliDraft(draftId: UUID) = value; override suspend fun listSmaliDrafts(projectId: ProjectId) = listOfNotNull(value); override suspend fun saveSmaliDraft(metadata: SmaliDraftMetadata) { value = metadata }; override suspend fun deleteSmaliDraft(draftId: UUID) { value = null } }
}
