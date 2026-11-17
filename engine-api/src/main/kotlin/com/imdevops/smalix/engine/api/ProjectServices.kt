package com.imdevops.smalix.engine.api

import com.imdevops.smalix.core.model.Project
import com.imdevops.smalix.core.model.ProjectId
import com.imdevops.smalix.core.model.Workspace
import java.nio.file.Path

interface ProjectRepository {
    suspend fun create(project: Project)

    suspend fun find(projectId: ProjectId): Project?

    suspend fun list(): List<Project>

    suspend fun update(project: Project)

    suspend fun delete(projectId: ProjectId)
}

interface WorkspaceStore {
    suspend fun create(projectId: ProjectId): Workspace

    /** Copies once into [Workspace.originalApk]; implementations must not overwrite it. */
    suspend fun copyOriginal(sourceApk: Path, workspace: Workspace)

    fun originalApk(workspace: Workspace): Path

    /** Removes only the workspace identified by [workspace]. */
    suspend fun delete(workspace: Workspace)
}
