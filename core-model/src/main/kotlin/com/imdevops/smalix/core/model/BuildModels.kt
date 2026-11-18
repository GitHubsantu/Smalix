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

/** Requests alignment of an already validated build-local APK into a distinct path. */
data class ZipAlignmentRequest(
    val input: ValidatedApk,
    val outputPath: Path,
    val deadline: Instant,
)

sealed interface ZipAlignmentResult {
    data class Created(val alignedApk: Path) : ZipAlignmentResult
    data class Rejected(val message: String) : ZipAlignmentResult { init { require(message.isNotBlank()) } }
    data class Failed(val message: String) : ZipAlignmentResult { init { require(message.isNotBlank()) } }
}

/** Metadata-only evidence for a successfully copied final APK. */
data class ExportedApk(
    val destination: Path,
    val sha256: String,
    val sizeBytes: Long,
    val exportedAt: Instant,
) {
    init {
        require(sha256.matches(Regex("^[0-9A-Fa-f]{64}$")))
        require(sizeBytes >= 0)
    }
}

sealed interface ApkExportResult {
    data class Exported(val apk: ExportedApk) : ApkExportResult
    data class Rejected(val message: String) : ApkExportResult { init { require(message.isNotBlank()) } }
    data class Failed(val message: String) : ApkExportResult { init { require(message.isNotBlank()) } }
    data object Cancelled : ApkExportResult
}
