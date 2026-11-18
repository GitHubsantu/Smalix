package com.imdevops.smalix.engine.manifest

import com.imdevops.smalix.core.model.AnalysisRequest
import com.imdevops.smalix.core.model.InspectionResult
import com.imdevops.smalix.core.model.ProjectId
import com.imdevops.smalix.engine.apk.ZipApkAnalyzer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class IsolatedManifestZipReplacementTest {
    @TempDir lateinit var temp: Path

    @Test fun `replaces only the manifest in a distinct isolated ZIP then re-inspects it`() {
        val input = temp.resolve("input.apk")
        val output = temp.resolve("result.apk")
        val originalManifest = manifest("Old")
        val classes = byteArrayOf(1, 2, 3, 4)
        writeZip(input, mapOf("AndroidManifest.xml" to originalManifest, "classes.dex" to classes))
        val inputHash = sha256(input)
        val document = success(BinaryXmlDocumentReader().read(originalManifest))
        val plan = ManifestDraftApplicationPlan.Ready(listOf(ManifestStringReplacement(listOf(0, 0), 0, 4, "Old", "New")))
        val replacement = assertInstanceOf(InMemoryManifestDraftApplication.Applied::class.java, InMemoryManifestDraftApplier().apply(document, plan))

        replaceManifestIntoNewZip(input, output, replacement.manifestBytes())

        assertEquals(inputHash, sha256(input))
        ZipFile(output.toFile()).use { zip ->
            assertArrayEquals(classes, zip.getInputStream(zip.getEntry("classes.dex")).readBytes())
            assertEquals("New", success(BinaryXmlDocumentReader().read(zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes())).strings[4])
        }
        val analysis = runBlocking {
            ZipApkAnalyzer().analyze(AnalysisRequest(ProjectId.random(), output, Instant.now().plusSeconds(30)))
        }
        assertInstanceOf(InspectionResult.Success::class.java, analysis)
    }

    private fun replaceManifestIntoNewZip(input: Path, output: Path, manifest: ByteArray) {
        require(input.toAbsolutePath().normalize() != output.toAbsolutePath().normalize()) { "Input and output ZIP paths must differ." }
        require(Files.notExists(output)) { "Isolated output ZIP must not already exist." }
        var replaced = false
        ZipInputStream(Files.newInputStream(input)).use { source ->
            ZipOutputStream(Files.newOutputStream(output)).use { destination ->
                while (true) {
                    val entry = source.nextEntry ?: break
                    destination.putNextEntry(ZipEntry(entry.name))
                    if (entry.name == "AndroidManifest.xml") {
                        require(!replaced) { "Input ZIP has multiple AndroidManifest.xml entries." }
                        destination.write(manifest)
                        replaced = true
                    } else {
                        source.copyTo(destination)
                    }
                    destination.closeEntry()
                    source.closeEntry()
                }
            }
        }
        require(replaced) { "Input ZIP has no AndroidManifest.xml entry." }
    }

    private fun writeZip(path: Path, entries: Map<String, ByteArray>) {
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        }
    }

    private fun sha256(path: Path): String = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }

    private fun success(result: InspectionResult<BinaryXmlDocument>): BinaryXmlDocument =
        (assertInstanceOf(InspectionResult.Success::class.java, result).value as BinaryXmlDocument)

    private fun manifest(label: String): ByteArray = document(
        stringPoolUtf8("manifest", "application", ANDROID_NAMESPACE, "label", label),
        startTag(0, emptyList()),
        startTag(1, listOf(BinaryXmlAttribute(2, 3, 4, 0x03, 4))),
        endTag(1),
        endTag(0),
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
        attributes.forEach { attribute -> bytes.putInt(attribute.namespaceIndex).putInt(attribute.nameIndex).putInt(attribute.rawValueIndex).putShort(8).put(0).put(attribute.valueType.toByte()).putInt(attribute.valueData) }
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

    private companion object { const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android" }
}
