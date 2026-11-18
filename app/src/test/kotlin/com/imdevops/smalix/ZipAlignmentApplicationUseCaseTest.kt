package com.imdevops.smalix

import com.imdevops.smalix.core.model.AnalysisRequest
import com.imdevops.smalix.core.model.ApkAnalysis
import com.imdevops.smalix.core.model.BuildWorkspace
import com.imdevops.smalix.core.model.DexInfo
import com.imdevops.smalix.core.model.InspectionResult
import com.imdevops.smalix.core.model.ManifestInfo
import com.imdevops.smalix.core.model.ProjectId
import com.imdevops.smalix.core.model.ValidatedApk
import com.imdevops.smalix.core.model.ZipAlignmentRequest
import com.imdevops.smalix.core.model.ZipAlignmentResult
import com.imdevops.smalix.engine.api.ApkAnalyzer
import com.imdevops.smalix.engine.api.DexDetector
import com.imdevops.smalix.engine.api.ManifestParser
import com.imdevops.smalix.engine.api.ZipAligner
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

class ZipAlignmentApplicationUseCaseTest {
    @TempDir lateinit var temp: Path

    @Test fun `aligns the validated replacement then reinspects the fixed aligned output`() = runBlocking {
        val workspace = workspace()
        val input = temp.resolve("manifest-applied.apk")
        Files.write(input, byteArrayOf(1, 2, 3))
        var analyzedPath: Path? = null
        val workflow = ZipAlignmentApplicationUseCase(
            object : ZipAligner {
                override suspend fun align(request: ZipAlignmentRequest): ZipAlignmentResult {
                    Files.copy(request.input.path, request.outputPath)
                    return ZipAlignmentResult.Created(request.outputPath)
                }
            },
            AlignedOutputReinspectionUseCase(
                object : ApkAnalyzer {
                    override suspend fun analyze(request: AnalysisRequest): InspectionResult<ApkAnalysis> {
                        analyzedPath = request.apkPath
                        return InspectionResult.Success(ApkAnalysis(ValidatedApk(request.apkPath, "b".repeat(64), Files.size(request.apkPath), emptyList()), emptySet(), emptyList()))
                    }
                },
                object : ManifestParser { override suspend fun parse(apk: ValidatedApk) = InspectionResult.Success(manifest()) },
                object : DexDetector { override suspend fun detect(apk: ValidatedApk) = InspectionResult.Success(DexInfo(emptyList())) },
            ),
        )

        val result = workflow.align(workspace, validated(input), Instant.MAX)

        val ready = assertInstanceOf(ZipAlignmentApplication.Ready::class.java, result)
        assertEquals(temp.resolve("aligned.apk"), ready.output.archive.path)
        assertEquals(temp.resolve("aligned.apk"), analyzedPath)
    }

    @Test fun `rejects evidence that does not identify manifest replacement output`() = runBlocking {
        val workspace = workspace()
        val wrong = temp.resolve("other.apk")
        Files.write(wrong, byteArrayOf(1))
        val workflow = ZipAlignmentApplicationUseCase(
            object : ZipAligner { override suspend fun align(request: ZipAlignmentRequest) = error("unused") },
            AlignedOutputReinspectionUseCase(
                object : ApkAnalyzer { override suspend fun analyze(request: AnalysisRequest) = error("unused") },
                object : ManifestParser { override suspend fun parse(apk: ValidatedApk) = error("unused") },
                object : DexDetector { override suspend fun detect(apk: ValidatedApk) = error("unused") },
            ),
        )

        val result = workflow.align(workspace, validated(wrong), Instant.MAX)

        assertTrue(result is ZipAlignmentApplication.Rejected)
    }

    private fun workspace() = BuildWorkspace(UUID.randomUUID(), ProjectId.random(), temp, temp.resolve("input.apk"))
    private fun validated(path: Path) = ValidatedBuildOutput(ValidatedApk(path, "a".repeat(64), Files.size(path), emptyList()), manifest(), DexInfo(emptyList()))
    private fun manifest() = ManifestInfo("com.example", null, null, null, null, null, emptyList(), emptyList())
}
