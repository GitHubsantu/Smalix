package com.imdevops.smalix

import com.imdevops.smalix.core.model.AnalysisFailure
import com.imdevops.smalix.core.model.AnalysisRequest
import com.imdevops.smalix.core.model.ApkAnalysis
import com.imdevops.smalix.core.model.BuildWorkspace
import com.imdevops.smalix.core.model.DexInfo
import com.imdevops.smalix.core.model.InspectionResult
import com.imdevops.smalix.core.model.ManifestInfo
import com.imdevops.smalix.core.model.ProjectId
import com.imdevops.smalix.core.model.ValidatedApk
import com.imdevops.smalix.engine.api.ApkAnalyzer
import com.imdevops.smalix.engine.api.DexDetector
import com.imdevops.smalix.engine.api.ManifestParser
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

class ManifestReplacementReinspectionUseCaseTest {
    @TempDir lateinit var temp: Path

    @Test fun `reinspects only fixed build output in ZIP then manifest order`() = runBlocking {
        val workspace = workspace()
        val output = workspace.root.resolve("manifest-applied.apk")
        Files.write(output, byteArrayOf(1))
        val archive = ValidatedApk(output, "a".repeat(64), 1, emptyList())
        var manifestCalls = 0; var dexCalls = 0
        val useCase = ManifestReplacementReinspectionUseCase(
            object : ApkAnalyzer {
                override suspend fun analyze(request: AnalysisRequest): InspectionResult<ApkAnalysis> {
                    assertEquals(output, request.apkPath)
                    return InspectionResult.Success(ApkAnalysis(archive, emptySet(), emptyList()))
                }
            },
            object : ManifestParser {
                override suspend fun parse(apk: ValidatedApk): InspectionResult<ManifestInfo> {
                    manifestCalls++
                    return InspectionResult.Success(manifest())
                }
            },
            object : DexDetector { override suspend fun detect(apk: ValidatedApk): InspectionResult<DexInfo> { dexCalls++; return InspectionResult.Success(DexInfo(emptyList())) } },
        )

        val result = useCase.reinspect(workspace, Instant.MAX)

        assertInstanceOf(ManifestReplacementReinspection.Ready::class.java, result)
        assertEquals(1, manifestCalls); assertEquals(1, dexCalls)
    }

    @Test fun `ZIP inspection failure short circuits manifest parser`() = runBlocking {
        val workspace = workspace()
        Files.write(workspace.root.resolve("manifest-applied.apk"), byteArrayOf(1))
        var manifestCalls = 0; var dexCalls = 0
        val useCase = ManifestReplacementReinspectionUseCase(
            object : ApkAnalyzer { override suspend fun analyze(request: AnalysisRequest) = InspectionResult.Failure(AnalysisFailure.MalformedApk("bad zip")) },
            object : ManifestParser { override suspend fun parse(apk: ValidatedApk): InspectionResult<ManifestInfo> { manifestCalls++; return InspectionResult.Success(manifest()) } },
            object : DexDetector { override suspend fun detect(apk: ValidatedApk): InspectionResult<DexInfo> { dexCalls++; return InspectionResult.Success(DexInfo(emptyList())) } },
        )

        val result = useCase.reinspect(workspace, Instant.MAX)

        assertInstanceOf(ManifestReplacementReinspection.InspectionFailed::class.java, result)
        assertEquals(0, manifestCalls); assertEquals(0, dexCalls)
    }

    @Test fun `missing output rejects before either engine`() = runBlocking {
        val workspace = workspace()
        val useCase = ManifestReplacementReinspectionUseCase(
            object : ApkAnalyzer { override suspend fun analyze(request: AnalysisRequest) = error("unused") },
            object : ManifestParser { override suspend fun parse(apk: ValidatedApk) = error("unused") },
            object : DexDetector { override suspend fun detect(apk: ValidatedApk) = error("unused") },
        )

        val result = useCase.reinspect(workspace, Instant.MAX)

        assertTrue(result is ManifestReplacementReinspection.Rejected)
    }

    @Test fun `DEX failure stops replacement reinspection after manifest validation`() = runBlocking {
        val workspace = workspace()
        Files.write(workspace.root.resolve("manifest-applied.apk"), byteArrayOf(1))
        val archive = ValidatedApk(workspace.root.resolve("manifest-applied.apk"), "a".repeat(64), 1, emptyList())
        var manifestCalls = 0
        var dexCalls = 0
        val useCase = ManifestReplacementReinspectionUseCase(
            object : ApkAnalyzer { override suspend fun analyze(request: AnalysisRequest) = InspectionResult.Success(ApkAnalysis(archive, emptySet(), emptyList())) },
            object : ManifestParser { override suspend fun parse(apk: ValidatedApk) = InspectionResult.Success(manifest()).also { manifestCalls++ } },
            object : DexDetector { override suspend fun detect(apk: ValidatedApk) = InspectionResult.Failure(AnalysisFailure.MalformedApk("bad dex")).also { dexCalls++ } },
        )

        val result = useCase.reinspect(workspace, Instant.MAX)

        assertTrue(result is ManifestReplacementReinspection.InspectionFailed)
        assertEquals(1, manifestCalls)
        assertEquals(1, dexCalls)
    }

    private fun workspace(): BuildWorkspace = BuildWorkspace(UUID.randomUUID(), ProjectId.random(), temp, temp.resolve("input.apk"))
    private fun manifest() = ManifestInfo("com.example", null, null, null, null, null, emptyList(), emptyList())
}
