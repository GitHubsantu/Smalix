package com.imdevops.smalix

import com.imdevops.smalix.core.model.AnalysisRequest
import com.imdevops.smalix.core.model.BuildWorkspace
import com.imdevops.smalix.core.model.InspectionResult
import com.imdevops.smalix.engine.api.ApkAnalyzer
import com.imdevops.smalix.engine.api.DexDetector
import com.imdevops.smalix.engine.api.ManifestParser
import java.nio.file.Files
import java.time.Instant

sealed interface SignedOutputReinspection {
    data class Ready(val output: ValidatedBuildOutput) : SignedOutputReinspection
    data class Rejected(val message: String) : SignedOutputReinspection
    data class InspectionFailed(val message: String) : SignedOutputReinspection
}

/** Final read-only inspection binding for `signed.apk`; export remains a separate future stage. */
class SignedOutputReinspectionUseCase(
    private val analyzer: ApkAnalyzer,
    private val manifestParser: ManifestParser,
    private val dexDetector: DexDetector,
) {
    suspend fun reinspect(workspace: BuildWorkspace, deadline: Instant): SignedOutputReinspection {
        val root = workspace.root.toAbsolutePath().normalize()
        val output = root.resolve(OUTPUT_NAME).normalize()
        if (output.parent != root || !Files.isRegularFile(output)) return SignedOutputReinspection.Rejected("Signed APK output is unavailable.")
        val archive = when (val result = analyzer.analyze(AnalysisRequest(workspace.projectId, output, deadline))) {
            is InspectionResult.Success -> result.value.archive
            is InspectionResult.Failure -> return SignedOutputReinspection.InspectionFailed(result.error.toString())
        }
        val manifest = when (val result = manifestParser.parse(archive)) {
            is InspectionResult.Success -> result.value
            is InspectionResult.Failure -> return SignedOutputReinspection.InspectionFailed(result.error.toString())
        }
        return when (val result = dexDetector.detect(archive)) {
            is InspectionResult.Success -> SignedOutputReinspection.Ready(ValidatedBuildOutput(archive, manifest, result.value))
            is InspectionResult.Failure -> SignedOutputReinspection.InspectionFailed(result.error.toString())
        }
    }

    private companion object { const val OUTPUT_NAME = "signed.apk" }
}
