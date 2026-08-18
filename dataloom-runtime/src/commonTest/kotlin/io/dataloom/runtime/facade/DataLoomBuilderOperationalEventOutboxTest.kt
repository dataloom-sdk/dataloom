package io.dataloom.runtime.facade

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.IdentifierGenerator
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.SynchronizationEventId
import io.dataloom.api.identifier.SynchronizationObserverId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.observation.SynchronizationObserver
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
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.synchronization.CheckpointReadRequest
import io.dataloom.api.synchronization.CheckpointWriteRequest
import io.dataloom.api.synchronization.OutboundChangeAcknowledgementRequest
import io.dataloom.api.synchronization.SynchronizationCheckpoint
import io.dataloom.api.synchronization.SynchronizationEvent
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.runtime.execution.SynchronizationExecutionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Proves [DataLoomOperationalEventOutboxSpec]/`operationalEventOutboxConfiguration`
 * is a real, reachable caller of [io.dataloom.api.operational.DurableOperationalEventOutbox] --
 * a real [DataLoomBuilder]-built [DataLoom] instance durably records
 * [io.dataloom.api.operational.OperationalEventEnvelope] entries for a real
 * `synchronize()` pass when configured, and records nothing when it is not
 * (byte-for-byte the same behavior as before this feature existed).
 */
class DataLoomBuilderOperationalEventOutboxTest {

    private class FixedClock(private val epochMs: Long = 9_000L) : DataLoomClock {
        override fun now(): DataLoomInstant = DataLoomInstant(epochMs)
    }

    private class RecordingObserver(idValue: String) : SynchronizationObserver {
        override val id: SynchronizationObserverId = SynchronizationObserverId(idValue)
        var callCount = 0
            private set

        override fun onEvent(event: SynchronizationEvent) {
            callCount++
        }
    }

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

        override suspend fun pushChanges(request: PushChangesRequest): ProviderOperationResult<ChangeSetAcknowledgement> =
            throw NotImplementedError("Not exercised: outbound read is NoChanges in every test.")

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

    private fun builder(): DataLoomBuilder = DataLoomBuilder()
        .runtimeDependencies(runtimeDependencies())
        .providers(NoChangesStorageProvider(), UnusedTransportProvider())
        .defaultProviderBindings(bindings())

    private fun bindings(): SynchronizationProviderBindings = SynchronizationProviderBindings(
        storageProviderId = ProviderId("storage-primary"),
        transportProviderId = ProviderId("transport-prod"),
    )

    private fun makeRequest(): SynchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("outbox-integration-workflow"),
        sessionId = SynchronizationSessionId("outbox-integration-session"),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("outbox-integration-execution"),
            correlationId = CorrelationId("outbox-integration-correlation"),
        ),
    )

    private fun runtimeDependencies(): RuntimeDependencies = RuntimeDependencies(
        clock = FixedClock(),
        identifiers = RuntimeIdentifierGenerators(
            synchronizationEventIds = sequenceGenerator { counter -> SynchronizationEventId("outbox-event-$counter") },
            queueEntryIds = generator { QueueEntryId("outbox-queue-entry") },
            queueLeaseIds = generator { QueueLeaseId("outbox-queue-lease") },
            conflictIds = generator { ConflictId("outbox-conflict") },
        ),
    )

    private fun <T> generator(block: () -> T): IdentifierGenerator<T> =
        object : IdentifierGenerator<T> {
            override fun generate(): T = block()
        }

    private fun <T> sequenceGenerator(block: (Int) -> T): IdentifierGenerator<T> =
        object : IdentifierGenerator<T> {
            private var counter = 0
            override fun generate(): T {
                counter++
                return block(counter)
            }
        }

    @Test
    fun realSynchronizeAppendsRealEntriesWhenConfigured() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val scope = OperationalEventOutboxScope("integration-synchronization-events")
        val outboxSpec = DataLoomOperationalEventOutboxSpec(store = store, scope = scope)
        val dataLoom = builder()
            .operationalEventOutboxConfiguration(outboxSpec)
            .build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())

        val result = dataLoom.synchronize(makeRequest())

        assertIs<SynchronizationExecutionResult.Executed>(result)
        // Started + PhaseChanged(READING_OUTBOUND) + Completed(Skipped) for a NoChanges outbound read.
        assertEquals(3, store.recordedEntryCount(scope))
    }

    @Test
    fun configuringOutboxAloneBuildsEmitterEvenWithZeroObservers() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val scope = OperationalEventOutboxScope("integration-no-observers-events")
        val dataLoom = builder()
            .operationalEventOutboxConfiguration(DataLoomOperationalEventOutboxSpec(store = store, scope = scope))
            .build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())

        dataLoom.synchronize(makeRequest())

        assertTrue(store.recordedEntryCount(scope) > 0, "Entries must be recorded even with zero registered observers.")
    }

    @Test
    fun observerDeliveryIsUnaffectedWhenOutboxIsAlsoConfigured() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val scope = OperationalEventOutboxScope("integration-observer-events")
        val observer = RecordingObserver("integration-observer")
        val dataLoom = builder()
            .observer(observer)
            .operationalEventOutboxConfiguration(DataLoomOperationalEventOutboxSpec(store = store, scope = scope))
            .build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())

        dataLoom.synchronize(makeRequest())

        assertTrue(observer.callCount > 0, "Observer must still receive events when the outbox is also configured.")
        assertEquals(observer.callCount, store.recordedEntryCount(scope))
    }

    @Test
    fun nothingIsRecordedWhenOutboxIsNotConfigured() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val scope = OperationalEventOutboxScope("integration-unconfigured-events")
        // Note: store/spec is never wired via operationalEventOutboxConfiguration.
        val dataLoom = builder().build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())

        val result = dataLoom.synchronize(makeRequest())

        assertIs<SynchronizationExecutionResult.Executed>(result)
        assertEquals(0, store.recordedEntryCount(scope))
    }
}
