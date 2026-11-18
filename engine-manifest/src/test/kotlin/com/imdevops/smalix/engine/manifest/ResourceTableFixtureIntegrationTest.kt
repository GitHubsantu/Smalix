package com.imdevops.smalix.engine.manifest

import com.imdevops.smalix.core.model.AnalysisRequest
import com.imdevops.smalix.core.model.InspectionResult
import com.imdevops.smalix.core.model.ProjectId
import com.imdevops.smalix.engine.apk.ZipApkAnalyzer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant

class ResourceTableFixtureIntegrationTest {
    @Test fun `indexes default literal resources from owned fixture`() = runBlocking {
        val path = Path.of(System.getenv("SMALIX_RESOURCE_FIXTURE") ?: "E:/project/simbridge-android/dist/SimBridge-1.1.3-universal.apk")
        assumeTrue(java.nio.file.Files.isRegularFile(path), "Set SMALIX_RESOURCE_FIXTURE to an owned APK.")
        val archive = (ZipApkAnalyzer().analyze(AnalysisRequest(ProjectId.random(), path, Instant.now().plusSeconds(120))) as InspectionResult.Success).value.archive
        val result = BinaryResourceTableReader().index(archive)
        assertTrue(result is InspectionResult.Success, "Resource reader failed: $result")
    }
}
