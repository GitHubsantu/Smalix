package com.imdevops.smalix

import com.imdevops.smalix.core.model.ApkExportResult
import com.imdevops.smalix.core.model.BuildWorkspace
import com.imdevops.smalix.core.model.DexInfo
import com.imdevops.smalix.core.model.ManifestInfo
import com.imdevops.smalix.core.model.ProjectId
import com.imdevops.smalix.core.model.ValidatedApk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

class ApkExportUseCaseTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test fun `exports signed evidence to a new external path without changing source`() = runBlocking {
        val workspace = workspace()
        val source = signed(workspace, byteArrayOf(1, 2, 3, 4))
        val sourceHash = sha256(source)
        val destination = Files.createDirectory(temporaryDirectory.resolve("exports")).resolve("release.apk")

        val result = ApkExportUseCase().export(workspace, evidence(source), destination, Instant.now().plusSeconds(30))

        val exported = assertInstanceOf(ApkExportResult.Exported::class.java, result, "Export returned $result").apk
        assertEquals(destination.toAbsolutePath().normalize(), exported.destination)
        assertEquals(sourceHash, exported.sha256)
        assertEquals(Files.size(source), exported.sizeBytes)
        assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(destination))
        assertEquals(sourceHash, sha256(source))
    }

    @Test fun `rejects existing or workspace export destination without replacement`() = runBlocking {
        val workspace = workspace()
        val source = signed(workspace, byteArrayOf(1, 2, 3))
        val existing = Files.createDirectory(temporaryDirectory.resolve("exports")).resolve("exists.apk")
        Files.write(existing, byteArrayOf(9, 9))

        val existingResult = ApkExportUseCase().export(workspace, evidence(source), existing, Instant.now().plusSeconds(30))
        val workspaceResult = ApkExportUseCase().export(workspace, evidence(source), workspace.root.resolve("outside.apk"), Instant.now().plusSeconds(30))

        assertTrue(existingResult is ApkExportResult.Rejected)
        assertArrayEquals(byteArrayOf(9, 9), Files.readAllBytes(existing))
        assertTrue(workspaceResult is ApkExportResult.Rejected)
    }

    @Test fun `rejects changed source wrong evidence missing parent and expired request`() = runBlocking {
        val workspace = workspace()
        val source = signed(workspace, byteArrayOf(1, 2, 3))
        val exports = Files.createDirectory(temporaryDirectory.resolve("exports"))
        val useCase = ApkExportUseCase()
        val staleEvidence = evidence(source)
        Files.write(source, byteArrayOf(4, 5, 6))
        val changed = useCase.export(workspace, staleEvidence, exports.resolve("changed.apk"), Instant.now().plusSeconds(30))
        val wrong = ValidatedBuildOutput(ValidatedApk(workspace.root.resolve("other.apk"), "A".repeat(64), 1, emptyList()), manifest(), DexInfo(emptyList()))
        val wrongPath = useCase.export(workspace, wrong, exports.resolve("wrong.apk"), Instant.now().plusSeconds(30))
        val missingParent = useCase.export(workspace, evidence(source), temporaryDirectory.resolve("missing").resolve("out.apk"), Instant.now().plusSeconds(30))
        val expired = useCase.export(workspace, evidence(source), exports.resolve("expired.apk"), Instant.EPOCH)

        assertTrue(changed is ApkExportResult.Rejected)
        assertTrue(wrongPath is ApkExportResult.Rejected)
        assertTrue(missingParent is ApkExportResult.Rejected)
        assertTrue(expired is ApkExportResult.Rejected)
        assertTrue(Files.notExists(exports.resolve("changed.apk")))
    }

    @Test fun `cancellation cleans the unique external temporary file`() = runBlocking {
        val workspace = workspace()
        val source = signed(workspace, ByteArray(128) { it.toByte() })
        val exports = Files.createDirectory(temporaryDirectory.resolve("exports"))
        var checks = 0

        val result = ApkExportUseCase().export(workspace, evidence(source), exports.resolve("cancelled.apk"), Instant.now().plusSeconds(30)) { ++checks >= 3 }

        assertEquals(ApkExportResult.Cancelled, result)
        assertTrue(Files.notExists(exports.resolve("cancelled.apk")))
        Files.list(exports).use { files -> assertEquals(0L, files.count()) }
    }

    private fun workspace(): BuildWorkspace {
        val root = Files.createDirectory(temporaryDirectory.resolve("workspace-${UUID.randomUUID()}"))
        return BuildWorkspace(UUID.randomUUID(), ProjectId.random(), root, root.resolve("input.apk"))
    }

    private fun signed(workspace: BuildWorkspace, bytes: ByteArray): Path = workspace.root.resolve("signed.apk").also { Files.write(it, bytes) }

    private fun evidence(path: Path) = ValidatedBuildOutput(
        ValidatedApk(path, sha256(path), Files.size(path), emptyList()), manifest(), DexInfo(emptyList()),
    )

    private fun manifest() = ManifestInfo("com.example", null, null, 28, null, null, emptyList(), emptyList())

    private fun sha256(path: Path): String = MessageDigest.getInstance("SHA-256")
        .digest(Files.readAllBytes(path)).joinToString("") { "%02X".format(it) }
}
