package com.imdevops.smalix.engine.apk

import com.imdevops.smalix.core.model.AnalysisFailure
import com.imdevops.smalix.core.model.AnalysisRequest
import com.imdevops.smalix.core.model.AnalysisWarning
import com.imdevops.smalix.core.model.ApkAnalysis
import com.imdevops.smalix.core.model.InputFormat
import com.imdevops.smalix.core.model.InspectionResult
import com.imdevops.smalix.core.model.ValidatedApk
import com.imdevops.smalix.core.model.ZipEntryInfo
import com.imdevops.smalix.engine.api.ApkAnalyzer
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

class ZipApkAnalyzer : ApkAnalyzer {
    override suspend fun analyze(request: AnalysisRequest): InspectionResult<ApkAnalysis> {
        formatFailure(request.apkPath)?.let { return InspectionResult.Failure(it) }
        if (isExpired(request.deadline)) return deadlineFailure()
        if (!Files.isRegularFile(request.apkPath)) {
            return InspectionResult.Failure(AnalysisFailure.IoFailure("APK file does not exist or is not a regular file."))
        }

        return try {
            val archiveSize = Files.size(request.apkPath)
            if (archiveSize > request.limits.maxArchiveBytes) {
                return InspectionResult.Failure(limitFailure("archive size", request.limits.maxArchiveBytes))
            }

            val sha256 = sha256(request.apkPath, request.deadline) ?: return deadlineFailure()
            scanZip(request, archiveSize, sha256)
        } catch (exception: ZipException) {
            InspectionResult.Failure(AnalysisFailure.MalformedApk("Invalid ZIP archive: ${exception.message ?: "unknown ZIP error"}"))
        } catch (exception: IOException) {
            InspectionResult.Failure(AnalysisFailure.IoFailure("Unable to read APK: ${exception.message ?: "I/O error"}"))
        } catch (exception: SecurityException) {
            InspectionResult.Failure(AnalysisFailure.IoFailure("APK access was denied."))
        }
    }

    private fun scanZip(
        request: AnalysisRequest,
        archiveSize: Long,
        sha256: String,
    ): InspectionResult<ApkAnalysis> {
        ZipFile(request.apkPath.toFile()).use { zipFile ->
            val entries = mutableListOf<ZipEntryInfo>()
            val normalizedNames = mutableSetOf<String>()
            val abiNames = mutableSetOf<String>()
            var totalUncompressedBytes = 0L
            var hasManifest = false

            val zipEntries = zipFile.entries()
            var entryCount = 0
            while (zipEntries.hasMoreElements()) {
                val entry = zipEntries.nextElement()
                entryCount += 1
                if (entryCount > request.limits.maxEntries) {
                    return InspectionResult.Failure(limitFailure("entry count", request.limits.maxEntries.toLong()))
                }
                if (isExpired(request.deadline)) return deadlineFailure()
                if (Thread.currentThread().isInterrupted) return InspectionResult.Failure(AnalysisFailure.Cancelled)

                val normalizedName = normalizeEntryName(entry.name)
                    ?: return InspectionResult.Failure(AnalysisFailure.UnsafeArchive("Unsafe ZIP entry path: ${entry.name}"))
                val uniquenessKey = normalizedName.trimEnd('/')
                if (!normalizedNames.add(uniquenessKey)) {
                    return InspectionResult.Failure(AnalysisFailure.UnsafeArchive("Duplicate ZIP entry path: $normalizedName"))
                }
                if (normalizedName.length > request.limits.maxEntryNameCharacters) {
                    return InspectionResult.Failure(limitFailure("entry name length", request.limits.maxEntryNameCharacters.toLong()))
                }

                if (uniquenessKey == MANIFEST_ENTRY) hasManifest = true
                abiName(normalizedName)?.let(abiNames::add)
                val measuredSize = measureEntry(zipFile, entry, request, totalUncompressedBytes)
                    ?: return deadlineFailure()
                val measuredBytes = when (measuredSize) {
                    is EntryFailure -> return InspectionResult.Failure(measuredSize.failure)
                    is EntryMeasurement -> measuredSize.bytes
                }
                totalUncompressedBytes += measuredBytes
                entries += ZipEntryInfo(
                    normalizedPath = normalizedName,
                    compressedSizeBytes = entry.compressedSize,
                    uncompressedSizeBytes = measuredBytes,
                )
            }

            if (!hasManifest) {
                return InspectionResult.Failure(AnalysisFailure.MalformedApk("APK is missing AndroidManifest.xml."))
            }
            return InspectionResult.Success(
                ApkAnalysis(
                    archive = ValidatedApk(request.apkPath, sha256, archiveSize, entries),
                    abiNames = abiNames,
                    warnings = emptyList<AnalysisWarning>(),
                ),
            )
        }
    }

    private fun measureEntry(
        zipFile: ZipFile,
        entry: ZipEntry,
        request: AnalysisRequest,
        totalBeforeEntry: Long,
    ): EntryMeasurementResult? {
        if (entry.isDirectory) return EntryMeasurement(0)
        var entryBytes = 0L
        zipFile.getInputStream(entry).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                if (isExpired(request.deadline)) return null
                if (Thread.currentThread().isInterrupted) return EntryFailure(AnalysisFailure.Cancelled)
                val read = input.read(buffer)
                if (read < 0) break
                entryBytes += read
                if (entryBytes > request.limits.maxEntryUncompressedBytes) {
                    return EntryFailure(limitFailure("entry uncompressed size", request.limits.maxEntryUncompressedBytes))
                }
                if (totalBeforeEntry > request.limits.maxTotalUncompressedBytes - entryBytes) {
                    return EntryFailure(limitFailure("total uncompressed size", request.limits.maxTotalUncompressedBytes))
                }
            }
        }

        if (entryBytes > request.limits.compressionRatioMinimumUncompressedBytes) {
            val compressedBytes = entry.compressedSize
            if (compressedBytes <= 0 || entryBytes > compressedBytes * request.limits.maxCompressionRatio) {
                return EntryFailure(limitFailure("compression ratio", request.limits.maxCompressionRatio.toLong()))
            }
        }
        return EntryMeasurement(entryBytes)
    }

    private fun sha256(path: Path, deadline: Instant): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                if (isExpired(deadline)) return null
                if (Thread.currentThread().isInterrupted) return null
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun formatFailure(path: Path): AnalysisFailure.UnsupportedFormat? = when {
        path.fileName.toString().endsWith(".aab", ignoreCase = true) -> unsupported(InputFormat.ANDROID_APP_BUNDLE)
        path.fileName.toString().endsWith(".xapk", ignoreCase = true) -> unsupported(InputFormat.XAPK)
        path.fileName.toString().startsWith("split_config.", ignoreCase = true) ||
            path.fileName.toString().equals("base.apk", ignoreCase = true) -> unsupported(InputFormat.SPLIT_APK)
        else -> null
    }

    private fun unsupported(format: InputFormat) = AnalysisFailure.UnsupportedFormat(
        format,
        "$format is unsupported; v0.1 accepts one standalone APK only.",
    )

    private fun normalizeEntryName(rawName: String): String? {
        if (rawName.isEmpty() || rawName.startsWith('/') || rawName.startsWith('\\') || DRIVE_PREFIX.containsMatchIn(rawName)) return null
        val normalized = rawName.replace('\\', '/')
        val parts = normalized.split('/')
        val isDirectory = rawName.endsWith('/') || rawName.endsWith('\\')
        val pathParts = if (isDirectory) parts.dropLast(1) else parts
        if (pathParts.isEmpty() || pathParts.any { it.isEmpty() || it == "." || it == ".." }) return null
        return pathParts.joinToString("/") + if (isDirectory) "/" else ""
    }

    private fun abiName(entryName: String): String? {
        val parts = entryName.split('/')
        return parts.getOrNull(0)?.takeIf { it == "lib" }
            ?.let { parts.getOrNull(1) }
            ?.takeIf { parts.size >= 3 && it.isNotBlank() }
    }

    private fun isExpired(deadline: Instant): Boolean = !Instant.now().isBefore(deadline)

    private fun deadlineFailure() = InspectionResult.Failure(
        AnalysisFailure.LimitExceeded("deadline", "APK analysis exceeded its deadline."),
    )

    private fun limitFailure(limit: String, maximum: Long) = AnalysisFailure.LimitExceeded(
        limit,
        "APK exceeds the configured $limit limit of $maximum.",
    )

    private sealed interface EntryMeasurementResult

    private data class EntryMeasurement(val bytes: Long) : EntryMeasurementResult

    private data class EntryFailure(val failure: AnalysisFailure) : EntryMeasurementResult

    private companion object {
        const val BUFFER_SIZE = 8 * 1024
        const val MANIFEST_ENTRY = "AndroidManifest.xml"
        val DRIVE_PREFIX = Regex("^[A-Za-z]:")
    }
}
