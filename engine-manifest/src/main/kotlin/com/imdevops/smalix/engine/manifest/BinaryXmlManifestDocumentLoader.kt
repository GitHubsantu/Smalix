package com.imdevops.smalix.engine.manifest

import com.imdevops.smalix.core.model.AnalysisFailure
import com.imdevops.smalix.core.model.InspectionResult
import com.imdevops.smalix.core.model.ValidatedApk
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.ZipFile

/** Reads a bounded binary AndroidManifest.xml only from a previously validated APK archive. */
class BinaryXmlManifestDocumentLoader(private val maxBytes: Int = BinaryXmlDocumentReader.DEFAULT_MAX_BYTES) {
    init { require(maxBytes > 0) }

    fun load(apk: ValidatedApk, deadline: Instant): InspectionResult<BinaryXmlDocument> = try {
        ZipFile(apk.path.toFile()).use { zip ->
            val entry = zip.getEntry(MANIFEST) ?: return failure("APK is missing AndroidManifest.xml.")
            require(entry.size < 0 || entry.size <= maxBytes) { "AndroidManifest.xml exceeds $maxBytes bytes." }
            val output = ByteArrayOutputStream()
            zip.getInputStream(entry).use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    require(!Instant.now().isAfter(deadline)) { "Manifest read deadline exceeded." }
                    val read = input.read(buffer)
                    if (read < 0) break
                    require(output.size() + read <= maxBytes) { "AndroidManifest.xml exceeds $maxBytes bytes." }
                    output.write(buffer, 0, read)
                }
            }
            BinaryXmlDocumentReader(maxBytes).read(output.toByteArray())
        }
    } catch (error: IllegalArgumentException) {
        failure(error.message ?: "Manifest document could not be read.")
    } catch (error: java.io.IOException) {
        InspectionResult.Failure(AnalysisFailure.IoFailure(error.message ?: "Manifest document could not be read."))
    }

    private fun failure(message: String): InspectionResult<Nothing> = when {
        message.contains("deadline") -> InspectionResult.Failure(AnalysisFailure.LimitExceeded("manifest deadline", message))
        message.contains("exceeds") -> InspectionResult.Failure(AnalysisFailure.LimitExceeded("manifest bytes", message))
        else -> InspectionResult.Failure(AnalysisFailure.MalformedApk(message))
    }

    private companion object {
        const val MANIFEST = "AndroidManifest.xml"
        const val BUFFER_SIZE = 16 * 1024
    }
}
