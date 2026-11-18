package com.imdevops.smalix.engine.manifest

import com.imdevops.smalix.core.model.AnalysisFailure
import com.imdevops.smalix.core.model.InspectionResult
import com.imdevops.smalix.core.model.ResourceEntry
import com.imdevops.smalix.core.model.ResourceIndex
import com.imdevops.smalix.core.model.ResourceType
import com.imdevops.smalix.core.model.ResourceValue
import com.imdevops.smalix.core.model.ValidatedApk
import com.imdevops.smalix.engine.api.ResourceReader
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/** Bounded reader for default-config literal string, color, and dimen resources only. */
class BinaryResourceTableReader(private val maxBytes: Int = 32 * 1024 * 1024, private val maxEntries: Int = 100_000) : ResourceReader {
    override suspend fun index(apk: ValidatedApk): InspectionResult<ResourceIndex> {
        try {
        if (apk.entries.none { it.normalizedPath == TABLE }) return InspectionResult.Failure(AnalysisFailure.MalformedApk("APK is missing resources.arsc."))
        return ZipFile(apk.path.toFile()).use { zip ->
            val entry = zip.getEntry(TABLE) ?: return InspectionResult.Failure(AnalysisFailure.MalformedApk("APK is missing resources.arsc."))
            if (entry.size !in 1..maxBytes.toLong()) return InspectionResult.Failure(AnalysisFailure.LimitExceeded("resource table size", "resources.arsc exceeds $maxBytes bytes."))
            val bytes = zip.getInputStream(entry).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > maxBytes) return InspectionResult.Failure(AnalysisFailure.LimitExceeded("resource table size", "resources.arsc exceeds $maxBytes bytes."))
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            InspectionResult.Success(ResourceIndex(parse(bytes)))
        }
        } catch (e: IllegalArgumentException) { return InspectionResult.Failure(AnalysisFailure.MalformedApk(e.message ?: "Malformed resources.arsc."))
        } catch (e: IndexOutOfBoundsException) { return InspectionResult.Failure(AnalysisFailure.MalformedApk("Malformed resources.arsc bounds."))
        } catch (e: Exception) { return InspectionResult.Failure(AnalysisFailure.IoFailure("Unable to read resources.arsc: ${e.message ?: "I/O error"}")) }
    }

    private fun parse(bytes: ByteArray): List<ResourceEntry> {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val root = chunk(b, 0, bytes.size); require(root.type == TABLE_TYPE && root.header >= 12) { "Invalid resource table header." }
        val global = stringPool(b, root.header, root.end)
        val result = mutableListOf<ResourceEntry>(); var p = root.header + chunk(b, root.header, root.end).size
        while (p < root.end) {
            val c = chunk(b, p, root.end)
            if (c.type == PACKAGE_TYPE) parsePackage(b, c, global, result)
            p = c.end
        }
        return result
    }

    private fun parsePackage(b: ByteBuffer, pkg: Chunk, global: List<String>, out: MutableList<ResourceEntry>) {
        require(pkg.header >= 284) { "Malformed resource package." }
        val id = i32(b, pkg.start + 8); val packageName = utf16Fixed(b, pkg.start + 12, 256).ifBlank { "package$id" }
        val typePool = stringPool(b, pkg.start + i32(b, pkg.start + 268), pkg.end)
        val keyPool = stringPool(b, pkg.start + i32(b, pkg.start + 276), pkg.end)
        var p = pkg.start + pkg.header
        while (p < pkg.end) { val c = chunk(b, p, pkg.end); if (c.type == TYPE_TYPE) parseType(b, c, id, packageName, typePool, keyPool, global, out); p = c.end }
    }

    private fun parseType(b: ByteBuffer, c: Chunk, pkgId: Int, pkgName: String, types: List<String>, keys: List<String>, global: List<String>, out: MutableList<ResourceEntry>) {
        require(c.header >= 28) { "Malformed resource type." }; val typeId = u8(b, c.start + 8); val count = i32(b, c.start + 12)
        require(count in 0..maxEntries && c.start + c.header <= c.end) { "Resource entry limit exceeded." }
        val configSize = i32(b, c.start + 20); require(configSize in 4..(c.header - 20)) { "Malformed resource configuration." }
        if ((c.start + 24 until c.start + c.header).any { u8(b, it) != 0 }) return
        val type = when (types.getOrNull(typeId - 1)) { "string" -> ResourceType.STRING; "color" -> ResourceType.COLOR; "dimen" -> ResourceType.DIMEN; else -> return }
        val entriesStart = c.start + i32(b, c.start + 16)
        repeat(count) { index ->
            val offset = i32(b, c.start + c.header + index * 4); if (offset == -1) return@repeat
            val e = entriesStart + offset; require(e + 16 <= c.end) { "Malformed resource entry." }; if (u16(b, e + 2) != 0) return@repeat
            val key = keys.getOrNull(i32(b, e + 4)) ?: return@repeat
            if (key.isBlank() || key.contains('/') || key.contains('\\') || key.contains('\u0000')) return@repeat
            val dataType = u8(b, e + 11); val data = i32(b, e + 12)
            val value = value(type, dataType, data, global) ?: return@repeat
            out += ResourceEntry((pkgId.toLong() shl 24) or (typeId.toLong() shl 16) or index.toLong(), pkgName, type, key, "", value, TABLE)
            require(out.size <= maxEntries) { "Resource result limit exceeded." }
        }
    }

    private fun value(type: ResourceType, dataType: Int, data: Int, strings: List<String>): ResourceValue? = when (dataType) {
        1 -> ResourceValue.Reference("@0x${data.toUInt().toString(16)}")
        3 -> strings.getOrNull(data)?.let(ResourceValue::Literal)
        28, 29 -> if (type == ResourceType.COLOR) ResourceValue.Literal("#%08X".format(data)) else null
        5 -> if (type == ResourceType.DIMEN) ResourceValue.Literal(dimen(data)) else null
        else -> null
    }

    private fun dimen(data: Int): String { val unit = arrayOf("px", "dp", "sp", "pt", "in", "mm").getOrElse(data and 0xf) { return "0px" }; val radix = doubleArrayOf(1.0/256, 1.0/32768, 1.0/8388608, 1.0/2147483648.0)[(data shr 4) and 3]; return "%.6f".format(java.util.Locale.ROOT, (data shr 8) * radix).trimEnd('0').trimEnd('.') + unit }
    private fun stringPool(b: ByteBuffer, start: Int, end: Int): List<String> {
        val c = chunk(b, start, end)
        require(c.type == STRING_POOL && c.header >= 28) { "Missing resource string pool." }
        val count = i32(b, start + 8)
        val flags = i32(b, start + 16)
        val stringsStart = i32(b, start + 20)
        require(count in 0..maxEntries && stringsStart in c.header..c.size) { "Malformed resource string pool." }
        require(start + c.header + count * 4 <= c.end) { "Malformed resource string offsets." }
        val base = start + stringsStart
        return List(count) { n ->
            val offset = i32(b, start + c.header + n * 4)
            require(offset >= 0 && base + offset < c.end) { "Malformed resource string offset." }
            stringAt(b, base + offset, flags and 0x100 != 0, c.end)
        }
    }

    private fun stringAt(b: ByteBuffer, p: Int, utf8: Boolean, end: Int): String {
        if (!utf8) {
            val (length, lengthBytes) = length16(b, p, end)
            val valueStart = p + lengthBytes
            require(length <= maxBytes / 2) { "Resource string exceeds size limit." }
            require(length <= (end - valueStart - 2) / 2 && u16(b, valueStart + length * 2) == 0) { "Malformed UTF-16 resource string." }
            return CharArray(length) { u16(b, valueStart + it * 2).toChar() }.concatToString()
        }
        var q = p
        q += length8(b, q, end).second // UTF-16 character count; it is not the byte count.
        val (byteLength, byteLengthBytes) = length8(b, q, end)
        q += byteLengthBytes
        require(byteLength <= maxBytes) { "Resource string exceeds size limit." }
        require(byteLength <= end - q - 1 && u8(b, q + byteLength) == 0) { "Malformed UTF-8 resource string." }
        return ByteArray(byteLength) { u8(b, q + it).toByte() }.toString(Charsets.UTF_8)
    }

    private fun length8(b: ByteBuffer, p: Int, end: Int): Pair<Int, Int> {
        require(p < end) { "Truncated resource string length." }
        val first = u8(b, p)
        return if (first and 0x80 == 0) first to 1 else {
            require(p + 1 < end) { "Truncated resource string length." }
            (((first and 0x7f) shl 8) or u8(b, p + 1)) to 2
        }
    }

    private fun length16(b: ByteBuffer, p: Int, end: Int): Pair<Int, Int> {
        require(p + 1 < end) { "Truncated resource string length." }
        val first = u16(b, p)
        return if (first and 0x8000 == 0) first to 2 else {
            require(p + 3 < end) { "Truncated resource string length." }
            (((first and 0x7fff) shl 16) or u16(b, p + 2)) to 4
        }
    }
    private fun utf16Fixed(b: ByteBuffer, p: Int, bytes: Int): String = buildString { for (i in 0 until bytes step 2) { val c=u16(b,p+i); if(c==0) break; append(c.toChar()) } }
    private fun chunk(b: ByteBuffer, start: Int, end: Int): Chunk { require(start >= 0 && start+8 <= end); val h=u16(b,start+2); val s=i32(b,start+4); require(h>=8 && s>=h && start+s<=end); return Chunk(u16(b,start),h,s,start,start+s) }
    private fun u8(b: ByteBuffer,p:Int)=b.get(p).toInt() and 0xff; private fun u16(b: ByteBuffer,p:Int)=b.getShort(p).toInt() and 0xffff; private fun i32(b: ByteBuffer,p:Int)=b.getInt(p)
    private data class Chunk(val type:Int,val header:Int,val size:Int,val start:Int,val end:Int)
    private companion object { const val TABLE="resources.arsc"; const val STRING_POOL=1; const val TABLE_TYPE=2; const val PACKAGE_TYPE=0x200; const val TYPE_TYPE=0x201 }
}
