package com.imdevops.smalix.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.nio.file.Path
import java.util.UUID

class SigningModelsTest {
    @Test fun `credentials redact and clear both password arrays`() {
        val store = "store".toCharArray()
        val key = "key".toCharArray()
        val credentials = SigningCredentials(store, key)

        credentials.clear()

        assertEquals("SigningCredentials(redacted)", credentials.toString())
        assertTrue(store.all { it == '\u0000' })
        assertTrue(key.all { it == '\u0000' })
    }

    @Test fun `profiles require matching metadata for their key type`() {
        assertThrows(IllegalArgumentException::class.java) {
            profile(SigningKeyReferenceType.PKCS12, null, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            profile(SigningKeyReferenceType.PKCS8_X509, "alias", null)
        }
    }

    @Test fun `export evidence requires a SHA 256 digest and nonnegative size`() {
        assertThrows(IllegalArgumentException::class.java) {
            ExportedApk(Path.of("export.apk"), "bad", 1, Instant.EPOCH)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExportedApk(Path.of("export.apk"), "a".repeat(64), -1, Instant.EPOCH)
        }
    }

    private fun profile(type: SigningKeyReferenceType, alias: String?, certificate: String?) = SigningProfile(
        UUID.randomUUID(), "profile", type, "C:/keys/key", certificate, alias, "a".repeat(64),
        setOf(SigningScheme.V2), Instant.EPOCH, Instant.EPOCH, true,
    )
}
