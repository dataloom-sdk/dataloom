package io.dataloom.api.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageContentRedactionTest {

    @Test
    fun bearerTokenIsMasked() {
        val redactor = PatternBasedMessageContentRedactor()
        val secret = "abc123.def456-ghi789"
        val input = "Request failed: Authorization header was Bearer $secret"

        val result = redactor.redact(input)

        assertFalse(result.contains(secret))
        assertTrue(result.contains("[REDACTED]"))
        assertTrue(result.startsWith("Request failed: Authorization header was"))
    }

    @Test
    fun jwtShapedTokenIsMasked() {
        val redactor = PatternBasedMessageContentRedactor()
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
        val input = "Token validation failed for $jwt during handshake"

        val result = redactor.redact(input)

        assertFalse(result.contains(jwt))
        assertTrue(result.contains("[REDACTED]"))
    }

    @Test
    fun awsAccessKeyIsMasked() {
        val redactor = PatternBasedMessageContentRedactor()
        val key = "AKIAIOSFODNN7EXAMPLE"
        val input = "Signature mismatch for access key $key"

        val result = redactor.redact(input)

        assertFalse(result.contains(key))
        assertTrue(result.contains("[REDACTED]"))
    }

    @Test
    fun sensitiveQueryParameterValueIsMaskedButKeyNameIsPreserved() {
        val redactor = PatternBasedMessageContentRedactor()
        val secret = "sk_live_topSecretValue123"
        val input = "GET https://api.example.test/data?token=$secret&page=2 failed with 401"

        val result = redactor.redact(input)

        assertFalse(result.contains(secret))
        assertTrue(result.contains("token=[REDACTED]"))
        assertTrue(result.contains("page=2"), "Unrelated query parameters must survive unchanged")
    }

    @Test
    fun basicAuthUrlCredentialsAreMasked() {
        val redactor = PatternBasedMessageContentRedactor()
        val input = "Connection refused: https://svc-user:hunter2@internal.example.test/health"

        val result = redactor.redact(input)

        assertFalse(result.contains("svc-user"))
        assertFalse(result.contains("hunter2"))
        assertTrue(result.contains("https://[REDACTED]@internal.example.test/health"))
    }

    @Test
    fun emailAddressIsMasked() {
        val redactor = PatternBasedMessageContentRedactor()
        val email = "person@example.test"
        val input = "Notification could not be delivered to $email"

        val result = redactor.redact(input)

        assertFalse(result.contains(email))
        assertTrue(result.contains("[REDACTED]"))
    }

    @Test
    fun ordinaryDiagnosticTextIsUnchanged() {
        val redactor = PatternBasedMessageContentRedactor()
        val input = "Connection timed out after 30000ms while calling endpoint /api/v1/sync"

        assertEquals(input, redactor.redact(input))
    }

    @Test
    fun emptyInputIsUnchanged() {
        val redactor = PatternBasedMessageContentRedactor()

        assertEquals("", redactor.redact(""))
    }

    @Test
    fun oversizedInputIsBoundedBeforeScanning() {
        val redactor = PatternBasedMessageContentRedactor()
        val huge = "x".repeat(20_000)

        val result = redactor.redact(huge)

        assertTrue(result.length <= 8_192)
    }

    @Test
    fun customMaskIsHonored() {
        val redactor = PatternBasedMessageContentRedactor(mask = "***")
        val email = "person@example.test"

        val result = redactor.redact("Contact $email for support")

        assertFalse(result.contains(email))
        assertTrue(result.contains("***"))
        assertFalse(result.contains("[REDACTED]"))
    }

    @Test
    fun multipleDistinctSecretsInOneMessageAreAllMasked() {
        val redactor = PatternBasedMessageContentRedactor()
        val token = "AKIAIOSFODNN7EXAMPLE"
        val email = "person@example.test"
        val input = "AWS key $token reported by $email"

        val result = redactor.redact(input)

        assertFalse(result.contains(token))
        assertFalse(result.contains(email))
        assertEquals(2, result.split("[REDACTED]").size - 1)
    }

    @Test
    fun blankMaskIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            PatternBasedMessageContentRedactor(mask = "   ")
        }
    }

    @Test
    fun overlongMaskIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            PatternBasedMessageContentRedactor(mask = "x".repeat(65))
        }
    }
}
