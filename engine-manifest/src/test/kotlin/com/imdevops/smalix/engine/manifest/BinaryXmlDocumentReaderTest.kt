package com.imdevops.smalix.engine.manifest

import com.imdevops.smalix.core.model.AnalysisFailure
import com.imdevops.smalix.core.model.InspectionResult
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BinaryXmlDocumentReaderTest {
    @Test fun `indexes unknown chunks and preserves original bytes`() {
        val bytes = documentWithChunk(type = 0x7777, payload = byteArrayOf(1, 2, 3, 4))

        val result = BinaryXmlDocumentReader().read(bytes)

        val document = assertInstanceOf(InspectionResult.Success::class.java, result).value as BinaryXmlDocument
        assertEquals(listOf(BinaryXmlChunk(8, 0x7777, 8, 12)), document.chunks)
        assertArrayEquals(bytes, document.originalBytes())
        document.originalBytes()[0] = 0
        assertArrayEquals(bytes, document.originalBytes())
    }

    @Test fun `rejects chunk extending past document boundary`() {
        val bytes = documentWithChunk(type = 1, payload = byteArrayOf()).also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(12, 32) }

        val result = BinaryXmlDocumentReader().read(bytes)

        val failure = assertInstanceOf(InspectionResult.Failure::class.java, result)
        assertInstanceOf(AnalysisFailure.MalformedApk::class.java, failure.error)
    }

    @Test fun `builds an immutable typed element tree`() {
        val start = startTag(nameIndex = 0, attributes = listOf(BinaryXmlAttribute(-1, 1, -1, 0x10, 26)))
        val bytes = document(start, endTag(nameIndex = 0))

        val result = BinaryXmlDocumentReader().read(bytes)

        val document = assertInstanceOf(InspectionResult.Success::class.java, result).value as BinaryXmlDocument
        assertEquals(listOf(BinaryXmlElement(-1, 0, listOf(BinaryXmlAttribute(-1, 1, -1, 0x10, 26)), emptyList())), document.rootElements)
    }

    @Test fun `enforces document byte limit`() {
        val result = BinaryXmlDocumentReader(maxBytes = 8).read(documentWithChunk(type = 1, payload = byteArrayOf()))

        val failure = assertInstanceOf(InspectionResult.Failure::class.java, result)
        assertInstanceOf(AnalysisFailure.LimitExceeded::class.java, failure.error)
    }

    private fun documentWithChunk(type: Int, payload: ByteArray): ByteArray {
        val bytes = ByteBuffer.allocate(8 + 8 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        bytes.putShort(0x0003).putShort(8).putInt(bytes.capacity())
        bytes.putShort(type.toShort()).putShort(8).putInt(8 + payload.size).put(payload)
        return bytes.array()
    }

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
}
