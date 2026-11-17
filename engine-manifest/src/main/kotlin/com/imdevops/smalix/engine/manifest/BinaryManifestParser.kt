package com.imdevops.smalix.engine.manifest

import com.imdevops.smalix.core.model.ActivityInfo
import com.imdevops.smalix.core.model.AnalysisFailure
import com.imdevops.smalix.core.model.InspectionResult
import com.imdevops.smalix.core.model.ManifestInfo
import com.imdevops.smalix.core.model.ManifestLabel
import com.imdevops.smalix.core.model.PermissionInfo
import com.imdevops.smalix.core.model.ValidatedApk
import com.imdevops.smalix.engine.api.ManifestParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.Charset
import java.util.zip.ZipFile

class BinaryManifestParser : ManifestParser {
    override suspend fun parse(apk: ValidatedApk): InspectionResult<ManifestInfo> = try {
        ZipFile(apk.path.toFile()).use { zip ->
            val entry = zip.getEntry(MANIFEST) ?: return malformed("APK is missing AndroidManifest.xml.")
            val bytes = zip.getInputStream(entry).use { input ->
                val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val read = input.read(buffer); if (read < 0) break; if (out.size() + read > MAX_BYTES) return malformed("AndroidManifest.xml exceeds $MAX_BYTES bytes."); out.write(buffer, 0, read) }
                out.toByteArray()
            }
            parseBinaryXml(bytes)
        }
    } catch (e: IOException) { InspectionResult.Failure(AnalysisFailure.IoFailure("Unable to read manifest: ${e.message ?: "I/O error"}")) }
      catch (e: IllegalArgumentException) { malformed(e.message ?: "Malformed binary AndroidManifest.xml.") }

    private fun parseBinaryXml(bytes: ByteArray): InspectionResult<ManifestInfo> {
        val reader = AxReader(bytes)
        if (reader.u16(0) != XML || reader.u16(2) != 8 || reader.i32(4) != bytes.size) return malformed("Invalid binary XML header.")
        var position = 8; var strings: List<String>? = null
        var packageName: String? = null; var versionName: String? = null; var versionCode: Long? = null
        var minSdk: Int? = null; var targetSdk: Int? = null; var label: ManifestLabel? = null
        val activities = mutableListOf<ActivityInfo>(); val permissions = mutableListOf<PermissionInfo>()
        while (position < bytes.size) {
            val type = reader.u16(position); val header = reader.u16(position + 2); val size = reader.i32(position + 4)
            require(header >= 8 && size >= header && position + size <= bytes.size) { "Invalid binary XML chunk." }
            if (type == STRING_POOL) strings = reader.stringPool(position, header, size)
            if (type == START_TAG) {
                val pool = requireNotNull(strings) { "String pool must precede XML elements." }
                require(header >= 16) { "Invalid XML start tag." }
                val name = pool.stringAt(reader.i32(position + 20))
                val attrStart = reader.u16(position + 24); val attrSize = reader.u16(position + 26); val attrCount = reader.u16(position + 28)
                require(attrSize >= 20 && position + 16 + attrStart + attrSize * attrCount <= position + size) { "Invalid XML attributes." }
                val attrs = linkedMapOf<String, String>()
                repeat(attrCount) { index ->
                    val at = position + 16 + attrStart + index * attrSize
                    val ns = reader.i32(at); val attrName = pool.stringAt(reader.i32(at + 4)); val raw = reader.i32(at + 8)
                    val valueType = reader.u8(at + 15); val valueData = reader.i32(at + 16)
                    val key = if (ns >= 0 && pool.stringAt(ns) == ANDROID_NS) "android:$attrName" else attrName
                    attrs[key] = if (raw >= 0) pool.stringAt(raw) else typedValue(valueType, valueData, pool)
                }
                when (name) {
                    "manifest" -> { packageName = attrs["package"]; versionName = attrs["android:versionName"]; versionCode = number(attrs["android:versionCode"]) ; if (attrs.containsKey("split")) return unsupportedSplit() }
                    "uses-sdk" -> { minSdk = number(attrs["android:minSdkVersion"])?.toInt(); targetSdk = number(attrs["android:targetSdkVersion"])?.toInt() }
                    "application" -> label = attrs["android:label"]?.let { if (it.startsWith("@")) ManifestLabel.ResourceReference(it) else ManifestLabel.Literal(it) }
                    "activity", "activity-alias" -> attrs["android:name"]?.let { activities += ActivityInfo(resolveName(it, packageName), boolean(attrs["android:exported"]), boolean(attrs["android:enabled"])) }
                    else -> if (name.startsWith("uses-permission")) attrs["android:name"]?.let { permissions += PermissionInfo(it) }
                }
            }
            position += size
        }
        return InspectionResult.Success(ManifestInfo(requireNotNull(packageName) { "Manifest package is missing." }, versionName, versionCode, minSdk, targetSdk, label, activities, permissions))
    }

    private fun typedValue(type: Int, data: Int, strings: List<String>) = when (type) {
        0x03 -> strings.stringAt(data); 0x12 -> if (data != 0) "true" else "false"; 0x10 -> data.toString(); 0x11 -> "0x${data.toUInt().toString(16)}"; 0x01 -> "@0x${data.toUInt().toString(16)}"; else -> data.toString()
    }
    private fun number(value: String?): Long? = value?.let { if (it.startsWith("0x")) it.drop(2).toLongOrNull(16) else it.toLongOrNull() }
    private fun boolean(value: String?): Boolean? = value?.let { it == "true" || it == "1" }
    private fun resolveName(name: String, packageName: String?) = when { name.startsWith(".") && packageName != null -> packageName + name; !name.contains('.') && packageName != null -> "$packageName.$name"; else -> name }
    private fun malformed(message: String) = InspectionResult.Failure(AnalysisFailure.MalformedApk(message))
    private fun unsupportedSplit() = InspectionResult.Failure(AnalysisFailure.UnsupportedFormat(com.imdevops.smalix.core.model.InputFormat.SPLIT_APK, "Split APK is unsupported; v0.1 accepts one standalone APK only."))

    private class AxReader(private val b: ByteArray) {
        fun u8(p: Int) = b.at(p).toInt() and 0xff
        fun u16(p: Int) = u8(p) or (u8(p + 1) shl 8)
        fun i32(p: Int): Int = u8(p) or (u8(p + 1) shl 8) or (u8(p + 2) shl 16) or (u8(p + 3) shl 24)
        fun stringPool(start: Int, header: Int, size: Int): List<String> {
            require(header >= 28); val count = i32(start + 8); val flags = i32(start + 16); val stringsStart = i32(start + 20)
            require(count in 0..200_000 && stringsStart in header..size); val utf8 = flags and 0x100 != 0
            return List(count) { index -> stringAt(start + stringsStart + i32(start + header + index * 4), utf8) }
        }
        fun stringAt(p: Int, utf8: Boolean): String = if (utf8) { var q = skip8(p); val length = len8(q).first; q += len8(q).second; String(b, q, length, Charsets.UTF_8) } else { val (length, used) = len16(p); String(b, p + used, length * 2, Charset.forName("UTF-16LE")) }
        fun len8(p: Int): Pair<Int, Int> { val a = u8(p); return if (a and 0x80 == 0) a to 1 else ((a and 0x7f) shl 8 or u8(p + 1)) to 2 }
        fun skip8(p: Int) = p + len8(p).second
        fun len16(p: Int): Pair<Int, Int> { val a = u16(p); return if (a and 0x8000 == 0) a to 2 else ((a and 0x7fff) shl 16 or u16(p + 2)) to 4 }
        private fun ByteArray.at(p: Int): Byte { require(p in indices) { "Truncated binary XML." }; return this[p] }
    }
    private fun List<String>.stringAt(index: Int): String { require(index in indices) { "Invalid string index." }; return this[index] }
    private companion object { const val MANIFEST = "AndroidManifest.xml"; const val MAX_BYTES = 16 * 1024 * 1024; const val XML = 0x0003; const val STRING_POOL = 0x0001; const val START_TAG = 0x0102; const val ANDROID_NS = "http://schemas.android.com/apk/res/android" }
}
