package com.imdevops.smalix

import com.imdevops.smalix.core.model.AnalysisRequest
import com.imdevops.smalix.core.model.InspectionResult
import com.imdevops.smalix.core.model.InspectionSummary
import com.imdevops.smalix.engine.api.ApkAnalyzer
import com.imdevops.smalix.engine.api.DexDetector
import com.imdevops.smalix.engine.api.ManifestParser

class InspectionUseCase(
    private val apkAnalyzer: ApkAnalyzer,
    private val manifestParser: ManifestParser,
    private val dexDetector: DexDetector,
) {
    suspend fun inspect(request: AnalysisRequest): InspectionResult<InspectionSummary> {
        val apk = when (val result = apkAnalyzer.analyze(request)) {
            is InspectionResult.Failure -> return result
            is InspectionResult.Success -> result.value
        }
        val manifest = when (val result = manifestParser.parse(apk.archive)) {
            is InspectionResult.Failure -> return result
            is InspectionResult.Success -> result.value
        }
        val dex = when (val result = dexDetector.detect(apk.archive)) {
            is InspectionResult.Failure -> return result
            is InspectionResult.Success -> result.value
        }
        return InspectionResult.Success(InspectionSummary(apk, manifest, dex))
    }
}
