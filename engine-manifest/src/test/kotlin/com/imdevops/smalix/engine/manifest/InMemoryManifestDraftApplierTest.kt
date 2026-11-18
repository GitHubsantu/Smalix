package com.imdevops.smalix.engine.manifest

import com.imdevops.smalix.core.model.InspectionResult
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class InMemoryManifestDraftApplierTest {
    private val reader = BinaryXmlDocumentReader()
    private val applier = InMemoryManifestDraftApplier()

    @Test fun `applies an equal size UTF-8 literal replacement to an in-memory copy`() {
        val source = manifest("Old")
        val document = success(reader.read(source))
        val plan = ManifestDraftApplicationPlan.Ready(listOf(ManifestStringReplacement(listOf(0, 0), 0, 4, "Old", "New")))

        val result = applier.apply(document, plan)

        val applied = assertInstanceOf(InMemoryManifestDraftApplication.Applied::class.java, result)
        assertArrayEquals(source, document.originalBytes())
        val replacement = applied.manifestBytes()
        assertEquals(source.size, replacement.size)
        assertEquals("New", success(reader.read(replacement)).strings[4])
    }

    @Test fun `rejects a replacement whose encoded size changes`() {
        val document = success(reader.read(manifest("Old")))
        val plan = ManifestDraftApplicationPlan.Ready(listOf(ManifestStringReplacement(listOf(0, 0), 0, 4, "Old", "Longer")))

        val result = applier.apply(document, plan)

        val rejected = assertInstanceOf(InMemoryManifestDraftApplication.Rejected::class.java, result)
        assertTrue(rejected.message.contains("preserve"))
    }

    @Test fun `applies an equal size UTF-16 literal replacement`() {
        val document = success(reader.read(manifest("Old", utf8 = false)))
        val plan = ManifestDraftApplicationPlan.Ready(listOf(ManifestStringReplacement(listOf(0, 0), 0, 4, "Old", "New")))

        val result = applier.apply(document, plan)

        val applied = assertInstanceOf(InMemoryManifestDraftApplication.Applied::class.java, result)
        assertEquals("New", success(reader.read(applied.manifestBytes())).strings[4])
    }

    @Test fun `returns a defensive no-op replacement`() {
        val source = manifest("Old")
        val document = success(reader.read(source))

        val result = applier.apply(document, ManifestDraftApplicationPlan.Ready(emptyList()))

        val applied = assertInstanceOf(InMemoryManifestDraftApplication.Applied::class.java, result)
        assertArrayEquals(source, applied.manifestBytes())
        applied.manifestBytes()[0] = 0
        assertArrayEquals(source, applied.manifestBytes())
    }

    private fun success(result: InspectionResult<BinaryXmlDocument>): BinaryXmlDocument =
        (assertInstanceOf(InspectionResult.Success::class.java, result).value as BinaryXmlDocument)

    private fun manifest(label: String, utf8: Boolean = true): ByteArray = document(
        if (utf8) stringPoolUtf8("manifest", "application", ANDROID_NAMESPACE, "label", label) else stringPoolUtf16("manifest", "application", ANDROID_NAMESPACE, "label", label),
        startTag(nameIndex = 0, attributes = emptyList()),
        startTag(nameIndex = 1, attributes = listOf(BinaryXmlAttribute(2, 3, 4, 0x03, 4))),
        endTag(nameIndex = 1),
        endTag(nameIndex = 0),
    )

    private fun document(vararg chunks: ByteArray): ByteArray {
        val total = 8 + chunks.sumOf { it.size }
        return ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0x0003).putShort(8).putInt(total)
            chunks.forEach(::put)
        }.array()
    }

    private fun startTag(nameIndex: Int, attributes: List<BinaryXmlAttribute>): ByteArray {
        val bytes = ByteBuffer.allocate(36 + attributes.size * 20).order(ByteOrder.LITTLE_ENDIAN)
        bytes.putShort(0x0102).putShort(16).putInt(bytes.capacity()).putInt(0).putInt(-1).putInt(-1).putInt(nameIndex)
        bytes.putShort(20).putShort(20).putShort(attributes.size.toShort()).putShort(0).putShort(0).putShort(0)
        attributes.forEach { attribute ->
            bytes.putInt(attribute.namespaceIndex).putInt(attribute.nameIndex).putInt(attribute.rawValueIndex).putShort(8).put(0).put(attribute.valueType.toByte()).putInt(attribute.valueData)
        }
        return bytes.array()
    }

    private fun endTag(nameIndex: Int): ByteArray = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply {
        putShort(0x0103).putShort(16).putInt(24).putInt(0).putInt(-1).putInt(-1).putInt(nameIndex)
    }.array()

    private fun stringPoolUtf8(vararg values: String): ByteArray {
        val strings = values.map { value -> byteArrayOf(value.length.toByte(), value.toByteArray().size.toByte()) + value.toByteArray() + byteArrayOf(0) }
        val offsets = strings.runningFold(0) { offset, value -> offset + value.size }.dropLast(1)
        val size = 28 + values.size * 4 + strings.sumOf { it.size }
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0x0001).putShort(28).putInt(size).putInt(values.size).putInt(0).putInt(0x100).putInt(28 + values.size * 4).putInt(0)
            offsets.forEach(::putInt)
            strings.forEach(::put)
        }.array()
    }

    private fun stringPoolUtf16(vararg values: String): ByteArray {
        val strings = values.map { value -> ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.length.toShort()).array() + value.toByteArray(Charsets.UTF_16LE) + byteArrayOf(0, 0) }
        val offsets = strings.runningFold(0) { offset, value -> offset + value.size }.dropLast(1)
        val size = 28 + values.size * 4 + strings.sumOf { it.size }
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0x0001).putShort(28).putInt(size).putInt(values.size).putInt(0).putInt(0).putInt(28 + values.size * 4).putInt(0)
            offsets.forEach(::putInt)
            strings.forEach(::put)
        }.array()
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
