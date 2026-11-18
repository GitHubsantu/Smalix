package com.imdevops.smalix.core.storage

import com.imdevops.smalix.core.model.ProjectId
import com.imdevops.smalix.core.model.SmaliDraft
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

class FileSystemSmaliDraftStoreTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test fun `atomically saves replaces loads and discards text draft without changing original`() = runBlocking {
        val workspaceStore = FileSystemWorkspaceStore(temporaryDirectory.resolve("projects"))
        val workspace = completeWorkspace(workspaceStore)
        val store = FileSystemSmaliDraftStore(workspaceStore)
        val original = Files.readAllBytes(workspace.originalApk)
        val first = draft(workspace.projectId)
        store.save(workspace, first)
        val replacement = first.copy(revision = 1, text = ".method public changed()V\n.end method")
        store.save(workspace, replacement)
        assertEquals(replacement, store.load(workspace, first.id, first.originalApkSha256))
        assertArrayEquals(original, Files.readAllBytes(workspace.originalApk))
        store.discard(workspace, first.id)
        assertNull(store.load(workspace, first.id, first.originalApkSha256))
        workspaceStore.delete(workspace)
    }

    @Test fun `rejects malformed stale and cross project drafts`() = runBlocking {
        val workspaceStore = FileSystemWorkspaceStore(temporaryDirectory.resolve("projects"))
        val workspace = completeWorkspace(workspaceStore)
        val store = FileSystemSmaliDraftStore(workspaceStore)
        val value = draft(workspace.projectId)
        store.save(workspace, value)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.load(workspace, value.id, "b".repeat(64)) } }
        Files.writeString(workspace.workDirectory.resolve("dex").resolve("${value.id}.smali"), "broken")
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.load(workspace, value.id, value.originalApkSha256) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.save(workspace, value.copy(projectId = ProjectId.random())) } }
        workspaceStore.delete(workspace)
    }

    private suspend fun completeWorkspace(store: FileSystemWorkspaceStore) = store.create(ProjectId.random()).also {
        Files.write(it.originalApk, byteArrayOf(1, 2, 3, 4))
    }

    private fun draft(projectId: ProjectId) = SmaliDraft(
        id = UUID.randomUUID(), projectId = projectId, originalApkSha256 = "a".repeat(64), dexEntryName = "classes.dex",
        classDescriptor = "Lcom/example/Fixture;", methodName = "run", methodDescriptor = "()V", revision = 0,
        text = ".method public run()V\n    return-void\n.end method",
    )
}
