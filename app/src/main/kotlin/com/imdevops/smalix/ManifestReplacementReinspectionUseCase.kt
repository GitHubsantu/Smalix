package com.imdevops.smalix

import com.imdevops.smalix.core.model.AnalysisRequest
import com.imdevops.smalix.core.model.BuildWorkspace
import com.imdevops.smalix.core.model.InspectionResult
import com.imdevops.smalix.core.model.ManifestInfo
import com.imdevops.smalix.core.model.ValidatedApk
import com.imdevops.smalix.engine.api.ApkAnalyzer
import com.imdevops.smalix.engine.api.ManifestParser
import java.nio.file.Files
import java.time.Instant

sealed interface ManifestReplacementReinspection {
    data class Ready(val archive: ValidatedApk, val manifest: ManifestInfo) : ManifestReplacementReinspection
    data class Rejected(val message: String) : ManifestReplacementReinspection
    data class InspectionFailed(val message: String) : ManifestReplacementReinspection
}

/** Revalidates only the fixed, isolated manifest replacement output before any later build stage. */
class ManifestReplacementReinspectionUseCase(
    private val analyzer: ApkAnalyzer,
    private val manifestParser: ManifestParser,
) {
    suspend fun reinspect(workspace: BuildWorkspace, deadline: Instant): ManifestReplacementReinspection {
        val root = workspace.root.toAbsolutePath().normalize()
        val output = root.resolve(OUTPUT_NAME).normalize()
        if (output.parent != root || !Files.isRegularFile(output)) {
            return ManifestReplacementReinspection.Rejected("Manifest replacement output is unavailable.")
        }
        val archive = when (val result = analyzer.analyze(AnalysisRequest(workspace.projectId, output, deadline))) {
            is InspectionResult.Failure -> return ManifestReplacementReinspection.InspectionFailed(result.error.toString())
            is InspectionResult.Success -> result.value.archive
        }
        return when (val result = manifestParser.parse(archive)) {
            is InspectionResult.Failure -> ManifestReplacementReinspection.InspectionFailed(result.error.toString())
            is InspectionResult.Success -> ManifestReplacementReinspection.Ready(archive, result.value)
        }
    }

    private companion object { const val OUTPUT_NAME = "manifest-applied.apk" }
}
