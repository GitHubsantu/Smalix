package com.imdevops.smalix

import com.imdevops.smalix.core.model.ApkSigningRequest
import com.imdevops.smalix.core.model.ApkSigningResult
import com.imdevops.smalix.core.model.BuildWorkspace
import com.imdevops.smalix.core.model.SigningCredentials
import com.imdevops.smalix.core.model.SigningInput
import com.imdevops.smalix.core.model.SigningProfile
import com.imdevops.smalix.engine.api.ApkSigningService
import java.nio.file.Files
import java.time.Instant

sealed interface ApkSigningApplication {
    data class Ready(val output: ValidatedBuildOutput) : ApkSigningApplication
    data class Rejected(val message: String) : ApkSigningApplication
    data class Failed(val message: String) : ApkSigningApplication
}

/** Signs only the fully reinspected aligned output, then requires complete signed-output reinspection. */
class ApkSigningApplicationUseCase(
    private val signer: ApkSigningService,
    private val reinspector: SignedOutputReinspectionUseCase,
) {
    suspend fun sign(
        workspace: BuildWorkspace,
        input: ValidatedBuildOutput,
        profile: SigningProfile,
        credentials: SigningCredentials,
        deadline: Instant,
    ): ApkSigningApplication {
        return try {
        val root = workspace.root.toAbsolutePath().normalize()
        val aligned = root.resolve(ALIGNED_NAME).normalize()
        val signed = root.resolve(SIGNED_NAME).normalize()
        if (aligned.parent != root || signed.parent != root || input.archive.path.toAbsolutePath().normalize() != aligned) {
            return ApkSigningApplication.Rejected("Signing requires the validated aligned APK output.")
        }
        if (!Files.isRegularFile(aligned) || Files.exists(signed)) {
            return ApkSigningApplication.Rejected("Aligned APK is unavailable or signed output already exists.")
        }
        val result = when (val signing = signer.sign(ApkSigningRequest(
            SigningInput(input.archive, input.manifest.minSdk), profile, credentials, signed, deadline,
        ))) {
            is ApkSigningResult.Rejected -> return ApkSigningApplication.Rejected(signing.message)
            is ApkSigningResult.Failed -> return ApkSigningApplication.Failed(signing.message)
            is ApkSigningResult.Created -> signing
        }
        if (result.signedApk.toAbsolutePath().normalize() != signed) {
            return ApkSigningApplication.Failed("Signer returned an unexpected output path.")
        }
        when (val reinspection = reinspector.reinspect(workspace, deadline)) {
            is SignedOutputReinspection.Ready -> ApkSigningApplication.Ready(reinspection.output)
            is SignedOutputReinspection.Rejected -> ApkSigningApplication.Failed(reinspection.message)
            is SignedOutputReinspection.InspectionFailed -> ApkSigningApplication.Failed(reinspection.message)
        }
        } finally {
            credentials.clear()
        }
    }

    private companion object {
        const val ALIGNED_NAME = "aligned.apk"
        const val SIGNED_NAME = "signed.apk"
    }
}
