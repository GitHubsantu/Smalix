package com.imdevops.smalix

import com.android.apksig.ApkVerifier
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
}
