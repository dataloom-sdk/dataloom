package io.dataloom.runtime.facade

import io.dataloom.api.circuit.AuthorizedCircuitAdministrationCommand
import io.dataloom.api.circuit.CircuitAdministrationAction
import io.dataloom.api.circuit.CircuitAdministrationAuthorizationDecision
import io.dataloom.api.circuit.CircuitAdministrationAuthorizationId
import io.dataloom.api.circuit.CircuitAdministrationAuthorizer
import io.dataloom.api.circuit.CircuitAdministrationCommandId
import io.dataloom.api.circuit.CircuitAdministrationCompareAndSetRequest
import io.dataloom.api.circuit.CircuitAdministrationCompareAndSetResult
import io.dataloom.api.circuit.CircuitAdministrationExecutionResult
import io.dataloom.api.circuit.CircuitAdministrationExecutor
import io.dataloom.api.circuit.CircuitAdministrationLoadResult
import io.dataloom.api.circuit.CircuitAdministrationPrincipalId
import io.dataloom.api.circuit.CircuitAdministrationReason
import io.dataloom.api.circuit.CircuitAdministrationRequest
import io.dataloom.api.circuit.CircuitAdministrationStateRecord
import io.dataloom.api.circuit.CircuitAdministrationStateStore
import io.dataloom.api.circuit.CircuitBreakerPhase
import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.circuit.CircuitBreakerState
import io.dataloom.api.circuit.CircuitBreakerStateRecord
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.operational.OperationalEventOutboxScope
import io.dataloom.api.operational.OperationalEventOutboxState
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderLifecycleResult
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.provider.SynchronizationProviderBindings
import io.dataloom.api.retry.AuthorizedRetryAdministrationCommand
import io.dataloom.api.retry.RetryAdministrationAction
import io.dataloom.api.retry.RetryAdministrationAuthorizationDecision
import io.dataloom.api.retry.RetryAdministrationAuthorizationId
import io.dataloom.api.retry.RetryAdministrationAuthorizer
import io.dataloom.api.retry.RetryAdministrationCommandId
import io.dataloom.api.retry.RetryAdministrationCompareAndSetRequest
import io.dataloom.api.retry.RetryAdministrationCompareAndSetResult
import io.dataloom.api.retry.RetryAdministrationExecutionResult
import io.dataloom.api.retry.RetryAdministrationExecutor
import io.dataloom.api.retry.RetryAdministrationLoadResult
import io.dataloom.api.retry.RetryAdministrationPrincipalId
import io.dataloom.api.retry.RetryAdministrationReason
import io.dataloom.api.retry.RetryAdministrationRequest
import io.dataloom.api.retry.RetryAdministrationStateRecord
import io.dataloom.api.retry.RetryAdministrationStateStore
import io.dataloom.api.retry.RetryFailureSnapshot
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.runtime.RuntimeIdentifierGenerators
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateRecord
import io.dataloom.api.state.DurableStateStore
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
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.runtime.retry.CircuitAdministrationResult
import io.dataloom.runtime.retry.RetryAdministrationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/**
 * Proves [DataLoomRetryCircuitAdministrationOperationalEventOutboxSpec]/
 * `retryCircuitAdministrationOperationalEventOutboxConfiguration` is a real,
 * reachable caller of [io.dataloom.api.operational.DurableOperationalEventOutbox]
 * for retry- and circuit-administration commands executed through a real
 * [DataLoomBuilder]-built [DataLoom] instance -- and that a durable-recording
 * failure never changes the real coordinator result the caller receives.
 */
class DataLoomBuilderRetryCircuitAdministrationOperationalEventOutboxTest {

    private class FixedClock(private val epochMs: Long = 9_000L) : DataLoomClock {
        override fun now(): DataLoomInstant = DataLoomInstant(epochMs)
    }

    private data class FakeError(
        override val code: ErrorCode = ErrorCode("DL-FAKE"),
        override val category: ErrorCategory = ErrorCategory.STORAGE,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "raw-sensitive-message",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private class NoChangesStorageProvider(id: String = "storage-primary") : StorageProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId(id),
            name = ProviderName("Storage $id"),
            type = ProviderType.STORAGE,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun readOutboundChanges(
            request: OutboundChangeReadRequest,
        ): ProviderOperationResult<OutboundChangeReadResult> =
            ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges)

        override suspend fun applyInboundChanges(request: InboundChangeApplyRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun acknowledgeOutboundChanges(
            request: OutboundChangeAcknowledgementRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun readCheckpoint(
            request: CheckpointReadRequest,
        ): ProviderOperationResult<SynchronizationCheckpoint?> = ProviderOperationResult.Success(null)

        override suspend fun writeCheckpoint(request: CheckpointWriteRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)
    }

    private class UnusedTransportProvider(id: String = "transport-prod") : TransportProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId(id),
            name = ProviderName("Transport $id"),
            type = ProviderType.TRANSPORT,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun pushChanges(
            request: PushChangesRequest,
        ): ProviderOperationResult<io.dataloom.api.synchronization.ChangeSetAcknowledgement> =
            throw NotImplementedError("Not exercised by this test.")

        override suspend fun pullChanges(request: PullChangesRequest): ProviderOperationResult<PullChangesResult> =
            ProviderOperationResult.Success(PullChangesResult.NoChanges())
    }

    private class InMemoryOperationalEventOutboxStore :
        DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState> {
        private val records = mutableMapOf<OperationalEventOutboxScope, DurableStateRecord<OperationalEventOutboxState>>()

        suspend fun recordedEntryCount(scope: OperationalEventOutboxScope): Int = records[scope]?.state?.entries?.size ?: 0

        override suspend fun load(
            scope: OperationalEventOutboxScope,
        ): ProviderOperationResult<DurableStateLoadResult<OperationalEventOutboxState>> {
            val record = records[scope]
            return ProviderOperationResult.Success(
                if (record == null) DurableStateLoadResult.Missing else DurableStateLoadResult.Found(record),
            )
        }

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<OperationalEventOutboxScope, OperationalEventOutboxState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<OperationalEventOutboxState>> {
            val current = records[request.scope]
            if (current?.version != request.expectedVersion) {
                return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Conflict(current))
            }
            val updated = DurableStateRecord(
                state = request.nextState,
                version = (current?.version ?: -1L) + 1L,
                schemaVersion = request.nextSchemaVersion,
            )
            records[request.scope] = updated
            return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Updated(updated))
        }
    }

    /** Always fails the underlying durable store -- proves append failures never propagate. */
    private class AlwaysFailingOperationalEventOutboxStore :
        DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState> {
        override suspend fun load(
            scope: OperationalEventOutboxScope,
        ): ProviderOperationResult<DurableStateLoadResult<OperationalEventOutboxState>> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<OperationalEventOutboxScope, OperationalEventOutboxState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<OperationalEventOutboxState>> =
            ProviderOperationResult.Failure(FakeError())
    }

    private class RecordingRetryAdministrationAuthorizer : RetryAdministrationAuthorizer {
        override suspend fun authorize(
            request: RetryAdministrationRequest,
        ): RetryAdministrationAuthorizationDecision =
            RetryAdministrationAuthorizationDecision.Authorized(RetryAdministrationAuthorizationId("authorization-001"))
    }

    private class InMemoryRetryAdministrationStateStore : RetryAdministrationStateStore {
        var record: RetryAdministrationStateRecord? = null

        override suspend fun load(
            commandId: RetryAdministrationCommandId,
        ): ProviderOperationResult<RetryAdministrationLoadResult> {
            val current = record
            return ProviderOperationResult.Success(
                if (current == null) RetryAdministrationLoadResult.Missing else RetryAdministrationLoadResult.Found(current),
            )
        }

        override suspend fun compareAndSet(
            request: RetryAdministrationCompareAndSetRequest,
        ): ProviderOperationResult<RetryAdministrationCompareAndSetResult> {
            val current = record
            val versionMatches = if (request.expectedVersion == null) current == null else current?.version == request.expectedVersion
            if (!versionMatches) {
                return ProviderOperationResult.Success(RetryAdministrationCompareAndSetResult.Conflict(current))
            }
            val updated = RetryAdministrationStateRecord(state = request.nextState, version = (current?.version ?: -1L) + 1L)
            record = updated
            return ProviderOperationResult.Success(RetryAdministrationCompareAndSetResult.Updated(updated))
        }
    }

    private class RecordingRetryAdministrationExecutor : RetryAdministrationExecutor {
        override suspend fun execute(command: AuthorizedRetryAdministrationCommand): RetryAdministrationExecutionResult =
            RetryAdministrationExecutionResult.Applied
    }

    private class RecordingCircuitAdministrationAuthorizer : CircuitAdministrationAuthorizer {
        override suspend fun authorize(
            request: CircuitAdministrationRequest,
        ): CircuitAdministrationAuthorizationDecision =
            CircuitAdministrationAuthorizationDecision.Authorized(CircuitAdministrationAuthorizationId("circuit-authorization-001"))
    }

    private class InMemoryCircuitAdministrationStateStore : CircuitAdministrationStateStore {
        var record: CircuitAdministrationStateRecord? = null

        override suspend fun load(
            commandId: CircuitAdministrationCommandId,
        ): ProviderOperationResult<CircuitAdministrationLoadResult> {
            val current = record
            return ProviderOperationResult.Success(
                if (current == null) CircuitAdministrationLoadResult.Missing else CircuitAdministrationLoadResult.Found(current),
            )
        }

        override suspend fun compareAndSet(
            request: CircuitAdministrationCompareAndSetRequest,
        ): ProviderOperationResult<CircuitAdministrationCompareAndSetResult> {
            val current = record
            val versionMatches = if (request.expectedVersion == null) current == null else current?.version == request.expectedVersion
            if (!versionMatches) {
                return ProviderOperationResult.Success(CircuitAdministrationCompareAndSetResult.Conflict(current))
            }
            val updated = CircuitAdministrationStateRecord(state = request.nextState, version = (current?.version ?: -1L) + 1L)
            record = updated
            return ProviderOperationResult.Success(CircuitAdministrationCompareAndSetResult.Updated(updated))
        }
    }

    private class RecordingCircuitAdministrationExecutor : CircuitAdministrationExecutor {
        override suspend fun execute(command: AuthorizedCircuitAdministrationCommand): CircuitAdministrationExecutionResult =
            CircuitAdministrationExecutionResult.Applied(
                CircuitBreakerStateRecord(
                    state = CircuitBreakerState(
                        scope = command.request.scope,
                        phase = CircuitBreakerPhase.CLOSED,
                        consecutiveFailures = 0,
                        failureWindowStartedAt = null,
                        openUntil = null,
                        probeGeneration = 0L,
                        probeInFlight = false,
                        updatedAt = DataLoomInstant(1_000_000L),
                    ),
                    version = 1L,
                ),
            )
    }

    private fun builder(): DataLoomBuilder = DataLoomBuilder()
        .runtimeDependencies(runtimeDependencies())
        .providers(NoChangesStorageProvider(), UnusedTransportProvider())
        .defaultProviderBindings(bindings())

    private fun bindings(): SynchronizationProviderBindings = SynchronizationProviderBindings(
        storageProviderId = ProviderId("storage-primary"),
        transportProviderId = ProviderId("transport-prod"),
    )

    private fun runtimeDependencies(): RuntimeDependencies = RuntimeDependencies(
        clock = FixedClock(),
        identifiers = RuntimeIdentifierGenerators(
            synchronizationEventIds = generator { io.dataloom.api.identifier.SynchronizationEventId("outbox-event") },
            queueEntryIds = generator { io.dataloom.api.identifier.QueueEntryId("outbox-queue-entry") },
            queueLeaseIds = generator { io.dataloom.api.identifier.QueueLeaseId("outbox-queue-lease") },
            conflictIds = generator { io.dataloom.api.identifier.ConflictId("outbox-conflict") },
        ),
    )

    private fun <T> generator(block: () -> T): io.dataloom.api.identifier.IdentifierGenerator<T> =
        object : io.dataloom.api.identifier.IdentifierGenerator<T> {
            override fun generate(): T = block()
        }

    private fun retryRequest(commandIdValue: String = "retry-outbox-command-001"): RetryAdministrationRequest =
        RetryAdministrationRequest(
            commandId = RetryAdministrationCommandId(commandIdValue),
            queueEntryId = io.dataloom.api.identifier.QueueEntryId("queue-failed-001"),
            principalId = RetryAdministrationPrincipalId("operator-001"),
            requestedAt = DataLoomInstant(999_000L),
            action = RetryAdministrationAction.REQUEUE,
            reason = RetryAdministrationReason("Operator requested a bounded retry."),
            originalFailure = RetryFailureSnapshot(
                code = ErrorCode("NETWORK_FINAL"),
                category = ErrorCategory.NETWORK,
                severity = ErrorSeverity.ERROR,
                recoverability = Recoverability.RECOVERABLE,
            ),
        )

    private fun circuitRequest(commandIdValue: String = "circuit-outbox-command-001"): CircuitAdministrationRequest =
        CircuitAdministrationRequest(
            commandId = CircuitAdministrationCommandId(commandIdValue),
            scope = CircuitBreakerScope.provider(ProviderId("transport-prod")),
            principalId = CircuitAdministrationPrincipalId("operator-001"),
            requestedAt = DataLoomInstant(999_000L),
            action = CircuitAdministrationAction.RESET,
            reason = CircuitAdministrationReason("Operator requested a bounded reset."),
        )

    @Test
    fun retryAdministration_appendsRealEntryWhenBothConfigured() = runTest {
        val outboxStore = InMemoryOperationalEventOutboxStore()
        val scope = OperationalEventOutboxScope("integration-retry-circuit-events")
        val dataLoom = builder()
            .retryAdministrationConfiguration(
                DataLoomRetryAdministrationSpec(
                    RecordingRetryAdministrationAuthorizer(),
                    InMemoryRetryAdministrationStateStore(),
                    RecordingRetryAdministrationExecutor(),
                ),
            )
            .retryCircuitAdministrationOperationalEventOutboxConfiguration(
                DataLoomRetryCircuitAdministrationOperationalEventOutboxSpec(store = outboxStore, scope = scope),
            )
            .build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())

        val result = checkNotNull(dataLoom.retryAdministration).execute(retryRequest())

        assertIs<RetryAdministrationResult.Succeeded>(result)
        assertEquals(1, outboxStore.recordedEntryCount(scope))
    }

    @Test
    fun circuitAdministration_appendsRealEntryWhenBothConfigured() = runTest {
        val outboxStore = InMemoryOperationalEventOutboxStore()
        val scope = OperationalEventOutboxScope("integration-retry-circuit-events")
        val dataLoom = builder()
            .circuitAdministrationConfiguration(
                DataLoomCircuitAdministrationSpec(
                    RecordingCircuitAdministrationAuthorizer(),
                    InMemoryCircuitAdministrationStateStore(),
                    RecordingCircuitAdministrationExecutor(),
                ),
            )
            .retryCircuitAdministrationOperationalEventOutboxConfiguration(
                DataLoomRetryCircuitAdministrationOperationalEventOutboxSpec(store = outboxStore, scope = scope),
            )
            .build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())

        val result = checkNotNull(dataLoom.circuitAdministration).execute(circuitRequest())

        assertIs<CircuitAdministrationResult.Succeeded>(result)
        assertEquals(1, outboxStore.recordedEntryCount(scope))
    }

    @Test
    fun bothProducers_shareOneOutboxWithoutCollision() = runTest {
        val outboxStore = InMemoryOperationalEventOutboxStore()
        val scope = OperationalEventOutboxScope("integration-shared-events")
        val dataLoom = builder()
            .retryAdministrationConfiguration(
                DataLoomRetryAdministrationSpec(
                    RecordingRetryAdministrationAuthorizer(),
                    InMemoryRetryAdministrationStateStore(),
                    RecordingRetryAdministrationExecutor(),
                ),
            )
            .circuitAdministrationConfiguration(
                DataLoomCircuitAdministrationSpec(
                    RecordingCircuitAdministrationAuthorizer(),
                    InMemoryCircuitAdministrationStateStore(),
                    RecordingCircuitAdministrationExecutor(),
                ),
            )
            .retryCircuitAdministrationOperationalEventOutboxConfiguration(
                DataLoomRetryCircuitAdministrationOperationalEventOutboxSpec(store = outboxStore, scope = scope),
            )
            .build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())

        checkNotNull(dataLoom.retryAdministration).execute(retryRequest(commandIdValue = "shared-001"))
        checkNotNull(dataLoom.circuitAdministration).execute(circuitRequest(commandIdValue = "shared-001"))

        assertEquals(2, outboxStore.recordedEntryCount(scope))
    }

    @Test
    fun nothingIsRecordedWhenOutboxIsNotConfigured() = runTest {
        val outboxStore = InMemoryOperationalEventOutboxStore()
        val scope = OperationalEventOutboxScope("integration-unconfigured-events")
        val dataLoom = builder()
            .retryAdministrationConfiguration(
                DataLoomRetryAdministrationSpec(
                    RecordingRetryAdministrationAuthorizer(),
                    InMemoryRetryAdministrationStateStore(),
                    RecordingRetryAdministrationExecutor(),
                ),
            )
            // Note: retryCircuitAdministrationOperationalEventOutboxConfiguration is never called.
            .build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())

        val result = checkNotNull(dataLoom.retryAdministration).execute(retryRequest())

        assertIs<RetryAdministrationResult.Succeeded>(result)
        assertEquals(0, outboxStore.recordedEntryCount(scope))
    }

    @Test
    fun nothingIsRecordedWhenAdministrationCapabilityIsNotConfigured() = runTest {
        val outboxStore = InMemoryOperationalEventOutboxStore()
        val scope = OperationalEventOutboxScope("integration-no-admin-events")
        val dataLoom = builder()
            // Note: neither retryAdministrationConfiguration nor circuitAdministrationConfiguration is called.
            .retryCircuitAdministrationOperationalEventOutboxConfiguration(
                DataLoomRetryCircuitAdministrationOperationalEventOutboxSpec(store = outboxStore, scope = scope),
            )
            .build()

        assertNull(dataLoom.retryAdministration)
        assertNull(dataLoom.circuitAdministration)
        assertEquals(0, outboxStore.recordedEntryCount(scope))
    }

    @Test
    fun retryAdministration_appendFailureDoesNotChangeTheRealResult() = runTest {
        val scope = OperationalEventOutboxScope("integration-failing-events")
        val dataLoom = builder()
            .retryAdministrationConfiguration(
                DataLoomRetryAdministrationSpec(
                    RecordingRetryAdministrationAuthorizer(),
                    InMemoryRetryAdministrationStateStore(),
                    RecordingRetryAdministrationExecutor(),
                ),
            )
            .retryCircuitAdministrationOperationalEventOutboxConfiguration(
                DataLoomRetryCircuitAdministrationOperationalEventOutboxSpec(
                    store = AlwaysFailingOperationalEventOutboxStore(),
                    scope = scope,
                ),
            )
            .build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())

        val result = checkNotNull(dataLoom.retryAdministration).execute(retryRequest())

        val succeeded = assertIs<RetryAdministrationResult.Succeeded>(result)
        assertEquals(1L, succeeded.record.version)
    }

    @Test
    fun circuitAdministration_appendFailureDoesNotChangeTheRealResult() = runTest {
        val scope = OperationalEventOutboxScope("integration-failing-events")
        val dataLoom = builder()
            .circuitAdministrationConfiguration(
                DataLoomCircuitAdministrationSpec(
                    RecordingCircuitAdministrationAuthorizer(),
                    InMemoryCircuitAdministrationStateStore(),
                    RecordingCircuitAdministrationExecutor(),
                ),
            )
            .retryCircuitAdministrationOperationalEventOutboxConfiguration(
                DataLoomRetryCircuitAdministrationOperationalEventOutboxSpec(
                    store = AlwaysFailingOperationalEventOutboxStore(),
                    scope = scope,
                ),
            )
            .build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())

        val result = checkNotNull(dataLoom.circuitAdministration).execute(circuitRequest())

        val succeeded = assertIs<CircuitAdministrationResult.Succeeded>(result)
        assertEquals(1L, succeeded.record.version)
    }
}
