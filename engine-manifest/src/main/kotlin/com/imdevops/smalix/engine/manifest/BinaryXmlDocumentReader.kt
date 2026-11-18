package com.imdevops.smalix.engine.manifest

import com.imdevops.smalix.core.model.AnalysisFailure
import com.imdevops.smalix.core.model.InspectionResult

/** Bounded, lossless index of binary XML bytes; it does not write archives or manifests. */
class BinaryXmlDocumentReader(private val maxBytes: Int = DEFAULT_MAX_BYTES) {
    init { require(maxBytes > 0) }

    fun read(bytes: ByteArray): InspectionResult<BinaryXmlDocument> {
        if (bytes.size > maxBytes) return InspectionResult.Failure(
            AnalysisFailure.LimitExceeded("manifest bytes", "AndroidManifest.xml exceeds $maxBytes bytes."),
        )
        return try {
            require(bytes.size >= ROOT_HEADER_SIZE) { "Truncated binary XML header." }
            val reader = Reader(bytes)
            require(reader.u16(0) == XML && reader.u16(2) == ROOT_HEADER_SIZE) { "Invalid binary XML header." }
            require(reader.i32(4) == bytes.size) { "Binary XML declared size does not match its bytes." }

            var offset = ROOT_HEADER_SIZE
            val chunks = mutableListOf<BinaryXmlChunk>()
            while (offset < bytes.size) {
                require(bytes.size - offset >= CHUNK_HEADER_SIZE) { "Truncated binary XML chunk header." }
                val type = reader.u16(offset)
                val headerSize = reader.u16(offset + 2)
                val size = reader.i32(offset + 4)
                require(headerSize >= CHUNK_HEADER_SIZE && size >= headerSize) { "Invalid binary XML chunk size." }
                require(size <= bytes.size - offset) { "Binary XML chunk exceeds document bounds." }
                chunks += BinaryXmlChunk(offset, type, headerSize, size)
                offset += size
            }
            require(offset == bytes.size) { "Binary XML chunk traversal did not end at document boundary." }
            InspectionResult.Success(BinaryXmlDocument(bytes, chunks))
        } catch (error: IllegalArgumentException) {
            InspectionResult.Failure(AnalysisFailure.MalformedApk(error.message ?: "Malformed binary XML."))
        }
    }

    private class Reader(private val bytes: ByteArray) {
        fun u16(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8)
        fun i32(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8) or (u8(offset + 2) shl 16) or (u8(offset + 3) shl 24)
        private fun u8(offset: Int): Int {
            require(offset in bytes.indices) { "Truncated binary XML." }
            return bytes[offset].toInt() and 0xff
        }
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 16 * 1024 * 1024
        private const val XML = 0x0003
        private const val ROOT_HEADER_SIZE = 8
        private const val CHUNK_HEADER_SIZE = 8
    }
}

data class BinaryXmlChunk(val offset: Int, val type: Int, val headerSize: Int, val size: Int) {
    init { require(offset >= 8 && headerSize >= 8 && size >= headerSize) }
}

class BinaryXmlDocument internal constructor(bytes: ByteArray, chunks: List<BinaryXmlChunk>) {
    private val source = bytes.copyOf()
    val chunks: List<BinaryXmlChunk> = chunks.toList()

    /** Returns a defensive copy suitable for a later in-memory applier. */
    fun originalBytes(): ByteArray = source.copyOf()
}
