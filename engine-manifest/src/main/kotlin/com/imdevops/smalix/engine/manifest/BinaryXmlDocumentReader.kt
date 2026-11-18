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
            var strings: List<String> = emptyList()
            val roots = mutableListOf<ElementBuilder>()
            val stack = ArrayDeque<ElementBuilder>()
            while (offset < bytes.size) {
                require(bytes.size - offset >= CHUNK_HEADER_SIZE) { "Truncated binary XML chunk header." }
                val type = reader.u16(offset)
                val headerSize = reader.u16(offset + 2)
                val size = reader.i32(offset + 4)
                require(headerSize >= CHUNK_HEADER_SIZE && size >= headerSize) { "Invalid binary XML chunk size." }
                require(size <= bytes.size - offset) { "Binary XML chunk exceeds document bounds." }
                chunks += BinaryXmlChunk(offset, type, headerSize, size)
                when (type) {
                    STRING_POOL -> strings = readStringPool(reader, offset, headerSize, size)
                    START_TAG -> readStartTag(reader, offset, headerSize, size).also { element ->
                        if (stack.isEmpty()) roots += element else stack.last().children += element
                        stack += element
                    }
                    END_TAG -> {
                        val (namespaceIndex, nameIndex) = readEndTag(reader, offset, headerSize, size)
                        val open = if (stack.isEmpty()) throw IllegalArgumentException("Unmatched binary XML end tag.") else stack.removeLast()
                        require(open.namespaceIndex == namespaceIndex && open.nameIndex == nameIndex) { "Mismatched binary XML end tag." }
                    }
                }
                offset += size
            }
            require(offset == bytes.size) { "Binary XML chunk traversal did not end at document boundary." }
            require(stack.isEmpty()) { "Unclosed binary XML element." }
            InspectionResult.Success(BinaryXmlDocument(bytes, chunks, strings, roots.map { it.freeze() }))
        } catch (error: IllegalArgumentException) {
            InspectionResult.Failure(AnalysisFailure.MalformedApk(error.message ?: "Malformed binary XML."))
        }
    }

    private class Reader(private val bytes: ByteArray) {
        fun u16(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8)
        fun i32(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8) or (u8(offset + 2) shl 16) or (u8(offset + 3) shl 24)
        fun u8(offset: Int): Int {
            require(offset in bytes.indices) { "Truncated binary XML." }
            return bytes[offset].toInt() and 0xff
        }
    }

    private fun readStartTag(reader: Reader, offset: Int, headerSize: Int, size: Int): ElementBuilder {
        require(headerSize >= 16 && size >= 36) { "Invalid binary XML start tag." }
        val namespaceIndex = reader.i32(offset + 16)
        val nameIndex = reader.i32(offset + 20)
        val attributeStart = reader.u16(offset + 24)
        val attributeSize = reader.u16(offset + 26)
        val attributeCount = reader.u16(offset + 28)
        require(nameIndex >= 0 && attributeSize >= 20) { "Invalid binary XML start tag attributes." }
        val firstAttribute = offset + 16 + attributeStart
        require(firstAttribute >= offset + headerSize && attributeCount <= (offset + size - firstAttribute) / attributeSize) { "Binary XML attributes exceed tag bounds." }
        val attributes = List(attributeCount) { index ->
            val at = firstAttribute + index * attributeSize
            BinaryXmlAttribute(
                namespaceIndex = reader.i32(at), nameIndex = reader.i32(at + 4), rawValueIndex = reader.i32(at + 8),
                valueType = reader.u8(at + 15), valueData = reader.i32(at + 16),
            )
        }
        return ElementBuilder(namespaceIndex, nameIndex, attributes)
    }

    private fun readStringPool(reader: Reader, offset: Int, headerSize: Int, size: Int): List<String> {
        require(headerSize >= 28) { "Invalid binary XML string pool." }
        val count = reader.i32(offset + 8)
        val styleCount = reader.i32(offset + 12)
        val flags = reader.i32(offset + 16)
        val dataStart = reader.i32(offset + 20)
        val stylesStart = reader.i32(offset + 24)
        require(count in 0..200_000 && dataStart in headerSize..size) { "Invalid binary XML string pool bounds." }
        require(styleCount >= 0) { "Invalid binary XML style count." }
        val offsetsEnd = offset + headerSize + count * Int.SIZE_BYTES
        val stringsEndOffset = if (styleCount == 0) size else stylesStart
        require(offsetsEnd <= offset + dataStart && stringsEndOffset in dataStart..size) {
            "Invalid binary XML string pool offsets."
        }
        val stringsEnd = offset + stringsEndOffset
        val utf8 = flags and 0x100 != 0
        return List(count) { index ->
            val at = offset + dataStart + reader.i32(offset + headerSize + index * 4)
            require(at in offset + dataStart until stringsEnd) { "Invalid binary XML string offset." }
            if (utf8) readUtf8(reader, at, stringsEnd) else readUtf16(reader, at, stringsEnd)
        }
    }

    private fun readUtf8(reader: Reader, start: Int, end: Int): String {
        fun byte(at: Int): Int {
            require(at in start until end) { "Truncated UTF-8 string pool value." }
            return reader.u8(at)
        }
        fun length(at: Int): Pair<Int, Int> {
            val first = byte(at)
            return if (first and 0x80 == 0) first to 1 else ((first and 0x7f) shl 8 or byte(at + 1)) to 2
        }
        val first = length(start); val second = length(start + first.second); val text = start + first.second + second.second
        require(second.first <= end - text - 1 && byte(text + second.first) == 0) { "Invalid UTF-8 string pool value." }
        return String(ByteArray(second.first) { byte(text + it).toByte() }, Charsets.UTF_8)
    }

    private fun readUtf16(reader: Reader, start: Int, end: Int): String {
        fun unit(at: Int): Int {
            require(at >= start && at <= end - 2) { "Truncated UTF-16 string pool value." }
            return reader.u16(at)
        }
        val first = unit(start)
        val (length, lengthBytes) = if (first and 0x8000 == 0) first to 2 else ((first and 0x7fff) shl 16 or unit(start + 2)) to 4
        val text = start + lengthBytes
        require(length <= (end - text - 2) / 2 && unit(text + length * 2) == 0) { "Invalid UTF-16 string pool value." }
        return String(ByteArray(length * 2) { reader.u8(text + it).toByte() }, Charsets.UTF_16LE)
    }

    private fun readEndTag(reader: Reader, offset: Int, headerSize: Int, size: Int): Pair<Int, Int> {
        require(headerSize >= 16 && size >= 24) { "Invalid binary XML end tag." }
        return reader.i32(offset + 16) to reader.i32(offset + 20)
    }

    private class ElementBuilder(
        val namespaceIndex: Int, val nameIndex: Int, val attributes: List<BinaryXmlAttribute>, val children: MutableList<ElementBuilder> = mutableListOf(),
    ) {
        fun freeze(): BinaryXmlElement = BinaryXmlElement(namespaceIndex, nameIndex, attributes, children.map { child -> child.freeze() })
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 16 * 1024 * 1024
        private const val XML = 0x0003
        private const val ROOT_HEADER_SIZE = 8
        private const val CHUNK_HEADER_SIZE = 8
        private const val START_TAG = 0x0102
        private const val END_TAG = 0x0103
        private const val STRING_POOL = 0x0001
    }
}

data class BinaryXmlChunk(val offset: Int, val type: Int, val headerSize: Int, val size: Int) {
    init { require(offset >= 8 && headerSize >= 8 && size >= headerSize) }
}

class BinaryXmlDocument internal constructor(bytes: ByteArray, chunks: List<BinaryXmlChunk>, strings: List<String>, roots: List<BinaryXmlElement>) {
    private val source = bytes.copyOf()
    val chunks: List<BinaryXmlChunk> = chunks.toList()
    val strings: List<String> = strings.toList()
    val rootElements: List<BinaryXmlElement> = roots.toList()

    /** Returns a defensive copy suitable for a later in-memory applier. */
    fun originalBytes(): ByteArray = source.copyOf()
}

data class BinaryXmlElement(
    val namespaceIndex: Int,
    val nameIndex: Int,
    val attributes: List<BinaryXmlAttribute>,
    val children: List<BinaryXmlElement>,
)

data class BinaryXmlAttribute(
    val namespaceIndex: Int,
    val nameIndex: Int,
    val rawValueIndex: Int,
    val valueType: Int,
    val valueData: Int,
)
