package io.dataloom.api.provider

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class ProviderOperationAndInterfaceTest {

    @Test
    fun `operation success preserves value`() {
        val result: ProviderOperationResult<String> = ProviderOperationResult.Success("ok")

        assertEquals("ok", (result as ProviderOperationResult.Success).value)
    }

    @Test
    fun `operation failure preserves dataloom error`() {
        val error: DataLoomError = TestDataLoomError(
            code = ErrorCode("DL-PROVIDER-ERROR"),
            category = ErrorCategory.PROVIDER,
            severity = ErrorSeverity.ERROR,
            recoverability = Recoverability.NON_RECOVERABLE,
            message = "Initialization failed.",
            cause = null,
        )
        val result: ProviderOperationResult<Nothing> = ProviderOperationResult.Failure(error)

        assertEquals(error, (result as ProviderOperationResult.Failure).error)
    }

    @Test
    fun `operation success and failure remain distinct`() {
        val success: ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)
        val failure: ProviderOperationResult<Unit> = ProviderOperationResult.Failure(
            TestDataLoomError(
                code = ErrorCode("DL-PROVIDER-FAIL"),
                category = ErrorCategory.PROVIDER,
                severity = ErrorSeverity.ERROR,
                recoverability = Recoverability.RECOVERABLE,
                message = "Failure.",
                cause = null,
            ),
        )

        assertNotEquals(success, failure)
    }

    @Test
    fun `provider exposes descriptor`() {
        val descriptor: ProviderDescriptor = sampleDescriptor()
        val provider: DataLoomProvider = FakeProvider(
            descriptor = descriptor,
            initializeResult = ProviderOperationResult.Success(Unit),
            healthResult = ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY)),
            closeResult = ProviderOperationResult.Success(Unit),
        )

        assertEquals(descriptor, provider.descriptor)
    }

    @Test
    fun `provider initialize result is returned`() {
        val expected: ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)
        val provider: DataLoomProvider = FakeProvider(
            descriptor = sampleDescriptor(),
            initializeResult = expected,
            healthResult = ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY)),
            closeResult = ProviderOperationResult.Success(Unit),
        )

        val result: ProviderOperationResult<Unit> = runSuspend {
            provider.initialize(ProviderInitializationContext())
        }

        assertEquals(expected, result)
    }

    @Test
    fun `provider health result is returned`() {
        val expected: ProviderOperationResult<ProviderHealth> = ProviderOperationResult.Success(
            ProviderHealth(
                status = ProviderHealthStatus.DEGRADED,
            ),
        )
        val provider: DataLoomProvider = FakeProvider(
            descriptor = sampleDescriptor(),
            initializeResult = ProviderOperationResult.Success(Unit),
            healthResult = expected,
            closeResult = ProviderOperationResult.Success(Unit),
        )

        val result: ProviderOperationResult<ProviderHealth> = runSuspend {
            provider.health()
        }

        assertEquals(expected, result)
    }

    @Test
    fun `provider close result is returned`() {
        val expected: ProviderOperationResult<Unit> = ProviderOperationResult.Failure(
            TestDataLoomError(
                code = ErrorCode("DL-PROVIDER-CLOSE"),
                category = ErrorCategory.PROVIDER,
                severity = ErrorSeverity.WARNING,
                recoverability = Recoverability.RECOVERABLE,
                message = "Close in progress.",
                cause = null,
            ),
        )
        val provider: DataLoomProvider = FakeProvider(
            descriptor = sampleDescriptor(),
            initializeResult = ProviderOperationResult.Success(Unit),
            healthResult = ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY)),
            closeResult = expected,
        )

        val result: ProviderOperationResult<Unit> = runSuspend {
            provider.close()
        }

        assertEquals(expected, result)
    }

    @Test
    fun `provider operations use platform independent result types`() {
        val provider: DataLoomProvider = FakeProvider(
            descriptor = sampleDescriptor(),
            initializeResult = ProviderOperationResult.Success(Unit),
            healthResult = ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.UNKNOWN)),
            closeResult = ProviderOperationResult.Success(Unit),
        )

        val initializeResult: ProviderOperationResult<Unit> = runSuspend {
            provider.initialize(ProviderInitializationContext())
        }
        val healthResult: ProviderOperationResult<ProviderHealth> = runSuspend { provider.health() }
        val closeResult: ProviderOperationResult<Unit> = runSuspend { provider.close() }

        assertIs<ProviderOperationResult.Success<Unit>>(initializeResult)
        assertIs<ProviderOperationResult.Success<ProviderHealth>>(healthResult)
        assertIs<ProviderOperationResult.Success<Unit>>(closeResult)
    }

    private fun sampleDescriptor(): ProviderDescriptor = ProviderDescriptor(
        id = ProviderId("provider.fake"),
        name = ProviderName("Fake Provider"),
        type = ProviderType.STORAGE,
        version = ProviderVersion("1.0.0"),
    )

    private class FakeProvider(
        override val descriptor: ProviderDescriptor,
        private val initializeResult: ProviderOperationResult<Unit>,
        private val healthResult: ProviderOperationResult<ProviderHealth>,
        private val closeResult: ProviderOperationResult<Unit>,
    ) : DataLoomProvider {

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> = initializeResult

        override suspend fun health(): ProviderOperationResult<ProviderHealth> = healthResult

        override suspend fun close(): ProviderOperationResult<Unit> = closeResult
    }

    private data class TestDataLoomError(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val severity: ErrorSeverity,
        override val recoverability: Recoverability,
        override val message: String,
        override val cause: Throwable?,
    ) : DataLoomError

    private fun <T> runSuspend(block: suspend () -> T): T {
        var continuationResult: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context: CoroutineContext = EmptyCoroutineContext

                override fun resumeWith(result: Result<T>) {
                    continuationResult = result
                }
            },
        )
        return continuationResult?.getOrThrow()
            ?: error("Suspend block did not complete synchronously in test.")
    }
}
