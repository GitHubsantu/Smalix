package com.imdevops.smalix

import com.imdevops.smalix.core.model.InspectionResult
import com.imdevops.smalix.core.model.InspectionSummary
import com.imdevops.smalix.core.model.ManifestLabel
import com.imdevops.smalix.core.model.ProjectId
import com.imdevops.smalix.core.model.AnalysisRequest
import com.imdevops.smalix.engine.apk.ZipApkAnalyzer
import com.imdevops.smalix.engine.dex.ZipDexDetector
import com.imdevops.smalix.engine.manifest.BinaryManifestParser
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class ChuturPaanFixtureIntegrationTest {
    @Test
    fun `inspects supplied ChuturPaan APK end to end`() = runBlocking {
        val path = System.getenv("SMALIX_CHUTURPAAN_FIXTURE")?.let(Path::of)
        assumeTrue(path != null && Files.isRegularFile(path), "No local ChuturPaan fixture supplied.")

        val result = InspectionUseCase(
            ZipApkAnalyzer(),
            BinaryManifestParser(),
            ZipDexDetector(),
        ).inspect(AnalysisRequest(ProjectId.random(), requireNotNull(path), Instant.now().plusSeconds(120)))

        val success = assertInstanceOf(InspectionResult.Success::class.java, result)
        val summary = assertInstanceOf(InspectionSummary::class.java, success.value)
        assertEquals("0b2c3dee174f49196f3c40836737d8b5c0c34b17b17031a05f5ba06380ffb45a", summary.apk.archive.sha256)
        assertEquals(setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64"), summary.apk.abiNames)
        assertEquals("com.paan.safa", summary.manifest.packageName)
        assertEquals("1.0.0", summary.manifest.versionName)
        assertEquals(4L, summary.manifest.versionCode)
        assertEquals(26, summary.manifest.minSdk)
        assertEquals(37, summary.manifest.targetSdk)
        assertEquals(ManifestLabel.ResourceReference("@0x7f0e0016"), summary.manifest.label)
        assertEquals(listOf("classes.dex"), summary.dex.entries)
    }
}
