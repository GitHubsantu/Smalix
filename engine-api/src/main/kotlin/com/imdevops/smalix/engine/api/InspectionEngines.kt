package com.imdevops.smalix.engine.api

import com.imdevops.smalix.core.model.AnalysisRequest
import com.imdevops.smalix.core.model.ApkAnalysis
import com.imdevops.smalix.core.model.DexInfo
import com.imdevops.smalix.core.model.DexClass
import com.imdevops.smalix.core.model.DexClassQuery
import com.imdevops.smalix.core.model.DexIndex
import com.imdevops.smalix.core.model.HookReference
import com.imdevops.smalix.core.model.HookSignature
import com.imdevops.smalix.core.model.InspectionResult
import com.imdevops.smalix.core.model.ManifestInfo
import com.imdevops.smalix.core.model.ManifestDraft
import com.imdevops.smalix.core.model.ManifestEdit
import com.imdevops.smalix.core.model.ManifestEditResult
import com.imdevops.smalix.core.model.ManifestValidation
import com.imdevops.smalix.core.model.ValidatedApk
import com.imdevops.smalix.core.model.ResourceEntry
import com.imdevops.smalix.core.model.ResourceIndex
import com.imdevops.smalix.core.model.ResourceQuery

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

interface HookReferenceFinder {
    suspend fun find(apk: ValidatedApk, target: HookSignature): InspectionResult<List<HookReference>>
}

/** Reads only bounded resources.arsc metadata; implementations must not extract or modify APK entries. */
interface ResourceReader {
    suspend fun index(apk: ValidatedApk): InspectionResult<ResourceIndex>
}

interface ResourceSearch {
    fun find(index: ResourceIndex, query: ResourceQuery): List<ResourceEntry>
}

interface ManifestDraftEditor {
    fun apply(draft: ManifestDraft, edit: ManifestEdit): ManifestEditResult
}

interface ManifestDraftValidator {
    fun validate(draft: ManifestDraft): ManifestValidation
}
