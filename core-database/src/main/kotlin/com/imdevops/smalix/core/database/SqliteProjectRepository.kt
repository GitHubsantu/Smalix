package com.imdevops.smalix.core.database

import com.imdevops.smalix.core.model.Project
import com.imdevops.smalix.core.model.ProjectAnalysisStatus
import com.imdevops.smalix.core.model.ProjectId
import com.imdevops.smalix.core.model.RelativePath
import com.imdevops.smalix.core.model.ManifestDraftMetadata
import com.imdevops.smalix.core.model.ManifestDraftValidationStatus
import com.imdevops.smalix.core.model.SmaliDraftMetadata
import com.imdevops.smalix.core.model.SmaliDraftStatus
import com.imdevops.smalix.core.model.ResourceDraftMetadata
import com.imdevops.smalix.core.model.ResourceDraftStatus
import com.imdevops.smalix.engine.api.ProjectRepository
import com.imdevops.smalix.engine.api.ManifestDraftRepository
import com.imdevops.smalix.engine.api.SmaliDraftRepository
import com.imdevops.smalix.engine.api.ResourceDraftRepository
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

class SqliteProjectRepository(databasePath: Path) : ProjectRepository, ManifestDraftRepository, SmaliDraftRepository, ResourceDraftRepository {
    private val url = "jdbc:sqlite:${databasePath.toAbsolutePath().normalize()}"

    init {
        databasePath.toAbsolutePath().normalize().parent?.let(java.nio.file.Files::createDirectories)
        connection().use(::createSchema)
    }

    override suspend fun create(project: Project) {
        connection().use { connection ->
        connection.prepareStatement(
            """INSERT INTO projects (
                id, display_name, created_at_ms, last_opened_at_ms, workspace_relative_path,
                original_relative_path, original_sha256, source_file_name, original_size_bytes,
                analysis_status, analysis_error
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        ).use { statement ->
            statement.bind(project)
            statement.executeUpdate()
        }
        }
    }

    override suspend fun find(projectId: ProjectId): Project? = connection().use { connection ->
        connection.prepareStatement("SELECT * FROM projects WHERE id = ?").use { statement ->
            statement.setString(1, projectId.value.toString())
            statement.executeQuery().use { results -> if (results.next()) results.project() else null }
        }
    }

    override suspend fun list(): List<Project> = connection().use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT * FROM projects ORDER BY created_at_ms DESC, id ASC").use { results ->
                buildList { while (results.next()) add(results.project()) }
            }
        }
    }

    override suspend fun update(project: Project) {
        connection().use { connection ->
        connection.prepareStatement(
            """UPDATE projects SET
                display_name = ?, last_opened_at_ms = ?, workspace_relative_path = ?,
                original_relative_path = ?, original_sha256 = ?, source_file_name = ?,
                original_size_bytes = ?, analysis_status = ?, analysis_error = ?
            WHERE id = ?""",
        ).use { statement ->
            statement.setString(1, project.displayName)
            statement.setNullableLong(2, project.lastOpenedAt?.toEpochMilli())
            statement.setString(3, project.workspaceRelativePath.value)
            statement.setString(4, project.originalRelativePath.value)
            statement.setNullableString(5, project.originalSha256)
            statement.setNullableString(6, project.sourceFileName)
            statement.setNullableLong(7, project.originalSizeBytes)
            statement.setString(8, project.analysisStatus.name)
            statement.setNullableString(9, project.analysisError)
            statement.setString(10, project.id.value.toString())
            statement.executeUpdate()
        }
        }
    }

    override suspend fun delete(projectId: ProjectId) {
        connection().use { connection ->
        connection.prepareStatement("DELETE FROM projects WHERE id = ?").use { statement ->
            statement.setString(1, projectId.value.toString())
            statement.executeUpdate()
        }
        }
    }

    override suspend fun findDraft(projectId: ProjectId): ManifestDraftMetadata? = connection().use { connection ->
        connection.prepareStatement("SELECT * FROM manifest_drafts WHERE project_id = ?").use { statement ->
            statement.setString(1, projectId.value.toString())
            statement.executeQuery().use { results -> if (results.next()) results.draftMetadata() else null }
        }
    }

    override suspend fun save(metadata: ManifestDraftMetadata) {
        connection().use { connection ->
            connection.prepareStatement(
                """INSERT INTO manifest_drafts (project_id, draft_id, original_apk_sha256, relative_path, revision, created_at_ms, updated_at_ms, validation_status)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                   ON CONFLICT(project_id) DO UPDATE SET draft_id=excluded.draft_id, original_apk_sha256=excluded.original_apk_sha256,
                   relative_path=excluded.relative_path, revision=excluded.revision, updated_at_ms=excluded.updated_at_ms, validation_status=excluded.validation_status""",
            ).use { statement ->
                statement.setString(1, metadata.projectId.value.toString()); statement.setString(2, metadata.draftId.toString())
                statement.setString(3, metadata.originalApkSha256); statement.setString(4, metadata.relativePath.value)
                statement.setLong(5, metadata.revision); statement.setLong(6, metadata.createdAt.toEpochMilli())
                statement.setLong(7, metadata.updatedAt.toEpochMilli()); statement.setString(8, metadata.validationStatus.name)
                statement.executeUpdate()
            }
        }
    }

    override suspend fun deleteManifestDraft(projectId: ProjectId) {
        connection().use { connection ->
            connection.prepareStatement("DELETE FROM manifest_drafts WHERE project_id = ?").use { statement ->
                statement.setString(1, projectId.value.toString()); statement.executeUpdate()
            }
        }
    }

    override suspend fun findSmaliDraft(draftId: UUID): SmaliDraftMetadata? = connection().use { connection ->
        connection.prepareStatement("SELECT * FROM smali_drafts WHERE draft_id = ?").use { statement ->
            statement.setString(1, draftId.toString())
            statement.executeQuery().use { results -> if (results.next()) results.smaliDraftMetadata() else null }
        }
    }

    override suspend fun listSmaliDrafts(projectId: ProjectId): List<SmaliDraftMetadata> = connection().use { connection ->
        connection.prepareStatement("SELECT * FROM smali_drafts WHERE project_id = ? ORDER BY updated_at_ms DESC, draft_id ASC").use { statement ->
            statement.setString(1, projectId.value.toString())
            statement.executeQuery().use { results -> buildList { while (results.next()) add(results.smaliDraftMetadata()) } }
        }
    }

    override suspend fun saveSmaliDraft(metadata: SmaliDraftMetadata) {
        connection().use { connection ->
            connection.prepareStatement(
                """INSERT INTO smali_drafts (draft_id, project_id, original_apk_sha256, relative_path, revision, created_at_ms, updated_at_ms, status)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                   ON CONFLICT(draft_id) DO UPDATE SET original_apk_sha256=excluded.original_apk_sha256, relative_path=excluded.relative_path,
                   revision=excluded.revision, updated_at_ms=excluded.updated_at_ms, status=excluded.status""",
            ).use { statement ->
                statement.setString(1, metadata.draftId.toString()); statement.setString(2, metadata.projectId.value.toString())
                statement.setString(3, metadata.originalApkSha256); statement.setString(4, metadata.relativePath.value)
                statement.setLong(5, metadata.revision); statement.setLong(6, metadata.createdAt.toEpochMilli())
                statement.setLong(7, metadata.updatedAt.toEpochMilli()); statement.setString(8, metadata.status.name)
                statement.executeUpdate()
            }
        }
    }

    override suspend fun deleteSmaliDraft(draftId: UUID) {
        connection().use { connection ->
            connection.prepareStatement("DELETE FROM smali_drafts WHERE draft_id = ?").use { statement ->
                statement.setString(1, draftId.toString()); statement.executeUpdate()
            }
        }
    }

    override suspend fun findResourceDraft(draftId: UUID): ResourceDraftMetadata? = connection().use { connection ->
        connection.prepareStatement("SELECT * FROM resource_drafts WHERE draft_id = ?").use { statement ->
            statement.setString(1, draftId.toString())
            statement.executeQuery().use { results -> if (results.next()) results.resourceDraftMetadata() else null }
        }
    }

    override suspend fun listResourceDrafts(projectId: ProjectId): List<ResourceDraftMetadata> = connection().use { connection ->
        connection.prepareStatement("SELECT * FROM resource_drafts WHERE project_id = ? ORDER BY updated_at_ms DESC, draft_id ASC").use { statement ->
            statement.setString(1, projectId.value.toString())
            statement.executeQuery().use { results -> buildList { while (results.next()) add(results.resourceDraftMetadata()) } }
        }
    }

    override suspend fun saveResourceDraft(metadata: ResourceDraftMetadata) {
        connection().use { connection ->
            connection.prepareStatement(
                """INSERT INTO resource_drafts (draft_id, project_id, original_apk_sha256, relative_path, revision, created_at_ms, updated_at_ms, status)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                   ON CONFLICT(draft_id) DO UPDATE SET original_apk_sha256=excluded.original_apk_sha256, relative_path=excluded.relative_path,
                   revision=excluded.revision, updated_at_ms=excluded.updated_at_ms, status=excluded.status""",
            ).use { statement ->
                statement.setString(1, metadata.draftId.toString()); statement.setString(2, metadata.projectId.value.toString())
                statement.setString(3, metadata.originalApkSha256); statement.setString(4, metadata.relativePath.value)
                statement.setLong(5, metadata.revision); statement.setLong(6, metadata.createdAt.toEpochMilli())
                statement.setLong(7, metadata.updatedAt.toEpochMilli()); statement.setString(8, metadata.status.name)
                statement.executeUpdate()
            }
        }
    }

    override suspend fun deleteResourceDraft(draftId: UUID) {
        connection().use { connection ->
            connection.prepareStatement("DELETE FROM resource_drafts WHERE draft_id = ?").use { statement ->
                statement.setString(1, draftId.toString()); statement.executeUpdate()
            }
        }
    }

    private fun connection(): Connection = DriverManager.getConnection(url).also { connection ->
        connection.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
    }

    private fun createSchema(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """CREATE TABLE IF NOT EXISTS projects (
                    id TEXT PRIMARY KEY NOT NULL,
                    display_name TEXT NOT NULL,
                    created_at_ms INTEGER NOT NULL,
                    last_opened_at_ms INTEGER,
                    workspace_relative_path TEXT NOT NULL,
                    original_relative_path TEXT NOT NULL,
                    original_sha256 TEXT,
                    source_file_name TEXT,
                    original_size_bytes INTEGER,
                    analysis_status TEXT NOT NULL,
                    analysis_error TEXT
                )""",
            )
            statement.execute(
                """CREATE TABLE IF NOT EXISTS smali_drafts (
                    draft_id TEXT PRIMARY KEY NOT NULL, project_id TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
                    original_apk_sha256 TEXT NOT NULL, relative_path TEXT NOT NULL, revision INTEGER NOT NULL,
                    created_at_ms INTEGER NOT NULL, updated_at_ms INTEGER NOT NULL, status TEXT NOT NULL
                )""",
            )
            statement.execute(
                """CREATE TABLE IF NOT EXISTS manifest_drafts (
                    project_id TEXT PRIMARY KEY NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
                    draft_id TEXT NOT NULL, original_apk_sha256 TEXT NOT NULL, relative_path TEXT NOT NULL,
                    revision INTEGER NOT NULL, created_at_ms INTEGER NOT NULL, updated_at_ms INTEGER NOT NULL,
                    validation_status TEXT NOT NULL
                )""",
            )
            statement.execute(
                """CREATE TABLE IF NOT EXISTS resource_drafts (
                    draft_id TEXT PRIMARY KEY NOT NULL, project_id TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
                    original_apk_sha256 TEXT NOT NULL, relative_path TEXT NOT NULL, revision INTEGER NOT NULL,
                    created_at_ms INTEGER NOT NULL, updated_at_ms INTEGER NOT NULL, status TEXT NOT NULL
                )""",
            )
        }
    }

    private fun java.sql.PreparedStatement.bind(project: Project) {
        setString(1, project.id.value.toString())
        setString(2, project.displayName)
        setLong(3, project.createdAt.toEpochMilli())
        setNullableLong(4, project.lastOpenedAt?.toEpochMilli())
        setString(5, project.workspaceRelativePath.value)
        setString(6, project.originalRelativePath.value)
        setNullableString(7, project.originalSha256)
        setNullableString(8, project.sourceFileName)
        setNullableLong(9, project.originalSizeBytes)
        setString(10, project.analysisStatus.name)
        setNullableString(11, project.analysisError)
    }

    private fun java.sql.PreparedStatement.setNullableString(index: Int, value: String?) =
        if (value == null) setNull(index, java.sql.Types.VARCHAR) else setString(index, value)

    private fun java.sql.PreparedStatement.setNullableLong(index: Int, value: Long?) =
        if (value == null) setNull(index, java.sql.Types.BIGINT) else setLong(index, value)

    private fun java.sql.ResultSet.project() = Project(
        id = ProjectId(UUID.fromString(getString("id"))),
        displayName = getString("display_name"),
        createdAt = Instant.ofEpochMilli(getLong("created_at_ms")),
        lastOpenedAt = getLongOrNull("last_opened_at_ms")?.let(Instant::ofEpochMilli),
        workspaceRelativePath = RelativePath.of(getString("workspace_relative_path")),
        originalRelativePath = RelativePath.of(getString("original_relative_path")),
        originalSha256 = getString("original_sha256"),
        sourceFileName = getString("source_file_name"),
        originalSizeBytes = getLongOrNull("original_size_bytes"),
        analysisStatus = ProjectAnalysisStatus.valueOf(getString("analysis_status")),
        analysisError = getString("analysis_error"),
    )

    private fun java.sql.ResultSet.draftMetadata() = ManifestDraftMetadata(
        projectId = ProjectId(UUID.fromString(getString("project_id"))), draftId = UUID.fromString(getString("draft_id")),
        originalApkSha256 = getString("original_apk_sha256"), relativePath = RelativePath.of(getString("relative_path")),
        revision = getLong("revision"), createdAt = Instant.ofEpochMilli(getLong("created_at_ms")),
        updatedAt = Instant.ofEpochMilli(getLong("updated_at_ms")), validationStatus = ManifestDraftValidationStatus.valueOf(getString("validation_status")),
    )

    private fun java.sql.ResultSet.smaliDraftMetadata() = SmaliDraftMetadata(
        draftId = UUID.fromString(getString("draft_id")), projectId = ProjectId(UUID.fromString(getString("project_id"))),
        originalApkSha256 = getString("original_apk_sha256"), relativePath = RelativePath.of(getString("relative_path")),
        revision = getLong("revision"), createdAt = Instant.ofEpochMilli(getLong("created_at_ms")),
        updatedAt = Instant.ofEpochMilli(getLong("updated_at_ms")), status = SmaliDraftStatus.valueOf(getString("status")),
    )

    private fun java.sql.ResultSet.resourceDraftMetadata() = ResourceDraftMetadata(
        draftId = UUID.fromString(getString("draft_id")), projectId = ProjectId(UUID.fromString(getString("project_id"))),
        originalApkSha256 = getString("original_apk_sha256"), relativePath = RelativePath.of(getString("relative_path")),
        revision = getLong("revision"), createdAt = Instant.ofEpochMilli(getLong("created_at_ms")),
        updatedAt = Instant.ofEpochMilli(getLong("updated_at_ms")), status = ResourceDraftStatus.valueOf(getString("status")),
    )

    private fun java.sql.ResultSet.getLongOrNull(column: String): Long? = getLong(column).takeUnless { wasNull() }
}
