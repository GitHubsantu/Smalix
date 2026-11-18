package com.imdevops.smalix

import com.imdevops.smalix.core.model.AnalysisRequest
import com.imdevops.smalix.core.model.BuildWorkspace
import com.imdevops.smalix.core.model.InspectionResult
import com.imdevops.smalix.core.model.ManifestDraft
import com.imdevops.smalix.core.model.ManifestInfo
import com.imdevops.smalix.engine.api.ApkAnalyzer
import com.imdevops.smalix.engine.api.ManifestParser
import com.imdevops.smalix.engine.manifest.BinaryXmlManifestDocumentLoader
import com.imdevops.smalix.engine.manifest.InMemoryManifestDraftApplier
import com.imdevops.smalix.engine.manifest.InMemoryManifestDraftApplication
import com.imdevops.smalix.engine.manifest.IsolatedManifestZipReplacement
import com.imdevops.smalix.engine.manifest.IsolatedManifestZipReplacer
import com.imdevops.smalix.engine.manifest.ManifestDraftApplicationPlan
import com.imdevops.smalix.engine.manifest.ManifestDraftApplicationPlanner
import java.nio.file.Files
import java.time.Instant

sealed interface ManifestBuildApplication {
    data class Ready(val manifest: ManifestInfo) : ManifestBuildApplication
    data class Rejected(val message: String) : ManifestBuildApplication
    data class Failed(val message: String) : ManifestBuildApplication
}

/** Applies the single approved manifest slice only within a BuildWorkspace and requires post-write reinspection. */
class ManifestBuildApplicationUseCase(
    private val analyzer: ApkAnalyzer,
    private val manifestParser: ManifestParser,
    private val documentLoader: BinaryXmlManifestDocumentLoader = BinaryXmlManifestDocumentLoader(),
    private val planner: ManifestDraftApplicationPlanner = ManifestDraftApplicationPlanner(),
    private val applier: InMemoryManifestDraftApplier = InMemoryManifestDraftApplier(),
    private val replacer: IsolatedManifestZipReplacer = IsolatedManifestZipReplacer(),
    private val reinspector: ManifestReplacementReinspectionUseCase = ManifestReplacementReinspectionUseCase(analyzer, manifestParser),
) {
    suspend fun apply(workspace: BuildWorkspace, draft: ManifestDraft, deadline: Instant): ManifestBuildApplication {
        val root = workspace.root.toAbsolutePath().normalize()
        val input = root.resolve(INPUT_NAME).normalize()
        if (workspace.inputApk.toAbsolutePath().normalize() != input || !Files.isRegularFile(input)) {
            return ManifestBuildApplication.Rejected("Build input APK is unavailable.")
        }
        val archive = when (val result = analyzer.analyze(AnalysisRequest(workspace.projectId, input, deadline))) {
            is InspectionResult.Failure -> return ManifestBuildApplication.Failed(result.error.toString())
            is InspectionResult.Success -> result.value.archive
        }
        if (archive.path.toAbsolutePath().normalize() != input) return ManifestBuildApplication.Failed("ZIP analyzer returned an unexpected build input path.")
        val original = when (val result = manifestParser.parse(archive)) {
            is InspectionResult.Failure -> return ManifestBuildApplication.Failed(result.error.toString())
            is InspectionResult.Success -> result.value
        }
        val document = when (val result = documentLoader.load(archive, deadline)) {
            is InspectionResult.Failure -> return ManifestBuildApplication.Failed(result.error.toString())
            is InspectionResult.Success -> result.value
        }
        val plan = when (val result = planner.plan(document, original, draft)) {
            is ManifestDraftApplicationPlan.Rejected -> return ManifestBuildApplication.Rejected(result.message)
            is ManifestDraftApplicationPlan.Ready -> result
        }
        val replacement = when (val result = applier.apply(document, plan)) {
            is InMemoryManifestDraftApplication.Rejected -> return ManifestBuildApplication.Rejected(result.message)
            is InMemoryManifestDraftApplication.Applied -> result
        }
        when (val result = replacer.replace(workspace, replacement.manifestBytes(), deadline)) {
            is IsolatedManifestZipReplacement.Rejected -> return ManifestBuildApplication.Failed(result.message)
            is IsolatedManifestZipReplacement.Created -> Unit
        }
        return when (val result = reinspector.reinspect(workspace, deadline)) {
            is ManifestReplacementReinspection.Ready -> ManifestBuildApplication.Ready(result.manifest)
            is ManifestReplacementReinspection.Rejected -> ManifestBuildApplication.Failed(result.message)
            is ManifestReplacementReinspection.InspectionFailed -> ManifestBuildApplication.Failed(result.message)
        }
    }

    private companion object { const val INPUT_NAME = "input.apk" }
}
