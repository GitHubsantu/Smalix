package com.imdevops.smalix.engine.apk

import com.imdevops.smalix.core.model.ValidatedApk
import com.imdevops.smalix.core.model.ZipAlignmentRequest
import com.imdevops.smalix.core.model.ZipAlignmentResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WindowsZipAlignerTest {
    @TempDir lateinit var temp: Path

    @Test fun `refuses unavailable executable without creating output`() = runBlocking {
        val input = archive("input.apk")
        val output = temp.resolve("aligned.apk")

        val result = WindowsZipAligner(temp.resolve("missing.exe")).align(request(input, output))

        assertTrue(result is ZipAlignmentResult.Rejected)
        assertTrue(Files.notExists(output))
    }

    @Test fun `uses configured zipalign only when available`() = runBlocking {
        val executable = System.getenv("SMALIX_ZIPALIGN")?.let(Path::of) ?: return@runBlocking
        if (!Files.isRegularFile(executable)) return@runBlocking
        val input = archive("input.apk")
        val output = temp.resolve("aligned.apk")
        val originalHash = sha256(input)

        val result = WindowsZipAligner(executable).align(request(input, output))

        val created = assertInstanceOf(ZipAlignmentResult.Created::class.java, result)
        assertEquals(output, created.alignedApk)
        assertTrue(Files.isRegularFile(output))
        assertEquals(originalHash, sha256(input))
    }

    private fun request(input: Path, output: Path) = ZipAlignmentRequest(
        ValidatedApk(input, "a".repeat(64), Files.size(input), emptyList()), output, Instant.now().plusSeconds(30),
    )

    private fun archive(name: String): Path = temp.resolve(name).also { path ->
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }
    }

    private fun sha256(path: Path): String = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
}
