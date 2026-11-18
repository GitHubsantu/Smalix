package com.imdevops.smalix

import com.imdevops.smalix.core.model.AnalysisRequest
import com.imdevops.smalix.core.model.ApkAnalysis
import com.imdevops.smalix.core.model.ApkSigningRequest
import com.imdevops.smalix.core.model.ApkSigningResult
import com.imdevops.smalix.core.model.BuildWorkspace
import com.imdevops.smalix.core.model.DexInfo
import com.imdevops.smalix.core.model.InspectionResult
import com.imdevops.smalix.core.model.ManifestInfo
import com.imdevops.smalix.core.model.ProjectId
import com.imdevops.smalix.core.model.SigningCertificateSummary
import com.imdevops.smalix.core.model.SigningCredentials
import com.imdevops.smalix.core.model.SigningKeyReferenceType
import com.imdevops.smalix.core.model.SigningProfile
import com.imdevops.smalix.core.model.SigningScheme
import com.imdevops.smalix.core.model.ValidatedApk
import com.imdevops.smalix.engine.api.ApkAnalyzer
import com.imdevops.smalix.engine.api.ApkSigningService
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

class ApkSigningApplicationUseCaseTest {
    @TempDir lateinit var temp: Path

    @Test fun `signs only aligned evidence then reinspects signed output and clears credentials`() = runBlocking {
        val workspace = BuildWorkspace(UUID.randomUUID(), ProjectId.random(), temp, temp.resolve("input.apk"))
        val aligned = temp.resolve("aligned.apk")
        Files.write(aligned, byteArrayOf(1, 2, 3))
        var analyzedPath: Path? = null
        val useCase = ApkSigningApplicationUseCase(
            object : ApkSigningService {
                override suspend fun sign(request: ApkSigningRequest): ApkSigningResult {
                    Files.copy(request.input.archive.path, request.outputPath)
                    return ApkSigningResult.Created(request.outputPath, SigningCertificateSummary("a".repeat(64), "CN=test"))
                }
            },
            SignedOutputReinspectionUseCase(
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
        val store = "store".toCharArray(); val key = "key".toCharArray()

        val result = useCase.sign(workspace, validated(aligned), profile(), SigningCredentials(store, key), Instant.MAX)

        val ready = assertInstanceOf(ApkSigningApplication.Ready::class.java, result)
        assertEquals(temp.resolve("signed.apk"), ready.output.archive.path)
        assertEquals(temp.resolve("signed.apk"), analyzedPath)
        assertTrue(store.all { it == '\u0000' }); assertTrue(key.all { it == '\u0000' })
    }

    @Test fun `rejects non aligned evidence without calling signer`() = runBlocking {
        val workspace = BuildWorkspace(UUID.randomUUID(), ProjectId.random(), temp, temp.resolve("input.apk"))
        val wrong = temp.resolve("other.apk"); Files.write(wrong, byteArrayOf(1))
        val useCase = ApkSigningApplicationUseCase(
            object : ApkSigningService { override suspend fun sign(request: ApkSigningRequest) = error("unused") },
            SignedOutputReinspectionUseCase(
                object : ApkAnalyzer { override suspend fun analyze(request: AnalysisRequest) = error("unused") },
                object : ManifestParser { override suspend fun parse(apk: ValidatedApk) = error("unused") },
                object : DexDetector { override suspend fun detect(apk: ValidatedApk) = error("unused") },
            ),
        )
        val credentials = SigningCredentials(charArrayOf('a'), charArrayOf('b'))

        val result = useCase.sign(workspace, validated(wrong), profile(), credentials, Instant.MAX)

        assertTrue(result is ApkSigningApplication.Rejected)
        assertTrue(credentials.storePassword.all { it == '\u0000' })
    }

    private fun validated(path: Path) = ValidatedBuildOutput(ValidatedApk(path, "a".repeat(64), Files.size(path), emptyList()), manifest(), DexInfo(emptyList()))
    private fun manifest() = ManifestInfo("com.example", null, null, 28, null, null, emptyList(), emptyList())
    private fun profile() = SigningProfile(UUID.randomUUID(), "test", SigningKeyReferenceType.PKCS12, "C:/test.p12", null, "test", "a".repeat(64), setOf(SigningScheme.V2), Instant.EPOCH, Instant.EPOCH, true)
}
