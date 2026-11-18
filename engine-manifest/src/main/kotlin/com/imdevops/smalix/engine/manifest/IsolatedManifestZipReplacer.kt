package com.imdevops.smalix.engine.manifest

import com.imdevops.smalix.core.model.BuildWorkspace
import com.imdevops.smalix.core.model.InspectionResult
import com.imdevops.smalix.core.model.ZipSafetyLimits
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Replaces only AndroidManifest.xml in a fresh, build-local APK; it never writes an original or export APK. */
class IsolatedManifestZipReplacer(
    private val limits: ZipSafetyLimits = ZipSafetyLimits(),
) {
    fun replace(workspace: BuildWorkspace, manifestBytes: ByteArray, deadline: Instant): IsolatedManifestZipReplacement {
        return try {
            val root = workspace.root.toAbsolutePath().normalize()
            val input = workspace.inputApk.toAbsolutePath().normalize()
            val output = root.resolve(OUTPUT_NAME).normalize()
            require(input == root.resolve(INPUT_NAME) && input.parent == root) { "Build input APK is outside its build workspace." }
            require(output.parent == root && Files.isDirectory(root) && Files.isRegularFile(input)) { "Build workspace is incomplete." }
            require(Files.notExists(output)) { "Build output already exists." }
            require(Files.size(input) <= limits.maxArchiveBytes) { "Build input exceeds the archive size limit." }
            require(!deadline.isBefore(Instant.now())) { "Build deadline has expired." }
            val parsed = BinaryXmlDocumentReader().read(manifestBytes)
            require(parsed is InspectionResult.Success) { "Replacement AndroidManifest.xml is invalid." }

            val temporary = Files.createTempFile(root, ".manifest-", ".apk")
            try {
                rewrite(input, temporary, manifestBytes, deadline)
                try { Files.move(temporary, output, ATOMIC_MOVE) }
                catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, output) }
            } finally {
                Files.deleteIfExists(temporary)
            }
            IsolatedManifestZipReplacement.Created(output)
        } catch (error: IllegalArgumentException) {
            IsolatedManifestZipReplacement.Rejected(error.message ?: "Manifest ZIP replacement was rejected.")
        } catch (error: java.io.IOException) {
            IsolatedManifestZipReplacement.Rejected(error.message ?: "Manifest ZIP replacement failed.")
        }
    }

    private fun rewrite(input: Path, temporary: Path, manifest: ByteArray, deadline: Instant) {
        var entries = 0
        var totalBytes = 0L
        var manifestCount = 0
        val names = mutableSetOf<String>()
        ZipInputStream(Files.newInputStream(input)).use { source ->
            ZipOutputStream(Files.newOutputStream(temporary)).use { destination ->
                while (true) {
                    checkDeadline(deadline)
                    val entry = source.nextEntry ?: break
                    require(++entries <= limits.maxEntries) { "Build input exceeds the entry limit." }
                    validateName(entry.name)
                    require(names.add(entry.name)) { "Build input has duplicate ZIP entry '${entry.name}'." }
                    destination.putNextEntry(ZipEntry(entry.name))
                    val bytes = if (entry.name == MANIFEST) {
                        require(++manifestCount == 1) { "Build input has multiple AndroidManifest.xml entries." }
                        manifest
                    } else {
                        source.readBounded(entry, deadline)
                    }
                    require(bytes.size.toLong() <= limits.maxEntryUncompressedBytes) { "ZIP entry '${entry.name}' exceeds the uncompressed size limit." }
                    totalBytes += bytes.size
                    require(totalBytes <= limits.maxTotalUncompressedBytes) { "Build input exceeds the total uncompressed size limit." }
                    destination.write(bytes)
                    destination.closeEntry()
                    source.closeEntry()
                }
            }
        }
        require(manifestCount == 1) { "Build input is missing AndroidManifest.xml." }
    }

    private fun ZipInputStream.readBounded(entry: ZipEntry, deadline: Instant): ByteArray {
        require(entry.size < 0 || entry.size <= limits.maxEntryUncompressedBytes) { "ZIP entry '${entry.name}' exceeds the uncompressed size limit." }
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            checkDeadline(deadline)
            val read = read(buffer)
            if (read < 0) break
            require(output.size().toLong() + read <= limits.maxEntryUncompressedBytes) { "ZIP entry '${entry.name}' exceeds the uncompressed size limit." }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun validateName(name: String) {
        require(name.isNotBlank() && name.length <= limits.maxEntryNameCharacters && !name.startsWith('/') && '\\' !in name) { "Unsafe ZIP entry path." }
        require(name.split('/').none { it == "." || it == ".." }) { "Unsafe ZIP entry path." }
    }

    private fun checkDeadline(deadline: Instant) {
        require(!Instant.now().isAfter(deadline)) { "Build deadline exceeded while rewriting ZIP." }
    }

    private companion object {
        const val INPUT_NAME = "input.apk"
        const val OUTPUT_NAME = "manifest-applied.apk"
        const val MANIFEST = "AndroidManifest.xml"
        const val BUFFER_SIZE = 16 * 1024
    }
}

sealed interface IsolatedManifestZipReplacement {
    data class Created(val outputApk: Path) : IsolatedManifestZipReplacement
    data class Rejected(val message: String) : IsolatedManifestZipReplacement
}
