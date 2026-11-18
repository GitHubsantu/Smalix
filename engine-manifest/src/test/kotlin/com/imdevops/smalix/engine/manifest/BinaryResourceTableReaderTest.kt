package com.imdevops.smalix.engine.manifest

import com.imdevops.smalix.core.model.AnalysisFailure
import com.imdevops.smalix.core.model.InspectionResult
import com.imdevops.smalix.core.model.ResourceValue
import com.imdevops.smalix.core.model.ValidatedApk
import com.imdevops.smalix.core.model.ZipEntryInfo
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

    @Test fun `uses declared UTF16 lengths without merging adjacent values`() = runBlocking {
        val entries = index(resourceTable(listOf("first value", "second value")))
        assertEquals(listOf("first value", "second value"), entries.map { (it.value as ResourceValue.Literal).value })
    }

    @Test fun `uses UTF8 byte length after a multi-byte length prefix`() = runBlocking {
        val first = "a".repeat(128)
        val entries = index(resourceTable(listOf(first, "next"), globalUtf8 = true))
        assertEquals(listOf(first, "next"), entries.map { (it.value as ResourceValue.Literal).value })
    }

    private suspend fun index(table: ByteArray) = run {
        val apk = temporaryDirectory.resolve("table.apk")
        ZipOutputStream(Files.newOutputStream(apk)).use { zip ->
            zip.putNextEntry(ZipEntry("resources.arsc"))
            zip.write(table)
            zip.closeEntry()
        }
        val result = BinaryResourceTableReader().index(
            ValidatedApk(apk, "a".repeat(64), Files.size(apk), listOf(ZipEntryInfo("resources.arsc", table.size.toLong(), table.size.toLong()))),
        )
        (result as InspectionResult.Success).value.entries
    }

    private fun resourceTable(values: List<String>, globalUtf8: Boolean = false): ByteArray {
        val global = stringPool(values, globalUtf8)
        val types = stringPool(listOf("string"))
        val keys = stringPool(values.indices.map { "value$it" })
        val type = typeChunk(values.size)
        val packageHeader = 288
        val packageSize = packageHeader + types.size + keys.size + type.size
        val pkg = ByteBuffer.allocate(packageSize).order(ByteOrder.LITTLE_ENDIAN)
        pkg.putShort(0x0200.toShort()); pkg.putShort(packageHeader.toShort()); pkg.putInt(packageSize); pkg.putInt(0x7f)
        val packageName = "test.pkg".toByteArray(Charsets.UTF_16LE)
        pkg.put(packageName); repeat(256 - packageName.size) { pkg.put(0) }
        pkg.putInt(packageHeader); pkg.putInt(0); pkg.putInt(packageHeader + types.size); pkg.putInt(0); pkg.putInt(0)
        pkg.put(types); pkg.put(keys); pkg.put(type)

        val size = 12 + global.size + pkg.array().size
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(2); putShort(12); putInt(size); putInt(1); put(global); put(pkg.array())
        }.array()
    }

    private fun typeChunk(entries: Int): ByteArray {
        val header = 48
        val entriesStart = header + entries * 4
        val size = entriesStart + entries * 16
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0x0201.toShort()); putShort(header.toShort()); putInt(size)
            put(1); put(0); putShort(0); putInt(entries); putInt(entriesStart)
            putInt(28); repeat(24) { put(0) }
            repeat(entries) { putInt(it * 16) }
            repeat(entries) { index ->
                putShort(8); putShort(0); putInt(index)
                putShort(8); put(0); put(3); putInt(index)
            }
        }.array()
    }

    private fun stringPool(strings: List<String>, utf8: Boolean = false): ByteArray {
        val content = ByteArrayOutputStream()
        val offsets = strings.map {
            val offset = content.size()
            if (utf8) writeUtf8(content, it) else writeUtf16(content, it)
            offset
        }
        val header = 28
        val stringsStart = header + strings.size * 4
        val size = align4(stringsStart + content.size())
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(1); putShort(header.toShort()); putInt(size); putInt(strings.size); putInt(0)
            putInt(if (utf8) 0x100 else 0); putInt(stringsStart); putInt(0)
            offsets.forEach(::putInt); put(content.toByteArray())
        }.array()
    }

    private fun writeUtf16(output: ByteArrayOutputStream, value: String) {
        val length = value.length
        if (length <= 0x7fff) writeShort(output, length) else { writeShort(output, 0x8000 or (length ushr 16)); writeShort(output, length and 0xffff) }
        output.write(value.toByteArray(Charsets.UTF_16LE)); writeShort(output, 0)
    }

    private fun writeUtf8(output: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeLength8(output, value.length); writeLength8(output, bytes.size); output.write(bytes); output.write(0)
    }

    private fun writeLength8(output: ByteArrayOutputStream, length: Int) {
        if (length < 0x80) output.write(length) else { output.write(0x80 or (length ushr 8)); output.write(length and 0xff) }
    }

    private fun writeShort(output: ByteArrayOutputStream, value: Int) { output.write(value and 0xff); output.write(value ushr 8 and 0xff) }
    private fun align4(value: Int) = (value + 3) and 3.inv()
}
