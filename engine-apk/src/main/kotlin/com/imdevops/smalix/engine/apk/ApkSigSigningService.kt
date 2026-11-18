package com.imdevops.smalix.engine.apk

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import com.imdevops.smalix.core.model.ApkSigningRequest
import com.imdevops.smalix.core.model.ApkSigningResult
import com.imdevops.smalix.core.model.SigningCertificateSummary
import com.imdevops.smalix.core.model.SigningKeyReferenceType
import com.imdevops.smalix.core.model.SigningScheme
import com.imdevops.smalix.engine.api.ApkSigningService
import java.io.BufferedInputStream
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.UUID

/** apksig-backed signer for the fixed isolated `aligned.apk` -> `signed.apk` build transition. */
class ApkSigSigningService : ApkSigningService {
    override suspend fun sign(request: ApkSigningRequest): ApkSigningResult = try {
        signSafely(request)
    } finally {
        request.credentials.clear()
    }

    private fun signSafely(request: ApkSigningRequest): ApkSigningResult {
        if (!Instant.now().isBefore(request.deadline)) return ApkSigningResult.Failed("APK signing exceeded its deadline.")
        val input = request.input.archive.path.toAbsolutePath().normalize()
        val output = request.outputPath.toAbsolutePath().normalize()
        if (input.fileName.toString() != ALIGNED_NAME || output.fileName.toString() != SIGNED_NAME || input.parent != output.parent) {
            return ApkSigningResult.Rejected("Signing requires isolated aligned.apk input and signed.apk output in the same build directory.")
        }
        if (!Files.isRegularFile(input)) return ApkSigningResult.Rejected("Validated aligned APK is unavailable for signing.")
        if (Files.exists(output)) return ApkSigningResult.Rejected("Signed APK output already exists.")
        if (!inputMatchesEvidence(input, request)) return ApkSigningResult.Rejected("Aligned APK changed after reinspection.")
        val schemeError = validateSchemes(request)
        if (schemeError != null) return ApkSigningResult.Rejected(schemeError)

        val loaded = loadSigner(request) ?: return ApkSigningResult.Rejected("Signing key, certificate, alias, or password is unavailable.")
        if (!loaded.fingerprint.equals(request.profile.certificateSha256, ignoreCase = true)) {
            return ApkSigningResult.Rejected("Signing certificate does not match the selected profile fingerprint.")
        }
        val temporary = output.parent.resolve(".${SIGNED_NAME}.${UUID.randomUUID()}.tmp")
        return try {
            val signer = ApkSigner.SignerConfig.Builder("smalix", loaded.privateKey, listOf(loaded.certificate)).build()
            ApkSigner.Builder(listOf(signer))
                .setInputApk(input.toFile())
                .setOutputApk(temporary.toFile())
                .setMinSdkVersion(request.input.minSdk ?: 1)
                .setV1SigningEnabled(SigningScheme.V1 in request.profile.selectedSchemes)
                .setV2SigningEnabled(SigningScheme.V2 in request.profile.selectedSchemes)
                .setV3SigningEnabled(SigningScheme.V3 in request.profile.selectedSchemes)
                .build()
                .sign()
            if (!Instant.now().isBefore(request.deadline)) return ApkSigningResult.Failed("APK signing exceeded its deadline.")
            verify(temporary, request, loaded) ?: return ApkSigningResult.Failed("APK signature verification failed.")
            try {
                Files.move(temporary, output)
            } catch (_: FileAlreadyExistsException) {
                return ApkSigningResult.Rejected("Signed APK output already exists.")
            } catch (_: IOException) {
                return ApkSigningResult.Failed("Unable to create signed APK.")
            }
            ApkSigningResult.Created(output, SigningCertificateSummary(loaded.fingerprint, loaded.certificate.subjectX500Principal.name))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            ApkSigningResult.Failed("APK signing was interrupted.")
        } catch (exception: Exception) {
            ApkSigningResult.Failed("APK signing failed (${exception.javaClass.simpleName}).")
        } finally {
            try { Files.deleteIfExists(temporary) } catch (_: IOException) { }
        }
    }

    private fun inputMatchesEvidence(input: Path, request: ApkSigningRequest): Boolean = try {
        Files.size(input) == request.input.archive.archiveSizeBytes &&
            sha256(input).equals(request.input.archive.sha256, ignoreCase = true)
    } catch (_: IOException) { false }

    private fun validateSchemes(request: ApkSigningRequest): String? {
        val schemes = request.profile.selectedSchemes
        if (schemes.isEmpty()) return "At least one APK signature scheme must be selected."
        val minSdk = request.input.minSdk ?: 1
        if (SigningScheme.V2 in schemes && minSdk < 24) return "V2 signing requires manifest minSdk 24 or higher."
        if (SigningScheme.V3 in schemes && minSdk < 28) return "V3 signing requires manifest minSdk 28 or higher."
        return null
    }

    private fun loadSigner(request: ApkSigningRequest): LoadedSigner? = try {
        when (request.profile.keyReferenceType) {
            SigningKeyReferenceType.PKCS12 -> loadPkcs12(request)
            SigningKeyReferenceType.PKCS8_X509 -> loadPkcs8(request)
        }
    } catch (_: Exception) { null }

    private fun loadPkcs12(request: ApkSigningRequest): LoadedSigner? {
        val path = Path.of(request.profile.keyReferencePath)
        if (!Files.isRegularFile(path)) return null
        val alias = request.profile.alias ?: return null
        val store = KeyStore.getInstance("PKCS12")
        Files.newInputStream(path).use { store.load(it, request.credentials.storePassword) }
        if (!store.containsAlias(alias) || !store.isKeyEntry(alias)) return null
        val key = store.getKey(alias, request.credentials.keyPassword) as? PrivateKey ?: return null
        val certificate = store.getCertificate(alias) as? X509Certificate ?: return null
        return LoadedSigner(key, certificate, fingerprint(certificate))
    }

    private fun loadPkcs8(request: ApkSigningRequest): LoadedSigner? {
        val keyPath = Path.of(request.profile.keyReferencePath)
        val certificatePath = request.profile.certificatePath?.let(Path::of) ?: return null
        if (!Files.isRegularFile(keyPath) || !Files.isRegularFile(certificatePath)) return null
        val encoded = Files.readAllBytes(keyPath)
        val certificate = Files.newInputStream(certificatePath).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as? X509Certificate
        } ?: return null
        val privateKey = listOf("RSA", "EC", "DSA").firstNotNullOfOrNull { algorithm ->
            try { KeyFactory.getInstance(algorithm).generatePrivate(PKCS8EncodedKeySpec(encoded)) } catch (_: Exception) { null }
        } ?: return null
        return LoadedSigner(privateKey, certificate, fingerprint(certificate))
    }

    private fun verify(temporary: Path, request: ApkSigningRequest, loaded: LoadedSigner): SigningCertificateSummary? {
        return try {
            val schemes = request.profile.selectedSchemes
            val platformResults = schemes.map { scheme -> scheme to verifyScheme(temporary, scheme) }
            if (platformResults.any { (_, result) -> result == null || !result.isVerified } ||
                (schemes.size == 1 && platformResults.any { (scheme, result) -> !reportsScheme(result!!, scheme) }) ||
                platformResults.any { (_, result) -> result!!.signerCertificates.none { fingerprint(it) == loaded.fingerprint } }
            ) null else SigningCertificateSummary(loaded.fingerprint, loaded.certificate.subjectX500Principal.name)
        } catch (_: Exception) { null }
    }

    /** Verify each selected scheme at an API range where that scheme is authoritative. */
    private fun verifyScheme(temporary: Path, scheme: SigningScheme): ApkVerifier.Result? = try {
        val builder = ApkVerifier.Builder(temporary.toFile())
        when (scheme) {
            SigningScheme.V1 -> builder.setMinCheckedPlatformVersion(24)
            SigningScheme.V2 -> builder.setMinCheckedPlatformVersion(24)
            SigningScheme.V3 -> builder.setMinCheckedPlatformVersion(28)
        }
        val result = builder.build().verify()
        result
    } catch (_: Exception) { null }

    private fun reportsScheme(result: ApkVerifier.Result, scheme: SigningScheme): Boolean = when (scheme) {
        SigningScheme.V1 -> result.isVerifiedUsingV1Scheme || result.v1SchemeSigners.isNotEmpty() || result.v1SchemeIgnoredSigners.isNotEmpty()
        SigningScheme.V2 -> result.isVerifiedUsingV2Scheme || result.v2SchemeSigners.isNotEmpty()
        SigningScheme.V3 -> result.isVerifiedUsingV3Scheme || result.v3SchemeSigners.isNotEmpty()
    }

    private fun fingerprint(certificate: X509Certificate): String = MessageDigest.getInstance("SHA-256")
        .digest(certificate.encoded).joinToString("") { "%02X".format(it) }

    private fun sha256(path: Path): String = BufferedInputStream(Files.newInputStream(path)).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { "%02X".format(it) }
    }

    private data class LoadedSigner(val privateKey: PrivateKey, val certificate: X509Certificate, val fingerprint: String)

    private companion object {
        const val ALIGNED_NAME = "aligned.apk"
        const val SIGNED_NAME = "signed.apk"
    }
}
