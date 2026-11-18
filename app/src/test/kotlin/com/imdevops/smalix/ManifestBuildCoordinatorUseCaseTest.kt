package com.imdevops.smalix

import com.imdevops.smalix.core.model.*
import com.imdevops.smalix.engine.api.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

class ManifestBuildCoordinatorUseCaseTest {
    @Test fun `matching draft uses the fresh preflight workspace`() = runBlocking {
        val fixture = fixture(); var applied = 0
        val coordinator = ManifestBuildCoordinatorUseCase(fixture.preflight) { workspace, _, _ ->
            applied++; assertEquals(fixture.build, workspace); ManifestBuildApplication.Ready(fixture.output)
        }

        val result = coordinator.apply(fixture.request, fixture.draft, Instant.MAX)

        val ready = assertInstanceOf(ManifestBuildCoordinatorResult.Ready::class.java, result)
        assertEquals(fixture.output, ready.output)
        assertEquals(1, fixture.builds.created); assertEquals(1, applied)
    }

    @Test fun `mismatched draft SHA rejects before preflight`() = runBlocking {
        val fixture = fixture(); var applied = 0
        val coordinator = ManifestBuildCoordinatorUseCase(fixture.preflight) { _, _, _ -> applied++; error("unused") }

        val result = coordinator.apply(fixture.request, fixture.draft.copy(originalApkSha256 = "b".repeat(64)), Instant.MAX)

        assertTrue(result is ManifestBuildCoordinatorResult.Rejected)
        assertEquals(0, fixture.builds.created); assertEquals(0, applied)
    }

    private fun fixture(): Fixture {
        val id = ProjectId.random(); val sha = "a".repeat(64); val manifest = ManifestInfo("com.example", null, null, null, null, ManifestLabel.Literal("Old"), emptyList(), emptyList())
        val project = Project(id, "P", Instant.EPOCH, null, RelativePath.of("projects/p"), RelativePath.of("projects/p/original.apk"), sha, "p.apk", 1, ProjectAnalysisStatus.ANALYZED, null)
        val workspace = Workspace(id, Path.of("p"), Path.of("p/original.apk"), Path.of("p/work"), Path.of("p/logs"))
        val build = BuildWorkspace(UUID.randomUUID(), id, Path.of("p/work/builds/b"), Path.of("p/work/builds/b/input.apk"))
        val builds = Builds(build)
        val inspection = InspectionUseCase(
            object : ApkAnalyzer { override suspend fun analyze(request: AnalysisRequest) = InspectionResult.Success(ApkAnalysis(ValidatedApk(request.apkPath, sha, 1, emptyList()), emptySet(), emptyList())) },
            object : ManifestParser { override suspend fun parse(apk: ValidatedApk) = InspectionResult.Success(manifest) },
            object : DexDetector { override suspend fun detect(apk: ValidatedApk) = InspectionResult.Success(DexInfo(emptyList())) },
        )
        val preflight = BuildPreflightUseCase(
            object : ProjectRepository { override suspend fun create(project: Project) = Unit; override suspend fun find(projectId: ProjectId) = project.takeIf { it.id == projectId }; override suspend fun list() = listOf(project); override suspend fun update(project: Project) = Unit; override suspend fun delete(projectId: ProjectId) = Unit },
            object : WorkspaceStore { override suspend fun create(projectId: ProjectId) = error("unused"); override suspend fun open(projectId: ProjectId) = workspace; override suspend fun copyOriginal(sourceApk: Path, workspace: Workspace) = Unit; override fun originalApk(workspace: Workspace) = workspace.originalApk; override suspend fun delete(workspace: Workspace) = Unit },
            builds, inspection,
        )
        val request = BuildRequest(UUID.randomUUID(), id, sha, RelativePath.of("exports/x.apk"), Instant.EPOCH)
        val output = ValidatedBuildOutput(ValidatedApk(build.root.resolve("manifest-applied.apk"), sha, 1, emptyList()), manifest, DexInfo(emptyList()))
        return Fixture(preflight, builds, build, request, ManifestDraft.from(id, sha, manifest), output)
    }

    private data class Fixture(val preflight: BuildPreflightUseCase, val builds: Builds, val build: BuildWorkspace, val request: BuildRequest, val draft: ManifestDraft, val output: ValidatedBuildOutput)
    private class Builds(private val build: BuildWorkspace) : BuildWorkspaceStore {
        var created = 0
        override suspend fun create(workspace: Workspace, buildId: UUID): BuildWorkspace { created++; return build }
    }
}
