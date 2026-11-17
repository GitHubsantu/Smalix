package com.imdevops.smalix.engine.apk

import com.imdevops.smalix.core.model.AnalysisFailure
import com.imdevops.smalix.core.model.AnalysisRequest
import com.imdevops.smalix.core.model.InspectionResult
import com.imdevops.smalix.core.model.ProjectId
import com.imdevops.smalix.core.model.ZipSafetyLimits
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipApkAnalyzerTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private val analyzer = ZipApkAnalyzer()

    @Test
    fun `analyzes a valid archive without extracting it`() = runBlocking {
        val apk = zip("valid.apk", mapOf(
            "AndroidManifest.xml" to "manifest".toByteArray(),
            "lib/arm64-v8a/libdemo.so" to byteArrayOf(1, 2),
            "classes.dex" to byteArrayOf(3, 4),
        ))

        val result = analyzer.analyze(request(apk))

        val success = assertInstanceOf(InspectionResult.Success::class.java, result)
        val analysis = assertInstanceOf(com.imdevops.smalix.core.model.ApkAnalysis::class.java, success.value)
        assertEquals(setOf("arm64-v8a"), analysis.abiNames)
        assertEquals(3, analysis.archive.entries.size)
        assertEquals(64, analysis.archive.sha256.length)
    }

    @Test
    fun `reports no ABI when archive has no native library entries`() = runBlocking {
        val apk = zip("no-native.apk", mapOf(
            "AndroidManifest.xml" to "manifest".toByteArray(),
            "classes.dex" to byteArrayOf(3, 4),
        ))

        val success = assertInstanceOf(InspectionResult.Success::class.java, analyzer.analyze(request(apk)))
        val analysis = assertInstanceOf(com.imdevops.smalix.core.model.ApkAnalysis::class.java, success.value)
        assertEquals(emptySet<String>(), analysis.abiNames)
    }

    @Test
    fun `rejects traversal entry paths`() = runBlocking {
        val apk = zip("traversal.apk", mapOf(
            "AndroidManifest.xml" to byteArrayOf(),
            "../escape" to byteArrayOf(1),
        ))

        val failure = assertInstanceOf(InspectionResult.Failure::class.java, analyzer.analyze(request(apk)))
        assertInstanceOf(AnalysisFailure.UnsafeArchive::class.java, failure.error)
    }

    @Test
    fun `rejects duplicate paths after slash normalization`() = runBlocking {
        val apk = zip("duplicate.apk", mapOf(
            "AndroidManifest.xml" to byteArrayOf(),
            "res\\values.xml" to byteArrayOf(1),
            "res/values.xml" to byteArrayOf(2),
        ))

        val failure = assertInstanceOf(InspectionResult.Failure::class.java, analyzer.analyze(request(apk)))
        assertInstanceOf(AnalysisFailure.UnsafeArchive::class.java, failure.error)
    }

    @Test
    fun `enforces total uncompressed size even for small entries`() = runBlocking {
        val apk = zip("total-size.apk", mapOf(
            "AndroidManifest.xml" to byteArrayOf(),
            "first.bin" to byteArrayOf(1, 2),
            "second.bin" to byteArrayOf(3, 4),
        ))
        val limits = ZipSafetyLimits(maxTotalUncompressedBytes = 3)

        val failure = assertInstanceOf(InspectionResult.Failure::class.java, analyzer.analyze(request(apk, limits)))
        val error = assertInstanceOf(AnalysisFailure.LimitExceeded::class.java, failure.error)
        assertEquals("total uncompressed size", error.limit)
    }

    @Test
    fun `applies compression ratio only above configured threshold`() = runBlocking {
        val apk = zip("ratio.apk", mapOf(
            "AndroidManifest.xml" to byteArrayOf(),
            "large.bin" to ByteArray(4_096) { 'A'.code.toByte() },
        ))
        val limits = ZipSafetyLimits(
            maxCompressionRatio = 1,
            compressionRatioMinimumUncompressedBytes = 1_024,
        )

        val failure = assertInstanceOf(InspectionResult.Failure::class.java, analyzer.analyze(request(apk, limits)))
        assertInstanceOf(AnalysisFailure.LimitExceeded::class.java, failure.error)
    }

    @Test
    fun `allows high compression ratio below configured threshold`() = runBlocking {
        val apk = zip("small-ratio.apk", mapOf(
            "AndroidManifest.xml" to byteArrayOf(),
            "small.bin" to ByteArray(512) { 'A'.code.toByte() },
        ))
        val limits = ZipSafetyLimits(
            maxCompressionRatio = 1,
            compressionRatioMinimumUncompressedBytes = 1_024,
        )

        assertInstanceOf(InspectionResult.Success::class.java, analyzer.analyze(request(apk, limits)))
    }

    @Test
    fun `rejects aab before reading archive`() = runBlocking {
        val aab = temporaryDirectory.resolve("bundle.aab")
        Files.write(aab, byteArrayOf())

        val failure = assertInstanceOf(InspectionResult.Failure::class.java, analyzer.analyze(request(aab)))
        val unsupported = assertInstanceOf(AnalysisFailure.UnsupportedFormat::class.java, failure.error)
        assertEquals(com.imdevops.smalix.core.model.InputFormat.ANDROID_APP_BUNDLE, unsupported.format)
    }

    private fun request(path: Path, limits: ZipSafetyLimits = ZipSafetyLimits()) = AnalysisRequest(
        projectId = ProjectId.random(),
        apkPath = path,
        deadline = Instant.now().plusSeconds(30),
        limits = limits,
    )

    private fun zip(fileName: String, entries: Map<String, ByteArray>): Path {
        val path = temporaryDirectory.resolve(fileName)
        ZipOutputStream(Files.newOutputStream(path)).use { output ->
            entries.forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                output.write(content)
                output.closeEntry()
            }
        }
        return path
    }
}
