package com.imdevops.smalix.core.storage

import com.imdevops.smalix.core.model.ProjectId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FileSystemWorkspaceStoreTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `creates fixed workspace layout and preserves copied original`() = runBlocking {
        val store = FileSystemWorkspaceStore(temporaryDirectory.resolve("projects"))
        val source = temporaryDirectory.resolve("source.apk")
        Files.write(source, byteArrayOf(1, 2, 3))

        val workspace = store.create(ProjectId.random())
        store.copyOriginal(source, workspace)
        Files.write(source, byteArrayOf(9, 9, 9))

        assertTrue(Files.isDirectory(workspace.workDirectory))
        assertTrue(Files.isDirectory(workspace.logsDirectory))
        assertArrayEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(store.originalApk(workspace)))
        store.delete(workspace)
    }

    @Test
    fun `never overwrites an existing original`() = runBlocking {
        val store = FileSystemWorkspaceStore(temporaryDirectory.resolve("projects"))
        val first = temporaryDirectory.resolve("first.apk")
        val second = temporaryDirectory.resolve("second.apk")
        Files.write(first, byteArrayOf(1))
        Files.write(second, byteArrayOf(2))
        val workspace = store.create(ProjectId.random())
        store.copyOriginal(first, workspace)

        assertThrows(IllegalStateException::class.java) { runBlocking { store.copyOriginal(second, workspace) } }
        assertArrayEquals(byteArrayOf(1), Files.readAllBytes(workspace.originalApk))
        store.delete(workspace)
    }

    @Test
    fun `deletes only the validated workspace`() = runBlocking {
        val projectsRoot = temporaryDirectory.resolve("projects")
        val store = FileSystemWorkspaceStore(projectsRoot)
        val workspace = store.create(ProjectId.random())

        store.delete(workspace)

        assertFalse(Files.exists(workspace.root))
        assertTrue(Files.isDirectory(projectsRoot))
    }

    @Test
    fun `reopens a complete workspace through a new store instance`() = runBlocking {
        val projectsRoot = temporaryDirectory.resolve("projects")
        val projectId = ProjectId.random()
        val firstStore = FileSystemWorkspaceStore(projectsRoot)
        val created = firstStore.create(projectId)
        Files.write(created.originalApk, byteArrayOf(1))

        val reopened = FileSystemWorkspaceStore(projectsRoot).open(projectId)

        assertTrue(Files.isRegularFile(reopened.originalApk))
        assertTrue(Files.isDirectory(reopened.workDirectory))
        assertTrue(Files.isDirectory(reopened.logsDirectory))
        firstStore.delete(reopened)
    }

    @Test
    fun `refuses an incomplete workspace`() = runBlocking {
        val store = FileSystemWorkspaceStore(temporaryDirectory.resolve("projects"))
        val workspace = store.create(ProjectId.random())

        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.open(workspace.projectId) } }
        store.delete(workspace)
    }
}
