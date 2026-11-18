package com.imdevops.smalix.engine.api

import com.imdevops.smalix.core.model.Project
import com.imdevops.smalix.core.model.ProjectId
import com.imdevops.smalix.core.model.ManifestDraft
import com.imdevops.smalix.core.model.ManifestDraftMetadata
import com.imdevops.smalix.core.model.SmaliDraft
import com.imdevops.smalix.core.model.SmaliValidation
import com.imdevops.smalix.core.model.ResourceDraft
import com.imdevops.smalix.core.model.ResourceDraftMetadata
import com.imdevops.smalix.core.model.ResourceDraftValidation
import com.imdevops.smalix.core.model.Workspace
import com.imdevops.smalix.core.model.BuildRequest
import com.imdevops.smalix.core.model.BuildResult
import com.imdevops.smalix.core.model.BuildWorkspace
import com.imdevops.smalix.core.model.SigningProfile
import java.nio.file.Path

interface ProjectRepository {
    suspend fun create(project: Project)

    suspend fun find(projectId: ProjectId): Project?

    suspend fun list(): List<Project>

    suspend fun update(project: Project)

    suspend fun delete(projectId: ProjectId)
}

interface WorkspaceStore {
    suspend fun create(projectId: ProjectId): Workspace

    /** Reconstructs a complete fixed workspace for an existing project. */
    suspend fun open(projectId: ProjectId): Workspace

    /** Copies once into [Workspace.originalApk]; implementations must not overwrite it. */
    suspend fun copyOriginal(sourceApk: Path, workspace: Workspace)

    fun originalApk(workspace: Workspace): Path

    /** Removes only the workspace identified by [workspace]. */
    suspend fun delete(workspace: Workspace)
}

interface ManifestDraftStore {
    suspend fun load(workspace: Workspace): ManifestDraft?

    suspend fun save(workspace: Workspace, draft: ManifestDraft)

    suspend fun discard(workspace: Workspace)
}

interface ManifestDraftRepository {
    suspend fun findDraft(projectId: ProjectId): ManifestDraftMetadata?
    suspend fun save(metadata: ManifestDraftMetadata)
    suspend fun deleteManifestDraft(projectId: ProjectId)
}

/** Stores staged Smali text under a validated workspace; it must never write APK or DEX bytes. */
interface SmaliDraftStore {
    suspend fun load(workspace: Workspace, draftId: java.util.UUID, expectedOriginalApkSha256: String): SmaliDraft?
    suspend fun save(workspace: Workspace, draft: SmaliDraft)
    suspend fun discard(workspace: Workspace, draftId: java.util.UUID)
}

/** Persists Smali draft metadata only; the text remains in the workspace. */
interface SmaliDraftRepository {
    suspend fun findSmaliDraft(draftId: java.util.UUID): com.imdevops.smalix.core.model.SmaliDraftMetadata?
    suspend fun listSmaliDrafts(projectId: ProjectId): List<com.imdevops.smalix.core.model.SmaliDraftMetadata>
    suspend fun saveSmaliDraft(metadata: com.imdevops.smalix.core.model.SmaliDraftMetadata)
    suspend fun deleteSmaliDraft(draftId: java.util.UUID)
}

interface SmaliDraftValidator { fun validate(draft: SmaliDraft): SmaliValidation }

/** Stores a staged resource draft as workspace text only; it must never write resources.arsc or APK bytes. */
interface ResourceDraftStore {
    suspend fun load(workspace: Workspace, draftId: java.util.UUID, expectedOriginalApkSha256: String): ResourceDraft?
    suspend fun save(workspace: Workspace, draft: ResourceDraft)
    suspend fun discard(workspace: Workspace, draftId: java.util.UUID)
}

/** Persists only resource-draft metadata; target/value text remains in the workspace draft file. */
interface ResourceDraftRepository {
    suspend fun findResourceDraft(draftId: java.util.UUID): ResourceDraftMetadata?
    suspend fun listResourceDrafts(projectId: ProjectId): List<ResourceDraftMetadata>
    suspend fun saveResourceDraft(metadata: ResourceDraftMetadata)
    suspend fun deleteResourceDraft(draftId: java.util.UUID)
}

/** Performs lexical validation only; applying a draft remains outside v0.4. */
interface ResourceDraftValidator { fun validate(draft: ResourceDraft): ResourceDraftValidation }

/** Persists signing-profile metadata only: no key bytes, passwords, certificate bytes, or APK bytes. */
interface SigningProfileRepository {
    suspend fun findSigningProfile(profileId: java.util.UUID): SigningProfile?
    suspend fun listSigningProfiles(): List<SigningProfile>
    suspend fun saveSigningProfile(profile: SigningProfile)
    suspend fun deleteSigningProfile(profileId: java.util.UUID)
}

/** Future isolated build contract; implementations must never mutate Workspace.originalApk. */
interface BuildPipeline { suspend fun build(workspace: Workspace, request: BuildRequest): BuildResult }

interface BuildWorkspaceStore { suspend fun create(workspace: Workspace, buildId: java.util.UUID): BuildWorkspace }
