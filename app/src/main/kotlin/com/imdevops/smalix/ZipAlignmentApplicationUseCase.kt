package com.imdevops.smalix

import com.imdevops.smalix.core.model.BuildWorkspace
import com.imdevops.smalix.core.model.ZipAlignmentRequest
import com.imdevops.smalix.core.model.ZipAlignmentResult
import com.imdevops.smalix.engine.api.ZipAligner
import java.nio.file.Files
import java.time.Instant

sealed interface ZipAlignmentApplication {
    data class Ready(val output: ValidatedBuildOutput) : ZipAlignmentApplication
    data class Rejected(val message: String) : ZipAlignmentApplication
    data class Failed(val message: String) : ZipAlignmentApplication
}

/** Aligns only a validated replacement APK into `aligned.apk` and requires complete post-alignment reinspection. */
class ZipAlignmentApplicationUseCase(
    private val aligner: ZipAligner,
    private val reinspector: AlignedOutputReinspectionUseCase,
) {
    suspend fun align(workspace: BuildWorkspace, input: ValidatedBuildOutput, deadline: Instant): ZipAlignmentApplication {
        val root = workspace.root.toAbsolutePath().normalize()
        val expectedInput = root.resolve(INPUT_NAME).normalize()
        val output = root.resolve(OUTPUT_NAME).normalize()
        if (expectedInput.parent != root || output.parent != root || input.archive.path.toAbsolutePath().normalize() != expectedInput) {
            return ZipAlignmentApplication.Rejected("ZIP alignment requires the validated manifest replacement output.")
        }
        if (!Files.isRegularFile(expectedInput) || Files.exists(output)) {
            return ZipAlignmentApplication.Rejected("ZIP alignment input is unavailable or aligned output already exists.")
        }
        val result = when (val aligned = aligner.align(ZipAlignmentRequest(input.archive, output, deadline))) {
            is ZipAlignmentResult.Rejected -> return ZipAlignmentApplication.Rejected(aligned.message)
            is ZipAlignmentResult.Failed -> return ZipAlignmentApplication.Failed(aligned.message)
            is ZipAlignmentResult.Created -> aligned
        }
        if (result.alignedApk.toAbsolutePath().normalize() != output) {
            return ZipAlignmentApplication.Failed("ZIP aligner returned an unexpected output path.")
        }
        return when (val reinspection = reinspector.reinspect(workspace, deadline)) {
            is AlignedOutputReinspection.Ready -> ZipAlignmentApplication.Ready(reinspection.output)
            is AlignedOutputReinspection.Rejected -> ZipAlignmentApplication.Failed(reinspection.message)
            is AlignedOutputReinspection.InspectionFailed -> ZipAlignmentApplication.Failed(reinspection.message)
        }
    }

    private companion object {
        const val INPUT_NAME = "manifest-applied.apk"
        const val OUTPUT_NAME = "aligned.apk"
    }
}
