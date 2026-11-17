package com.imdevops.smalix

import com.imdevops.smalix.core.model.*
import com.imdevops.smalix.engine.api.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant

class InspectionUseCaseTest {
    @Test fun `stops at analyzer failure`() = runBlocking {
        var parserCalled = false
        val failure = AnalysisFailure.MalformedApk("bad zip")
        val result = InspectionUseCase(
            object : ApkAnalyzer { override suspend fun analyze(request: AnalysisRequest) = InspectionResult.Failure(failure) },
            object : ManifestParser { override suspend fun parse(apk: ValidatedApk): InspectionResult<ManifestInfo> { parserCalled = true; error("must not run") } },
            object : DexDetector { override suspend fun detect(apk: ValidatedApk): InspectionResult<DexInfo> = error("must not run") },
        ).inspect(request())
        assertEquals(InspectionResult.Failure(failure), result); assertEquals(false, parserCalled)
    }
    private fun request() = AnalysisRequest(ProjectId.random(), Path.of("sample.apk"), Instant.now().plusSeconds(1))
}
