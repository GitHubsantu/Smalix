package com.imdevops.smalix

import com.imdevops.smalix.core.model.AnalysisFailure
import com.imdevops.smalix.core.model.AnalysisRequest
import com.imdevops.smalix.core.model.InspectionResult
import com.imdevops.smalix.core.model.InspectionSummary
import com.imdevops.smalix.core.model.Project
import com.imdevops.smalix.core.model.ProjectAnalysisStatus
import com.imdevops.smalix.core.model.ZipSafetyLimits
import com.imdevops.smalix.engine.api.ProjectRepository
import java.nio.file.Path
import java.time.Instant

class ProjectInspectionUseCase(
    private val projectRepository: ProjectRepository,
    private val inspectionUseCase: InspectionUseCase,
) {
    suspend fun inspect(
        project: Project,
        immutableOriginalApk: Path,
        deadline: Instant,
        limits: ZipSafetyLimits = ZipSafetyLimits(),
    ): InspectionResult<InspectionSummary> {
        projectRepository.update(project.copy(analysisStatus = ProjectAnalysisStatus.ANALYZING, analysisError = null))
        return when (val result = inspectionUseCase.inspect(AnalysisRequest(project.id, immutableOriginalApk, deadline, limits))) {
            is InspectionResult.Success -> {
                projectRepository.update(
                    project.copy(
                        originalSha256 = result.value.apk.archive.sha256,
                        originalSizeBytes = result.value.apk.archive.archiveSizeBytes,
                        analysisStatus = ProjectAnalysisStatus.ANALYZED,
                        analysisError = null,
                    ),
                )
                result
            }
            is InspectionResult.Failure -> {
                projectRepository.update(
                    project.copy(
                        analysisStatus = ProjectAnalysisStatus.FAILED,
                        analysisError = errorMessage(result.error),
                    ),
                )
                result
            }
        }
    }

    private fun errorMessage(error: AnalysisFailure): String = when (error) {
        is AnalysisFailure.UnsafeArchive -> error.message
        is AnalysisFailure.LimitExceeded -> error.message
        is AnalysisFailure.MalformedApk -> error.message
        is AnalysisFailure.UnsupportedFormat -> error.message
        is AnalysisFailure.IoFailure -> error.message
        AnalysisFailure.Cancelled -> "APK analysis was cancelled."
    }
}
