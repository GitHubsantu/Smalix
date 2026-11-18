package com.imdevops.smalix.engine.manifest

import com.imdevops.smalix.core.model.AnalysisFailure
import com.imdevops.smalix.core.model.InspectionResult
import com.imdevops.smalix.core.model.ValidatedApk
import com.imdevops.smalix.core.model.ZipEntryInfo
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BinaryResourceTableReaderTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test fun `rejects malformed resource table without extraction`() = runBlocking {
        val apk = temporaryDirectory.resolve("bad.apk")
        ZipOutputStream(Files.newOutputStream(apk)).use { zip -> zip.putNextEntry(ZipEntry("resources.arsc")); zip.write(byteArrayOf(2, 0)); zip.closeEntry() }
        val result = BinaryResourceTableReader().index(ValidatedApk(apk, "a".repeat(64), Files.size(apk), listOf(ZipEntryInfo("resources.arsc", 2, 2))))
        assertTrue((result as InspectionResult.Failure).error is AnalysisFailure.MalformedApk)
    }
}
