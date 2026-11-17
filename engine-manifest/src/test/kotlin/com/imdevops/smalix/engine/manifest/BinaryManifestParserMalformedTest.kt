package com.imdevops.smalix.engine.manifest

import com.imdevops.smalix.core.model.AnalysisFailure
import com.imdevops.smalix.core.model.InspectionResult
import com.imdevops.smalix.core.model.ValidatedApk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BinaryManifestParserMalformedTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `returns malformed apk for invalid binary XML`() = runBlocking {
        val result = BinaryManifestParser().parse(apk("AndroidManifest.xml" to byteArrayOf(3, 0, 8, 0, 1, 0, 0, 0)))
        val failure = assertInstanceOf(InspectionResult.Failure::class.java, result)
        assertInstanceOf(AnalysisFailure.MalformedApk::class.java, failure.error)
    }

    @Test
    fun `returns malformed apk when manifest entry is absent`() = runBlocking {
        val result = BinaryManifestParser().parse(apk("classes.dex" to byteArrayOf()))
        val failure = assertInstanceOf(InspectionResult.Failure::class.java, result)
        assertInstanceOf(AnalysisFailure.MalformedApk::class.java, failure.error)
    }

    private fun apk(vararg entries: Pair<String, ByteArray>): ValidatedApk {
        val path = temporaryDirectory.resolve("fixture.apk")
        ZipOutputStream(Files.newOutputStream(path)).use { zip -> entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
        return ValidatedApk(path, "0".repeat(64), Files.size(path), emptyList())
    }
}
