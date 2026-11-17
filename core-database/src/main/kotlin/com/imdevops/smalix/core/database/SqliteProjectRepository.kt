package com.imdevops.smalix.core.database

import com.imdevops.smalix.core.model.Project
import com.imdevops.smalix.core.model.ProjectAnalysisStatus
import com.imdevops.smalix.core.model.ProjectId
import com.imdevops.smalix.core.model.RelativePath
import com.imdevops.smalix.engine.api.ProjectRepository
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

class SqliteProjectRepository(databasePath: Path) : ProjectRepository {
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

    private fun java.sql.ResultSet.getLongOrNull(column: String): Long? = getLong(column).takeUnless { wasNull() }
}
