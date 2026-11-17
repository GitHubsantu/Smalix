package com.imdevops.smalix.core.model

import java.time.Instant
import java.util.UUID

data class ManifestDraft(
    val id: UUID,
    val projectId: ProjectId,
    val originalApkSha256: String,
    val revision: Long,
    val packageName: String,
    val versionName: String?,
    val versionCode: Long?,
    val minSdk: Int?,
    val targetSdk: Int?,
    val label: ManifestLabel?,
    val activities: List<ActivityInfo>,
    val permissions: List<PermissionInfo>,
    val originalActivityNames: Set<String>,
) {
    init {
        require(SHA256.matches(originalApkSha256)) { "Original APK SHA-256 must be 64 hexadecimal characters." }
        require(revision >= 0)
        require(packageName.isNotBlank())
    }

    companion object {
        fun from(projectId: ProjectId, originalApkSha256: String, manifest: ManifestInfo): ManifestDraft = ManifestDraft(
            id = UUID.randomUUID(),
            projectId = projectId,
            originalApkSha256 = originalApkSha256.lowercase(),
            revision = 0,
            packageName = manifest.packageName,
            versionName = manifest.versionName,
            versionCode = manifest.versionCode,
            minSdk = manifest.minSdk,
            targetSdk = manifest.targetSdk,
            label = manifest.label,
            activities = manifest.activities,
            permissions = manifest.permissions,
            originalActivityNames = manifest.activities.map(ActivityInfo::name).toSet(),
        )

        private val SHA256 = Regex("^[0-9A-Fa-f]{64}$")
    }
}

sealed interface ManifestEdit {
    data class SetLabel(val label: ManifestLabel?) : ManifestEdit
    data class SetVersionName(val value: String?) : ManifestEdit
    data class SetVersionCode(val value: Long?) : ManifestEdit
    data class SetSdk(val minSdk: Int?, val targetSdk: Int?) : ManifestEdit
    data class AddActivity(val activity: ActivityInfo) : ManifestEdit
    data class UpdateActivity(val activity: ActivityInfo) : ManifestEdit
    data class RemoveActivity(val name: String) : ManifestEdit
    data class AddPermission(val permission: PermissionInfo) : ManifestEdit
    data class RemovePermission(val name: String) : ManifestEdit
}

data class ManifestEditResult(
    val draft: ManifestDraft,
    val validation: ManifestValidation,
)

data class ManifestValidation(val issues: List<ManifestIssue>) {
    val isValid: Boolean get() = issues.none { it.severity == ManifestIssueSeverity.ERROR }
}

data class ManifestIssue(
    val severity: ManifestIssueSeverity,
    val field: String,
    val message: String,
)

enum class ManifestIssueSeverity { ERROR, WARNING }

data class ManifestDraftMetadata(
    val projectId: ProjectId,
    val draftId: UUID,
    val originalApkSha256: String,
    val relativePath: RelativePath,
    val revision: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val validationStatus: ManifestDraftValidationStatus,
) {
    init {
        require(SHA256.matches(originalApkSha256)) { "Original APK SHA-256 must be 64 hexadecimal characters." }
        require(revision >= 0)
    }

    companion object {
        private val SHA256 = Regex("^[0-9A-Fa-f]{64}$")
    }
}

enum class ManifestDraftValidationStatus { VALID, INVALID }
