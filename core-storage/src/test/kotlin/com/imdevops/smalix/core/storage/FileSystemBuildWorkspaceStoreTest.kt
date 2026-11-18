package com.imdevops.smalix.core.storage

import com.imdevops.smalix.core.model.ProjectId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class FileSystemBuildWorkspaceStoreTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test fun `creates a fresh isolated copy without changing immutable original`() = runBlocking {
        val workspaces = FileSystemWorkspaceStore(temporaryDirectory.resolve("projects")); val workspace = workspaces.create(ProjectId.random())
        val original = byteArrayOf(1, 2, 3, 4); Files.write(workspace.originalApk, original)
        val build = FileSystemBuildWorkspaceStore(workspaces).create(workspace)
        assertArrayEquals(original, Files.readAllBytes(build.inputApk)); assertArrayEquals(original, Files.readAllBytes(workspace.originalApk))
    }

    @Test fun `refuses to reuse a build directory`() = runBlocking {
        val workspaces = FileSystemWorkspaceStore(temporaryDirectory.resolve("projects")); val workspace = workspaces.create(ProjectId.random()); Files.write(workspace.originalApk, byteArrayOf(1))
        val store = FileSystemBuildWorkspaceStore(workspaces); val id = UUID.randomUUID(); store.create(workspace, id)
        assertThrows(java.nio.file.FileAlreadyExistsException::class.java) { runBlocking { store.create(workspace, id) } }
    }
}
