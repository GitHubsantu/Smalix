package com.imdevops.smalix.core.storage

import com.imdevops.smalix.core.model.ProjectId
import com.imdevops.smalix.core.model.SmaliDraft
import com.imdevops.smalix.core.model.Workspace
import com.imdevops.smalix.engine.api.SmaliDraftStore
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

class FileSystemSmaliDraftStore(
    private val workspaceStore: FileSystemWorkspaceStore,
) : SmaliDraftStore {
    override suspend fun load(workspace: Workspace, draftId: UUID, expectedOriginalApkSha256: String): SmaliDraft? {
        require(expectedOriginalApkSha256.matches(SHA256)) { "Expected SHA-256 is malformed." }
        val path = draftPath(workspace, draftId)
        if (!Files.isRegularFile(path)) return null
        val payload = ENVELOPE.matchEntire(Files.readString(path))?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Smali draft is malformed.")
        val draft = try {
            val bytes = Base64.getDecoder().decode(payload)
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                read(input).also { require(input.available() == 0) { "Smali draft is malformed." } }
            }
        } catch (_: EOFException) {
            throw IllegalArgumentException("Smali draft is malformed.")
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Smali draft is malformed.")
        }
        require(draft.id == draftId && draft.projectId == workspace.projectId) { "Smali draft belongs to another project." }
        require(draft.originalApkSha256.equals(expectedOriginalApkSha256, ignoreCase = true)) { "Smali draft is stale for the current original APK." }
        return draft
    }

    override suspend fun save(workspace: Workspace, draft: SmaliDraft) {
        require(draft.projectId == workspace.projectId) { "Smali draft belongs to another project." }
        val target = draftPath(workspace, draft.id)
        val encoded = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { write(it, draft) }
            Base64.getEncoder().encodeToString(output.toByteArray())
        }
        val temporary = Files.createTempFile(target.parent, ".smali-draft-", ".tmp")
        try {
            Files.writeString(temporary, "{\"format\":1,\"payload\":\"$encoded\"}")
            try { Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, target, REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
    }

    override suspend fun discard(workspace: Workspace, draftId: UUID) {
        Files.deleteIfExists(draftPath(workspace, draftId))
    }

    private fun draftPath(workspace: Workspace, draftId: UUID): Path {
        workspaceStore.originalApk(workspace)
        require(Files.isRegularFile(workspace.originalApk)) { "Workspace original APK is missing." }
        require(Files.isDirectory(workspace.workDirectory)) { "Workspace work directory is missing." }
        val directory = workspace.workDirectory.resolve("dex")
        Files.createDirectories(directory)
        return directory.resolve("$draftId.smali").normalize().also { require(it.parent == directory) }
    }

    private fun write(output: DataOutputStream, draft: SmaliDraft) {
        output.writeUTF(draft.id.toString()); output.writeUTF(draft.projectId.value.toString()); output.writeUTF(draft.originalApkSha256)
        output.writeUTF(draft.dexEntryName); output.writeUTF(draft.classDescriptor); output.writeUTF(draft.methodName)
        output.writeUTF(draft.methodDescriptor); output.writeLong(draft.revision); output.writeUTF(draft.text)
    }

    private fun read(input: DataInputStream) = SmaliDraft(
        id = UUID.fromString(input.readUTF()), projectId = ProjectId(UUID.fromString(input.readUTF())), originalApkSha256 = input.readUTF(),
        dexEntryName = input.readUTF(), classDescriptor = input.readUTF(), methodName = input.readUTF(),
        methodDescriptor = input.readUTF(), revision = input.readLong(), text = input.readUTF(),
    )

    private companion object {
        val SHA256 = Regex("^[0-9A-Fa-f]{64}$")
        val ENVELOPE = Regex("^\\{\"format\":1,\"payload\":\"([A-Za-z0-9+/=]+)\"}$")
    }
}
