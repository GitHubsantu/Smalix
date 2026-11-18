package com.imdevops.smalix.core.model

import java.time.Instant
import java.util.UUID

data class SmaliDraft(
    val id: UUID,
    val projectId: ProjectId,
    val originalApkSha256: String,
    val dexEntryName: String,
    val classDescriptor: String,
    val methodName: String,
    val methodDescriptor: String,
    val revision: Long,
    val text: String,
) {
    init { require(originalApkSha256.matches(Regex("^[0-9A-Fa-f]{64}$"))); require(dexEntryName.matches(Regex("^classes(?:[2-9][0-9]*)?\\.dex$"))); require(classDescriptor.matches(Regex("^L[^;]+;$"))); require(methodName.isNotBlank()); require(methodDescriptor.startsWith("(")); require(revision >= 0) }
}

data class SmaliDraftMetadata(
    val draftId: UUID,
    val projectId: ProjectId,
    val originalApkSha256: String,
    val relativePath: RelativePath,
    val revision: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val status: SmaliDraftStatus,
) {
    init {
        require(originalApkSha256.matches(Regex("^[0-9A-Fa-f]{64}$")))
        require(revision >= 0)
    }
}
enum class SmaliDraftStatus { VALID, INVALID }
data class SmaliValidation(val messages: List<String>) { val isValid get() = messages.isEmpty() }
