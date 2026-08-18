package io.dataloom.runtime.execution.lifecycle

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
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
import io.dataloom.api.operational.DurableOperationalEventOutbox
import io.dataloom.api.operational.OperationalEventOutboxScope
import io.dataloom.api.operational.OperationalEventOutboxState
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
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
import io.dataloom.runtime.execution.SynchronizationExecutionContext
import io.dataloom.runtime.observation.SynchronizationEventDispatchResult
import io.dataloom.runtime.observation.SynchronizationEventDispatcher
import io.dataloom.runtime.observation.SynchronizationObserverRegistry
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Proves [DispatchingSynchronizationLifecycleEventEmitter]'s optional
 * operational-event-outbox bridge is a real, reachable caller of
 * [DurableOperationalEventOutbox] when configured, that append/envelope
 * failures never affect the returned [SynchronizationEventDispatchResult] or
 * propagate as an exception (except [CancellationException]), and that
 * nothing changes when the bridge is not configured.
 */
class DispatchingSynchronizationLifecycleEventEmitterOperationalOutboxTest {

    private val scope = OperationalEventOutboxScope("test-synchronization-events")

    private class FixedClock(private val epochMs: Long = 1_000_000L) : DataLoomClock {
        var callCount = 0
            private set

        override fun now(): DataLoomInstant {
            callCount++
            return DataLoomInstant(epochMs)
        }
    }

    private class SequenceIdGenerator : IdentifierGenerator<SynchronizationEventId> {
        private var counter = 0
        var callCount = 0
            private set

        override fun generate(): SynchronizationEventId {
            counter++
            callCount++
            return SynchronizationEventId("event-$counter")
        }
    }

    private class RecordingObserver(idValue: String) : SynchronizationObserver {
        override val id: SynchronizationObserverId = SynchronizationObserverId(idValue)
        var callCount = 0
            private set

        override fun onEvent(event: SynchronizationEvent) {
            callCount++
        }
    }

    private class CancellingObserver(idValue: String) : SynchronizationObserver {
        override val id: SynchronizationObserverId = SynchronizationObserverId(idValue)

        override fun onEvent(event: SynchronizationEvent) {
            throw CancellationException("Cancellation from observer in test.")
        }
    }

    /** Ordinary in-memory [DurableStateStore] fixture -- appends really persist. */
    private class InMemoryOperationalEventOutboxStore :
        DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState> {
        private val records = mutableMapOf<OperationalEventOutboxScope, DurableStateRecord<OperationalEventOutboxState>>()

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

    /** [load] always returns [ProviderOperationResult.Failure] -- append reports PersistenceFailure, never throws. */
    private class PersistenceFailureOperationalEventOutboxStore :
        DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState> {
        private val fakeError = object : DataLoomError {
            override val code = ErrorCode("STORE-DOWN")
            override val category = ErrorCategory.STORAGE
            override val severity = ErrorSeverity.ERROR
            override val recoverability = Recoverability.RECOVERABLE
            override val message = "Store unavailable in test."
            override val cause: Throwable? = null
        }

        override suspend fun load(
            scope: OperationalEventOutboxScope,
        ): ProviderOperationResult<DurableStateLoadResult<OperationalEventOutboxState>> =
            ProviderOperationResult.Failure(fakeError)

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<OperationalEventOutboxScope, OperationalEventOutboxState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<OperationalEventOutboxState>> =
            ProviderOperationResult.Failure(fakeError)
    }

    /** [load] throws an ordinary exception -- proves the emitter's swallow boundary, not just the outbox's own outcome type. */
    private class ThrowingOperationalEventOutboxStore :
        DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState> {
        override suspend fun load(
            scope: OperationalEventOutboxScope,
        ): ProviderOperationResult<DurableStateLoadResult<OperationalEventOutboxState>> {
            throw IllegalStateException("Store threw in test.")
        }

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<OperationalEventOutboxScope, OperationalEventOutboxState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<OperationalEventOutboxState>> {
            throw IllegalStateException("Store threw in test.")
        }
    }

    /** Never actually invoked by these tests -- only satisfies [SynchronizationExecutionContext]'s required types. */
    private class NoopStorageProvider : StorageProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("storage-outbox-test"),
            name = ProviderName("Storage Outbox Test"),
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

    /** Never actually invoked by these tests -- only satisfies [SynchronizationExecutionContext]'s required types. */
    private class NoopTransportProvider : TransportProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("transport-outbox-test"),
            name = ProviderName("Transport Outbox Test"),
            type = ProviderType.TRANSPORT,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun pushChanges(request: PushChangesRequest): ProviderOperationResult<ChangeSetAcknowledgement> =
            throw NotImplementedError("Never invoked by these tests.")

        override suspend fun pullChanges(request: PullChangesRequest): ProviderOperationResult<PullChangesResult> =
            ProviderOperationResult.Success(PullChangesResult.NoChanges())
    }

    private fun makeRequest(): SynchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("workflow-outbox-test"),
        sessionId = SynchronizationSessionId("session-outbox-test"),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("execution-outbox-test"),
            correlationId = CorrelationId("correlation-outbox-test"),
        ),
    )

    private fun makeContext(): SynchronizationExecutionContext = SynchronizationExecutionContext(
        request = makeRequest(),
        providers = io.dataloom.core.provider.ResolvedSynchronizationProviders(
            storageProvider = NoopStorageProvider(),
            transportProvider = NoopTransportProvider(),
            schedulerProvider = null,
            connectivityProvider = null,
            queueProvider = null,
        ),
        runtimeDependencies = io.dataloom.api.runtime.RuntimeDependencies(
            clock = FixedClock(),
            identifiers = io.dataloom.api.runtime.RuntimeIdentifierGenerators(
                synchronizationEventIds = SequenceIdGenerator(),
                queueEntryIds = object : IdentifierGenerator<QueueEntryId> {
                    override fun generate() = QueueEntryId("queue-outbox-test")
                },
                queueLeaseIds = object : IdentifierGenerator<QueueLeaseId> {
                    override fun generate() = QueueLeaseId("lease-outbox-test")
                },
                conflictIds = object : IdentifierGenerator<ConflictId> {
                    override fun generate() = ConflictId("conflict-outbox-test")
                },
            ),
        ),
        lifecycleEventEmitter = null,
    )

    private fun makeDispatcher(observer: SynchronizationObserver? = null): SynchronizationEventDispatcher {
        val observers = if (observer != null) listOf(observer) else emptyList()
        return SynchronizationEventDispatcher(SynchronizationObserverRegistry(observers))
    }

    // -------------------------------------------------------------------------
    // Real wiring: entries actually appear
    // -------------------------------------------------------------------------

    @Test
    fun emitStarted_withOutboxAndScopeConfigured_durablyAppendsAnEnvelope() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val outbox = DurableOperationalEventOutbox(store)
        val clock = FixedClock()
        val idGen = SequenceIdGenerator()
        val emitter = DispatchingSynchronizationLifecycleEventEmitter(
            dispatcher = makeDispatcher(),
            clock = clock,
            eventIdGenerator = idGen,
            operationalEventOutbox = outbox,
            operationalEventOutboxScope = scope,
        )

        emitter.emitStarted(makeContext())

        val entries = outbox.entries(scope)
        assertIs<ProviderOperationResult.Success<List<io.dataloom.api.operational.OperationalEventEnvelope>>>(entries)
        assertEquals(1, entries.value.size)
        assertEquals("dataloom.synchronization.started", entries.value[0].type.value)
    }

    @Test
    fun emitStarted_recordingIsAdditiveToObserverDelivery() = runTest {
        val observer = RecordingObserver("obs-outbox")
        val store = InMemoryOperationalEventOutboxStore()
        val outbox = DurableOperationalEventOutbox(store)
        val emitter = DispatchingSynchronizationLifecycleEventEmitter(
            dispatcher = makeDispatcher(observer),
            clock = FixedClock(),
            eventIdGenerator = SequenceIdGenerator(),
            operationalEventOutbox = outbox,
            operationalEventOutboxScope = scope,
        )

        val result = emitter.emitStarted(makeContext())

        assertIs<SynchronizationEventDispatchResult.Delivered>(result)
        assertEquals(1, observer.callCount)
        val entries = outbox.entries(scope)
        assertIs<ProviderOperationResult.Success<List<io.dataloom.api.operational.OperationalEventEnvelope>>>(entries)
        assertEquals(1, entries.value.size)
    }

    @Test
    fun emitStarted_doesNotReadClockOrGenerateIdAgainForTheBridge() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val outbox = DurableOperationalEventOutbox(store)
        val clock = FixedClock()
        val idGen = SequenceIdGenerator()
        val emitter = DispatchingSynchronizationLifecycleEventEmitter(
            dispatcher = makeDispatcher(),
            clock = clock,
            eventIdGenerator = idGen,
            operationalEventOutbox = outbox,
            operationalEventOutboxScope = scope,
        )

        emitter.emitStarted(makeContext())

        assertEquals(1, clock.callCount, "The bridge must reuse the already-read occurredAt, not read the clock again.")
        assertEquals(1, idGen.callCount, "The bridge must reuse the already-generated event ID, not generate a new one.")
    }

    // -------------------------------------------------------------------------
    // Swallow boundary: append failure never affects the dispatch result
    // -------------------------------------------------------------------------

    @Test
    fun emitStarted_persistenceFailureIsSwallowed_dispatchResultUnaffected() = runTest {
        val outbox = DurableOperationalEventOutbox(PersistenceFailureOperationalEventOutboxStore())
        val observer = RecordingObserver("obs-swallow")
        val emitter = DispatchingSynchronizationLifecycleEventEmitter(
            dispatcher = makeDispatcher(observer),
            clock = FixedClock(),
            eventIdGenerator = SequenceIdGenerator(),
            operationalEventOutbox = outbox,
            operationalEventOutboxScope = scope,
        )

        val result = emitter.emitStarted(makeContext())

        assertIs<SynchronizationEventDispatchResult.Delivered>(result)
        assertEquals(1, observer.callCount, "Observer delivery must be unaffected by an outbox persistence failure.")
    }

    @Test
    fun emitStarted_storeThrowing_doesNotPropagateAndDoesNotAffectDispatchResult() = runTest {
        val outbox = DurableOperationalEventOutbox(ThrowingOperationalEventOutboxStore())
        val observer = RecordingObserver("obs-throw")
        val emitter = DispatchingSynchronizationLifecycleEventEmitter(
            dispatcher = makeDispatcher(observer),
            clock = FixedClock(),
            eventIdGenerator = SequenceIdGenerator(),
            operationalEventOutbox = outbox,
            operationalEventOutboxScope = scope,
        )

        val result = emitter.emitStarted(makeContext())

        assertIs<SynchronizationEventDispatchResult.Delivered>(result)
        assertEquals(1, observer.callCount)
    }

    @Test
    fun emitStarted_cancellationFromObserverStillPropagates_recordingNeverReached() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val outbox = DurableOperationalEventOutbox(store)
        val emitter = DispatchingSynchronizationLifecycleEventEmitter(
            dispatcher = makeDispatcher(CancellingObserver("cancel-obs")),
            clock = FixedClock(),
            eventIdGenerator = SequenceIdGenerator(),
            operationalEventOutbox = outbox,
            operationalEventOutboxScope = scope,
        )

        var threw = false
        try {
            emitter.emitStarted(makeContext())
        } catch (expected: CancellationException) {
            threw = true
        }

        assertTrue(threw, "CancellationException must still propagate.")
        val entries = outbox.entries(scope)
        assertIs<ProviderOperationResult.Success<List<io.dataloom.api.operational.OperationalEventEnvelope>>>(entries)
        assertEquals(0, entries.value.size, "Nothing should be recorded when dispatch itself is cancelled.")
    }

    // -------------------------------------------------------------------------
    // Not configured -- zero behavior change
    // -------------------------------------------------------------------------

    @Test
    fun emitStarted_withNeitherOutboxNorScopeConfigured_performsNoWork() = runTest {
        val observer = RecordingObserver("obs-unconfigured")
        val emitter = DispatchingSynchronizationLifecycleEventEmitter(
            dispatcher = makeDispatcher(observer),
            clock = FixedClock(),
            eventIdGenerator = SequenceIdGenerator(),
        )

        val result = emitter.emitStarted(makeContext())

        assertIs<SynchronizationEventDispatchResult.Delivered>(result)
        assertEquals(1, observer.callCount)
    }

    @Test
    fun emitStarted_withOutboxButNoScope_performsNoWork() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val outbox = DurableOperationalEventOutbox(store)
        val emitter = DispatchingSynchronizationLifecycleEventEmitter(
            dispatcher = makeDispatcher(),
            clock = FixedClock(),
            eventIdGenerator = SequenceIdGenerator(),
            operationalEventOutbox = outbox,
            operationalEventOutboxScope = null,
        )

        emitter.emitStarted(makeContext())

        val entries = outbox.entries(scope)
        assertIs<ProviderOperationResult.Success<List<io.dataloom.api.operational.OperationalEventEnvelope>>>(entries)
        assertEquals(0, entries.value.size)
    }

    @Test
    fun emitStarted_withScopeButNoOutbox_performsNoWork() = runTest {
        val observer = RecordingObserver("obs-scope-only")
        val emitter = DispatchingSynchronizationLifecycleEventEmitter(
            dispatcher = makeDispatcher(observer),
            clock = FixedClock(),
            eventIdGenerator = SequenceIdGenerator(),
            operationalEventOutbox = null,
            operationalEventOutboxScope = scope,
        )

        val result = emitter.emitStarted(makeContext())

        assertIs<SynchronizationEventDispatchResult.Delivered>(result)
        assertEquals(1, observer.callCount)
    }
}
