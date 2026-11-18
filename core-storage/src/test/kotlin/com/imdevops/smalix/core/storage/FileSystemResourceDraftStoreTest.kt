package com.imdevops.smalix.core.storage

import com.imdevops.smalix.core.model.ProjectId
import com.imdevops.smalix.core.model.ResourceDraft
import com.imdevops.smalix.core.model.ResourceType
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

class FileSystemResourceDraftStoreTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test fun `atomically saves loads replaces and discards without changing original`() = runBlocking {
        val workspaces = FileSystemWorkspaceStore(temporaryDirectory.resolve("projects")); val workspace = completeWorkspace(workspaces)
        val store = FileSystemResourceDraftStore(workspaces); val original = Files.readAllBytes(workspace.originalApk); val first = draft(workspace.projectId)
        store.save(workspace, first); val replacement = first.copy(proposedValue = "New value", revision = 1); store.save(workspace, replacement)
        assertEquals(replacement, store.load(workspace, first.id, first.originalApkSha256)); assertArrayEquals(original, Files.readAllBytes(workspace.originalApk))
        store.discard(workspace, first.id); assertNull(store.load(workspace, first.id, first.originalApkSha256)); workspaces.delete(workspace)
    }

    @Test fun `rejects malformed stale and cross project drafts`() = runBlocking {
        val workspaces = FileSystemWorkspaceStore(temporaryDirectory.resolve("projects")); val workspace = completeWorkspace(workspaces); val store = FileSystemResourceDraftStore(workspaces); val value = draft(workspace.projectId)
        store.save(workspace, value); assertThrows(IllegalArgumentException::class.java) { runBlocking { store.load(workspace, value.id, "b".repeat(64)) } }
        Files.writeString(workspace.workDirectory.resolve("resources").resolve("${value.id}.json"), "broken")
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.load(workspace, value.id, value.originalApkSha256) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.save(workspace, value.copy(projectId = ProjectId.random())) } }; workspaces.delete(workspace)
    }

    private suspend fun completeWorkspace(store: FileSystemWorkspaceStore) = store.create(ProjectId.random()).also { Files.write(it.originalApk, byteArrayOf(1, 2, 3, 4)) }
    private fun draft(projectId: ProjectId) = ResourceDraft(UUID.randomUUID(), projectId, "a".repeat(64), 0x7f01_0001, "sample.app", ResourceType.STRING, "title", "", "Old", "Old", 0)
}
