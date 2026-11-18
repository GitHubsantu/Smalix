package com.imdevops.smalix.core.storage

import com.imdevops.smalix.core.model.ProjectId
import com.imdevops.smalix.core.model.ResourceDraft
import com.imdevops.smalix.core.model.ResourceType
import com.imdevops.smalix.core.model.Workspace
import com.imdevops.smalix.engine.api.ResourceDraftStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Base64
import java.util.UUID

/** Atomic workspace-only resource draft store. The JSON envelope contains no APK/resource-table bytes. */
class FileSystemResourceDraftStore(
    private val workspaceStore: FileSystemWorkspaceStore,
) : ResourceDraftStore {
    override suspend fun load(workspace: Workspace, draftId: UUID, expectedOriginalApkSha256: String): ResourceDraft? {
        require(expectedOriginalApkSha256.matches(SHA256)) { "Expected SHA-256 is malformed." }
        val path = draftPath(workspace, draftId)
        if (!Files.isRegularFile(path)) return null
        val payload = ENVELOPE.matchEntire(Files.readString(path))?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Resource draft is malformed.")
        val draft = try {
            DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(payload))).use { input ->
                read(input).also { require(input.available() == 0) { "Resource draft is malformed." } }
            }
        } catch (_: EOFException) { throw IllegalArgumentException("Resource draft is malformed.") }
          catch (_: IllegalArgumentException) { throw IllegalArgumentException("Resource draft is malformed.") }
        require(draft.id == draftId && draft.projectId == workspace.projectId) { "Resource draft belongs to another project." }
        require(draft.originalApkSha256.equals(expectedOriginalApkSha256, ignoreCase = true)) { "Resource draft is stale for the current original APK." }
        return draft
    }

    override suspend fun save(workspace: Workspace, draft: ResourceDraft) {
        require(draft.projectId == workspace.projectId) { "Resource draft belongs to another project." }
        val target = draftPath(workspace, draft.id)
        val encoded = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { write(it, draft) }
            Base64.getEncoder().encodeToString(output.toByteArray())
        }
        val temporary = Files.createTempFile(target.parent, ".resource-draft-", ".tmp")
        try {
            Files.writeString(temporary, "{\"format\":1,\"payload\":\"$encoded\"}")
            try { Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, target, REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
    }

    override suspend fun discard(workspace: Workspace, draftId: UUID) { Files.deleteIfExists(draftPath(workspace, draftId)) }

    private fun draftPath(workspace: Workspace, draftId: UUID): Path {
        workspaceStore.originalApk(workspace)
        require(Files.isRegularFile(workspace.originalApk)) { "Workspace original APK is missing." }
        require(Files.isDirectory(workspace.workDirectory)) { "Workspace work directory is missing." }
        val directory = workspace.workDirectory.resolve("resources")
        Files.createDirectories(directory)
        return directory.resolve("$draftId.json").normalize().also { require(it.parent == directory) }
    }

    private fun write(output: DataOutputStream, draft: ResourceDraft) {
        output.writeUTF(draft.id.toString()); output.writeUTF(draft.projectId.value.toString()); output.writeUTF(draft.originalApkSha256)
        output.writeLong(draft.resourceId); output.writeUTF(draft.packageName); output.writeUTF(draft.type.name); output.writeUTF(draft.name)
        output.writeUTF(draft.configuration); output.writeUTF(draft.originalValue); output.writeUTF(draft.proposedValue); output.writeLong(draft.revision)
    }

    private fun read(input: DataInputStream) = ResourceDraft(
        id = UUID.fromString(input.readUTF()), projectId = ProjectId(UUID.fromString(input.readUTF())), originalApkSha256 = input.readUTF(),
        resourceId = input.readLong(), packageName = input.readUTF(), type = ResourceType.valueOf(input.readUTF()), name = input.readUTF(),
        configuration = input.readUTF(), originalValue = input.readUTF(), proposedValue = input.readUTF(), revision = input.readLong(),
    )

    private companion object {
        val SHA256 = Regex("^[0-9A-Fa-f]{64}$")
        val ENVELOPE = Regex("^\\{\"format\":1,\"payload\":\"([A-Za-z0-9+/=]+)\"}$")
    }
}
