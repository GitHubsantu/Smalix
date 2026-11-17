package com.imdevops.smalix.core.storage

import com.imdevops.smalix.core.model.ActivityInfo
import com.imdevops.smalix.core.model.ManifestDraft
import com.imdevops.smalix.core.model.ManifestLabel
import com.imdevops.smalix.core.model.PermissionInfo
import com.imdevops.smalix.core.model.ProjectId
import com.imdevops.smalix.core.model.Workspace
import com.imdevops.smalix.engine.api.ManifestDraftStore
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

class FileSystemManifestDraftStore(
    private val workspaceStore: FileSystemWorkspaceStore,
) : ManifestDraftStore {
    override suspend fun load(workspace: Workspace): ManifestDraft? {
        val path = draftPath(workspace)
        if (!Files.isRegularFile(path)) return null
        val payload = DRAFT_ENVELOPE.matchEntire(Files.readString(path))?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Manifest draft is malformed.")
        val bytes = try {
            Base64.getDecoder().decode(payload)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Manifest draft is malformed.")
        }
        return try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val draft = read(input)
                require(input.available() == 0) { "Manifest draft is malformed." }
                draft
            }
        } catch (_: EOFException) {
            throw IllegalArgumentException("Manifest draft is malformed.")
        }
    }

    override suspend fun save(workspace: Workspace, draft: ManifestDraft) {
        require(draft.projectId == workspace.projectId) { "Draft belongs to another project." }
        val target = draftPath(workspace)
        val bytes = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { write(it, draft) }
            output.toByteArray()
        }
        val temp = Files.createTempFile(workspace.workDirectory, ".manifest-draft-", ".json")
        try {
            Files.writeString(temp, "{\"format\":1,\"payload\":\"${Base64.getEncoder().encodeToString(bytes)}\"}")
            try {
                Files.move(temp, target, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    override suspend fun discard(workspace: Workspace) {
        Files.deleteIfExists(draftPath(workspace))
    }

    private fun draftPath(workspace: Workspace): Path {
        workspaceStore.originalApk(workspace)
        require(Files.isRegularFile(workspace.originalApk)) { "Workspace original APK is missing." }
        require(Files.isDirectory(workspace.workDirectory)) { "Workspace work directory is missing." }
        return workspace.workDirectory.resolve(DRAFT_FILE).normalize()
    }

    private fun write(output: DataOutputStream, draft: ManifestDraft) {
        output.writeUTF(draft.id.toString())
        output.writeUTF(draft.projectId.value.toString())
        output.writeUTF(draft.originalApkSha256)
        output.writeLong(draft.revision)
        output.writeUTF(draft.packageName)
        writeNullableString(output, draft.versionName)
        writeNullableLong(output, draft.versionCode)
        writeNullableInt(output, draft.minSdk)
        writeNullableInt(output, draft.targetSdk)
        writeLabel(output, draft.label)
        writeList(output, draft.activities) { activity ->
            output.writeUTF(activity.name)
            writeNullableBoolean(output, activity.exported)
            writeNullableBoolean(output, activity.enabled)
        }
        writeList(output, draft.permissions) { permission -> output.writeUTF(permission.name) }
        writeList(output, draft.originalActivityNames) { name -> output.writeUTF(name) }
    }

    private fun read(input: DataInputStream): ManifestDraft = ManifestDraft(
        id = UUID.fromString(input.readUTF()),
        projectId = ProjectId(UUID.fromString(input.readUTF())),
        originalApkSha256 = input.readUTF(), revision = input.readLong(), packageName = input.readUTF(),
        versionName = readNullableString(input), versionCode = readNullableLong(input),
        minSdk = readNullableInt(input), targetSdk = readNullableInt(input), label = readLabel(input),
        activities = readList(input) { ActivityInfo(input.readUTF(), readNullableBoolean(input), readNullableBoolean(input)) },
        permissions = readList(input) { PermissionInfo(input.readUTF()) },
        originalActivityNames = readList(input) { input.readUTF() }.toSet(),
    )

    private fun writeNullableString(output: DataOutputStream, value: String?) { output.writeBoolean(value != null); value?.let(output::writeUTF) }
    private fun readNullableString(input: DataInputStream): String? = if (input.readBoolean()) input.readUTF() else null
    private fun writeNullableLong(output: DataOutputStream, value: Long?) { output.writeBoolean(value != null); value?.let(output::writeLong) }
    private fun readNullableLong(input: DataInputStream): Long? = if (input.readBoolean()) input.readLong() else null
    private fun writeNullableInt(output: DataOutputStream, value: Int?) { output.writeBoolean(value != null); value?.let(output::writeInt) }
    private fun readNullableInt(input: DataInputStream): Int? = if (input.readBoolean()) input.readInt() else null
    private fun writeNullableBoolean(output: DataOutputStream, value: Boolean?) { output.writeByte(if (value == null) -1 else if (value) 1 else 0) }
    private fun readNullableBoolean(input: DataInputStream): Boolean? = when (input.readByte().toInt()) { -1 -> null; 0 -> false; 1 -> true; else -> throw IllegalArgumentException("Manifest draft is malformed.") }
    private fun writeLabel(output: DataOutputStream, value: ManifestLabel?) {
        when (value) {
            null -> output.writeByte(0)
            is ManifestLabel.Literal -> { output.writeByte(1); output.writeUTF(value.value) }
            is ManifestLabel.ResourceReference -> { output.writeByte(2); output.writeUTF(value.value) }
        }
    }
    private fun readLabel(input: DataInputStream): ManifestLabel? = when (input.readByte().toInt()) { 0 -> null; 1 -> ManifestLabel.Literal(input.readUTF()); 2 -> ManifestLabel.ResourceReference(input.readUTF()); else -> throw IllegalArgumentException("Manifest draft is malformed.") }
    private fun <T> writeList(output: DataOutputStream, values: Collection<T>, write: (T) -> Unit) { output.writeInt(values.size); values.forEach(write) }
    private fun <T> readList(input: DataInputStream, read: () -> T): List<T> { val size = input.readInt(); require(size in 0..MAX_LIST_SIZE) { "Manifest draft is malformed." }; return List(size) { read() } }

    private companion object {
        const val DRAFT_FILE = "manifest-draft.json"
        const val MAX_LIST_SIZE = 100_000
        val DRAFT_ENVELOPE = Regex("^\\{\"format\":1,\"payload\":\"([A-Za-z0-9+/=]+)\"}$")
    }
}
