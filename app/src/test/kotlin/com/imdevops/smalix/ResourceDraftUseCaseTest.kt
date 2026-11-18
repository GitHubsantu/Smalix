package com.imdevops.smalix

import com.imdevops.smalix.core.model.*
import com.imdevops.smalix.engine.api.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

class ResourceDraftUseCaseTest {
    @Test fun `creates reopens saves and discards SHA bound staged resource draft`() = runBlocking {
        val project = project(); val texts = Texts(); val records = Records(); val useCase = useCase(project, texts, records); val draft = draft(project.id)
        assertEquals(draft, (useCase.openOrCreate(project.id, draft, Instant.MAX) as ResourceDraftSessionResult.Ready).draft)
        val saved = useCase.saveValue(project.id, draft.id, "new", Instant.MAX) as ResourceDraftSaveResult.Saved
        assertEquals(1, saved.draft.revision); assertEquals("new", texts.value?.proposedValue)
        assertEquals(saved.draft, (useCase.discard(project.id, draft.id) as ResourceDraftSessionResult.Ready).draft); assertNull(texts.value); assertNull(records.value)
    }
    @Test fun `rejects stale SHA and invalid value without replacing saved text`() = runBlocking {
        val project = project(); val texts = Texts(); val records = Records(); val useCase = useCase(project, texts, records); val draft = draft(project.id)
        useCase.openOrCreate(project.id, draft, Instant.MAX); val rejected = useCase.saveValue(project.id, draft.id, "bad", Instant.MAX) as ResourceDraftSaveResult.Rejected
        assertFalse(rejected.validation.isValid); assertEquals("old", texts.value?.proposedValue)
        val stale = useCase(project, Texts(), Records(), "b".repeat(64)).openOrCreate(project.id, draft, Instant.MAX)
        assertEquals(ResourceDraftSessionResult.StaleDraft("Original APK SHA-256 changed; re-inspect before opening a resource draft."), stale)
    }
    private fun useCase(project: Project, texts: Texts, records: Records, sha: String = "a".repeat(64)) = ResourceDraftUseCase(
        projects = object : ProjectRepository { override suspend fun create(project: Project)=Unit; override suspend fun find(id: ProjectId)=project.takeIf { it.id==id }; override suspend fun list()=listOf(project); override suspend fun update(project: Project)=Unit; override suspend fun delete(id: ProjectId)=Unit },
        workspaces = object : WorkspaceStore { val w=Workspace(project.id,Path.of("p"),Path.of("p/original.apk"),Path.of("p/work"),Path.of("p/logs")); override suspend fun create(id: ProjectId)=error("unused"); override suspend fun open(id: ProjectId)=w; override suspend fun copyOriginal(p: Path,w: Workspace)=Unit; override fun originalApk(w: Workspace)=w.originalApk; override suspend fun delete(w: Workspace)=Unit },
        drafts=texts, metadata=records, inspection=InspectionUseCase(object:ApkAnalyzer { override suspend fun analyze(r:AnalysisRequest)=InspectionResult.Success(ApkAnalysis(ValidatedApk(Path.of("p/original.apk"),sha,1,emptyList()),emptySet(),emptyList())) }, object:ManifestParser { override suspend fun parse(a:ValidatedApk)=InspectionResult.Success(ManifestInfo("p",null,null,null,null,null,emptyList(),emptyList())) }, object:DexDetector { override suspend fun detect(a:ValidatedApk)=InspectionResult.Success(DexInfo(emptyList())) }),
        validator=object:ResourceDraftValidator { override fun validate(d:ResourceDraft)=ResourceDraftValidation(if(d.proposedValue=="bad") listOf("bad") else emptyList()) }, now={Instant.EPOCH})
    private fun project()=Project(ProjectId.random(),"P",Instant.EPOCH,null,RelativePath.of("projects/p"),RelativePath.of("projects/p/original.apk"),"a".repeat(64),"p.apk",1,ProjectAnalysisStatus.ANALYZED,null)
    private fun draft(id:ProjectId)=ResourceDraft(UUID.randomUUID(),id,"a".repeat(64),1,"sample.app",ResourceType.STRING,"title","","old","old",0)
    private class Texts(var value:ResourceDraft?=null):ResourceDraftStore { override suspend fun load(w:Workspace,id:UUID,sha:String)=value; override suspend fun save(w:Workspace,d:ResourceDraft){value=d}; override suspend fun discard(w:Workspace,id:UUID){value=null} }
    private class Records(var value:ResourceDraftMetadata?=null):ResourceDraftRepository { override suspend fun findResourceDraft(id:UUID)=value; override suspend fun listResourceDrafts(id:ProjectId)=listOfNotNull(value); override suspend fun saveResourceDraft(m:ResourceDraftMetadata){value=m}; override suspend fun deleteResourceDraft(id:UUID){value=null} }
}
