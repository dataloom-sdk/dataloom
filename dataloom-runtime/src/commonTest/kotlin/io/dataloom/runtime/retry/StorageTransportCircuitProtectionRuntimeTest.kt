package io.dataloom.runtime.retry

import io.dataloom.api.circuit.CircuitBreakerCompareAndSetRequest
import io.dataloom.api.circuit.CircuitBreakerCompareAndSetResult
import io.dataloom.api.circuit.CircuitBreakerLoadResult
import io.dataloom.api.circuit.CircuitBreakerScope
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest

class StorageTransportCircuitProtectionRuntimeTest {

    @Test
    fun `storage scope mismatch fails before provider store or clock work`() {
        val provider = RecordingStorageProvider()
        val store = RecordingCircuitStore()
        val clock = CountingClock(now)
        val invalidScopes = storageScopes(provider.descriptor.id).copy(
            readCheckpoint = CircuitBreakerScope.providerOperation(
                providerId = provider.descriptor.id,
                operation = StorageCircuitOperation.WRITE_CHECKPOINT.retryOperation,
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            StorageCircuitProtectionRuntime.create(
                storageProvider = provider,
                clock = clock,
                circuitBreakerConfiguration = configuration(),
                circuitBreakerStateStore = store,
                scopes = invalidScopes,
                providerTimeout = SchedulingDelay.ZERO,
            )
        }

        assertEquals(0, provider.operationCalls)
        assertEquals(0, store.loadCalls)
        assertEquals(0, store.compareCalls)
        assertEquals(0, clock.readCalls)
    }

    @Test
    fun `transport provider mismatch fails before provider store or clock work`() {
        val provider = RecordingTransportProvider()
        val store = RecordingCircuitStore()
        val clock = CountingClock(now)
        val invalidScopes = transportScopes(provider.descriptor.id).copy(
            initialization = CircuitBreakerScope.providerOperation(
                providerId = ProviderId("different-transport"),
                operation = TransportCircuitOperation.INITIALIZE.retryOperation,
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            TransportCircuitProtectionRuntime.create(
                transportProvider = provider,
                clock = clock,
                circuitBreakerConfiguration = configuration(),
                circuitBreakerStateStore = store,
                scopes = invalidScopes,
            )
        }

        assertEquals(0, provider.operationCalls)
        assertEquals(0, store.loadCalls)
        assertEquals(0, store.compareCalls)
        assertEquals(0, clock.readCalls)
    }

    @Test
    fun `zero transport timeout records failure and rejects the next bound call`() = runTest {
        val provider = RecordingTransportProvider()
        val store = RecordingCircuitStore()
        val clock = FixedClock(now)
        val operations = TransportCircuitProtectionRuntime.create(
            transportProvider = provider,
            clock = clock,
            circuitBreakerConfiguration = configuration(),
            circuitBreakerStateStore = store,
            scopes = transportScopes(provider.descriptor.id),
            providerTimeout = SchedulingDelay.ZERO,
        )

        val first = assertIs<CircuitBreakerExecutionResult.Executed<Unit>>(
            operations.initialize(ProviderInitializationContext()),
        )
        val failure = assertIs<CircuitProtectedOperationResult.Failure>(first.operationResult)
        assertEquals(TransportTimeoutErrors.PROVIDER_TIMEOUT_CODE, failure.error.code.value)
        assertIs<CircuitBreakerRecordResult.Recorded>(first.recordResult)
        assertEquals(0, provider.initializeCalls)
        assertEquals(
            transportScopes(provider.descriptor.id).initialization,
            store.lastLoadedScope,
        )

        val second = assertIs<CircuitBreakerExecutionResult.Rejected>(
            operations.initialize(ProviderInitializationContext()),
        )
        assertEquals(CircuitBreakerRejectionReason.OPEN, second.reason)
        assertEquals(0, provider.initializeCalls)
    }

    @Test
    fun `storage operation surface owns the exact configured scope`() = runTest {
        val provider = RecordingStorageProvider()
        val store = RecordingCircuitStore()
        val scopes = storageScopes(provider.descriptor.id)
        val operations = StorageCircuitProtectionRuntime.create(
            storageProvider = provider,
            clock = FixedClock(now),
            circuitBreakerConfiguration = configuration(failureThreshold = 2),
            circuitBreakerStateStore = store,
            scopes = scopes,
        )

        val result = assertIs<CircuitBreakerExecutionResult.Executed<Unit>>(
            operations.initialize(ProviderInitializationContext()),
        )

        assertIs<CircuitProtectedOperationResult.Success<Unit>>(result.operationResult)
        assertIs<CircuitBreakerRecordResult.Ignored>(result.recordResult)
        assertEquals(scopes.initialization, store.lastLoadedScope)
        assertEquals(1, provider.initializeCalls)
        assertSame(scopes, operations.scopes)
        assertSame(provider.descriptor, operations.descriptor)
    }

    @Test
    fun `custom classifier remains effective through runtime assembly`() = runTest {
        val providerError = TestError(
            code = ErrorCode("TRANSPORT_SEMANTIC_FAILURE"),
            category = ErrorCategory.PROVIDER,
            recoverability = Recoverability.RECOVERABLE,
        )
        val provider = RecordingTransportProvider(
            initializeResult = ProviderOperationResult.Failure(providerError),
        )
        val store = RecordingCircuitStore()
        val classifier = CircuitBreakerFailureClassifier {
            CircuitBreakerFailureDisposition.RECORD_SUCCESS
        }
        val operations = TransportCircuitProtectionRuntime.create(
            transportProvider = provider,
            clock = FixedClock(now),
            circuitBreakerConfiguration = configuration(),
            circuitBreakerStateStore = store,
            scopes = transportScopes(provider.descriptor.id),
            failureClassifier = classifier,
        )

        val first = assertIs<CircuitBreakerExecutionResult.Executed<Unit>>(
            operations.initialize(ProviderInitializationContext()),
        )
        val semantic = assertIs<CircuitProtectedOperationResult.NonCircuitFailure>(
            first.operationResult,
        )

        assertSame(providerError, semantic.error)
        assertIs<CircuitBreakerRecordResult.Ignored>(first.recordResult)
        assertEquals(1, provider.initializeCalls)
        assertEquals(0, store.compareCalls)

        assertIs<CircuitBreakerExecutionResult.Executed<Unit>>(
            operations.initialize(ProviderInitializationContext()),
        )
        assertEquals(2, provider.initializeCalls)
    }

    @Test
    fun `valid runtime construction is side effect free`() {
        val storage = RecordingStorageProvider()
        val transport = RecordingTransportProvider()
        val storageStore = RecordingCircuitStore()
        val transportStore = RecordingCircuitStore()
        val clock = CountingClock(now)

        val storageOperations = StorageCircuitProtectionRuntime.create(
            storageProvider = storage,
            clock = clock,
            circuitBreakerConfiguration = configuration(),
            circuitBreakerStateStore = storageStore,
            scopes = storageScopes(storage.descriptor.id),
            providerTimeout = SchedulingDelay(100L),
        )
        val transportOperations = TransportCircuitProtectionRuntime.create(
            transportProvider = transport,
            clock = clock,
            circuitBreakerConfiguration = configuration(),
            circuitBreakerStateStore = transportStore,
            scopes = transportScopes(transport.descriptor.id),
            providerTimeout = SchedulingDelay(100L),
        )

        assertSame(storage.descriptor, storageOperations.descriptor)
        assertSame(transport.descriptor, transportOperations.descriptor)
        assertEquals(0, storage.operationCalls)
        assertEquals(0, transport.operationCalls)
        assertEquals(0, storageStore.loadCalls)
        assertEquals(0, transportStore.loadCalls)
        assertEquals(0, clock.readCalls)
    }

    private class RecordingStorageProvider : StorageProvider {
        var initializeCalls: Int = 0
            private set
        var operationCalls: Int = 0
            private set

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("protected-storage"),
            name = ProviderName("Protected Storage"),
            type = ProviderType.STORAGE,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> {
            operationCalls++
            initializeCalls++
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun health(): ProviderOperationResult<ProviderHealth> {
            operationCalls++
            return ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))
        }

        override suspend fun close(): ProviderOperationResult<Unit> {
            operationCalls++
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun readOutboundChanges(
            request: OutboundChangeReadRequest,
        ): ProviderOperationResult<OutboundChangeReadResult> {
            operationCalls++
            return ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges)
        }

        override suspend fun applyInboundChanges(
            request: InboundChangeApplyRequest,
        ): ProviderOperationResult<Unit> {
            operationCalls++
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun acknowledgeOutboundChanges(
            request: OutboundChangeAcknowledgementRequest,
        ): ProviderOperationResult<Unit> {
            operationCalls++
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun readCheckpoint(
            request: CheckpointReadRequest,
        ): ProviderOperationResult<SynchronizationCheckpoint?> {
            operationCalls++
            return ProviderOperationResult.Success(null)
        }

        override suspend fun writeCheckpoint(
            request: CheckpointWriteRequest,
        ): ProviderOperationResult<Unit> {
            operationCalls++
            return ProviderOperationResult.Success(Unit)
        }
    }

    private class RecordingTransportProvider(
        private val initializeResult: ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit),
    ) : TransportProvider {
        var initializeCalls: Int = 0
            private set
        var operationCalls: Int = 0
            private set

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("protected-transport"),
            name = ProviderName("Protected Transport"),
            type = ProviderType.TRANSPORT,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> {
            operationCalls++
            initializeCalls++
            return initializeResult
        }

        override suspend fun health(): ProviderOperationResult<ProviderHealth> {
            operationCalls++
            return ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))
        }

        override suspend fun close(): ProviderOperationResult<Unit> {
            operationCalls++
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun pushChanges(
            request: PushChangesRequest,
        ): ProviderOperationResult<ChangeSetAcknowledgement> {
            operationCalls++
            return ProviderOperationResult.Failure(TestError())
        }

        override suspend fun pullChanges(
            request: PullChangesRequest,
        ): ProviderOperationResult<PullChangesResult> {
            operationCalls++
            return ProviderOperationResult.Success(PullChangesResult.NoChanges())
        }
    }

    private class RecordingCircuitStore : CircuitBreakerStateStore {
        private val records = mutableMapOf<CircuitBreakerScope, CircuitBreakerStateRecord>()
        var loadCalls: Int = 0
            private set
        var compareCalls: Int = 0
            private set
        var lastLoadedScope: CircuitBreakerScope? = null
            private set

        override suspend fun load(
            scope: CircuitBreakerScope,
        ): ProviderOperationResult<CircuitBreakerLoadResult> {
            loadCalls++
            lastLoadedScope = scope
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
        override val code: ErrorCode = ErrorCode("PROTECTION_RUNTIME_TEST_FAILURE"),
        override val category: ErrorCategory = ErrorCategory.PROVIDER,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val message: String = "Protection runtime test failure.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private companion object {
        val now = DataLoomInstant(1_000L)

        fun configuration(failureThreshold: Int = 1): CircuitBreakerConfiguration =
            CircuitBreakerConfiguration(
                failureThreshold = failureThreshold,
                failureWindow = SchedulingDelay(1_000L),
                openDuration = SchedulingDelay(10_000L),
            )

        fun storageScopes(providerId: ProviderId): StorageCircuitScopes = StorageCircuitScopes(
            initialization = storageScope(providerId, StorageCircuitOperation.INITIALIZE),
            health = storageScope(providerId, StorageCircuitOperation.HEALTH),
            close = storageScope(providerId, StorageCircuitOperation.CLOSE),
            readOutboundChanges = storageScope(
                providerId,
                StorageCircuitOperation.READ_OUTBOUND_CHANGES,
            ),
            applyInboundChanges = storageScope(
                providerId,
                StorageCircuitOperation.APPLY_INBOUND_CHANGES,
            ),
            acknowledgeOutboundChanges = storageScope(
                providerId,
                StorageCircuitOperation.ACKNOWLEDGE_OUTBOUND_CHANGES,
            ),
            readCheckpoint = storageScope(providerId, StorageCircuitOperation.READ_CHECKPOINT),
            writeCheckpoint = storageScope(providerId, StorageCircuitOperation.WRITE_CHECKPOINT),
        )

        fun transportScopes(providerId: ProviderId): TransportCircuitScopes =
            TransportCircuitScopes(
                initialization = transportScope(
                    providerId,
                    TransportCircuitOperation.INITIALIZE,
                ),
                health = transportScope(providerId, TransportCircuitOperation.HEALTH),
                close = transportScope(providerId, TransportCircuitOperation.CLOSE),
                pushChanges = transportScope(
                    providerId,
                    TransportCircuitOperation.PUSH_CHANGES,
                ),
                pullChanges = transportScope(
                    providerId,
                    TransportCircuitOperation.PULL_CHANGES,
                ),
            )

        fun storageScope(
            providerId: ProviderId,
            operation: StorageCircuitOperation,
        ): CircuitBreakerScope = CircuitBreakerScope.providerOperation(
            providerId = providerId,
            operation = operation.retryOperation,
        )

        fun transportScope(
            providerId: ProviderId,
            operation: TransportCircuitOperation,
        ): CircuitBreakerScope = CircuitBreakerScope.providerOperation(
            providerId = providerId,
            operation = operation.retryOperation,
        )
    }
}
