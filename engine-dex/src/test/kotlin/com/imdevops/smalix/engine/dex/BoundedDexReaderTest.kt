package com.imdevops.smalix.engine.dex

import com.imdevops.smalix.core.model.InspectionResult
import com.imdevops.smalix.core.model.ValidatedApk
import com.imdevops.smalix.core.model.ZipEntryInfo
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.outputStream

class BoundedDexReaderTest {
    @TempDir lateinit var temp: Path

    @Test fun `indexes a generated one class dex`() = runBlocking {
        val result = BoundedDexReader().index(apk(dex()))
        assertInstanceOf(InspectionResult.Success::class.java, result)
        val success = result as InspectionResult.Success<com.imdevops.smalix.core.model.DexIndex>
        assertEquals(listOf("Lsample/Main;"), success.value.classes.map { it.descriptor })
    }

    @Test fun `returns typed failure for malformed dex`() = runBlocking {
        val result = BoundedDexReader().index(apk(byteArrayOf(1, 2, 3)))
        assertInstanceOf(InspectionResult.Failure::class.java, result)
    }

    @Test fun `enforces configured class limit`() = runBlocking {
        val result = BoundedDexReader(maxClasses = 0).index(apk(dex()))
        assertInstanceOf(InspectionResult.Failure::class.java, result)
    }

    private fun apk(bytes: ByteArray): ValidatedApk {
        val path = temp.resolve("fixture.apk")
        ZipOutputStream(path.outputStream()).use { it.putNextEntry(ZipEntry("classes.dex")); it.write(bytes); it.closeEntry() }
        return ValidatedApk(path, "0".repeat(64), bytes.size.toLong(), listOf(ZipEntryInfo("classes.dex", bytes.size.toLong(), bytes.size.toLong())))
    }

    private fun dex(): ByteArray = ByteBuffer.allocate(180).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("dex\n035\u0000".toByteArray()); position(56); putInt(1); putInt(112); putInt(1); putInt(116); position(96); putInt(1); putInt(120)
        position(112); putInt(152); position(116); putInt(0); position(120); putInt(0); putInt(1); position(152); put(13); put("Lsample/Main;\u0000".toByteArray())
    }.array()
}
