package com.imdevops.smalix.core.database

import com.imdevops.smalix.core.model.Project
import com.imdevops.smalix.core.model.ProjectAnalysisStatus
import com.imdevops.smalix.core.model.ProjectId
import com.imdevops.smalix.core.model.RelativePath
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

class SqliteProjectRepositoryTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test fun `persists metadata only through CRUD operations`() = runBlocking {
        val repository = SqliteProjectRepository(temporaryDirectory.resolve("metadata.db"))
        val created = project()
        repository.create(created)
        assertEquals(created, repository.find(created.id))

        val updated = created.copy(analysisStatus = ProjectAnalysisStatus.ANALYZED, originalSha256 = "a".repeat(64), originalSizeBytes = 42)
        repository.update(updated)
        assertEquals(listOf(updated), repository.list())

        repository.delete(created.id)
        assertNull(repository.find(created.id))
    }

    private fun project() = Project(
        id = ProjectId.random(), displayName = "Fixture", createdAt = Instant.ofEpochMilli(1_000), lastOpenedAt = null,
        workspaceRelativePath = RelativePath.of("projects/fixture"), originalRelativePath = RelativePath.of("projects/fixture/original.apk"),
        originalSha256 = null, sourceFileName = "fixture.apk", originalSizeBytes = null,
        analysisStatus = ProjectAnalysisStatus.NOT_ANALYZED, analysisError = null,
    )
}
