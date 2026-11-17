package com.imdevops.smalix.core.storage

import com.imdevops.smalix.core.model.ActivityInfo
import com.imdevops.smalix.core.model.ManifestDraft
import com.imdevops.smalix.core.model.ManifestLabel
import com.imdevops.smalix.core.model.PermissionInfo
import com.imdevops.smalix.core.model.ProjectId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class FileSystemManifestDraftStoreTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `saves replaces and loads a complete draft without changing original`() = runBlocking {
        val workspaceStore = FileSystemWorkspaceStore(temporaryDirectory.resolve("projects"))
        val workspace = completeWorkspace(workspaceStore)
        val drafts = FileSystemManifestDraftStore(workspaceStore)
        val originalBytes = Files.readAllBytes(workspace.originalApk)
        val initial = draft(workspace.projectId)

        drafts.save(workspace, initial)
        val replacement = initial.copy(revision = 1, versionName = "2.0")
        drafts.save(workspace, replacement)

        assertEquals(replacement, drafts.load(workspace))
        assertArrayEquals(originalBytes, Files.readAllBytes(workspace.originalApk))
        workspaceStore.delete(workspace)
    }

    @Test
    fun `discards the saved draft`() = runBlocking {
        val workspaceStore = FileSystemWorkspaceStore(temporaryDirectory.resolve("projects"))
        val workspace = completeWorkspace(workspaceStore)
        val drafts = FileSystemManifestDraftStore(workspaceStore)
        drafts.save(workspace, draft(workspace.projectId))

        drafts.discard(workspace)

        assertNull(drafts.load(workspace))
        workspaceStore.delete(workspace)
    }

    @Test
    fun `rejects a draft for another project`() = runBlocking {
        val workspaceStore = FileSystemWorkspaceStore(temporaryDirectory.resolve("projects"))
        val workspace = completeWorkspace(workspaceStore)
        val drafts = FileSystemManifestDraftStore(workspaceStore)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { drafts.save(workspace, draft(ProjectId.random())) }
        }
        workspaceStore.delete(workspace)
    }

    @Test
    fun `rejects malformed draft content`() = runBlocking {
        val workspaceStore = FileSystemWorkspaceStore(temporaryDirectory.resolve("projects"))
        val workspace = completeWorkspace(workspaceStore)
        Files.writeString(workspace.workDirectory.resolve("manifest-draft.json"), "not a manifest draft")

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { FileSystemManifestDraftStore(workspaceStore).load(workspace) }
        }
        workspaceStore.delete(workspace)
    }

    private suspend fun completeWorkspace(store: FileSystemWorkspaceStore) = store.create(ProjectId.random()).also { workspace ->
        Files.write(workspace.originalApk, byteArrayOf(1, 2, 3))
    }

    private fun draft(projectId: ProjectId) = ManifestDraft(
        id = UUID.randomUUID(), projectId = projectId, originalApkSha256 = "a".repeat(64), revision = 0,
        packageName = "com.example.fixture", versionName = "1.0", versionCode = 1, minSdk = 26, targetSdk = 35,
        label = ManifestLabel.Literal("Fixture"), activities = listOf(ActivityInfo(".MainActivity", true, null)),
        permissions = listOf(PermissionInfo("android.permission.INTERNET")), originalActivityNames = setOf(".MainActivity"),
    )
}
