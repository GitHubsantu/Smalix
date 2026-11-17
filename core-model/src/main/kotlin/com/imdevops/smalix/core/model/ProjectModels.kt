package com.imdevops.smalix.core.model

import java.nio.file.Path
import java.time.Instant

data class Project(
    val id: ProjectId,
    val displayName: String,
    val createdAt: Instant,
    val lastOpenedAt: Instant?,
    val workspaceRelativePath: RelativePath,
    val originalRelativePath: RelativePath,
    val originalSha256: String?,
    val sourceFileName: String?,
    val originalSizeBytes: Long?,
    val analysisStatus: ProjectAnalysisStatus,
    val analysisError: String?,
) {
    init {
        require(displayName.isNotBlank())
        require(originalSizeBytes == null || originalSizeBytes >= 0)
    }
}

enum class ProjectAnalysisStatus {
    NOT_ANALYZED,
    ANALYZING,
    ANALYZED,
    FAILED,
}

data class Workspace(
    val projectId: ProjectId,
    val root: Path,
    val originalApk: Path,
    val workDirectory: Path,
    val logsDirectory: Path,
)

@JvmInline
value class RelativePath private constructor(val value: String) {
    companion object {
        fun of(value: String): RelativePath {
            require(value.isNotBlank())
            val normalized = value.replace('\\', '/')
            require(!normalized.startsWith('/') && !normalized.matches(Regex("^[A-Za-z]:.*")))
            require(normalized.split('/').none { it.isEmpty() || it == "." || it == ".." })
            return RelativePath(normalized)
        }
    }
}
