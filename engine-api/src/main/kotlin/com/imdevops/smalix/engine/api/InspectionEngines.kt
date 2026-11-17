package com.imdevops.smalix.engine.api

import com.imdevops.smalix.core.model.AnalysisRequest
import com.imdevops.smalix.core.model.ApkAnalysis
import com.imdevops.smalix.core.model.DexInfo
import com.imdevops.smalix.core.model.DexClass
import com.imdevops.smalix.core.model.DexClassQuery
import com.imdevops.smalix.core.model.DexIndex
import com.imdevops.smalix.core.model.InspectionResult
import com.imdevops.smalix.core.model.ManifestInfo
import com.imdevops.smalix.core.model.ManifestDraft
import com.imdevops.smalix.core.model.ManifestEdit
import com.imdevops.smalix.core.model.ManifestEditResult
import com.imdevops.smalix.core.model.ManifestValidation
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

interface DexReader {
    suspend fun index(apk: ValidatedApk): InspectionResult<DexIndex>
}

interface DexSearch {
    fun find(index: DexIndex, query: DexClassQuery): List<DexClass>
}

interface ManifestDraftEditor {
    fun apply(draft: ManifestDraft, edit: ManifestEdit): ManifestEditResult
}

interface ManifestDraftValidator {
    fun validate(draft: ManifestDraft): ManifestValidation
}
