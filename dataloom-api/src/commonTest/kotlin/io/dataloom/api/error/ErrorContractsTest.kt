package io.dataloom.api.error

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ErrorContractsTest {

    @Test
    fun `error category exposes all required values`() {
        assertEquals(
            setOf(
                "NETWORK",
                "STORAGE",
                "AUTHENTICATION",
                "AUTHORIZATION",
                "SERIALIZATION",
                "VALIDATION",
                "CONFIGURATION",
                "QUEUE",
                "SCHEDULER",
                "POLICY",
                "CONFLICT",
                "STATE",
                "PROVIDER",
                "PLUGIN",
                "SECURITY",
                "INTERNAL",
            ),
            ErrorCategory.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun `error severity exposes all required values`() {
        assertEquals(
            setOf("WARNING", "ERROR", "CRITICAL"),
            ErrorSeverity.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun `recoverability exposes all required values`() {
        assertEquals(
            setOf("RECOVERABLE", "NON_RECOVERABLE", "UNKNOWN"),
            Recoverability.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun `error code rejects blank values`() {
        assertFailsWith<IllegalArgumentException> { ErrorCode("") }
        assertFailsWith<IllegalArgumentException> { ErrorCode("  ") }
    }

    @Test
    fun `dataloom error contract exposes every required property`() {
        val rootCause = IllegalStateException("placeholder-cause")
        val error: DataLoomError = TestDataLoomError(
            code = ErrorCode("DL-ERROR-VALIDATION"),
            category = ErrorCategory.VALIDATION,
            severity = ErrorSeverity.ERROR,
            recoverability = Recoverability.RECOVERABLE,
            message = "Placeholder validation failure.",
            cause = rootCause,
        )

        assertEquals("DL-ERROR-VALIDATION", error.code.value)
        assertEquals(ErrorCategory.VALIDATION, error.category)
        assertEquals(ErrorSeverity.ERROR, error.severity)
        assertEquals(Recoverability.RECOVERABLE, error.recoverability)
        assertEquals("Placeholder validation failure.", error.message)
        assertSame(rootCause, error.cause)
    }

    private data class TestDataLoomError(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val severity: ErrorSeverity,
        override val recoverability: Recoverability,
        override val message: String,
        override val cause: Throwable?,
    ) : DataLoomError
}
