package com.imdevops.smalix

import com.imdevops.smalix.core.model.AnalysisRequest
import com.imdevops.smalix.core.model.BuildWorkspace
import com.imdevops.smalix.core.model.InspectionResult
import com.imdevops.smalix.engine.api.ApkAnalyzer
import com.imdevops.smalix.engine.api.DexDetector
import com.imdevops.smalix.engine.api.ManifestParser
import java.nio.file.Files
import java.time.Instant

sealed interface AlignedOutputReinspection {
    data class Ready(val output: ValidatedBuildOutput) : AlignedOutputReinspection
    data class Rejected(val message: String) : AlignedOutputReinspection
    data class InspectionFailed(val message: String) : AlignedOutputReinspection
}

/** Revalidates only the fixed, isolated aligned APK before any signing stage. */
class AlignedOutputReinspectionUseCase(
    private val analyzer: ApkAnalyzer,
    private val manifestParser: ManifestParser,
    private val dexDetector: DexDetector,
) {
    suspend fun reinspect(workspace: BuildWorkspace, deadline: Instant): AlignedOutputReinspection {
        val root = workspace.root.toAbsolutePath().normalize()
        val output = root.resolve(OUTPUT_NAME).normalize()
        if (output.parent != root || !Files.isRegularFile(output)) {
            return AlignedOutputReinspection.Rejected("Aligned APK output is unavailable.")
        }
        val archive = when (val result = analyzer.analyze(AnalysisRequest(workspace.projectId, output, deadline))) {
            is InspectionResult.Failure -> return AlignedOutputReinspection.InspectionFailed(result.error.toString())
            is InspectionResult.Success -> result.value.archive
        }
        val manifest = when (val result = manifestParser.parse(archive)) {
            is InspectionResult.Failure -> return AlignedOutputReinspection.InspectionFailed(result.error.toString())
            is InspectionResult.Success -> result.value
        }
        return when (val result = dexDetector.detect(archive)) {
            is InspectionResult.Failure -> AlignedOutputReinspection.InspectionFailed(result.error.toString())
            is InspectionResult.Success -> AlignedOutputReinspection.Ready(ValidatedBuildOutput(archive, manifest, result.value))
        }
    }

    private companion object { const val OUTPUT_NAME = "aligned.apk" }
}
