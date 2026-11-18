package com.imdevops.smalix.core.model

import java.time.Instant
import java.util.UUID

/** A text-only, unapplied replacement for one literal resource value. */
data class ResourceDraft(
    val id: UUID,
    val projectId: ProjectId,
    val originalApkSha256: String,
    val resourceId: Long,
    val packageName: String,
    val type: ResourceType,
    val name: String,
    val configuration: String,
    val originalValue: String,
    val proposedValue: String,
    val revision: Long,
) {
    init {
        require(SHA256.matches(originalApkSha256))
        require(resourceId in 0..0xffff_ffffL)
        require(PACKAGE.matches(packageName))
        require(name.isNotBlank() && name.none { it == '/' || it == '\\' || it == '\u0000' })
        require(configuration.isEmpty() || QUALIFIER.matches(configuration))
        require(revision >= 0)
    }

    companion object {
        fun from(projectId: ProjectId, originalApkSha256: String, entry: ResourceEntry): ResourceDraft? {
            val literal = entry.value as? ResourceValue.Literal ?: return null
            return ResourceDraft(
                id = UUID.randomUUID(), projectId = projectId, originalApkSha256 = originalApkSha256.lowercase(),
                resourceId = entry.resourceId, packageName = entry.packageName, type = entry.type, name = entry.name,
                configuration = entry.configuration, originalValue = literal.value, proposedValue = literal.value, revision = 0,
            )
        }

        private val SHA256 = Regex("^[0-9A-Fa-f]{64}$")
        private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_.]*$")
        private val QUALIFIER = Regex("^[A-Za-z0-9_-]+$")
    }
}

/** Metadata-only record: resource-table values and staged text belong in the workspace draft file. */
data class ResourceDraftMetadata(
    val draftId: UUID,
    val projectId: ProjectId,
    val originalApkSha256: String,
    val relativePath: RelativePath,
    val revision: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val status: ResourceDraftStatus,
) {
    init {
        require(SHA256.matches(originalApkSha256))
        require(revision >= 0)
    }

    private companion object { val SHA256 = Regex("^[0-9A-Fa-f]{64}$") }
}

enum class ResourceDraftStatus { VALID, INVALID }

data class ResourceDraftValidation(val messages: List<String>) {
    val isValid: Boolean get() = messages.isEmpty()
}
