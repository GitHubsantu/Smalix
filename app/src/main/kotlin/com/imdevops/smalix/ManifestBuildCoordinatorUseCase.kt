package com.imdevops.smalix

import com.imdevops.smalix.core.model.BuildRequest
import com.imdevops.smalix.core.model.BuildWorkspace
import com.imdevops.smalix.core.model.ManifestDraft
import com.imdevops.smalix.core.model.ManifestInfo
import java.time.Instant

sealed interface ManifestBuildCoordinatorResult {
    data class Ready(val manifest: ManifestInfo) : ManifestBuildCoordinatorResult
    data class Rejected(val message: String) : ManifestBuildCoordinatorResult
    data class Failed(val message: String) : ManifestBuildCoordinatorResult
}

/** Binds a SHA-matched staged manifest draft to a fresh build preflight workspace only. */
class ManifestBuildCoordinatorUseCase(
    private val preflight: BuildPreflightUseCase,
    private val apply: suspend (BuildWorkspace, ManifestDraft, Instant) -> ManifestBuildApplication,
) {
    constructor(preflight: BuildPreflightUseCase, workflow: ManifestBuildApplicationUseCase) : this(preflight, workflow::apply)

    suspend fun apply(request: BuildRequest, draft: ManifestDraft, deadline: Instant): ManifestBuildCoordinatorResult {
        if (draft.projectId != request.projectId || !draft.originalApkSha256.equals(request.expectedOriginalApkSha256, true)) {
            return ManifestBuildCoordinatorResult.Rejected("Manifest draft does not match the build request original APK.")
        }
        return when (val ready = preflight.prepare(request, deadline)) {
            is BuildPreflightResult.Rejected -> ManifestBuildCoordinatorResult.Rejected(ready.message)
            is BuildPreflightResult.InspectionFailed -> ManifestBuildCoordinatorResult.Failed(ready.message)
            is BuildPreflightResult.Ready -> when (val result = apply(ready.workspace, draft, deadline)) {
                is ManifestBuildApplication.Ready -> ManifestBuildCoordinatorResult.Ready(result.manifest)
                is ManifestBuildApplication.Rejected -> ManifestBuildCoordinatorResult.Rejected(result.message)
                is ManifestBuildApplication.Failed -> ManifestBuildCoordinatorResult.Failed(result.message)
            }
        }
    }
}
