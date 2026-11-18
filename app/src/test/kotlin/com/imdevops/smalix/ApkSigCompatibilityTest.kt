package com.imdevops.smalix

import com.android.apksig.ApkVerifier
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import com.android.apksig.ApkSigner

class ApkSigCompatibilityTest {
    @Test fun `verifier reports an unsigned archive without modifying it`() {
        val apk = Files.createTempFile("smalix-apksig-", ".apk")
        try {
            ZipOutputStream(Files.newOutputStream(apk)).use { zip ->
                zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zip.write(byteArrayOf(0x03, 0x00, 0x08, 0x00))
                zip.closeEntry()
            }
            val before = Files.readAllBytes(apk)
            val result = ApkVerifier.Builder(apk.toFile())
                .setMinCheckedPlatformVersion(21)
                .build()
                .verify()

            assertFalse(result.isVerified)
            assertTrue(result.errors.isNotEmpty())
            assertTrue(before.contentEquals(Files.readAllBytes(apk)))
        } finally {
            Files.deleteIfExists(apk)
        }
    }

    @Test fun `owner signed fixture verifies when supplied`() {
        val fixture = System.getenv("SMALIX_SIGNED_APK_FIXTURE")?.let(Path::of)
        assumeTrue(fixture != null && Files.isRegularFile(fixture), "Set SMALIX_SIGNED_APK_FIXTURE to an owned signed APK.")

        val result = ApkVerifier.Builder(requireNotNull(fixture).toFile()).build().verify()

        assertTrue(result.isVerified, "Owner-provided APK did not verify: ${result.errors}")
    }

    @Test fun `signer signs a temporary archive without changing its input`() {
        val directory = Files.createTempDirectory("smalix-apksig-sign-")
        val input = directory.resolve("input.apk")
        val output = directory.resolve("signed.apk")
        val keystore = directory.resolve("test.p12")
        val password = UUID.randomUUID().toString().toCharArray()
        try {
            writeUnsignedApk(input)
            val originalBytes = Files.readAllBytes(input)
            generateTestKey(keystore, password)
            val keys = KeyStore.getInstance("PKCS12").also { store ->
                Files.newInputStream(keystore).use { store.load(it, password) }
            }
            val key = keys.getKey("smalix-test", password) as java.security.PrivateKey
            val certificate = keys.getCertificate("smalix-test") as java.security.cert.X509Certificate
            val signer = ApkSigner.SignerConfig.Builder("smalix-test", key, listOf(certificate)).build()

            ApkSigner.Builder(listOf(signer))
                .setInputApk(input.toFile())
                .setOutputApk(output.toFile())
                .setMinSdkVersion(24)
                .setV1SigningEnabled(false)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(false)
                .build()
                .sign()

            val verification = ApkVerifier.Builder(output.toFile()).setMinCheckedPlatformVersion(24).build().verify()
            assertTrue(verification.isVerified, "Temporary signed APK did not verify: ${verification.errors}")
            assertTrue(originalBytes.contentEquals(Files.readAllBytes(input)))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun writeUnsignedApk(path: Path) {
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(byteArrayOf(0x03, 0x00, 0x08, 0x00))
            zip.closeEntry()
        }
    }

    private fun generateTestKey(keystore: Path, password: CharArray) {
        val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool.exe")
        check(Files.isRegularFile(keytool)) { "JDK keytool is unavailable." }
        val process = ProcessBuilder(
            keytool.toString(), "-genkeypair", "-alias", "smalix-test", "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "1", "-storetype", "PKCS12", "-keystore", keystore.toString(),
            "-storepass", String(password), "-keypass", String(password), "-dname", "CN=Smalix Test", "-noprompt",
        ).redirectErrorStream(true).start()
        process.inputStream.use { it.readBytes() }
        check(process.waitFor() == 0) { "Temporary test key generation failed." }
    }
}
