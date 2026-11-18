package com.imdevops.smalix

import com.imdevops.smalix.core.model.BuildWorkspace
import com.imdevops.smalix.core.model.DexInfo
import com.imdevops.smalix.core.model.ManifestInfo
import com.imdevops.smalix.core.model.ProjectId
import com.imdevops.smalix.core.model.ValidatedApk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class PostReinspectionBuildValidationUseCaseTest {
    @TempDir lateinit var temp: Path

    @Test fun `binds only checked fixed output evidence`() {
        val workspace = workspace()
        val output = temp.resolve("manifest-applied.apk")
        Files.write(output, byteArrayOf(1, 2, 3))
        val result = PostReinspectionBuildValidationUseCase().validate(workspace, ready(output, 3))

        val validated = assertInstanceOf(PostReinspectionBuildValidation.Ready::class.java, result)
        assertEquals(output, validated.output.archive.path)
        assertEquals("a".repeat(64), validated.output.archive.sha256)
    }

    @Test fun `rejects reinspection evidence for another file`() {
        val workspace = workspace()
        Files.write(temp.resolve("manifest-applied.apk"), byteArrayOf(1))
        val other = temp.resolve("other.apk")
        Files.write(other, byteArrayOf(1))

        val result = PostReinspectionBuildValidationUseCase().validate(workspace, ready(other, 1))

        assertTrue(result is PostReinspectionBuildValidation.Rejected)
    }

    private fun workspace() = BuildWorkspace(UUID.randomUUID(), ProjectId.random(), temp, temp.resolve("input.apk"))
    private fun ready(path: Path, size: Long) = ManifestReplacementReinspection.Ready(
        ValidatedApk(path, "a".repeat(64), size, emptyList()),
        ManifestInfo("com.example", null, null, null, null, null, emptyList(), emptyList()),
        DexInfo(emptyList()),
    )
}
