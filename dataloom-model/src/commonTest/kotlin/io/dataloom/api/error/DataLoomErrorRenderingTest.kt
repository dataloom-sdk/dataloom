package io.dataloom.api.error

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Proves [safeDiagnosticString] never leaks a wrapped [Throwable]'s own
 * `message`/`toString()` — the concrete risk this function exists to close.
 * A naive `data class` `toString()` on a [DataLoomError] implementation
 * would render [DataLoomError.cause] via its own `toString()`, which this
 * codebase does not control and cannot assume is safe (a wrapped HTTP client
 * exception commonly embeds the request URL, which may carry a token in a
 * query parameter).
 */
class DataLoomErrorRenderingTest {

    @Test
    fun `safe diagnostic string never contains the wrapped exception message`() {
        val sensitiveCause = FakeSensitiveException(
            "Failed to connect to https://api.example.com/sync?token=super-secret-abc123",
        )
        val error = TestError(
            code = ErrorCode("TEST_FAILURE"),
            category = ErrorCategory.NETWORK,
            severity = ErrorSeverity.ERROR,
            recoverability = Recoverability.RECOVERABLE,
            message = "Sanitized transport failure summary",
            cause = sensitiveCause,
        )

        val rendered = error.safeDiagnosticString()

        assertFalse(rendered.contains("super-secret-abc123"))
        assertFalse(rendered.contains("token="))
        assertFalse(rendered.contains(sensitiveCause.message ?: "unreachable"))
    }

    @Test
    fun `safe diagnostic string includes the cause type name for diagnosability`() {
        val error = TestError(
            code = ErrorCode("TEST_FAILURE"),
            category = ErrorCategory.NETWORK,
            severity = ErrorSeverity.ERROR,
            recoverability = Recoverability.RECOVERABLE,
            message = "Sanitized transport failure summary",
            cause = FakeSensitiveException("irrelevant"),
        )

        val rendered = error.safeDiagnosticString()

        assertTrue(rendered.contains("FakeSensitiveException"))
    }

    @Test
    fun `safe diagnostic string with no cause renders cause as null`() {
        val error = TestError(
            code = ErrorCode("TEST_FAILURE"),
            category = ErrorCategory.NETWORK,
            severity = ErrorSeverity.ERROR,
            recoverability = Recoverability.RECOVERABLE,
            message = "Sanitized transport failure summary",
            cause = null,
        )

        val rendered = error.safeDiagnosticString()

        assertTrue(rendered.endsWith("cause=null)"))
    }

    @Test
    fun `safe diagnostic string includes the concrete implementation type name`() {
        val error = TestError(
            code = ErrorCode("TEST_FAILURE"),
            category = ErrorCategory.NETWORK,
            severity = ErrorSeverity.ERROR,
            recoverability = Recoverability.RECOVERABLE,
            message = "Sanitized transport failure summary",
            cause = null,
        )

        assertTrue(error.safeDiagnosticString().startsWith("TestError("))
    }

    @Test
    fun `safe diagnostic string includes the sanitized message field verbatim`() {
        val error = TestError(
            code = ErrorCode("TEST_FAILURE"),
            category = ErrorCategory.NETWORK,
            severity = ErrorSeverity.ERROR,
            recoverability = Recoverability.RECOVERABLE,
            message = "Sanitized transport failure summary",
            cause = null,
        )

        assertEquals(
            "TestError(code=TEST_FAILURE, category=NETWORK, severity=ERROR, " +
                "recoverability=RECOVERABLE, message=Sanitized transport failure summary, cause=null)",
            error.safeDiagnosticString(),
        )
    }

    private class FakeSensitiveException(message: String) : Exception(message)

    private data class TestError(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val severity: ErrorSeverity,
        override val recoverability: Recoverability,
        override val message: String,
        override val cause: Throwable? = null,
    ) : DataLoomError {
        override fun toString(): String = safeDiagnosticString()
    }
}
