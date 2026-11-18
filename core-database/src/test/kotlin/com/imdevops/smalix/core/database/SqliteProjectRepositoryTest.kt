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
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

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

    @Test fun `persists draft metadata and cascades it with its project`() = runBlocking {
        val repository = SqliteProjectRepository(temporaryDirectory.resolve("metadata.db"))
        val project = project()
        repository.create(project)
        val metadata = draftMetadata(project.id)

        repository.save(metadata)
        assertEquals(metadata, repository.findDraft(project.id))

        val updated = metadata.copy(revision = 2, updatedAt = Instant.ofEpochMilli(3_000), validationStatus = ManifestDraftValidationStatus.INVALID)
        repository.save(updated)
        assertEquals(updated, repository.findDraft(project.id))

        repository.deleteManifestDraft(project.id)
        assertNull(repository.findDraft(project.id))

        repository.save(metadata)
        repository.delete(project.id)
        assertNull(repository.findDraft(project.id))
    }

    @Test fun `persists smali draft metadata and cascades it with its project`() = runBlocking {
        val repository = SqliteProjectRepository(temporaryDirectory.resolve("metadata.db"))
        val project = project()
        repository.create(project)
        val metadata = SmaliDraftMetadata(
            draftId = UUID.randomUUID(), projectId = project.id, originalApkSha256 = "a".repeat(64),
            relativePath = RelativePath.of("projects/${project.id.value}/work/dex/draft.smali"), revision = 0,
            createdAt = Instant.ofEpochMilli(1_000), updatedAt = Instant.ofEpochMilli(2_000), status = SmaliDraftStatus.VALID,
        )
        repository.saveSmaliDraft(metadata)
        assertEquals(listOf(metadata), repository.listSmaliDrafts(project.id))
        repository.delete(project.id)
        assertNull(repository.findSmaliDraft(metadata.draftId))
    }

    @Test fun `persists resource draft metadata only and cascades it with its project`() = runBlocking {
        val repository = SqliteProjectRepository(temporaryDirectory.resolve("metadata.db")); val project = project(); repository.create(project)
        val metadata = ResourceDraftMetadata(
            draftId = UUID.randomUUID(), projectId = project.id, originalApkSha256 = "a".repeat(64),
            relativePath = RelativePath.of("projects/${project.id.value}/work/resources/draft.json"), revision = 0,
            createdAt = Instant.ofEpochMilli(1_000), updatedAt = Instant.ofEpochMilli(2_000), status = ResourceDraftStatus.VALID,
        )
        repository.saveResourceDraft(metadata)
        assertEquals(metadata, repository.findResourceDraft(metadata.draftId)); assertEquals(listOf(metadata), repository.listResourceDrafts(project.id))
        val updated = metadata.copy(revision = 1, updatedAt = Instant.ofEpochMilli(3_000), status = ResourceDraftStatus.INVALID)
        repository.saveResourceDraft(updated); assertEquals(updated, repository.findResourceDraft(metadata.draftId))
        repository.delete(project.id); assertNull(repository.findResourceDraft(metadata.draftId))
    }

    private fun project() = Project(
        id = ProjectId.random(), displayName = "Fixture", createdAt = Instant.ofEpochMilli(1_000), lastOpenedAt = null,
        workspaceRelativePath = RelativePath.of("projects/fixture"), originalRelativePath = RelativePath.of("projects/fixture/original.apk"),
        originalSha256 = null, sourceFileName = "fixture.apk", originalSizeBytes = null,
        analysisStatus = ProjectAnalysisStatus.NOT_ANALYZED, analysisError = null,
    )

    private fun draftMetadata(projectId: ProjectId) = ManifestDraftMetadata(
        projectId = projectId, draftId = UUID.randomUUID(), originalApkSha256 = "a".repeat(64),
        relativePath = RelativePath.of("projects/${projectId.value}/work/manifest-draft.json"), revision = 0,
        createdAt = Instant.ofEpochMilli(1_000), updatedAt = Instant.ofEpochMilli(2_000),
        validationStatus = ManifestDraftValidationStatus.VALID,
    )
}
