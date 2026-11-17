package com.imdevops.smalix

import com.imdevops.smalix.core.model.*
import com.imdevops.smalix.engine.api.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant

class ReopenProjectInspectionUseCaseTest {
    @Test fun `reports missing project without opening workspace`() = runBlocking {
        var opened = false
        val result = ReopenProjectInspectionUseCase(emptyRepository(), workspaceStore { opened = true; error("must not open") }, failingInspection())
            .inspect(ProjectId.random(), Instant.now().plusSeconds(1))
        assertEquals(ReopenInspectionResult.ProjectNotFound, result)
        assertEquals(false, opened)
    }

    @Test fun `reports unavailable workspace without inspection`() = runBlocking {
        val id = ProjectId.random()
        val result = ReopenProjectInspectionUseCase(repository(project(id)), workspaceStore { throw IllegalArgumentException("Workspace original APK is missing.") }, failingInspection())
            .inspect(id, Instant.now().plusSeconds(1))
        assertEquals(ReopenInspectionResult.WorkspaceUnavailable("Workspace original APK is missing."), result)
    }

    private fun failingInspection() = ProjectInspectionUseCase(emptyRepository(), InspectionUseCase(
        object : ApkAnalyzer { override suspend fun analyze(request: AnalysisRequest): InspectionResult<ApkAnalysis> = error("must not inspect") },
        object : ManifestParser { override suspend fun parse(apk: ValidatedApk): InspectionResult<ManifestInfo> = error("must not parse") },
        object : DexDetector { override suspend fun detect(apk: ValidatedApk): InspectionResult<DexInfo> = error("must not detect") },
    ))
    private fun emptyRepository() = repository(null)
    private fun repository(project: Project?) = object : ProjectRepository {
        override suspend fun create(project: Project) = Unit
        override suspend fun find(projectId: ProjectId) = project
        override suspend fun list() = emptyList<Project>()
        override suspend fun update(project: Project) = Unit
        override suspend fun delete(projectId: ProjectId) = Unit
    }
    private fun workspaceStore(open: suspend () -> Workspace) = object : WorkspaceStore {
        override suspend fun create(projectId: ProjectId): Workspace = error("unused")
        override suspend fun open(projectId: ProjectId) = open()
        override suspend fun copyOriginal(sourceApk: Path, workspace: Workspace) = Unit
        override fun originalApk(workspace: Workspace) = workspace.originalApk
        override suspend fun delete(workspace: Workspace) = Unit
    }
    private fun project(id: ProjectId) = Project(id, "Sample", Instant.EPOCH, null, RelativePath.of("projects/x"), RelativePath.of("projects/x/original.apk"), null, null, null, ProjectAnalysisStatus.NOT_ANALYZED, null)
}
