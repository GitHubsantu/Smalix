package com.imdevops.smalix.core.storage

import com.imdevops.smalix.core.model.ProjectId
import com.imdevops.smalix.core.model.Workspace
import com.imdevops.smalix.engine.api.WorkspaceStore
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes

class FileSystemWorkspaceStore(projectsRoot: Path) : WorkspaceStore {
    private val projectsRoot = projectsRoot.toAbsolutePath().normalize()

    override suspend fun create(projectId: ProjectId): Workspace {
        Files.createDirectories(projectsRoot)
        val root = workspaceRoot(projectId)
        Files.createDirectory(root)
        val workDirectory = Files.createDirectory(root.resolve(WORK_DIRECTORY))
        val logsDirectory = Files.createDirectory(root.resolve(LOGS_DIRECTORY))
        return Workspace(projectId, root, root.resolve(ORIGINAL_APK), workDirectory, logsDirectory)
    }

    override suspend fun open(projectId: ProjectId): Workspace {
        val root = workspaceRoot(projectId)
        val workspace = Workspace(projectId, root, root.resolve(ORIGINAL_APK), root.resolve(WORK_DIRECTORY), root.resolve(LOGS_DIRECTORY))
        require(Files.isDirectory(root)) { "Workspace does not exist." }
        require(Files.isRegularFile(workspace.originalApk)) { "Workspace original APK is missing." }
        require(Files.isDirectory(workspace.workDirectory)) { "Workspace work directory is missing." }
        require(Files.isDirectory(workspace.logsDirectory)) { "Workspace logs directory is missing." }
        return workspace
    }

    override suspend fun copyOriginal(sourceApk: Path, workspace: Workspace) {
        val validatedWorkspace = validateWorkspace(workspace)
        val source = sourceApk.toAbsolutePath().normalize()
        require(Files.isRegularFile(source)) { "Original APK source must be a regular file." }
        require(source != validatedWorkspace.originalApk) { "Original APK source already belongs to this workspace." }
        check(!Files.exists(validatedWorkspace.originalApk)) { "Workspace already has an original APK." }

        val temporary = Files.createTempFile(validatedWorkspace.root, ".original-", ".apk")
        try {
            Files.copy(source, temporary, REPLACE_EXISTING)
            moveWithoutReplacing(temporary, validatedWorkspace.originalApk)
            markReadOnlyIfSupported(validatedWorkspace.originalApk)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    override fun originalApk(workspace: Workspace): Path = validateWorkspace(workspace).originalApk

    override suspend fun delete(workspace: Workspace) {
        val validatedWorkspace = validateWorkspace(workspace)
        Files.walkFileTree(validatedWorkspace.root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                clearReadOnlyIfSupported(file)
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(directory: Path, exception: IOException?): FileVisitResult {
                if (exception != null) throw exception
                Files.delete(directory)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun validateWorkspace(workspace: Workspace): Workspace {
        val root = workspaceRoot(workspace.projectId)
        require(workspace.root.toAbsolutePath().normalize() == root) { "Workspace root is outside this store." }
        require(workspace.originalApk.toAbsolutePath().normalize() == root.resolve(ORIGINAL_APK)) {
            "Workspace original path is invalid."
        }
        require(workspace.workDirectory.toAbsolutePath().normalize() == root.resolve(WORK_DIRECTORY)) {
            "Workspace work path is invalid."
        }
        require(workspace.logsDirectory.toAbsolutePath().normalize() == root.resolve(LOGS_DIRECTORY)) {
            "Workspace logs path is invalid."
        }
        return Workspace(workspace.projectId, root, root.resolve(ORIGINAL_APK), root.resolve(WORK_DIRECTORY), root.resolve(LOGS_DIRECTORY))
    }

    private fun workspaceRoot(projectId: ProjectId): Path {
        val root = projectsRoot.resolve(projectId.value.toString()).normalize()
        require(root.startsWith(projectsRoot)) { "Workspace root escapes the projects directory." }
        return root
    }

    private fun moveWithoutReplacing(source: Path, destination: Path) {
        try {
            Files.move(source, destination, ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(source, destination)
        }
    }

    private fun markReadOnlyIfSupported(path: Path) {
        if (path.fileSystem.supportedFileAttributeViews().contains("dos")) {
            Files.setAttribute(path, "dos:readonly", true)
        }
    }

    private fun clearReadOnlyIfSupported(path: Path) {
        if (path.fileSystem.supportedFileAttributeViews().contains("dos")) {
            Files.setAttribute(path, "dos:readonly", false)
        }
    }

    private companion object {
        const val ORIGINAL_APK = "original.apk"
        const val WORK_DIRECTORY = "work"
        const val LOGS_DIRECTORY = "logs"
    }
}
