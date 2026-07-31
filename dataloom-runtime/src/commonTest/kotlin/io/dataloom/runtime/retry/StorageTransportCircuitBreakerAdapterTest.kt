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
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.synchronization.CheckpointReadRequest
import io.dataloom.api.synchronization.CheckpointWriteRequest
import io.dataloom.api.synchronization.OutboundChangeAcknowledgementRequest
import io.dataloom.api.synchronization.SynchronizationCheckpoint
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

class StorageTransportCircuitBreakerAdapterTest {

    @Test
    fun `transport timeout opens circuit without invoking delegate`() = runTest {
        val provider = RecordingTransportProvider()
        val store = RecordingCircuitStore()
        val protectedProvider = TransportProviderTimeoutRuntime.create(
            transportProvider = provider,
            clock = FixedClock(now),
            providerTimeout = SchedulingDelay.ZERO,
        )
        val adapter = transportAdapter(protectedProvider, store)
        val scope = transportScope(TransportCircuitOperation.INITIALIZE)

        val first = assertIs<CircuitBreakerExecutionResult.Executed<Unit>>(
            adapter.initialize(scope, ProviderInitializationContext()),
        )
        val failure = assertIs<CircuitProtectedOperationResult.Failure>(first.operationResult)

        assertEquals(TransportTimeoutErrors.PROVIDER_TIMEOUT_CODE, failure.error.code.value)
        assertEquals(Recoverability.UNKNOWN, failure.error.recoverability)
        assertIs<CircuitBreakerRecordResult.Recorded>(first.recordResult)
        assertEquals(0, provider.initializeCalls)

        val second = assertIs<CircuitBreakerExecutionResult.Rejected>(
            adapter.initialize(scope, ProviderInitializationContext()),
        )
        assertEquals(CircuitBreakerRejectionReason.OPEN, second.reason)
        assertEquals(0, provider.initializeCalls)
    }

    @Test
    fun `transport push and pull timeouts remain replay ambiguous`() {
        val push = TransportTimeoutErrors.providerTimedOut(
            TransportCircuitOperation.PUSH_CHANGES,
        )
        val pull = TransportTimeoutErrors.providerTimedOut(
            TransportCircuitOperation.PULL_CHANGES,
        )

        assertEquals(Recoverability.UNKNOWN, push.recoverability)
        assertEquals(Recoverability.UNKNOWN, pull.recoverability)
        assertEquals(ErrorCategory.NETWORK, push.category)
        assertEquals(ErrorCategory.NETWORK, pull.category)
        assertEquals(
            CircuitBreakerFailureDisposition.RECORD_FAILURE,
            TransportCircuitBreakerFailureClassifier.classify(push),
        )
        assertEquals(
            CircuitBreakerFailureDisposition.RECORD_FAILURE,
            TransportCircuitBreakerFailureClassifier.classify(pull),
        )
    }

    @Test
    fun `storage reads remain recoverable while mutations remain ambiguous`() {
        val read = StorageTimeoutErrors.providerTimedOut(
            StorageCircuitOperation.READ_OUTBOUND_CHANGES,
        )
        val checkpointRead = StorageTimeoutErrors.providerTimedOut(
            StorageCircuitOperation.READ_CHECKPOINT,
        )
        val apply = StorageTimeoutErrors.providerTimedOut(
            StorageCircuitOperation.APPLY_INBOUND_CHANGES,
        )
        val acknowledge = StorageTimeoutErrors.providerTimedOut(
            StorageCircuitOperation.ACKNOWLEDGE_OUTBOUND_CHANGES,
        )
        val checkpointWrite = StorageTimeoutErrors.providerTimedOut(
            StorageCircuitOperation.WRITE_CHECKPOINT,
        )

        assertEquals(Recoverability.RECOVERABLE, read.recoverability)
        assertEquals(Recoverability.RECOVERABLE, checkpointRead.recoverability)
        assertEquals(Recoverability.UNKNOWN, apply.recoverability)
        assertEquals(Recoverability.UNKNOWN, acknowledge.recoverability)
        assertEquals(Recoverability.UNKNOWN, checkpointWrite.recoverability)
        assertEquals(
            CircuitBreakerFailureDisposition.RECORD_FAILURE,
            StorageCircuitBreakerFailureClassifier.classify(checkpointWrite),
        )
    }

    @Test
    fun `scope mismatch fails before state or provider access`() = runTest {
        val provider = RecordingTransportProvider()
        val store = RecordingCircuitStore()
        val adapter = transportAdapter(provider, store)
        val wrongProvider = CircuitBreakerScope.providerOperation(
            providerId = ProviderId("different-transport"),
            operation = TransportCircuitOperation.INITIALIZE.retryOperation,
        )

        assertFailsWith<IllegalArgumentException> {
            adapter.initialize(wrongProvider, ProviderInitializationContext())
        }
        assertEquals(0, store.loadCalls)
        assertEquals(0, provider.initializeCalls)

        val wrongOperation = transportScope(TransportCircuitOperation.PULL_CHANGES)
        assertFailsWith<IllegalArgumentException> {
            adapter.initialize(wrongOperation, ProviderInitializationContext())
        }
        assertEquals(0, store.loadCalls)
        assertEquals(0, provider.initializeCalls)
    }

    @Test
    fun `successful storage operation remains visible when circuit recording fails`() = runTest {
        val scope = storageScope(StorageCircuitOperation.INITIALIZE)
        val persistenceError = TestError(
            code = ErrorCode("CIRCUIT_STORE_WRITE_FAILED"),
            category = ErrorCategory.STORAGE,
        )
        val store = RecordingCircuitStore(
            initialRecords = mapOf(scope to closedFailureRecord(scope)),
            compareError = persistenceError,
        )
        val provider = RecordingStorageProvider()
        val adapter = storageAdapter(provider, store, failureThreshold = 2)

        val executed = assertIs<CircuitBreakerExecutionResult.Executed<Unit>>(
            adapter.initialize(scope, ProviderInitializationContext()),
        )
        val success = assertIs<CircuitProtectedOperationResult.Success<Unit>>(
            executed.operationResult,
        )
        val recordFailure = assertIs<CircuitBreakerRecordResult.PersistenceFailure>(
            executed.recordResult,
        )

        assertSame(Unit, success.value)
        assertSame(persistenceError, recordFailure.error)
        assertEquals(1, provider.initializeCalls)
        assertEquals(1, store.compareCalls)
    }

    @Test
    fun `caller cancellation propagates without circuit recording`() = runTest {
        val provider = RecordingStorageProvider(cancelInitialize = true)
        val store = RecordingCircuitStore()
        val adapter = storageAdapter(provider, store)

        val cancellation = assertFailsWith<CancellationException> {
            adapter.initialize(
                storageScope(StorageCircuitOperation.INITIALIZE),
                ProviderInitializationContext(),
            )
        }

        assertEquals("storage initialize cancelled", cancellation.message)
        assertEquals(1, provider.initializeCalls)
        assertEquals(1, store.loadCalls)
        assertEquals(0, store.compareCalls)
    }

    @Test
    fun `construction performs no provider store or clock work`() {
        val storage = RecordingStorageProvider()
        val transport = RecordingTransportProvider()
        val store = RecordingCircuitStore()
        val clock = CountingClock(now)
        val storageProvider = StorageProviderTimeoutRuntime.create(
            storageProvider = storage,
            clock = clock,
            providerTimeout = SchedulingDelay(100L),
        )
        val transportProvider = TransportProviderTimeoutRuntime.create(
            transportProvider = transport,
            clock = clock,
            providerTimeout = SchedulingDelay(100L),
        )
        val gate = CircuitBreakerExecutionGate(
            CircuitBreakerCoordinator(
                configuration = configuration(1),
                clock = clock,
                stateStore = store,
            ),
        )

        CircuitBreakerStorageOperationAdapter(storageProvider, gate)
        CircuitBreakerTransportOperationAdapter(transportProvider, gate)

        assertEquals(0, storage.initializeCalls)
        assertEquals(0, transport.initializeCalls)
        assertEquals(0, store.loadCalls)
        assertEquals(0, store.compareCalls)
        assertEquals(0, clock.readCalls)
    }

    private fun transportAdapter(
        provider: TransportProvider,
        store: CircuitBreakerStateStore,
        failureThreshold: Int = 1,
    ): CircuitBreakerTransportOperationAdapter = CircuitBreakerTransportOperationAdapter(
        transportProvider = provider,
        executionGate = executionGate(store, failureThreshold),
    )

    private fun storageAdapter(
        provider: StorageProvider,
        store: CircuitBreakerStateStore,
        failureThreshold: Int = 1,
    ): CircuitBreakerStorageOperationAdapter = CircuitBreakerStorageOperationAdapter(
        storageProvider = provider,
        executionGate = executionGate(store, failureThreshold),
    )

    private fun executionGate(
        store: CircuitBreakerStateStore,
        failureThreshold: Int,
    ): CircuitBreakerExecutionGate = CircuitBreakerExecutionGate(
        CircuitBreakerCoordinator(
            configuration = configuration(failureThreshold),
            clock = FixedClock(now),
            stateStore = store,
        ),
    )

    private class RecordingTransportProvider : TransportProvider {
        var initializeCalls: Int = 0
            private set

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = transportProviderId,
            name = ProviderName("Circuit Transport"),
            type = ProviderType.TRANSPORT,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> {
            initializeCalls++
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun pushChanges(
            request: PushChangesRequest,
        ): ProviderOperationResult<ChangeSetAcknowledgement> =
            ProviderOperationResult.Failure(TestError())

        override suspend fun pullChanges(
            request: PullChangesRequest,
        ): ProviderOperationResult<PullChangesResult> =
            ProviderOperationResult.Success(PullChangesResult.NoChanges())
    }

    private class RecordingStorageProvider(
        private val cancelInitialize: Boolean = false,
    ) : StorageProvider {
        var initializeCalls: Int = 0
            private set

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = storageProviderId,
            name = ProviderName("Circuit Storage"),
            type = ProviderType.STORAGE,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> {
            initializeCalls++
            if (cancelInitialize) throw CancellationException("storage initialize cancelled")
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun readOutboundChanges(
            request: OutboundChangeReadRequest,
        ): ProviderOperationResult<OutboundChangeReadResult> =
            ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges)

        override suspend fun applyInboundChanges(
            request: InboundChangeApplyRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun acknowledgeOutboundChanges(
            request: OutboundChangeAcknowledgementRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun readCheckpoint(
            request: CheckpointReadRequest,
        ): ProviderOperationResult<SynchronizationCheckpoint?> =
            ProviderOperationResult.Success(null)

        override suspend fun writeCheckpoint(
            request: CheckpointWriteRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)
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
            val current = records[scope]
            return ProviderOperationResult.Success(
                current?.let(CircuitBreakerLoadResult::Found)
                    ?: CircuitBreakerLoadResult.Missing,
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
            val next = CircuitBreakerStateRecord(
                state = request.nextState,
                version = (current?.version ?: -1L) + 1L,
            )
            records[request.scope] = next
            return ProviderOperationResult.Success(
                CircuitBreakerCompareAndSetResult.Updated(next),
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

    private data class TestError(
        override val code: ErrorCode = ErrorCode("PROVIDER_TEST_FAILURE"),
        override val category: ErrorCategory = ErrorCategory.PROVIDER,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val message: String = "Provider circuit test failure.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private companion object {
        val now = DataLoomInstant(1_000L)
        val storageProviderId = ProviderId("storage-circuit-provider")
        val transportProviderId = ProviderId("transport-circuit-provider")

        fun configuration(failureThreshold: Int): CircuitBreakerConfiguration =
            CircuitBreakerConfiguration(
                failureThreshold = failureThreshold,
                failureWindow = SchedulingDelay(1_000L),
                openDuration = SchedulingDelay(10_000L),
            )

        fun storageScope(operation: StorageCircuitOperation): CircuitBreakerScope =
            CircuitBreakerScope.providerOperation(
                providerId = storageProviderId,
                operation = operation.retryOperation,
            )

        fun transportScope(operation: TransportCircuitOperation): CircuitBreakerScope =
            CircuitBreakerScope.providerOperation(
                providerId = transportProviderId,
                operation = operation.retryOperation,
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
    }
}
