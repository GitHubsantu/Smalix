package com.imdevops.smalix

import com.imdevops.smalix.core.model.BuildWorkspace
import com.imdevops.smalix.core.model.DexInfo
import com.imdevops.smalix.core.model.ManifestInfo
import com.imdevops.smalix.core.model.ValidatedApk
import java.io.IOException
import java.nio.file.Files

/** Immutable, in-memory evidence for the build-local APK after ZIP, manifest, and DEX reinspection. */
data class ValidatedBuildOutput(
    val archive: ValidatedApk,
    val manifest: ManifestInfo,
    val dex: DexInfo,
)

sealed interface PostReinspectionBuildValidation {
    data class Ready(val output: ValidatedBuildOutput) : PostReinspectionBuildValidation
    data class Rejected(val message: String) : PostReinspectionBuildValidation
}

/** Binds reinspection evidence to only the fixed build-local output before any later byte-changing stage. */
class PostReinspectionBuildValidationUseCase {
    fun validate(workspace: BuildWorkspace, reinspection: ManifestReplacementReinspection.Ready): PostReinspectionBuildValidation {
        val root = workspace.root.toAbsolutePath().normalize()
        val expected = root.resolve(OUTPUT_NAME).normalize()
        val archive = reinspection.archive
        if (expected.parent != root || archive.path.toAbsolutePath().normalize() != expected || !Files.isRegularFile(expected)) {
            return PostReinspectionBuildValidation.Rejected("Reinspection did not validate the fixed build output.")
        }
        val currentSize = try {
            Files.size(expected)
        } catch (_: IOException) {
            return PostReinspectionBuildValidation.Rejected("Build output is unavailable after reinspection.")
        }
        if (archive.archiveSizeBytes != currentSize) {
            return PostReinspectionBuildValidation.Rejected("Build output changed after reinspection.")
        }
        if (!archive.sha256.matches(SHA_256)) {
            return PostReinspectionBuildValidation.Rejected("Reinspection returned an invalid build output SHA-256.")
        }
        return PostReinspectionBuildValidation.Ready(ValidatedBuildOutput(archive, reinspection.manifest, reinspection.dex))
    }

    private companion object {
        const val OUTPUT_NAME = "manifest-applied.apk"
        val SHA_256 = Regex("^[0-9A-Fa-f]{64}$")
    }
}
