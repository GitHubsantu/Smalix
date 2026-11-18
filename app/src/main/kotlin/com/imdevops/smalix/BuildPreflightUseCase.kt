package com.imdevops.smalix

import com.imdevops.smalix.core.model.AnalysisRequest
import com.imdevops.smalix.core.model.BuildRequest
import com.imdevops.smalix.core.model.BuildWorkspace
import com.imdevops.smalix.core.model.InspectionResult
import com.imdevops.smalix.core.model.ProjectAnalysisStatus
import com.imdevops.smalix.engine.api.BuildWorkspaceStore
import com.imdevops.smalix.engine.api.ProjectRepository
import com.imdevops.smalix.engine.api.WorkspaceStore
import java.time.Instant

sealed interface BuildPreflightResult {
    data class Ready(val workspace: BuildWorkspace) : BuildPreflightResult
    data class Rejected(val message: String) : BuildPreflightResult
    data class InspectionFailed(val message: String) : BuildPreflightResult
}

/** Verifies the immutable original and creates only an isolated input copy. */
class BuildPreflightUseCase(
    private val projects: ProjectRepository, private val workspaces: WorkspaceStore,
    private val buildWorkspaces: BuildWorkspaceStore, private val inspection: InspectionUseCase,
) {
    suspend fun prepare(request: BuildRequest, deadline: Instant): BuildPreflightResult {
        val project = projects.find(request.projectId) ?: return BuildPreflightResult.Rejected("Project no longer exists.")
        val sha = project.originalSha256 ?: return BuildPreflightResult.Rejected("Project has not been inspected.")
        if (project.analysisStatus != ProjectAnalysisStatus.ANALYZED || !sha.equals(request.expectedOriginalApkSha256, true)) return BuildPreflightResult.Rejected("Build request does not match the inspected original APK.")
        val workspace = try { workspaces.open(project.id) } catch (e: IllegalArgumentException) { return BuildPreflightResult.Rejected(e.message ?: "Project workspace is unavailable.") }
        return when (val result = inspection.inspect(AnalysisRequest(project.id, workspaces.originalApk(workspace), deadline))) {
            is InspectionResult.Failure -> BuildPreflightResult.InspectionFailed(result.error.toString())
            is InspectionResult.Success -> if (!result.value.apk.archive.sha256.equals(sha, true)) BuildPreflightResult.Rejected("Original APK SHA-256 changed; re-inspect before building.") else BuildPreflightResult.Ready(buildWorkspaces.create(workspace, request.id))
        }
    }
}
