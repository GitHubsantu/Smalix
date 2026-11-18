package com.imdevops.smalix

import com.imdevops.smalix.core.model.ApkExportResult
import com.imdevops.smalix.core.model.BuildWorkspace
import com.imdevops.smalix.core.model.ExportedApk
import java.io.BufferedInputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import java.time.Instant

/** Exports only fresh signed-output evidence to a new external APK path; it never overwrites. */
class ApkExportUseCase {
    suspend fun export(
        workspace: BuildWorkspace,
        input: ValidatedBuildOutput,
        destination: Path,
        deadline: Instant,
        isCancelled: () -> Boolean = { false },
    ): ApkExportResult {
        if (isCancelled()) return ApkExportResult.Cancelled
        if (!Instant.now().isBefore(deadline)) return ApkExportResult.Rejected("APK export exceeded its deadline.")

        val root = workspace.root.toAbsolutePath().normalize()
        val source = root.resolve(SIGNED_NAME).normalize()
        val target = destination.toAbsolutePath().normalize()
        if (input.archive.path.toAbsolutePath().normalize() != source || source.parent != root) {
            return ApkExportResult.Rejected("Export requires the fully reinspected signed APK output.")
        }
        if (!target.fileName.toString().endsWith(".apk", ignoreCase = true) || target == source || target.startsWith(root)) {
            return ApkExportResult.Rejected("Export requires a new external .apk destination.")
        }
        val parent = target.parent ?: return ApkExportResult.Rejected("Export destination requires a parent directory.")
        if (!Files.isRegularFile(source) || Files.exists(target)) {
            return ApkExportResult.Rejected("Signed APK is unavailable or export destination already exists.")
        }
        if (!Files.isDirectory(parent) || target.startsWith(root) || parent.startsWith(root)) {
            return ApkExportResult.Rejected("Export destination parent is unavailable or inside the build workspace.")
        }

        when (val sourceEvidence = hashAndSize(source, deadline, isCancelled)) {
            HashResult.Cancelled -> return ApkExportResult.Cancelled
            HashResult.Expired -> return ApkExportResult.Rejected("APK export exceeded its deadline.")
            is HashResult.Failed -> return ApkExportResult.Failed(sourceEvidence.message)
            is HashResult.Value -> if (!sourceEvidence.matches(input)) return ApkExportResult.Rejected("Signed APK changed after reinspection.")
        }

        var temporary: Path? = null
        var committed = false
        return try {
            temporary = Files.createTempFile(parent, ".smalix-export-", ".tmp")
            when (val copied = copyAndHash(source, temporary, input, deadline, isCancelled)) {
                HashResult.Cancelled -> return ApkExportResult.Cancelled
                HashResult.Expired -> return ApkExportResult.Rejected("APK export exceeded its deadline.")
                is HashResult.Failed -> return ApkExportResult.Failed(copied.message)
                is HashResult.Value -> if (!copied.matches(input)) return ApkExportResult.Rejected("Signed APK changed while exporting.")
            }
            when (val afterCopy = hashAndSize(source, deadline, isCancelled)) {
                HashResult.Cancelled -> return ApkExportResult.Cancelled
                HashResult.Expired -> return ApkExportResult.Rejected("APK export exceeded its deadline.")
                is HashResult.Failed -> return ApkExportResult.Failed(afterCopy.message)
                is HashResult.Value -> if (!afterCopy.matches(input)) return ApkExportResult.Rejected("Signed APK changed while exporting.")
            }
            if (Files.exists(target)) return ApkExportResult.Rejected("Export destination already exists.")
            val finalTarget = parent.resolve(target.fileName).normalize()
            try {
                Files.move(temporary, finalTarget, ATOMIC_MOVE)
                committed = true
            } catch (_: AtomicMoveNotSupportedException) {
                return ApkExportResult.Rejected("Export destination does not support an atomic move.")
            } catch (_: FileAlreadyExistsException) {
                return ApkExportResult.Rejected("Export destination already exists.")
            } catch (_: IOException) {
                return ApkExportResult.Failed("Unable to create exported APK.")
            }
            when (val verified = hashAndSize(finalTarget, Instant.MAX) { false }) {
                is HashResult.Value -> if (verified.matches(input)) {
                    ApkExportResult.Exported(ExportedApk(finalTarget, verified.sha256, verified.sizeBytes, Instant.now()))
                } else ApkExportResult.Failed("Exported APK did not match signed output evidence.")
                else -> ApkExportResult.Failed("Unable to verify exported APK.")
            }
        } catch (_: IOException) {
            ApkExportResult.Failed("Unable to create export temporary file.")
        } finally {
            if (!committed) temporary?.let { path -> try { Files.deleteIfExists(path) } catch (_: IOException) { } }
        }
    }

    private fun copyAndHash(source: Path, temporary: Path, input: ValidatedBuildOutput, deadline: Instant, isCancelled: () -> Boolean): HashResult {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        return try {
            BufferedInputStream(Files.newInputStream(source)).use { from ->
                Files.newOutputStream(temporary).use { to ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        if (isCancelled()) return HashResult.Cancelled
                        if (!Instant.now().isBefore(deadline)) return HashResult.Expired
                        val read = from.read(buffer)
                        if (read < 0) break
                        size += read
                        if (size > input.archive.archiveSizeBytes) return HashResult.Value("", size)
                        digest.update(buffer, 0, read)
                        to.write(buffer, 0, read)
                    }
                }
            }
            HashResult.Value(digest.digest().hex(), size)
        } catch (_: IOException) {
            HashResult.Failed("Unable to copy signed APK for export.")
        }
    }

    private fun hashAndSize(path: Path, deadline: Instant, isCancelled: () -> Boolean): HashResult {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        return try {
            BufferedInputStream(Files.newInputStream(path)).use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    if (isCancelled()) return HashResult.Cancelled
                    if (!Instant.now().isBefore(deadline)) return HashResult.Expired
                    val read = input.read(buffer)
                    if (read < 0) break
                    size += read
                    digest.update(buffer, 0, read)
                }
            }
            HashResult.Value(digest.digest().hex(), size)
        } catch (_: IOException) {
            HashResult.Failed("Unable to read APK during export.")
        }
    }

    private fun HashResult.Value.matches(input: ValidatedBuildOutput): Boolean =
        sizeBytes == input.archive.archiveSizeBytes && sha256.equals(input.archive.sha256, ignoreCase = true)

    private fun ByteArray.hex(): String = joinToString("") { "%02X".format(it) }

    private sealed interface HashResult {
        data class Value(val sha256: String, val sizeBytes: Long) : HashResult
        data class Failed(val message: String) : HashResult
        data object Expired : HashResult
        data object Cancelled : HashResult
    }

    private companion object {
        const val SIGNED_NAME = "signed.apk"
        const val BUFFER_SIZE = 32 * 1024
    }
}
