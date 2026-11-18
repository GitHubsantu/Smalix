package com.imdevops.smalix.engine.apk

import com.android.apksig.ApkVerifier
import com.imdevops.smalix.core.model.ApkSigningRequest
import com.imdevops.smalix.core.model.ApkSigningResult
import com.imdevops.smalix.core.model.SigningCredentials
import com.imdevops.smalix.core.model.SigningInput
import com.imdevops.smalix.core.model.SigningKeyReferenceType
import com.imdevops.smalix.core.model.SigningProfile
import com.imdevops.smalix.core.model.SigningScheme
import com.imdevops.smalix.core.model.ValidatedApk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApkSigSigningServiceTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test fun `signs each supported scheme selection without changing aligned input`() = runBlocking {
        val password = UUID.randomUUID().toString().toCharArray()
        val keystore = temporaryDirectory.resolve("test.p12")
        generateTestKey(keystore, password)
        val fingerprint = certificateFingerprint(keystore, password)
        val combinations = listOf(
            setOf(SigningScheme.V1), setOf(SigningScheme.V2), setOf(SigningScheme.V3),
            setOf(SigningScheme.V1, SigningScheme.V2, SigningScheme.V3),
        )
        combinations.forEachIndexed { index, schemes ->
            val build = Files.createDirectory(temporaryDirectory.resolve("build-$index"))
            val input = build.resolve("aligned.apk")
            writeUnsignedApk(input)
            val originalHash = sha256(input)
            val credentials = SigningCredentials(password.copyOf(), password.copyOf())
            val result = ApkSigSigningService().sign(request(input, build.resolve("signed.apk"), keystore, fingerprint, schemes, credentials, 28))

            val created = assertInstanceOf(ApkSigningResult.Created::class.java, result, "Signing selection $schemes returned $result")
            assertEquals(build.resolve("signed.apk"), created.signedApk)
            assertEquals(originalHash, sha256(input))
            assertTrue(credentials.storePassword.all { it == '\u0000' })
            assertTrue(credentials.keyPassword.all { it == '\u0000' })
            val verified = ApkVerifier.Builder(created.signedApk.toFile()).setMinCheckedPlatformVersion(28).build().verify()
            assertTrue(verified.isVerified, "${schemes}: ${verified.errors}")
        }
        password.fill('\u0000')
    }

    @Test fun `rejects stale input existing output and unsupported minimum sdk`() = runBlocking {
        val password = UUID.randomUUID().toString().toCharArray()
        val keystore = temporaryDirectory.resolve("test.p12")
        generateTestKey(keystore, password)
        val fingerprint = certificateFingerprint(keystore, password)
        val build = Files.createDirectory(temporaryDirectory.resolve("build"))
        val input = build.resolve("aligned.apk")
        writeUnsignedApk(input)
        val baseline = request(input, build.resolve("signed.apk"), keystore, fingerprint, setOf(SigningScheme.V2), SigningCredentials(password.copyOf(), password.copyOf()), 23)
        assertTrue(ApkSigSigningService().sign(baseline) is ApkSigningResult.Rejected)

        val stale = baseline.copy(input = baseline.input.copy(archive = baseline.input.archive.copy(sha256 = "0".repeat(64))), credentials = SigningCredentials(password.copyOf(), password.copyOf()), outputPath = build.resolve("signed.apk"))
        assertTrue(ApkSigSigningService().sign(stale) is ApkSigningResult.Rejected)

        Files.writeString(build.resolve("signed.apk"), "present")
        val existing = baseline.copy(credentials = SigningCredentials(password.copyOf(), password.copyOf()), input = baseline.input.copy(minSdk = 28))
        assertTrue(ApkSigSigningService().sign(existing) is ApkSigningResult.Rejected)
        password.fill('\u0000')
    }

    @Test fun `owner explicit pkcs8 profile signs when supplied through environment`() = runBlocking {
        val key = System.getenv("SMALIX_TEST_KEY_PK8")?.let(Path::of)
        val certificate = System.getenv("SMALIX_TEST_CERT_PEM")?.let(Path::of)
        assumeTrue(key != null && certificate != null && Files.isRegularFile(key) && Files.isRegularFile(certificate),
            "Set SMALIX_TEST_KEY_PK8 and SMALIX_TEST_CERT_PEM to an authorized disposable pair.")
        val build = Files.createDirectory(temporaryDirectory.resolve("pkcs8-build"))
        val input = build.resolve("aligned.apk")
        writeUnsignedApk(input)
        val fingerprint = Files.newInputStream(requireNotNull(certificate)).use {
            MessageDigest.getInstance("SHA-256").digest(CertificateFactory.getInstance("X.509").generateCertificate(it).encoded)
                .joinToString("") { byte -> "%02X".format(byte) }
        }
        val profile = SigningProfile(UUID.randomUUID(), "owner-test-only", SigningKeyReferenceType.PKCS8_X509,
            requireNotNull(key).toString(), certificate.toString(), null, fingerprint, setOf(SigningScheme.V2), Instant.EPOCH, Instant.EPOCH, true)
        val credentials = SigningCredentials(CharArray(0), CharArray(0))

        val result = ApkSigSigningService().sign(ApkSigningRequest(
            SigningInput(ValidatedApk(input, sha256(input), Files.size(input), emptyList()), 24), profile, credentials,
            build.resolve("signed.apk"), Instant.now().plusSeconds(30),
        ))

        assertTrue(result is ApkSigningResult.Created, "Explicit PKCS#8/X.509 pair did not sign: $result")
    }

    private fun request(
        input: Path, output: Path, keystore: Path, fingerprint: String, schemes: Set<SigningScheme>, credentials: SigningCredentials, minSdk: Int,
    ) = ApkSigningRequest(
        SigningInput(ValidatedApk(input, sha256(input), Files.size(input), emptyList()), minSdk),
        SigningProfile(UUID.randomUUID(), "test", SigningKeyReferenceType.PKCS12, keystore.toString(), null, "smalix-test", fingerprint,
            schemes, Instant.EPOCH, Instant.EPOCH, true), credentials, output, Instant.now().plusSeconds(30),
    )

    private fun certificateFingerprint(keystore: Path, password: CharArray): String {
        val store = KeyStore.getInstance("PKCS12")
        Files.newInputStream(keystore).use { store.load(it, password) }
        return MessageDigest.getInstance("SHA-256").digest(store.getCertificate("smalix-test").encoded).joinToString("") { "%02X".format(it) }
    }

    private fun writeUnsignedApk(path: Path) = ZipOutputStream(Files.newOutputStream(path)).use { zip ->
        zip.putNextEntry(ZipEntry("AndroidManifest.xml")); zip.write(binaryManifest()); zip.closeEntry()
    }

    /** Minimal valid binary manifest with uses-sdk minSdk=28, sufficient for apksig verification. */
    private fun binaryManifest(): ByteArray {
        val strings = listOf("manifest", "uses-sdk", "http://schemas.android.com/apk/res/android", "minSdkVersion")
        val poolData = strings.map { value -> byteArrayOf(value.length.toByte(), value.length.toByte()) + value.toByteArray() + byteArrayOf(0) }
        val offsets = poolData.runningFold(0) { offset, bytes -> offset + bytes.size }.dropLast(1)
        val poolSize = 28 + strings.size * 4 + poolData.sumOf { it.size }
        val pool = ByteBuffer.allocate(poolSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(1).putShort(28).putInt(poolSize).putInt(strings.size).putInt(0).putInt(0x100)
            putInt(28 + strings.size * 4).putInt(0); offsets.forEach(::putInt); poolData.forEach(::put)
        }.array()
        val manifest = startTag(0, emptyList())
        val usesSdk = startTag(1, listOf(intAttribute(2, 3, 28)))
        val endUsesSdk = endTag(1)
        val endManifest = endTag(0)
        val size = 8 + pool.size + manifest.size + usesSdk.size + endUsesSdk.size + endManifest.size
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(3).putShort(8).putInt(size); put(pool); put(manifest); put(usesSdk); put(endUsesSdk); put(endManifest)
        }.array()
    }

    private fun startTag(name: Int, attributes: List<ByteArray>) = ByteBuffer.allocate(36 + attributes.sumOf { it.size }).order(ByteOrder.LITTLE_ENDIAN).apply {
        putShort(0x0102).putShort(16).putInt(capacity()).putInt(0).putInt(-1).putInt(-1).putInt(name)
        putShort(20).putShort(20).putShort(attributes.size.toShort()).putShort(0).putShort(0).putShort(0)
        attributes.forEach(::put)
    }.array()

    private fun intAttribute(namespace: Int, name: Int, value: Int) = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).apply {
        putInt(namespace).putInt(name).putInt(-1).putShort(8).put(0).put(0x10).putInt(value)
    }.array()

    private fun endTag(name: Int) = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply {
        putShort(0x0103).putShort(16).putInt(24).putInt(0).putInt(-1).putInt(-1).putInt(name)
    }.array()

    private fun generateTestKey(keystore: Path, password: CharArray) {
        val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool.exe")
        check(Files.isRegularFile(keytool)) { "JDK keytool is unavailable." }
        val process = ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "smalix-test", "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "1", "-storetype", "PKCS12", "-keystore", keystore.toString(), "-storepass", String(password),
            "-keypass", String(password), "-dname", "CN=Smalix Test", "-noprompt").redirectErrorStream(true).start()
        process.inputStream.use { it.readBytes() }; check(process.waitFor() == 0) { "Temporary test key generation failed." }
    }

    private fun sha256(path: Path): String = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02X".format(it) }
}
