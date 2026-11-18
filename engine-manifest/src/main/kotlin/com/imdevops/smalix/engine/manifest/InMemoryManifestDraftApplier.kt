package com.imdevops.smalix.engine.manifest

/**
 * Applies an already-approved plan to a defensive in-memory manifest copy only.
 * It never accesses ZIP/APK files and only replaces equal-size encoded string bytes.
 */
class InMemoryManifestDraftApplier {
    fun apply(document: BinaryXmlDocument, plan: ManifestDraftApplicationPlan.Ready): InMemoryManifestDraftApplication {
        return try {
            val bytes = document.originalBytes()
            require(plan.stringReplacements.map(ManifestStringReplacement::stringPoolIndex).distinct().size == plan.stringReplacements.size) {
                "A string-pool entry may be replaced only once."
            }
            val pool = stringPool(bytes, document)
            val output = bytes.copyOf()
            plan.stringReplacements.forEach { replacement ->
                require(replacement.stringPoolIndex in document.strings.indices) { "Replacement string-pool index is invalid." }
                val attribute = document.attributeAt(replacement.elementPath, replacement.attributeIndex)
                require(attribute.rawValueIndex == replacement.stringPoolIndex && attribute.valueType == TYPE_STRING) {
                    "Replacement target no longer identifies a literal string attribute."
                }
                require(document.strings[replacement.stringPoolIndex] == replacement.originalValue) {
                    "Replacement source string does not match the document."
                }
                val slot = pool.slot(replacement.stringPoolIndex)
                val encoded = replacement.replacementValue.toByteArray(slot.charset)
                require(replacement.replacementValue.length == slot.characterLength && encoded.size == slot.byteLength) {
                    "Replacement must preserve the original string length and encoding size."
                }
                encoded.copyInto(output, slot.valueOffset)
            }
            val verified = BinaryXmlDocumentReader(maxBytes = bytes.size.coerceAtLeast(1)).read(output)
            val replacement = (verified as? com.imdevops.smalix.core.model.InspectionResult.Success)?.value
                ?: throw IllegalArgumentException("Replacement manifest failed binary XML validation.")
            plan.stringReplacements.forEach { require(replacement.strings[it.stringPoolIndex] == it.replacementValue) }
            InMemoryManifestDraftApplication.Applied(output)
        } catch (error: IllegalArgumentException) {
            InMemoryManifestDraftApplication.Rejected(error.message ?: "Manifest replacement was rejected.")
        }
    }

    private fun stringPool(bytes: ByteArray, document: BinaryXmlDocument): StringPool {
        val chunks = document.chunks.filter { it.type == STRING_POOL }
        require(chunks.size == 1) { "Expected exactly one binary XML string pool." }
        val chunk = chunks.single()
        require(chunk.headerSize >= STRING_POOL_HEADER_SIZE) { "Invalid binary XML string pool." }
        val reader = Bytes(bytes)
        val count = reader.i32(chunk.offset + 8)
        val styleCount = reader.i32(chunk.offset + 12)
        val flags = reader.i32(chunk.offset + 16)
        val dataStart = reader.i32(chunk.offset + 20)
        val stylesStart = reader.i32(chunk.offset + 24)
        require(count == document.strings.size && styleCount >= 0 && dataStart in chunk.headerSize..chunk.size) { "Invalid binary XML string pool." }
        val tableEnd = chunk.offset + chunk.headerSize + count * Int.SIZE_BYTES
        val stringsEndOffset = if (styleCount == 0) chunk.size else stylesStart
        require(tableEnd <= chunk.offset + dataStart && stringsEndOffset in dataStart..chunk.size) { "Invalid binary XML string pool offsets." }
        return StringPool(reader, chunk.offset, chunk.headerSize, chunk.offset + dataStart, chunk.offset + stringsEndOffset, flags and UTF8_FLAG != 0)
    }

    private fun BinaryXmlDocument.attributeAt(path: List<Int>, index: Int): BinaryXmlAttribute {
        require(path.isNotEmpty() && index >= 0) { "Invalid replacement target." }
        var element = rootElements.getOrNull(path.first()) ?: throw IllegalArgumentException("Replacement element is missing.")
        path.drop(1).forEach { childIndex -> element = element.children.getOrNull(childIndex) ?: throw IllegalArgumentException("Replacement element is missing.") }
        return element.attributes.getOrNull(index) ?: throw IllegalArgumentException("Replacement attribute is missing.")
    }

    private class StringPool(
        private val reader: Bytes,
        private val offset: Int,
        private val headerSize: Int,
        private val dataStart: Int,
        private val dataEnd: Int,
        private val utf8: Boolean,
    ) {
        fun slot(index: Int): StringSlot {
            val relative = reader.i32(offset + headerSize + index * Int.SIZE_BYTES)
            require(relative >= 0) { "Invalid binary XML string offset." }
            val start = dataStart + relative
            require(start in dataStart until dataEnd) { "Invalid binary XML string offset." }
            return if (utf8) utf8Slot(start) else utf16Slot(start)
        }

        private fun utf8Slot(start: Int): StringSlot {
            val (characters, characterBytes) = length8(start)
            val (byteLength, byteLengthBytes) = length8(start + characterBytes)
            val valueOffset = start + characterBytes + byteLengthBytes
            require(byteLength <= dataEnd - valueOffset - 1 && reader.u8(valueOffset + byteLength) == 0) { "Invalid UTF-8 string pool value." }
            return StringSlot(valueOffset, byteLength, characters, Charsets.UTF_8)
        }

        private fun utf16Slot(start: Int): StringSlot {
            val (characters, lengthBytes) = length16(start)
            val valueOffset = start + lengthBytes
            require(characters <= (dataEnd - valueOffset - 2) / 2 && reader.u16(valueOffset + characters * 2) == 0) {
                "Invalid UTF-16 string pool value."
            }
            return StringSlot(valueOffset, characters * 2, characters, Charsets.UTF_16LE)
        }

        private fun length8(at: Int): Pair<Int, Int> {
            val first = byte(at)
            return if (first and 0x80 == 0) first to 1 else ((first and 0x7f) shl 8 or byte(at + 1)) to 2
        }

        private fun length16(at: Int): Pair<Int, Int> {
            val first = unit(at)
            return if (first and 0x8000 == 0) first to 2 else ((first and 0x7fff) shl 16 or unit(at + 2)) to 4
        }

        private fun byte(at: Int): Int {
            require(at in dataStart until dataEnd) { "Truncated UTF-8 string pool value." }
            return reader.u8(at)
        }

        private fun unit(at: Int): Int {
            require(at >= dataStart && at <= dataEnd - 2) { "Truncated UTF-16 string pool value." }
            return reader.u16(at)
        }
    }

    private class Bytes(private val bytes: ByteArray) {
        fun u8(offset: Int): Int {
            require(offset in bytes.indices) { "Truncated binary XML." }
            return bytes[offset].toInt() and 0xff
        }

        fun u16(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8)
        fun i32(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8) or (u8(offset + 2) shl 16) or (u8(offset + 3) shl 24)
    }

    private data class StringSlot(val valueOffset: Int, val byteLength: Int, val characterLength: Int, val charset: java.nio.charset.Charset)

    private companion object {
        const val STRING_POOL = 0x0001
        const val STRING_POOL_HEADER_SIZE = 28
        const val UTF8_FLAG = 0x100
        const val TYPE_STRING = 0x03
    }
}

sealed interface InMemoryManifestDraftApplication {
    class Applied internal constructor(bytes: ByteArray) : InMemoryManifestDraftApplication {
        private val replacement = bytes.copyOf()
        fun manifestBytes(): ByteArray = replacement.copyOf()
    }

    data class Rejected(val message: String) : InMemoryManifestDraftApplication
}
