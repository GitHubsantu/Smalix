package com.imdevops.smalix

import com.imdevops.smalix.core.model.Project
import com.imdevops.smalix.core.model.ProjectId
import com.imdevops.smalix.core.model.Workspace
import com.imdevops.smalix.engine.api.ProjectRepository
import com.imdevops.smalix.engine.api.WorkspaceStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant

class ProjectCreationUseCaseTest {
    @Test fun `copies original before persisting metadata`() = runBlocking {
        val events = mutableListOf<String>()
        val store = RecordingWorkspaceStore(events)
        val repository = RecordingRepository(events)

        val created = ProjectCreationUseCase(repository, store) { Instant.ofEpochMilli(1) }
            .create(Path.of("source.apk"))

        assertEquals(listOf("workspace", "copy", "database"), events)
        assertEquals("source", created.project.displayName)
        assertEquals("source.apk", created.project.sourceFileName)
        assertEquals("projects/${created.project.id.value}/original.apk", created.project.originalRelativePath.value)
    }

    @Test fun `removes only new workspace when database persistence fails`() = runBlocking {
        val events = mutableListOf<String>()
        val store = RecordingWorkspaceStore(events)
        val repository = RecordingRepository(events, createFailure = IllegalStateException("database unavailable"))

        assertThrows(IllegalStateException::class.java) {
            runBlocking { ProjectCreationUseCase(repository, store).create(Path.of("source.apk")) }
        }
        assertEquals(listOf("workspace", "copy", "database", "delete"), events)
    }

    private class RecordingRepository(
        private val events: MutableList<String>,
        private val createFailure: RuntimeException? = null,
    ) : ProjectRepository {
        override suspend fun create(project: Project) { events += "database"; createFailure?.let { throw it } }
        override suspend fun find(projectId: ProjectId): Project? = null
        override suspend fun list(): List<Project> = emptyList()
        override suspend fun update(project: Project) = Unit
        override suspend fun delete(projectId: ProjectId) = Unit
    }

    private class RecordingWorkspaceStore(private val events: MutableList<String>) : WorkspaceStore {
        private lateinit var workspace: Workspace
        override suspend fun create(projectId: ProjectId): Workspace {
            events += "workspace"
            workspace = Workspace(projectId, Path.of("root"), Path.of("root/original.apk"), Path.of("root/work"), Path.of("root/logs"))
            return workspace
        }
        override suspend fun open(projectId: ProjectId): Workspace = workspace
        override suspend fun copyOriginal(sourceApk: Path, workspace: Workspace) { events += "copy" }
        override fun originalApk(workspace: Workspace): Path = workspace.originalApk
        override suspend fun delete(workspace: Workspace) { assertEquals(this.workspace, workspace); events += "delete" }
    }
}
