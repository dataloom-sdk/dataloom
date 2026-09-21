package io.dataloom.api.lifecycle

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.provider.ProviderType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class AppLifecycleContractsTest {

    @Test
    fun `app lifecycle state exposes exactly the three coarse states`() {
        assertEquals(
            setOf("FOREGROUND", "BACKGROUND", "TERMINATING_SOON"),
            AppLifecycleState.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun `capabilities use stable non-blank labels`() {
        assertEquals("state-stream", AppLifecycleCapabilities.STATE_STREAM.value)
        assertEquals("terminating-soon", AppLifecycleCapabilities.TERMINATING_SOON.value)
    }

    @Test
    fun `provider type for lifecycle providers is APP_LIFECYCLE`() {
        assertEquals("APP_LIFECYCLE", ProviderType.APP_LIFECYCLE.name)
    }

    @Test
    fun `observation exception carries the canonical error and its message`() {
        val error: DataLoomError = TestError()

        val exception = AppLifecycleObservationException(error)

        assertSame(error, exception.error)
        assertEquals("lifecycle source unavailable", exception.message)
    }

    private class TestError : DataLoomError {
        override val code: ErrorCode = ErrorCode("LIFECYCLE_TEST")
        override val category: ErrorCategory = ErrorCategory.PROVIDER
        override val severity: ErrorSeverity = ErrorSeverity.ERROR
        override val recoverability: Recoverability = Recoverability.RECOVERABLE
        override val message: String = "lifecycle source unavailable"
        override val cause: Throwable? = null
    }
}
