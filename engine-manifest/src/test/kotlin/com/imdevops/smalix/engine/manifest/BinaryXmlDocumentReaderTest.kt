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
}
