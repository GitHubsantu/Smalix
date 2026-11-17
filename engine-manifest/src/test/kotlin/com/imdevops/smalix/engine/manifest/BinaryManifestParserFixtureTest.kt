package com.imdevops.smalix.engine.manifest

import com.imdevops.smalix.core.model.InspectionResult
import com.imdevops.smalix.core.model.ValidatedApk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class BinaryManifestParserFixtureTest {
    @Test
    fun `parses supplied binary manifest fixture`() = runBlocking {
        val path = System.getenv("SMALIX_MANIFEST_FIXTURE")?.let(Path::of)
        assumeTrue(path != null && Files.isRegularFile(path), "No local manifest fixture supplied.")
        val fixture = requireNotNull(path)

        val result = BinaryManifestParser().parse(ValidatedApk(fixture, "0".repeat(64), Files.size(fixture), emptyList()))
        check(result is InspectionResult.Success) { result.toString() }
        val success = result
        println(success.value)
    }
}
