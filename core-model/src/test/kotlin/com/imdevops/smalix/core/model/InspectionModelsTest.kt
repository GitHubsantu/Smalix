package com.imdevops.smalix.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class InspectionModelsTest {
    @Test
    fun `default ratio threshold is one mebibyte while total limit remains configured`() {
        val limits = ZipSafetyLimits()

        assertEquals(ZipSafetyLimits.MEBIBYTE, limits.compressionRatioMinimumUncompressedBytes)
        assertEquals(2 * ZipSafetyLimits.GIBIBYTE, limits.maxTotalUncompressedBytes)
        assertEquals(100, limits.maxCompressionRatio)
    }

    @Test
    fun `relative paths reject traversal and absolute paths`() {
        assertEquals("project/original.apk", RelativePath.of("project\\original.apk").value)
        assertThrows(IllegalArgumentException::class.java) { RelativePath.of("../original.apk") }
        assertThrows(IllegalArgumentException::class.java) { RelativePath.of("C:/original.apk") }
    }

    @Test
    fun `unsupported format must include a clear message`() {
        assertThrows(IllegalArgumentException::class.java) {
            AnalysisFailure.UnsupportedFormat(InputFormat.XAPK, " ")
        }
    }

    @Test
    fun `dex model rejects malformed entry names and duplicate classes`() {
        assertThrows(IllegalArgumentException::class.java) { DexEntry("classes01.dex", 0) }
        val clazz = DexClass("classes.dex", "Lsample/Main;", 0, emptyList(), emptyList())
        assertThrows(IllegalArgumentException::class.java) { DexIndex(listOf(DexEntry("classes.dex", 1)), listOf(clazz, clazz)) }
    }

    @Test fun `hook signature requires descriptor-shaped input`() {
        assertThrows(IllegalArgumentException::class.java) { HookSignature("sample.Main", "run", "()", "V") }
        assertEquals("()V", HookSignature("Lsample/Main;", "run", "()", "V").methodDescriptor)
    }
}
