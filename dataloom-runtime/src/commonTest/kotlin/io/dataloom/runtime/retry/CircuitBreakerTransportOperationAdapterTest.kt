package io.dataloom.runtime.retry

import io.dataloom.api.circuit.CircuitBreakerCompareAndSetRequest
import io.dataloom.api.circuit.CircuitBreakerCompareAndSetResult
import io.dataloom.api.circuit.CircuitBreakerLoadResult
import io.dataloom.api.circuit.CircuitBreakerPhase
import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.circuit.CircuitBreakerState
import io.dataloom.api.circuit.CircuitBreakerStateRecord
import io.dataloom.api.circuit.CircuitBreakerStateStore
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest

class CircuitBreakerTransportOperationAdapterTest {

    @Test
    fun `initialize executes once and preserves accepted circuit evidence`() = runTest {
        val provider = RecordingTransportProvider()
        val store = RecordingCircuitStore()
        val adapter = adapter(provider, store)

        val result = assertIs<CircuitBreakerExecutionResult.Executed<Unit>>(
            adapter.initialize(
                scope = scope(TransportCircuitOperation.INITIALIZE),
                context = ProviderInitializationContext(),
            ),
        )

        assertIs<CircuitProtectedOperationResult.Success<Unit>>(result.operationResult)
        assertIs<CircuitBreakerRecordResult.Ignored>(result.recordResult)
        assertEquals(1, provider.initializeCalls)
        assertEquals(1, store.loadCalls)
        assertEquals(0, store.compareCalls)
    }

    @Test
    fun `open transport circuit prevents provider invocation`() = runTest {
        val operationScope = scope(TransportCircuitOperation.INITIALIZE)
        val provider = RecordingTransportProvider()
        val store = RecordingCircuitStore(
            initialRecords = mapOf(operationScope to openRecord(operationScope)),
        )
        val adapter = adapter(provider, store)

        val rejected = assertIs<CircuitBreakerExecutionResult.Rejected>(
            adapter.initialize(operationScope, ProviderInitializationContext()),
        )

        assertEquals(CircuitBreakerRejectionReason.OPEN, rejected.reason)
        assertEquals(0, provider.initializeCalls)
        assertEquals(1, store.loadCalls)
    }

    @Test
    fun `provider success followed by failed circuit write preserves execution`() = runTest {
        val operationScope = scope(TransportCircuitOperation.INITIALIZE)
        val storeError = error("TRANSPORT_CIRCUIT_WRITE_FAILED", ErrorCategory.STORAGE)
        val store = RecordingCircuitStore(
            initialRecords = mapOf(operationScope to closedFailureRecord(operationScope)),
            compareError = storeError,
        )
        val provider = RecordingTransportProvider()
        val adapter = adapter(provider, store)

        val executed = assertIs<CircuitBreakerExecutionResult.Executed<Unit>>(
            adapter.initialize(operationScope, ProviderInitializationContext()),
        )
        val success = assertIs<CircuitProtectedOperationResult.Success<Unit>>(
            executed.operationResult,
        )
        val recordFailure = assertIs<CircuitBreakerRecordResult.PersistenceFailure>(
            executed.recordResult,
        )

        assertSame(Unit, success.value)
        assertSame(storeError, recordFailure.error)
        assertEquals(1, provider.initializeCalls)
        assertEquals(1, store.compareCalls)
    }

    @Test
    fun `zero provider timeout is classified inside transport circuit without delegate call`() = runTest {
        val provider = RecordingTransportProvider()
        val protectedProvider = TransportProviderTimeoutRuntime.create(
            transportProvider = provider,
            clock = FixedClock(now),
            providerTimeout = SchedulingDelay.ZERO,
        )
        val store = RecordingCircuitStore()
        val adapter = adapter(protectedProvider, store)

        val executed = assertIs<CircuitBreakerExecutionResult.Executed<Unit>>(
            adapter.initialize(
                scope = scope(TransportCircuitOperation.INITIALIZE),
                context = ProviderInitializationContext(),
            ),
        )
        val failure = assertIs<CircuitProtectedOperationResult.Failure>(
            executed.operationResult,
        )

        assertEquals(TransportTimeoutErrors.PROVIDER_TIMEOUT_CODE, failure.error.code.value)
        assertEquals(Recoverability.UNKNOWN, failure.error.recoverability)
        assertIs<CircuitBreakerRecordResult.Recorded>(executed.recordResult)
        assertEquals(0, provider.initializeCalls)
        assertEquals(1, store.compareCalls)
    }

    @Test
    fun `push timeout remains replay ambiguous while still counting as circuit failure`() {
        val pushTimeout = TransportTimeoutErrors.providerTimedOut(
            TransportCircuitOperation.PUSH_CHANGES,
        )
        val pullTimeout = TransportTimeoutErrors.providerTimedOut(
            TransportCircuitOperation.PULL_CHANGES,
        )

        assertEquals(Recoverability.UNKNOWN, pushTimeout.recoverability)
        assertEquals(ErrorCategory.NETWORK, pushTimeout.category)
        assertEquals(
            CircuitBreakerFailureDisposition.RECORD_FAILURE,
            TransportCircuitBreakerFailureClassifier.classify(pushTimeout),
        )
        assertEquals(Recoverability.RECOVERABLE, pullTimeout.recoverability)
        assertEquals(
            CircuitBreakerFailureDisposition.RECORD_FAILURE,
            TransportCircuitBreakerFailureClassifier.classify(pullTimeout),
        )
    }

    @Test
    fun `provider scope mismatch fails before store or provider access`() {
        val provider = RecordingTransportProvider()
        val store = RecordingCircuitStore()
        val adapter = adapter(provider, store)
        val wrongScope = CircuitBreakerScope.providerOperation(
            providerId = ProviderId("different-transport"),
            operation = TransportCircuitOperation.INITIALIZE.retryOperation,
        )

        assertFailsWith<IllegalArgumentException> {
            runTest {
                adapter.initialize(wrongScope, ProviderInitializationContext())
            }
        }

        assertEquals(0, store.loadCalls)
        assertEquals(0, provider.initializeCalls)
    }

    @Test
    fun `operation scope mismatch fails before store or provider access`() {
        val provider = RecordingTransportProvider()
        val store = RecordingCircuitStore()
        val adapter = adapter(provider, store)
        val wrongScope = scope(TransportCircuitOperation.PULL_CHANGES)

        assertFailsWith<IllegalArgumentException> {
            runTest {
                adapter.initialize(wrongScope, ProviderInitializationContext())
            }
        }

        assertEquals(0, store.loadCalls)
        assertEquals(0, provider.initializeCalls)
    }

    @Test
    fun `caller cancellation propagates without circuit recording`() = runTest {
        val provider = RecordingTransportProvider(cancelInitialize = true)
        val store = RecordingCircuitStore()
        val adapter = adapter(provider, store)

        val cancellation = assertFailsWith<CancellationException> {
            adapter.initialize(
                scope = scope(TransportCircuitOperation.INITIALIZE),
                context = ProviderInitializationContext(),
            )
        }

        assertEquals("transport initialize cancelled", cancellation.message)
        assertEquals(1, provider.initializeCalls)
        assertEquals(1, store.loadCalls)
        assertEquals(0, store.compareCalls)
    }

    @Test
    fun `runtime construction performs no provider store or clock work`() {
        val provider = RecordingTransportProvider()
        val clock = CountingClock(now)
        val store = RecordingCircuitStore()

        val protectedProvider = TransportProviderTimeoutRuntime.create(
            transportProvider = provider,
            clock = clock,
            providerTimeout = SchedulingDelay(100L),
        )
        CircuitBreakerTransportOperationAdapter(
            transportProvider = protectedProvider,
            executionGate = CircuitBreakerExecutionGate(
                CircuitBreakerCoordinator(
                    configuration = configuration(),
                    clock = clock,
                    stateStore = store,
                ),
            ),
        )

        assertEquals(0, provider.initializeCalls)
        assertEquals(0, provider.healthCalls)
        assertEquals(0, provider.closeCalls)
        assertEquals(0, store.loadCalls)
        assertEquals(0, store.compareCalls)
        assertEquals(0, clock.readCalls)
    }

    private fun adapter(
        provider: TransportProvider,
        store: CircuitBreakerStateStore,
    ): CircuitBreakerTransportOperationAdapter =
        CircuitBreakerTransportOperationAdapter(
            transportProvider = provider,
            executionGate = CircuitBreakerExecutionGate(
                CircuitBreakerCoordinator(
                    configuration = configuration(),
                    clock = FixedClock(now),
                    stateStore = store,
                ),
            ),
        )

    private class RecordingTransportProvider(
        private val cancelInitialize: Boolean = false,
    ) : TransportProvider {
        var initializeCalls: Int = 0
            private set
        var healthCalls: Int = 0
            private set
        var closeCalls: Int = 0
            private set
        var pushCalls: Int = 0
            private set
        var pullCalls: Int = 0
            private set

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = providerId,
            name = ProviderName("Circuit Transport"),
            type = ProviderType.TRANSPORT,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> {
            initializeCalls++
            if (cancelInitialize) {
                throw CancellationException("transport initialize cancelled")
            }
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun health(): ProviderOperationResult<ProviderHealth> {
            healthCalls++
            return ProviderOperationResult.Success(
                ProviderHealth(ProviderHealthStatus.HEALTHY),
            )
        }

        override suspend fun close(): ProviderOperationResult<Unit> {
            closeCalls++
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun pushChanges(
            request: PushChangesRequest,
        ): ProviderOperationResult<ChangeSetAcknowledgement> {
            pushCalls++
            return ProviderOperationResult.Failure(
                error("PUSH_NOT_CONFIGURED", ErrorCategory.CONFIGURATION),
            )
        }

        override suspend fun pullChanges(
            request: PullChangesRequest,
        ): ProviderOperationResult<PullChangesResult> {
            pullCalls++
            return ProviderOperationResult.Failure(
                error("PULL_NOT_CONFIGURED", ErrorCategory.CONFIGURATION),
            )
        }
    }

    private class RecordingCircuitStore(
        initialRecords: Map<CircuitBreakerScope, CircuitBreakerStateRecord> = emptyMap(),
        private val compareError: DataLoomError? = null,
    ) : CircuitBreakerStateStore {
        private val records = initialRecords.toMutableMap()
        var loadCalls: Int = 0
            private set
        var compareCalls: Int = 0
            private set

        override suspend fun load(
            scope: CircuitBreakerScope,
        ): ProviderOperationResult<CircuitBreakerLoadResult> {
            loadCalls++
            val record = records[scope]
            return ProviderOperationResult.Success(
                if (record == null) CircuitBreakerLoadResult.Missing
                else CircuitBreakerLoadResult.Found(record),
            )
        }

        override suspend fun compareAndSet(
            request: CircuitBreakerCompareAndSetRequest,
        ): ProviderOperationResult<CircuitBreakerCompareAndSetResult> {
            compareCalls++
            compareError?.let { return ProviderOperationResult.Failure(it) }
            val current = records[request.scope]
            if (current?.version != request.expectedVersion) {
                return ProviderOperationResult.Success(
                    CircuitBreakerCompareAndSetResult.Conflict(current),
                )
            }
            val updated = CircuitBreakerStateRecord(
                state = request.nextState,
                version = (current?.version ?: -1L) + 1L,
            )
            records[request.scope] = updated
            return ProviderOperationResult.Success(
                CircuitBreakerCompareAndSetResult.Updated(updated),
            )
        }
    }

    private class FixedClock(
        private val instant: DataLoomInstant,
    ) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
    }

    private class CountingClock(
        private val instant: DataLoomInstant,
    ) : DataLoomClock {
        var readCalls: Int = 0
            private set

        override fun now(): DataLoomInstant {
            readCalls++
            return instant
        }
    }

    private companion object {
        val providerId = ProviderId("transport-circuit-provider")
        val now = DataLoomInstant(1_000L)

        fun configuration(): CircuitBreakerConfiguration = CircuitBreakerConfiguration(
            failureThreshold = 1,
            failureWindow = SchedulingDelay(1_000L),
            openDuration = SchedulingDelay(10_000L),
        )

        fun scope(operation: TransportCircuitOperation): CircuitBreakerScope =
            CircuitBreakerScope.providerOperation(
                providerId = providerId,
                operation = operation.retryOperation,
            )

        fun openRecord(scope: CircuitBreakerScope): CircuitBreakerStateRecord =
            CircuitBreakerStateRecord(
                state = CircuitBreakerState(
                    scope = scope,
                    phase = CircuitBreakerPhase.OPEN,
                    consecutiveFailures = 0,
                    failureWindowStartedAt = null,
                    openUntil = DataLoomInstant(10_000L),
                    probeGeneration = 0L,
                    probeInFlight = false,
                    updatedAt = now,
                ),
                version = 0L,
            )

        fun closedFailureRecord(scope: CircuitBreakerScope): CircuitBreakerStateRecord =
            CircuitBreakerStateRecord(
                state = CircuitBreakerState(
                    scope = scope,
                    phase = CircuitBreakerPhase.CLOSED,
                    consecutiveFailures = 1,
                    failureWindowStartedAt = now,
                    openUntil = null,
                    probeGeneration = 0L,
                    probeInFlight = false,
                    updatedAt = now,
                ),
                version = 0L,
            )

        fun error(
            code: String,
            category: ErrorCategory,
            recoverability: Recoverability = Recoverability.RECOVERABLE,
        ): DataLoomError = TestError(
            code = ErrorCode(code),
            category = category,
            recoverability = recoverability,
        )
    }

    private data class TestError(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val recoverability: Recoverability,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val message: String = "Transport circuit test error.",
        override val cause: Throwable? = null,
    ) : DataLoomError
}
