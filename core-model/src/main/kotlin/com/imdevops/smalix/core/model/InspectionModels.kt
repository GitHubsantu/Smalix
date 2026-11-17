package com.imdevops.smalix.core.model

import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class AnalysisRequest(
    val projectId: ProjectId,
    val apkPath: Path,
    val deadline: Instant,
    val limits: ZipSafetyLimits = ZipSafetyLimits(),
)

data class ZipSafetyLimits(
    val maxArchiveBytes: Long = GIBIBYTE,
    val maxEntries: Int = 100_000,
    val maxEntryUncompressedBytes: Long = 256 * MEBIBYTE,
    val maxTotalUncompressedBytes: Long = 2 * GIBIBYTE,
    val maxCompressionRatio: Int = 100,
    val compressionRatioMinimumUncompressedBytes: Long = MEBIBYTE,
    val maxEntryNameCharacters: Int = 4_096,
    val importDeadline: Duration = Duration.ofSeconds(120),
) {
    init {
        require(maxArchiveBytes > 0)
        require(maxEntries > 0)
        require(maxEntryUncompressedBytes > 0)
        require(maxTotalUncompressedBytes > 0)
        require(maxCompressionRatio > 0)
        require(compressionRatioMinimumUncompressedBytes > 0)
        require(maxEntryNameCharacters > 0)
        require(!importDeadline.isNegative && !importDeadline.isZero)
    }

    companion object {
        const val MEBIBYTE: Long = 1_024L * 1_024L
        const val GIBIBYTE: Long = 1_024L * MEBIBYTE
    }
}

sealed interface InspectionResult<out T> {
    data class Success<T>(val value: T) : InspectionResult<T>

    data class Failure(val error: AnalysisFailure) : InspectionResult<Nothing>
}

data class ApkAnalysis(
    val archive: ValidatedApk,
    val abiNames: Set<String>,
    val warnings: List<AnalysisWarning>,
)

data class InspectionSummary(
    val apk: ApkAnalysis,
    val manifest: ManifestInfo,
    val dex: DexInfo,
)

data class ValidatedApk(
    val path: Path,
    val sha256: String,
    val archiveSizeBytes: Long,
    val entries: List<ZipEntryInfo>,
)

data class ZipEntryInfo(
    val normalizedPath: String,
    val compressedSizeBytes: Long,
    val uncompressedSizeBytes: Long,
)

data class ManifestInfo(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long?,
    val minSdk: Int?,
    val targetSdk: Int?,
    val label: ManifestLabel?,
    val activities: List<ActivityInfo>,
    val permissions: List<PermissionInfo>,
)

sealed interface ManifestLabel {
    data class Literal(val value: String) : ManifestLabel

    data class ResourceReference(val value: String) : ManifestLabel
}

data class ActivityInfo(
    val name: String,
    val exported: Boolean?,
    val enabled: Boolean?,
)

data class PermissionInfo(val name: String)

data class DexInfo(val entries: List<String>) {
    val isMultidex: Boolean get() = entries.size > 1
}

data class AnalysisWarning(val code: String, val message: String)

sealed interface AnalysisFailure {
    data class UnsafeArchive(val message: String) : AnalysisFailure

    data class LimitExceeded(val limit: String, val message: String) : AnalysisFailure

    data class MalformedApk(val message: String) : AnalysisFailure

    data class UnsupportedFormat(val format: InputFormat, val message: String) : AnalysisFailure {
        init {
            require(message.isNotBlank())
        }
    }

    data class IoFailure(val message: String) : AnalysisFailure

    data object Cancelled : AnalysisFailure
}

enum class InputFormat {
    ANDROID_APP_BUNDLE,
    XAPK,
    SPLIT_APK,
}

@JvmInline
value class ProjectId(val value: UUID) {
    companion object {
        fun random(): ProjectId = ProjectId(UUID.randomUUID())
    }
}
