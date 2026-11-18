package com.imdevops.smalix

import com.imdevops.smalix.core.model.AnalysisRequest
import com.imdevops.smalix.core.model.ApkAnalysis
import com.imdevops.smalix.core.model.BuildWorkspace
import com.imdevops.smalix.core.model.InspectionResult
import com.imdevops.smalix.core.model.ManifestDraft
import com.imdevops.smalix.core.model.ManifestInfo
import com.imdevops.smalix.core.model.ManifestLabel
import com.imdevops.smalix.core.model.ProjectId
import com.imdevops.smalix.core.model.ValidatedApk
import com.imdevops.smalix.engine.api.ApkAnalyzer
import com.imdevops.smalix.engine.api.DexDetector
import com.imdevops.smalix.engine.api.ManifestParser
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ManifestBuildApplicationUseCaseTest {
    @TempDir lateinit var temp: Path

    @Test fun `applies approved literal label only to build local output then reinspects it`() = runBlocking {
        val input = temp.resolve("input.apk")
        writeZip(input, manifest("Old"))
        val workspace = BuildWorkspace(UUID.randomUUID(), ProjectId.random(), temp, input)
        val old = info("Old")
        val draft = ManifestDraft.from(workspace.projectId, "a".repeat(64), old).copy(label = ManifestLabel.Literal("New"))
        val analyzer = object : ApkAnalyzer {
            override suspend fun analyze(request: AnalysisRequest) = InspectionResult.Success(
                ApkAnalysis(ValidatedApk(request.apkPath, "a".repeat(64), Files.size(request.apkPath), emptyList()), emptySet(), emptyList()),
            )
        }
        val parser = object : ManifestParser {
            override suspend fun parse(apk: ValidatedApk) = InspectionResult.Success(if (apk.path.fileName.toString() == "input.apk") old else info("New"))
        }

        val result = ManifestBuildApplicationUseCase(analyzer, parser, object : DexDetector { override suspend fun detect(apk: ValidatedApk) = InspectionResult.Success(com.imdevops.smalix.core.model.DexInfo(emptyList())) }).apply(workspace, draft, Instant.now().plusSeconds(30))

        val ready = assertInstanceOf(ManifestBuildApplication.Ready::class.java, result)
        assertEquals(ManifestLabel.Literal("New"), ready.manifest.label)
        assertEquals("Old", stringAtManifest(input))
        assertEquals("New", stringAtManifest(temp.resolve("manifest-applied.apk")))
    }

    private fun stringAtManifest(apk: Path): String = java.util.zip.ZipFile(apk.toFile()).use { zip ->
        val bytes = zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes()
        val document = (assertInstanceOf(InspectionResult.Success::class.java, com.imdevops.smalix.engine.manifest.BinaryXmlDocumentReader().read(bytes)).value as com.imdevops.smalix.engine.manifest.BinaryXmlDocument)
        document.strings[4]
    }

    private fun info(label: String) = ManifestInfo("com.example", "1.0", 1, 26, 35, ManifestLabel.Literal(label), emptyList(), emptyList())
    private fun writeZip(path: Path, manifest: ByteArray) = ZipOutputStream(Files.newOutputStream(path)).use { zip ->
        zip.putNextEntry(ZipEntry("AndroidManifest.xml")); zip.write(manifest); zip.closeEntry(); zip.putNextEntry(ZipEntry("classes.dex")); zip.write(byteArrayOf(1)); zip.closeEntry()
    }

    private fun manifest(label: String): ByteArray = document(pool("manifest", "application", ANDROID_NAMESPACE, "label", label), start(0, emptyList()), start(1, listOf(com.imdevops.smalix.engine.manifest.BinaryXmlAttribute(2, 3, 4, 0x03, 4))), end(1), end(0))
    private fun document(vararg chunks: ByteArray): ByteArray { val total = 8 + chunks.sumOf { it.size }; return ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN).apply { putShort(3).putShort(8).putInt(total); chunks.forEach(::put) }.array() }
    private fun start(name: Int, attributes: List<com.imdevops.smalix.engine.manifest.BinaryXmlAttribute>): ByteArray = ByteBuffer.allocate(36 + attributes.size * 20).order(ByteOrder.LITTLE_ENDIAN).apply { putShort(0x0102).putShort(16).putInt(capacity()).putInt(0).putInt(-1).putInt(-1).putInt(name); putShort(20).putShort(20).putShort(attributes.size.toShort()).putShort(0).putShort(0).putShort(0); attributes.forEach { putInt(it.namespaceIndex).putInt(it.nameIndex).putInt(it.rawValueIndex).putShort(8).put(0).put(it.valueType.toByte()).putInt(it.valueData) } }.array()
    private fun end(name: Int): ByteArray = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply { putShort(0x0103).putShort(16).putInt(24).putInt(0).putInt(-1).putInt(-1).putInt(name) }.array()
    private fun pool(vararg values: String): ByteArray { val strings = values.map { value -> byteArrayOf(value.length.toByte(), value.toByteArray().size.toByte()) + value.toByteArray() + byteArrayOf(0) }; val offsets = strings.runningFold(0) { offset, value -> offset + value.size }.dropLast(1); val size = 28 + values.size * 4 + strings.sumOf { it.size }; return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply { putShort(1).putShort(28).putInt(size).putInt(values.size).putInt(0).putInt(0x100).putInt(28 + values.size * 4).putInt(0); offsets.forEach(::putInt); strings.forEach(::put) }.array() }
    private companion object { const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android" }
}
