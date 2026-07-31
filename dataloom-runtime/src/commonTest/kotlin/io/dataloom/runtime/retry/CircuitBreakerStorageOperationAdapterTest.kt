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
import io.dataloom.api.storage.InboundChangeApplyRequest
import io.dataloom.api.storage.OutboundChangeReadRequest
import io.dataloom.api.storage.OutboundChangeReadResult
import io.dataloom.api.storage.StorageProvider
import io.dataloom.api.synchronization.CheckpointReadRequest
import io.dataloom.api.synchronization.CheckpointWriteRequest
import io.dataloom.api.synchronization.OutboundChangeAcknowledgementRequest
import io.dataloom.api.synchronization.SynchronizationCheckpoint
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest

class CircuitBreakerStorageOperationAdapterTest {

    @Test
    fun `initialize executes once and preserves accepted circuit evidence`() = runTest {
        val provider = RecordingStorageProvider()
        val store = RecordingCircuitStore()
        val adapter = adapter(provider, store)

        val result = assertIs<CircuitBreakerExecutionResult.Executed<Unit>>(
            adapter.initialize(
                scope = scope(StorageCircuitOperation.INITIALIZE),
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
    fun `open storage circuit prevents provider invocation`() = runTest {
        val operationScope = scope(StorageCircuitOperation.INITIALIZE)
        val provider = RecordingStorageProvider()
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
        val operationScope = scope(StorageCircuitOperation.INITIALIZE)
        val storeError = error("STORAGE_CIRCUIT_WRITE_FAILED", ErrorCategory.STORAGE)
        val store = RecordingCircuitStore(
            initialRecords = mapOf(operationScope to closedFailureRecord(operationScope)),
            compareError = storeError,
        )
        val provider = RecordingStorageProvider()
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
    fun `zero provider timeout is classified inside storage circuit without delegate call`() = runTest {
        val provider = RecordingStorageProvider()
        val protectedProvider = StorageProviderTimeoutRuntime.create(
            storageProvider = provider,
            clock = FixedClock(now),
            providerTimeout = SchedulingDelay.ZERO,
        )
        val store = RecordingCircuitStore()
        val adapter = adapter(protectedProvider, store)

        val executed = assertIs<CircuitBreakerExecutionResult.Executed<Unit>>(
            adapter.initialize(
                scope = scope(StorageCircuitOperation.INITIALIZE),
                context = ProviderInitializationContext(),
            ),
        )
        val failure = assertIs<CircuitProtectedOperationResult.Failure>(
            executed.operationResult,
        )

        assertEquals(StorageTimeoutErrors.PROVIDER_TIMEOUT_CODE, failure.error.code.value)
        assertEquals(Recoverability.UNKNOWN, failure.error.recoverability)
        assertIs<CircuitBreakerRecordResult.Recorded>(executed.recordResult)
        assertEquals(0, provider.initializeCalls)
        assertEquals(1, store.compareCalls)
    }

    @Test
    fun `mutating timeouts remain unknown while read timeouts remain recoverable`() {
        val applyTimeout = StorageTimeoutErrors.providerTimedOut(
            StorageCircuitOperation.APPLY_INBOUND_CHANGES,
        )
        val acknowledgementTimeout = StorageTimeoutErrors.providerTimedOut(
            StorageCircuitOperation.ACKNOWLEDGE_OUTBOUND_CHANGES,
        )
        val checkpointWriteTimeout = StorageTimeoutErrors.providerTimedOut(
            StorageCircuitOperation.WRITE_CHECKPOINT,
        )
        val readTimeout = StorageTimeoutErrors.providerTimedOut(
            StorageCircuitOperation.READ_OUTBOUND_CHANGES,
        )
        val checkpointReadTimeout = StorageTimeoutErrors.providerTimedOut(
            StorageCircuitOperation.READ_CHECKPOINT,
        )

        assertEquals(Recoverability.UNKNOWN, applyTimeout.recoverability)
        assertEquals(Recoverability.UNKNOWN, acknowledgementTimeout.recoverability)
        assertEquals(Recoverability.UNKNOWN, checkpointWriteTimeout.recoverability)
        assertEquals(Recoverability.RECOVERABLE, readTimeout.recoverability)
        assertEquals(Recoverability.RECOVERABLE, checkpointReadTimeout.recoverability)
        assertEquals(
            CircuitBreakerFailureDisposition.RECORD_FAILURE,
            StorageCircuitBreakerFailureClassifier.classify(applyTimeout),
        )
    }

    @Test
    fun `provider scope mismatch fails before store or provider access`() = runTest {
        val provider = RecordingStorageProvider()
        val store = RecordingCircuitStore()
        val adapter = adapter(provider, store)
        val wrongScope = CircuitBreakerScope.providerOperation(
            providerId = ProviderId("different-storage"),
            operation = StorageCircuitOperation.INITIALIZE.retryOperation,
        )

        assertFailsWith<IllegalArgumentException> {
            adapter.initialize(wrongScope, ProviderInitializationContext())
        }

        assertEquals(0, store.loadCalls)
        assertEquals(0, provider.initializeCalls)
    }

    @Test
    fun `operation scope mismatch fails before store or provider access`() = runTest {
        val provider = RecordingStorageProvider()
        val store = RecordingCircuitStore()
        val adapter = adapter(provider, store)
        val wrongScope = scope(StorageCircuitOperation.READ_CHECKPOINT)

        assertFailsWith<IllegalArgumentException> {
            adapter.initialize(wrongScope, ProviderInitializationContext())
        }

        assertEquals(0, store.loadCalls)
        assertEquals(0, provider.initializeCalls)
    }

    @Test
    fun `caller cancellation propagates without circuit recording`() = runTest {
        val provider = RecordingStorageProvider(cancelInitialize = true)
        val store = RecordingCircuitStore()
        val adapter = adapter(provider, store)

        val cancellation = assertFailsWith<CancellationException> {
            adapter.initialize(
                scope = scope(StorageCircuitOperation.INITIALIZE),
                context = ProviderInitializationContext(),
            )
        }

        assertEquals("storage initialize cancelled", cancellation.message)
        assertEquals(1, provider.initializeCalls)
        assertEquals(1, store.loadCalls)
        assertEquals(0, store.compareCalls)
    }

    @Test
    fun `runtime construction performs no provider store or clock work`() {
        val provider = RecordingStorageProvider()
        val clock = CountingClock(now)
        val store = RecordingCircuitStore()

        val protectedProvider = StorageProviderTimeoutRuntime.create(
            storageProvider = provider,
            clock = clock,
            providerTimeout = SchedulingDelay(100L),
        )
        CircuitBreakerStorageOperationAdapter(
            storageProvider = protectedProvider,
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
        provider: StorageProvider,
        store: CircuitBreakerStateStore,
    ): CircuitBreakerStorageOperationAdapter =
        CircuitBreakerStorageOperationAdapter(
            storageProvider = provider,
            executionGate = CircuitBreakerExecutionGate(
                CircuitBreakerCoordinator(
                    configuration = configuration(),
                    clock = FixedClock(now),
                    stateStore = store,
                ),
            ),
        )

    private class RecordingStorageProvider(
        private val cancelInitialize: Boolean = false,
    ) : StorageProvider {
        var initializeCalls: Int = 0
            private set
        var healthCalls: Int = 0
            private set
        var closeCalls: Int = 0
            private set
        var readCalls: Int = 0
            private set
        var applyCalls: Int = 0
            private set
        var acknowledgeCalls: Int = 0
            private set
        var checkpointReadCalls: Int = 0
            private set
        var checkpointWriteCalls: Int = 0
            private set

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = providerId,
            name = ProviderName("Circuit Storage"),
            type = ProviderType.STORAGE,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> {
            initializeCalls++
            if (cancelInitialize) {
                throw CancellationException("storage initialize cancelled")
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

        override suspend fun readOutboundChanges(
            request: OutboundChangeReadRequest,
        ): ProviderOperationResult<OutboundChangeReadResult> {
            readCalls++
            return ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges)
        }

        override suspend fun applyInboundChanges(
            request: InboundChangeApplyRequest,
        ): ProviderOperationResult<Unit> {
            applyCalls++
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun acknowledgeOutboundChanges(
            request: OutboundChangeAcknowledgementRequest,
        ): ProviderOperationResult<Unit> {
            acknowledgeCalls++
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun readCheckpoint(
            request: CheckpointReadRequest,
        ): ProviderOperationResult<SynchronizationCheckpoint?> {
            checkpointReadCalls++
            return ProviderOperationResult.Success(null)
        }

        override suspend fun writeCheckpoint(
            request: CheckpointWriteRequest,
        ): ProviderOperationResult<Unit> {
            checkpointWriteCalls++
            return ProviderOperationResult.Success(Unit)
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
        val providerId = ProviderId("storage-circuit-provider")
        val now = DataLoomInstant(1_000L)

        fun configuration(): CircuitBreakerConfiguration = CircuitBreakerConfiguration(
            failureThreshold = 1,
            failureWindow = SchedulingDelay(1_000L),
            openDuration = SchedulingDelay(10_000L),
        )

        fun scope(operation: StorageCircuitOperation): CircuitBreakerScope =
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
        override val message: String = "Storage circuit test error.",
        override val cause: Throwable? = null,
    ) : DataLoomError
}
