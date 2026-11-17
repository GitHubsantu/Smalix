package com.imdevops.smalix

import com.imdevops.smalix.core.model.InspectionResult
import com.imdevops.smalix.core.model.InspectionSummary
import com.imdevops.smalix.core.model.ProjectId
import com.imdevops.smalix.core.model.ZipSafetyLimits
import com.imdevops.smalix.engine.api.ProjectRepository
import com.imdevops.smalix.engine.api.WorkspaceStore
import java.time.Instant

sealed interface ReopenInspectionResult {
    data class Completed(val result: InspectionResult<InspectionSummary>) : ReopenInspectionResult
    data object ProjectNotFound : ReopenInspectionResult
    data class WorkspaceUnavailable(val message: String) : ReopenInspectionResult
}

class ReopenProjectInspectionUseCase(
    private val projectRepository: ProjectRepository,
    private val workspaceStore: WorkspaceStore,
    private val projectInspection: ProjectInspectionUseCase,
) {
    suspend fun inspect(
        projectId: ProjectId,
        deadline: Instant,
        limits: ZipSafetyLimits = ZipSafetyLimits(),
    ): ReopenInspectionResult {
        val project = projectRepository.find(projectId) ?: return ReopenInspectionResult.ProjectNotFound
        val workspace = try {
            workspaceStore.open(projectId)
        } catch (error: IllegalArgumentException) {
            return ReopenInspectionResult.WorkspaceUnavailable(error.message ?: "Project workspace is unavailable.")
        }
        return ReopenInspectionResult.Completed(
            projectInspection.inspect(project, workspaceStore.originalApk(workspace), deadline, limits),
        )
    }
}
