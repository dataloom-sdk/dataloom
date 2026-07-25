package io.dataloom.runtime.connectivity

import io.dataloom.api.connectivity.ConnectivityCheckRequest
import io.dataloom.api.connectivity.ConnectivityProvider
import io.dataloom.api.connectivity.ConnectivityRequirement
import io.dataloom.api.connectivity.ConnectivitySnapshot
import io.dataloom.api.connectivity.ConnectivityStatus
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.DataLoomProvider
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Deterministic common tests for [SynchronizationConnectivityPreflight].
 *
 * All fakes are stateless or deterministically stateful. No real network, real
 * database, filesystem, Thread.sleep, arbitrary delay, Android API, JVM-only
 * API, reflection, ServiceLoader, system clock, random IDs, production
 * credentials, or personal data is used.
 *
 * Suspend functions are exercised using [kotlin.coroutines.startCoroutine]
 * primitives from the Kotlin standard library.
 */
class SynchronizationConnectivityPreflightTest {

    // =========================================================================
    // runSuspend helper
    // =========================================================================

    private object Pending

    private fun <T> runSuspend(block: suspend () -> T): T {
        var rawResult: Any? = Pending
        var thrown: Throwable? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context: CoroutineContext = EmptyCoroutineContext
                override fun resumeWith(result: Result<T>) {
                    if (result.isSuccess) {
                        rawResult = result.getOrNull()
                    } else {
                        thrown = result.exceptionOrNull()
                    }
                }
            },
        )
        thrown?.let { throw it }
        check(rawResult !== Pending) { "Suspend block did not complete synchronously in test." }
        @Suppress("UNCHECKED_CAST")
        return rawResult as T
    }

    // =========================================================================
    // Fake implementations
    // =========================================================================

    private data class TestError(
        override val code: ErrorCode = ErrorCode("DL-TEST"),
        override val category: ErrorCategory = ErrorCategory.PROVIDER,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE,
        override val message: String = "test connectivity error",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private class RecordingConnectivityProvider(
        private val result: ProviderOperationResult<ConnectivitySnapshot>,
    ) : ConnectivityProvider {
        var callCount: Int = 0
        var lastRequest: ConnectivityCheckRequest? = null

        override val descriptor = ProviderDescriptor(
            id = ProviderId("connectivity-test"),
            name = ProviderName("Test Connectivity"),
            type = ProviderType.CONNECTIVITY,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun currentConnectivity(
            request: ConnectivityCheckRequest,
        ): ProviderOperationResult<ConnectivitySnapshot> {
            callCount++
            lastRequest = request
            return result
        }
    }

    private class ThrowingConnectivityProvider(
        private val exception: Throwable,
    ) : ConnectivityProvider {
        override val descriptor = ProviderDescriptor(
            id = ProviderId("throwing-connectivity"),
            name = ProviderName("Throwing Connectivity"),
            type = ProviderType.CONNECTIVITY,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun currentConnectivity(
            request: ConnectivityCheckRequest,
        ): ProviderOperationResult<ConnectivitySnapshot> {
            throw exception
        }
    }

    // =========================================================================
    // Sample request factory
    // =========================================================================

    private fun sampleRequest(executionId: String = "exec-001"): SynchronizationRequest =
        SynchronizationRequest(
            workflowId = WorkflowId("workflow-001"),
            sessionId = SynchronizationSessionId("session-001"),
            direction = SynchronizationDirection.PUSH,
            mode = SynchronizationMode.DELTA,
            context = ExecutionContext(
                executionId = ExecutionId(executionId),
                correlationId = CorrelationId("corr-001"),
            ),
        )

    // =========================================================================
    // NotRequired (NONE requirement)
    // =========================================================================

    @Test
    fun `NONE requirement returns NotRequired`() {
        val preflight = SynchronizationConnectivityPreflight()
        val result = runSuspend {
            preflight.evaluate(
                requirement = ConnectivityRequirement.NONE,
                provider = null,
                request = sampleRequest(),
            )
        }
        assertIs<ConnectivityPreflightResult.NotRequired>(result)
    }

    @Test
    fun `NONE requirement does not invoke provider`() {
        val provider = RecordingConnectivityProvider(
            result = ProviderOperationResult.Success(
                ConnectivitySnapshot(ConnectivityStatus.AVAILABLE, isMetered = false),
            ),
        )
        val preflight = SynchronizationConnectivityPreflight()
        runSuspend {
            preflight.evaluate(
                requirement = ConnectivityRequirement.NONE,
                provider = provider,
                request = sampleRequest(),
            )
        }
        assertEquals(0, provider.callCount)
    }

    @Test
    fun `NONE requirement with null provider returns NotRequired`() {
        val preflight = SynchronizationConnectivityPreflight()
        val result = runSuspend {
            preflight.evaluate(
                requirement = ConnectivityRequirement.NONE,
                provider = null,
                request = sampleRequest(),
            )
        }
        assertIs<ConnectivityPreflightResult.NotRequired>(result)
    }

    // =========================================================================
    // ProviderNotConfigured
    // =========================================================================

    @Test
    fun `AVAILABLE requirement with null provider returns ProviderNotConfigured`() {
        val preflight = SynchronizationConnectivityPreflight()
        val result = runSuspend {
            preflight.evaluate(
                requirement = ConnectivityRequirement.AVAILABLE,
                provider = null,
                request = sampleRequest(),
            )
        }
        assertIs<ConnectivityPreflightResult.ProviderNotConfigured>(result)
    }

    @Test
    fun `UNMETERED requirement with null provider returns ProviderNotConfigured`() {
        val preflight = SynchronizationConnectivityPreflight()
        val result = runSuspend {
            preflight.evaluate(
                requirement = ConnectivityRequirement.UNMETERED,
                provider = null,
                request = sampleRequest(),
            )
        }
        assertIs<ConnectivityPreflightResult.ProviderNotConfigured>(result)
    }

    // =========================================================================
    // Satisfied connectivity
    // =========================================================================

    @Test
    fun `AVAILABLE requirement satisfied by AVAILABLE status`() {
        val snapshot = ConnectivitySnapshot(ConnectivityStatus.AVAILABLE, isMetered = null)
        val provider = RecordingConnectivityProvider(
            result = ProviderOperationResult.Success(snapshot),
        )
        val preflight = SynchronizationConnectivityPreflight()
        val result = runSuspend {
            preflight.evaluate(
                requirement = ConnectivityRequirement.AVAILABLE,
                provider = provider,
                request = sampleRequest(),
            )
        }
        assertIs<ConnectivityPreflightResult.Satisfied>(result)
        assertEquals(snapshot, result.snapshot)
    }

    @Test
    fun `UNMETERED requirement satisfied by AVAILABLE unmetered status`() {
        val snapshot = ConnectivitySnapshot(ConnectivityStatus.AVAILABLE, isMetered = false)
        val provider = RecordingConnectivityProvider(
            result = ProviderOperationResult.Success(snapshot),
        )
        val preflight = SynchronizationConnectivityPreflight()
        val result = runSuspend {
            preflight.evaluate(
                requirement = ConnectivityRequirement.UNMETERED,
                provider = provider,
                request = sampleRequest(),
            )
        }
        assertIs<ConnectivityPreflightResult.Satisfied>(result)
    }

    @Test
    fun `provider is invoked exactly once when connectivity is required`() {
        val provider = RecordingConnectivityProvider(
            result = ProviderOperationResult.Success(
                ConnectivitySnapshot(ConnectivityStatus.AVAILABLE, isMetered = false),
            ),
        )
        val preflight = SynchronizationConnectivityPreflight()
        runSuspend {
            preflight.evaluate(
                requirement = ConnectivityRequirement.AVAILABLE,
                provider = provider,
                request = sampleRequest(),
            )
        }
        assertEquals(1, provider.callCount)
    }

    @Test
    fun `exact ConnectivityCheckRequest is supplied to provider`() {
        val request = sampleRequest("exec-xyz")
        val provider = RecordingConnectivityProvider(
            result = ProviderOperationResult.Success(
                ConnectivitySnapshot(ConnectivityStatus.AVAILABLE, isMetered = null),
            ),
        )
        val preflight = SynchronizationConnectivityPreflight()
        runSuspend {
            preflight.evaluate(
                requirement = ConnectivityRequirement.AVAILABLE,
                provider = provider,
                request = request,
            )
        }
        assertNotNull(provider.lastRequest)
        assertEquals(request.context, provider.lastRequest!!.context)
    }

    // =========================================================================
    // RequirementNotMet
    // =========================================================================

    @Test
    fun `AVAILABLE requirement not met by UNAVAILABLE status`() {
        val provider = RecordingConnectivityProvider(
            result = ProviderOperationResult.Success(
                ConnectivitySnapshot(ConnectivityStatus.UNAVAILABLE, isMetered = null),
            ),
        )
        val preflight = SynchronizationConnectivityPreflight()
        val result = runSuspend {
            preflight.evaluate(
                requirement = ConnectivityRequirement.AVAILABLE,
                provider = provider,
                request = sampleRequest(),
            )
        }
        assertIs<ConnectivityPreflightResult.RequirementNotMet>(result)
        assertEquals(ConnectivityRequirement.AVAILABLE, result.requirement)
        assertEquals(ConnectivityStatus.UNAVAILABLE, result.status)
    }

    @Test
    fun `AVAILABLE requirement not met by UNKNOWN status`() {
        val provider = RecordingConnectivityProvider(
            result = ProviderOperationResult.Success(
                ConnectivitySnapshot(ConnectivityStatus.UNKNOWN, isMetered = null),
            ),
        )
        val preflight = SynchronizationConnectivityPreflight()
        val result = runSuspend {
            preflight.evaluate(
                requirement = ConnectivityRequirement.AVAILABLE,
                provider = provider,
                request = sampleRequest(),
            )
        }
        assertIs<ConnectivityPreflightResult.RequirementNotMet>(result)
    }

    @Test
    fun `AVAILABLE requirement not met by LIMITED status`() {
        val provider = RecordingConnectivityProvider(
            result = ProviderOperationResult.Success(
                ConnectivitySnapshot(ConnectivityStatus.LIMITED, isMetered = null),
            ),
        )
        val preflight = SynchronizationConnectivityPreflight()
        val result = runSuspend {
            preflight.evaluate(
                requirement = ConnectivityRequirement.AVAILABLE,
                provider = provider,
                request = sampleRequest(),
            )
        }
        assertIs<ConnectivityPreflightResult.RequirementNotMet>(result)
    }

    @Test
    fun `UNMETERED requirement not met by AVAILABLE metered status`() {
        val provider = RecordingConnectivityProvider(
            result = ProviderOperationResult.Success(
                ConnectivitySnapshot(ConnectivityStatus.AVAILABLE, isMetered = true),
            ),
        )
        val preflight = SynchronizationConnectivityPreflight()
        val result = runSuspend {
            preflight.evaluate(
                requirement = ConnectivityRequirement.UNMETERED,
                provider = provider,
                request = sampleRequest(),
            )
        }
        assertIs<ConnectivityPreflightResult.RequirementNotMet>(result)
    }

    @Test
    fun `UNMETERED requirement not met by AVAILABLE null metering state`() {
        // null metering state must not satisfy UNMETERED
        val provider = RecordingConnectivityProvider(
            result = ProviderOperationResult.Success(
                ConnectivitySnapshot(ConnectivityStatus.AVAILABLE, isMetered = null),
            ),
        )
        val preflight = SynchronizationConnectivityPreflight()
        val result = runSuspend {
            preflight.evaluate(
                requirement = ConnectivityRequirement.UNMETERED,
                provider = provider,
                request = sampleRequest(),
            )
        }
        assertIs<ConnectivityPreflightResult.RequirementNotMet>(result)
    }

    @Test
    fun `UNMETERED requirement not met by UNAVAILABLE status`() {
        val provider = RecordingConnectivityProvider(
            result = ProviderOperationResult.Success(
                ConnectivitySnapshot(ConnectivityStatus.UNAVAILABLE, isMetered = false),
            ),
        )
        val preflight = SynchronizationConnectivityPreflight()
        val result = runSuspend {
            preflight.evaluate(
                requirement = ConnectivityRequirement.UNMETERED,
                provider = provider,
                request = sampleRequest(),
            )
        }
        assertIs<ConnectivityPreflightResult.RequirementNotMet>(result)
    }

    @Test
    fun `UNMETERED requirement not met by UNKNOWN status even if unmetered`() {
        val provider = RecordingConnectivityProvider(
            result = ProviderOperationResult.Success(
                ConnectivitySnapshot(ConnectivityStatus.UNKNOWN, isMetered = false),
            ),
        )
        val preflight = SynchronizationConnectivityPreflight()
        val result = runSuspend {
            preflight.evaluate(
                requirement = ConnectivityRequirement.UNMETERED,
                provider = provider,
                request = sampleRequest(),
            )
        }
        assertIs<ConnectivityPreflightResult.RequirementNotMet>(result)
    }

    // =========================================================================
    // CheckFailed
    // =========================================================================

    @Test
    fun `provider failure returns CheckFailed with exact error`() {
        val error = TestError(code = ErrorCode("DL-CONN-ERROR"))
        val provider = RecordingConnectivityProvider(
            result = ProviderOperationResult.Failure(error),
        )
        val preflight = SynchronizationConnectivityPreflight()
        val result = runSuspend {
            preflight.evaluate(
                requirement = ConnectivityRequirement.AVAILABLE,
                provider = provider,
                request = sampleRequest(),
            )
        }
        assertIs<ConnectivityPreflightResult.CheckFailed>(result)
        assertEquals(error, result.error)
    }

    @Test
    fun `CheckFailed is not treated as RequirementNotMet`() {
        val provider = RecordingConnectivityProvider(
            result = ProviderOperationResult.Failure(TestError()),
        )
        val preflight = SynchronizationConnectivityPreflight()
        val result = runSuspend {
            preflight.evaluate(
                requirement = ConnectivityRequirement.AVAILABLE,
                provider = provider,
                request = sampleRequest(),
            )
        }
        assertIs<ConnectivityPreflightResult.CheckFailed>(result)
    }

    // =========================================================================
    // Cancellation propagation
    // =========================================================================

    @Test
    fun `CancellationException from provider propagates normally`() {
        val cancellation = CancellationException("test cancel")
        val provider = ThrowingConnectivityProvider(cancellation)
        val preflight = SynchronizationConnectivityPreflight()
        var thrown: Throwable? = null
        val block: suspend () -> Unit = {
            preflight.evaluate(
                requirement = ConnectivityRequirement.AVAILABLE,
                provider = provider,
                request = sampleRequest(),
            )
        }
        block.startCoroutine(
            object : Continuation<Unit> {
                override val context: CoroutineContext = EmptyCoroutineContext
                override fun resumeWith(result: Result<Unit>) {
                    thrown = result.exceptionOrNull()
                }
            },
        )
        assertEquals(cancellation, thrown)
    }

    @Test
    fun `unexpected exception from provider propagates normally`() {
        val exception = IllegalStateException("provider bug")
        val provider = ThrowingConnectivityProvider(exception)
        val preflight = SynchronizationConnectivityPreflight()
        var thrown: Throwable? = null
        val block: suspend () -> Unit = {
            preflight.evaluate(
                requirement = ConnectivityRequirement.AVAILABLE,
                provider = provider,
                request = sampleRequest(),
            )
        }
        block.startCoroutine(
            object : Continuation<Unit> {
                override val context: CoroutineContext = EmptyCoroutineContext
                override fun resumeWith(result: Result<Unit>) {
                    thrown = result.exceptionOrNull()
                }
            },
        )
        assertEquals(exception, thrown)
    }
}
