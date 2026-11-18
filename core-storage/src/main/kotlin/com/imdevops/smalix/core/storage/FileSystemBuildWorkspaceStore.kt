package com.imdevops.smalix.core.storage

import com.imdevops.smalix.core.model.BuildWorkspace
import com.imdevops.smalix.core.model.Workspace
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

/** Creates a fresh build-local copy; it never changes Workspace.originalApk. */
class FileSystemBuildWorkspaceStore(private val workspaceStore: FileSystemWorkspaceStore) {
    suspend fun create(workspace: Workspace, buildId: UUID = UUID.randomUUID()): BuildWorkspace {
        workspaceStore.originalApk(workspace)
        require(Files.isRegularFile(workspace.originalApk)) { "Workspace original APK is missing." }
        val builds = workspace.workDirectory.resolve("builds").normalize()
        require(builds.parent == workspace.workDirectory) { "Build directory is invalid." }
        Files.createDirectories(builds)
        val root = builds.resolve(buildId.toString()).normalize()
        require(root.parent == builds) { "Build directory is invalid." }
        Files.createDirectory(root)
        val input = root.resolve("input.apk")
        val temporary = Files.createTempFile(root, ".input-", ".tmp")
        try {
            Files.copy(workspace.originalApk, temporary, REPLACE_EXISTING)
            try { Files.move(temporary, input, ATOMIC_MOVE) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, input) }
        } finally { Files.deleteIfExists(temporary) }
        return BuildWorkspace(buildId, workspace.projectId, root, input)
    }
}
