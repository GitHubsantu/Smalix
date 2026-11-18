package com.imdevops.smalix.engine.manifest

import com.imdevops.smalix.core.model.BuildWorkspace
import com.imdevops.smalix.core.model.InspectionResult
import com.imdevops.smalix.core.model.ProjectId
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class IsolatedManifestZipReplacerTest {
    @TempDir lateinit var temp: Path

    @Test fun `creates only a build local output and preserves the input APK`() {
        val input = temp.resolve("input.apk")
        val source = manifest("Old")
        val dex = byteArrayOf(5, 6, 7)
        writeZip(input, mapOf("AndroidManifest.xml" to source, "classes.dex" to dex))
        val inputHash = sha256(input)
        val document = success(BinaryXmlDocumentReader().read(source))
        val replacement = assertInstanceOf(
            InMemoryManifestDraftApplication.Applied::class.java,
            InMemoryManifestDraftApplier().apply(document, ManifestDraftApplicationPlan.Ready(listOf(ManifestStringReplacement(listOf(0, 0), 0, 4, "Old", "New")))),
        )
        val workspace = BuildWorkspace(UUID.randomUUID(), ProjectId.random(), temp, input)

        val result = IsolatedManifestZipReplacer().replace(workspace, replacement.manifestBytes(), Instant.now().plusSeconds(30))

        val created = assertInstanceOf(IsolatedManifestZipReplacement.Created::class.java, result)
        assertEquals(temp.resolve("manifest-applied.apk"), created.outputApk)
        assertEquals(inputHash, sha256(input))
        ZipFile(created.outputApk.toFile()).use { zip ->
            assertArrayEquals(dex, zip.getInputStream(zip.getEntry("classes.dex")).readBytes())
            assertEquals("New", success(BinaryXmlDocumentReader().read(zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes())).strings[4])
        }
    }

    @Test fun `rejects a build workspace whose input is outside its root`() {
        val input = temp.resolve("other.apk")
        writeZip(input, mapOf("AndroidManifest.xml" to manifest("Old")))
        val result = IsolatedManifestZipReplacer().replace(
            BuildWorkspace(UUID.randomUUID(), ProjectId.random(), temp, input),
            manifest("Old"),
            Instant.now().plusSeconds(30),
        )

        val rejected = assertInstanceOf(IsolatedManifestZipReplacement.Rejected::class.java, result)
        assertTrue(rejected.message.contains("outside"))
    }

    private fun writeZip(path: Path, entries: Map<String, ByteArray>) = ZipOutputStream(Files.newOutputStream(path)).use { zip ->
        entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
    }

    private fun sha256(path: Path): String = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }

    private fun success(result: InspectionResult<BinaryXmlDocument>): BinaryXmlDocument =
        (assertInstanceOf(InspectionResult.Success::class.java, result).value as BinaryXmlDocument)

    private fun manifest(label: String): ByteArray = document(
        pool("manifest", "application", ANDROID_NAMESPACE, "label", label), start(0, emptyList()), start(1, listOf(BinaryXmlAttribute(2, 3, 4, 0x03, 4))), end(1), end(0),
    )

    private fun document(vararg chunks: ByteArray): ByteArray {
        val total = 8 + chunks.sumOf { it.size }
        return ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN).apply { putShort(3).putShort(8).putInt(total); chunks.forEach(::put) }.array()
    }

    private fun start(name: Int, attributes: List<BinaryXmlAttribute>): ByteArray = ByteBuffer.allocate(36 + attributes.size * 20).order(ByteOrder.LITTLE_ENDIAN).apply {
        putShort(0x0102).putShort(16).putInt(capacity()).putInt(0).putInt(-1).putInt(-1).putInt(name); putShort(20).putShort(20).putShort(attributes.size.toShort()).putShort(0).putShort(0).putShort(0)
        attributes.forEach { putInt(it.namespaceIndex).putInt(it.nameIndex).putInt(it.rawValueIndex).putShort(8).put(0).put(it.valueType.toByte()).putInt(it.valueData) }
    }.array()

    private fun end(name: Int): ByteArray = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply {
        putShort(0x0103).putShort(16).putInt(24).putInt(0).putInt(-1).putInt(-1).putInt(name)
    }.array()

    private fun pool(vararg values: String): ByteArray {
        val strings = values.map { value -> byteArrayOf(value.length.toByte(), value.toByteArray().size.toByte()) + value.toByteArray() + byteArrayOf(0) }
        val offsets = strings.runningFold(0) { offset, value -> offset + value.size }.dropLast(1)
        val size = 28 + values.size * 4 + strings.sumOf { it.size }
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(1).putShort(28).putInt(size).putInt(values.size).putInt(0).putInt(0x100).putInt(28 + values.size * 4).putInt(0); offsets.forEach(::putInt); strings.forEach(::put)
        }.array()
    }

    private companion object { const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android" }
}
