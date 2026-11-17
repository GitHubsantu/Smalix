package com.imdevops.smalix

import com.imdevops.smalix.core.model.Project
import com.imdevops.smalix.core.model.ProjectAnalysisStatus
import com.imdevops.smalix.core.model.ProjectId
import com.imdevops.smalix.core.model.RelativePath
import com.imdevops.smalix.core.model.Workspace
import com.imdevops.smalix.engine.api.ProjectRepository
import com.imdevops.smalix.engine.api.WorkspaceStore
import java.nio.file.Path
import java.time.Instant

data class CreatedProject(val project: Project, val workspace: Workspace)

class ProjectCreationUseCase(
    private val projectRepository: ProjectRepository,
    private val workspaceStore: WorkspaceStore,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun create(sourceApk: Path, displayName: String = defaultName(sourceApk)): CreatedProject {
        val projectId = ProjectId.random()
        val workspace = workspaceStore.create(projectId)
        try {
            workspaceStore.copyOriginal(sourceApk, workspace)
            val project = Project(
                id = projectId,
                displayName = displayName,
                createdAt = now(),
                lastOpenedAt = null,
                workspaceRelativePath = RelativePath.of("projects/${projectId.value}"),
                originalRelativePath = RelativePath.of("projects/${projectId.value}/original.apk"),
                originalSha256 = null,
                sourceFileName = sourceApk.fileName?.toString(),
                originalSizeBytes = null,
                analysisStatus = ProjectAnalysisStatus.NOT_ANALYZED,
                analysisError = null,
            )
            projectRepository.create(project)
            return CreatedProject(project, workspace)
        } catch (failure: Throwable) {
            try {
                workspaceStore.delete(workspace)
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    private companion object {
        fun defaultName(sourceApk: Path): String {
            val fileName = requireNotNull(sourceApk.fileName) { "APK source path must include a file name." }.toString()
            return fileName.removeSuffix(".apk").removeSuffix(".APK").ifBlank { fileName }
        }
    }
}
