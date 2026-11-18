package com.imdevops.smalix

import com.imdevops.smalix.core.model.*
import com.imdevops.smalix.engine.api.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

class BuildPreflightUseCaseTest {
    @Test fun `matching SHA creates isolated workspace while stale SHA creates none`() = runBlocking {
        val project = Project(ProjectId.random(), "P", Instant.EPOCH, null, RelativePath.of("projects/p"), RelativePath.of("projects/p/original.apk"), "a".repeat(64), "p.apk", 1, ProjectAnalysisStatus.ANALYZED, null)
        val builds = Builds(); val useCase = useCase(project, builds, "a".repeat(64)); val request = BuildRequest(UUID.randomUUID(), project.id, "a".repeat(64), RelativePath.of("exports/x.apk"), Instant.EPOCH)
        assertTrue(useCase.prepare(request, Instant.MAX) is BuildPreflightResult.Ready); assertEquals(1, builds.created)
        assertTrue(useCase.prepare(request.copy(expectedOriginalApkSha256 = "b".repeat(64)), Instant.MAX) is BuildPreflightResult.Rejected); assertEquals(1, builds.created)
    }
    private fun useCase(project: Project, builds: Builds, sha: String) = BuildPreflightUseCase(
        object : ProjectRepository { override suspend fun create(p:Project)=Unit; override suspend fun find(id:ProjectId)=project.takeIf { it.id==id }; override suspend fun list()=listOf(project); override suspend fun update(p:Project)=Unit; override suspend fun delete(id:ProjectId)=Unit },
        object : WorkspaceStore { val w=Workspace(project.id,Path.of("p"),Path.of("p/original.apk"),Path.of("p/work"),Path.of("p/logs")); override suspend fun create(id:ProjectId)=error("unused"); override suspend fun open(id:ProjectId)=w; override suspend fun copyOriginal(p:Path,w:Workspace)=Unit; override fun originalApk(w:Workspace)=w.originalApk; override suspend fun delete(w:Workspace)=Unit }, builds,
        InspectionUseCase(object:ApkAnalyzer { override suspend fun analyze(r:AnalysisRequest)=InspectionResult.Success(ApkAnalysis(ValidatedApk(Path.of("p/original.apk"),sha,1,emptyList()),emptySet(),emptyList())) }, object:ManifestParser { override suspend fun parse(a:ValidatedApk)=InspectionResult.Success(ManifestInfo("p",null,null,null,null,null,emptyList(),emptyList())) }, object:DexDetector { override suspend fun detect(a:ValidatedApk)=InspectionResult.Success(DexInfo(emptyList())) }))
    private class Builds : BuildWorkspaceStore { var created=0; override suspend fun create(w:Workspace,id:UUID):BuildWorkspace { created++; return BuildWorkspace(id,w.projectId,Path.of("p/work/builds/$id"),Path.of("p/work/builds/$id/input.apk")) } }
}
