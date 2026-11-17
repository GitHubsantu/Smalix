package com.imdevops.smalix.engine.api

import com.imdevops.smalix.core.model.AnalysisRequest
import com.imdevops.smalix.core.model.ApkAnalysis
import com.imdevops.smalix.core.model.DexInfo
import com.imdevops.smalix.core.model.InspectionResult
import com.imdevops.smalix.core.model.ManifestInfo
import com.imdevops.smalix.core.model.ValidatedApk

interface ApkAnalyzer {
    suspend fun analyze(request: AnalysisRequest): InspectionResult<ApkAnalysis>
}

interface ManifestParser {
    suspend fun parse(apk: ValidatedApk): InspectionResult<ManifestInfo>
}

interface DexDetector {
    suspend fun detect(apk: ValidatedApk): InspectionResult<DexInfo>
}
