package com.imdevops.smalix

import com.imdevops.smalix.core.model.*
import com.imdevops.smalix.engine.api.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant

class ProjectInspectionUseCaseTest {
    @Test fun `persists analysis metadata after successful read-only inspection`() = runBlocking {
        val repository = RecordingRepository()
        val useCase = ProjectInspectionUseCase(repository, inspectionUseCase(InspectionResult.Success(summary())))

        val result = useCase.inspect(project(), Path.of("original.apk"), Instant.now().plusSeconds(1))

        assertEquals(ProjectAnalysisStatus.ANALYZED, repository.updates.last().analysisStatus)
        assertEquals("a".repeat(64), repository.updates.last().originalSha256)
        assertEquals(123L, repository.updates.last().originalSizeBytes)
        assertEquals(result, InspectionResult.Success(summary()))
    }

    @Test fun `persists a typed failure message`() = runBlocking {
        val repository = RecordingRepository()
        val failure = AnalysisFailure.MalformedApk("Manifest is corrupt.")
        val result = ProjectInspectionUseCase(repository, inspectionUseCase(InspectionResult.Failure(failure)))
            .inspect(project(), Path.of("original.apk"), Instant.now().plusSeconds(1))

        assertEquals(InspectionResult.Failure(failure), result)
        assertEquals(ProjectAnalysisStatus.FAILED, repository.updates.last().analysisStatus)
        assertEquals("Manifest is corrupt.", repository.updates.last().analysisError)
    }

    private fun inspectionUseCase(result: InspectionResult<InspectionSummary>) = InspectionUseCase(
        object : ApkAnalyzer { override suspend fun analyze(request: AnalysisRequest): InspectionResult<ApkAnalysis> = when (result) {
            is InspectionResult.Success -> InspectionResult.Success(result.value.apk)
            is InspectionResult.Failure -> result
        } },
        object : ManifestParser { override suspend fun parse(apk: ValidatedApk): InspectionResult<ManifestInfo> = when (result) {
            is InspectionResult.Success -> InspectionResult.Success(result.value.manifest)
            is InspectionResult.Failure -> result
        } },
        object : DexDetector { override suspend fun detect(apk: ValidatedApk): InspectionResult<DexInfo> = when (result) {
            is InspectionResult.Success -> InspectionResult.Success(result.value.dex)
            is InspectionResult.Failure -> result
        } },
    )

    private fun summary() = InspectionSummary(
        ApkAnalysis(ValidatedApk(Path.of("original.apk"), "a".repeat(64), 123, emptyList()), emptySet(), emptyList()),
        ManifestInfo("sample", null, null, null, null, null, emptyList(), emptyList()), DexInfo(emptyList()),
    )
    private fun project() = Project(ProjectId.random(), "Sample", Instant.EPOCH, null, RelativePath.of("projects/sample"), RelativePath.of("projects/sample/original.apk"), null, "sample.apk", null, ProjectAnalysisStatus.NOT_ANALYZED, null)

    private class RecordingRepository : ProjectRepository {
        val updates = mutableListOf<Project>()
        override suspend fun create(project: Project) = Unit
        override suspend fun find(projectId: ProjectId): Project? = null
        override suspend fun list(): List<Project> = emptyList()
        override suspend fun update(project: Project) { updates += project }
        override suspend fun delete(projectId: ProjectId) = Unit
    }
}
