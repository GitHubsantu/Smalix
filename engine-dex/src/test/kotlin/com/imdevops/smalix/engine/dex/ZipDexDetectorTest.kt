package com.imdevops.smalix.engine.dex

import com.imdevops.smalix.core.model.AnalysisFailure
import com.imdevops.smalix.core.model.InspectionResult
import com.imdevops.smalix.core.model.ValidatedApk
import com.imdevops.smalix.core.model.ZipEntryInfo
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.nio.file.Path

class ZipDexDetectorTest {
    private val detector = ZipDexDetector()

    @Test
    fun `orders primary and secondary dex entries numerically`() = runBlocking {
        val result = detector.detect(apkWith("classes3.dex", "classes.dex", "classes2.dex"))

        val success = assertInstanceOf(InspectionResult.Success::class.java, result)
        val info = assertInstanceOf(com.imdevops.smalix.core.model.DexInfo::class.java, success.value)
        assertEquals(listOf("classes.dex", "classes2.dex", "classes3.dex"), info.entries)
        assertEquals(true, info.isMultidex)
    }

    @Test
    fun `allows code-free APK inventory`() = runBlocking {
        val result = detector.detect(apkWith("AndroidManifest.xml"))

        val success = assertInstanceOf(InspectionResult.Success::class.java, result)
        val info = assertInstanceOf(com.imdevops.smalix.core.model.DexInfo::class.java, success.value)
        assertEquals(emptyList<String>(), info.entries)
        assertEquals(false, info.isMultidex)
    }

    @Test
    fun `rejects missing multidex sequence entry`() = runBlocking {
        val result = detector.detect(apkWith("classes.dex", "classes3.dex"))

        val failure = assertInstanceOf(InspectionResult.Failure::class.java, result)
        assertInstanceOf(AnalysisFailure.MalformedApk::class.java, failure.error)
    }

    private fun apkWith(vararg names: String): ValidatedApk = ValidatedApk(
        path = Path.of("sample.apk"),
        sha256 = "0".repeat(64),
        archiveSizeBytes = 0,
        entries = names.map { ZipEntryInfo(it, 0, 0) },
    )
}
