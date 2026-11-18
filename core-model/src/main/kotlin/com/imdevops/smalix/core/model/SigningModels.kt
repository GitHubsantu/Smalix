package com.imdevops.smalix.core.model

import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/** APK Signature Scheme selections supported by the first isolated signing stage. */
enum class SigningScheme { V1, V2, V3 }

/** A profile refers to user-controlled files; it never contains their bytes or passwords. */
enum class SigningKeyReferenceType { PKCS12, PKCS8_X509 }

data class SigningProfile(
    val id: UUID,
    val displayName: String,
    val keyReferenceType: SigningKeyReferenceType,
    val keyReferencePath: String,
    val certificatePath: String?,
    val alias: String?,
    val certificateSha256: String,
    val selectedSchemes: Set<SigningScheme>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val enabled: Boolean,
) {
    init {
        require(displayName.isNotBlank())
        require(keyReferencePath.isNotBlank())
        require(certificateSha256.matches(Regex("^[0-9A-Fa-f]{64}$")))
        require(selectedSchemes.isNotEmpty())
        when (keyReferenceType) {
            SigningKeyReferenceType.PKCS12 -> {
                require(!alias.isNullOrBlank())
                require(certificatePath == null)
            }
            SigningKeyReferenceType.PKCS8_X509 -> {
                require(!certificatePath.isNullOrBlank())
                require(alias == null)
            }
        }
    }
}

/** Ephemeral passwords for one operation.  Its string representation intentionally contains no secret. */
class SigningCredentials(
    val storePassword: CharArray,
    val keyPassword: CharArray,
) {
    fun clear() {
        storePassword.fill('\u0000')
        keyPassword.fill('\u0000')
    }

    override fun toString(): String = "SigningCredentials(redacted)"
}

data class ApkSigningRequest(
    /** Analyzer and manifest evidence from the immediately preceding aligned-output reinspection. */
    val input: SigningInput,
    val profile: SigningProfile,
    val credentials: SigningCredentials,
    val outputPath: Path,
    val deadline: Instant,
)

data class SigningInput(
    val archive: ValidatedApk,
    val minSdk: Int?,
)

data class SigningCertificateSummary(
    val sha256: String,
    val subject: String,
)

sealed interface ApkSigningResult {
    data class Created(val signedApk: Path, val certificate: SigningCertificateSummary) : ApkSigningResult
    data class Rejected(val message: String) : ApkSigningResult { init { require(message.isNotBlank()) } }
    data class Failed(val message: String) : ApkSigningResult { init { require(message.isNotBlank()) } }
}
