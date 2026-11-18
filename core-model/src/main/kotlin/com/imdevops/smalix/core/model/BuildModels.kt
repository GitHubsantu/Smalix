package com.imdevops.smalix.core.model

import java.time.Instant
import java.util.UUID
import java.nio.file.Path

data class BuildRequest(
    val id: UUID,
    val projectId: ProjectId,
    val expectedOriginalApkSha256: String,
    val exportPath: RelativePath,
    val requestedAt: Instant,
) {
    init { require(expectedOriginalApkSha256.matches(Regex("^[0-9A-Fa-f]{64}$"))); require(exportPath.value.endsWith(".apk", true)) }
}

sealed interface BuildResult {
    data class Verified(val buildId: UUID, val exportPath: RelativePath, val outputSha256: String) : BuildResult
    data class Rejected(val message: String) : BuildResult { init { require(message.isNotBlank()) } }
    data class Failed(val stage: BuildStage, val message: String) : BuildResult { init { require(message.isNotBlank()) } }
    data object Cancelled : BuildResult
}

enum class BuildStage { PRECHECK, COPY, APPLY, VALIDATE, ALIGN, SIGN, VERIFY, EXPORT }

data class BuildWorkspace(
    val buildId: UUID,
    val projectId: ProjectId,
    val root: Path,
    val inputApk: Path,
)
