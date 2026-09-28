package io.dataloom.runtime.facade

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.ChangeSet
import io.dataloom.api.change.EntityReference
import io.dataloom.api.circuit.CircuitBreakerCompareAndSetRequest
import io.dataloom.api.circuit.CircuitBreakerCompareAndSetResult
import io.dataloom.api.circuit.CircuitBreakerLoadResult
import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.circuit.CircuitBreakerStateRecord
import io.dataloom.api.circuit.CircuitBreakerStateStore
import io.dataloom.api.conflict.ResolvedConflictDecisionRecord
import io.dataloom.api.conflict.UnresolvedConflictRecord
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ChangeSetId
import io.dataloom.api.identifier.CheckpointKey
import io.dataloom.api.identifier.CheckpointToken
import io.dataloom.api.identifier.ConflictDetectorId
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.ConflictResolverId
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.IdentifierGenerator
import io.dataloom.api.identifier.QueueConsumerId
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.RetryPolicyId
import io.dataloom.api.identifier.SynchronizationEventId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.ChangeOperation
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
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
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.queue.QueueLease
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.runtime.RuntimeIdentifierGenerators
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateRecord
import io.dataloom.api.state.DurableStateStore
import io.dataloom.api.storage.InboundChangeApplyRequest
import io.dataloom.api.storage.LocalConflictCandidateReadRequest
import io.dataloom.api.storage.LocalConflictCandidateReadResult
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
import io.dataloom.runtime.conflict.ConflictOrchestrationBindings
import io.dataloom.runtime.queue.ProviderProtectedQueuedSynchronizationExecutionHandler
import io.dataloom.runtime.queue.QueueEntryExecutionOutcome
import io.dataloom.runtime.queue.QueuedSynchronizationWork
import io.dataloom.runtime.queue.QueuedSynchronizationWorkResolution
import io.dataloom.runtime.queue.QueuedSynchronizationWorkResolver
import io.dataloom.runtime.retry.CircuitBreakerConfiguration
import io.dataloom.runtime.retry.RetryBackoffStrategy
import io.dataloom.runtime.retry.StandardRetryPolicy
import io.dataloom.runtime.retry.StorageCircuitOperation
import io.dataloom.runtime.retry.StorageCircuitScopes
import io.dataloom.runtime.retry.SynchronizationRetryEvaluator
import io.dataloom.runtime.retry.TransportCircuitOperation
import io.dataloom.runtime.retry.TransportCircuitScopes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * End-to-end proof, through [DataLoomBuilder], that applying a resolved
 * conflict decision is retried by the production retry evaluator and
 * circuit-protected by the production provider-protection bridge exactly like
 * any other storage operation -- with no conflict-specific retry code.
 *
 * The object graph is the one a host gets from the builder: built-in conflict
 * detector and resolver, a durable resolved-decision log, and real circuit
 * breakers. Queue attempts are driven through
 * [ProviderProtectedQueuedSynchronizationExecutionHandler], the production
 * component that maps a protected synchronization result to a retry outcome.
 */
class DataLoomBuilderConflictRetryCircuitTest {

    private val entity = EntityReference(EntityType("invoice"), EntityId("inv-1"))
    private val localEvent = ChangeEvent(ChangeEventId("local-1"), entity, ChangeOperation.UPDATE)
    private val remoteEvent = ChangeEvent(ChangeEventId("remote-1"), entity, ChangeOperation.UPDATE)
    private val workflowId = WorkflowId("conflict-retry-workflow")
    private val bindings = SynchronizationProviderBindings(
        storageProviderId = ProviderId("storage-e2e"),
        transportProviderId = ProviderId("transport-e2e"),
    )

    private class MutableClock(var nowMillis: Long) : DataLoomClock {
        override fun now(): DataLoomInstant = DataLoomInstant(nowMillis)
    }

    private data class TestError(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val recoverability: Recoverability,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val message: String = "test",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private class Fixture(
        val dataLoom: DataLoom,
        val storage: ScriptedStorage,
        val resolvedStore: InMemoryStore<ConflictId, ResolvedConflictDecisionRecord>,
        val clock: MutableClock,
        val handler: ProviderProtectedQueuedSynchronizationExecutionHandler,
    )

    private fun fixture(
        maximumRetryAttempts: Int,
        circuitFailureThreshold: Int = 50,
    ): Fixture {
        val clock = MutableClock(1_000_000L)
        val storage = ScriptedStorage(localEvent)
        val transport = FixedPullTransport(
            PullChangesResult.Changes(
                changeSet = ChangeSet(ChangeSetId("remote-batch"), listOf(remoteEvent)),
                hasMore = false,
                nextCheckpoint = SynchronizationCheckpoint(CheckpointKey(workflowId.value), CheckpointToken("next")),
            ),
        )
        val resolvedStore = InMemoryStore<ConflictId, ResolvedConflictDecisionRecord>()
        val circuitConfiguration = CircuitBreakerConfiguration(
            failureThreshold = circuitFailureThreshold,
            failureWindow = SchedulingDelay(600_000L),
            openDuration = SchedulingDelay(10_000L),
        )
        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(runtimeDependencies(clock))
            .providers(storage, transport)
            .defaultProviderBindings(bindings)
            .conflictDetectionConfiguration(
                DataLoomConflictDetectionSpec(
                    detectors = emptyList(),
                    resolvers = emptyList(),
                    bindings = ConflictOrchestrationBindings(
                        detectorId = ConflictDetectorId("dataloom.builtin.operation"),
                        resolverId = ConflictResolverId("dataloom.builtin.server-wins"),
                    ),
                    unresolvedConflictStore = InMemoryStore<ConflictId, UnresolvedConflictRecord>(),
                    resolvedConflictDecisionStore = resolvedStore,
                ),
            )
            .providerProtectionConfiguration(
                DataLoomProviderProtectionSpec(
                    storage = DataLoomStorageProtectionSpec(
                        circuitBreakerConfiguration = circuitConfiguration,
                        circuitBreakerStateStore = InMemoryCircuitStore(),
                        scopes = storageScopes(storage.descriptor.id),
                    ),
                    transport = DataLoomTransportProtectionSpec(
                        circuitBreakerConfiguration = circuitConfiguration,
                        circuitBreakerStateStore = InMemoryCircuitStore(),
                        scopes = transportScopes(transport.descriptor.id),
                    ),
                ),
            )
            .build()
        val handler = ProviderProtectedQueuedSynchronizationExecutionHandler(
            workResolver = QueuedSynchronizationWorkResolver {
                QueuedSynchronizationWorkResolution.Resolved(QueuedSynchronizationWork(pullRequest(), bindings))
            },
            protectedSynchronization = checkNotNull(dataLoom.protectedSynchronization),
            retryEvaluator = SynchronizationRetryEvaluator(
                retryPolicy = StandardRetryPolicy(
                    id = RetryPolicyId("conflict-retry-e2e"),
                    strategy = RetryBackoffStrategy.Immediate,
                    maximumAttempts = maximumRetryAttempts,
                ),
                clock = clock,
            ),
            retryOperation = RetryOperation("queued.synchronize"),
            clock = clock,
        )
        return Fixture(dataLoom, storage, resolvedStore, clock, handler)
    }

    private fun entry(retryAttempt: Int): QueueEntry = QueueEntry(
        id = QueueEntryId("entry-1"),
        synchronizationRequest = pullRequest(),
        state = QueueEntryState.LEASED,
        enqueuedAt = DataLoomInstant(1_000L),
        availableAt = DataLoomInstant(1_000L),
        lease = QueueLease(
            id = QueueLeaseId("lease-1"),
            consumerId = QueueConsumerId("consumer-1"),
            acquiredAt = DataLoomInstant(1_500L),
            expiresAt = DataLoomInstant(9_000_000L),
        ),
        retryAttempt = if (retryAttempt == 0) null else RetryAttempt(retryAttempt),
    )

    private fun transient(code: String) = TestError(ErrorCode(code), ErrorCategory.STORAGE, Recoverability.RECOVERABLE)

    @Test
    fun transientApplyFailureIsRetriedThenSucceedsWithTheReplayedDecision() = runBlocking {
        val f = fixture(maximumRetryAttempts = 3)
        assertIs<ProviderLifecycleResult.InitializeSuccess>(f.dataLoom.initialize())
        f.storage.failNextApplies(1, transient("STORAGE-BUSY"))

        val first = f.handler.execute(entry(retryAttempt = 0)).outcome
        val rescheduled = assertIs<QueueEntryExecutionOutcome.Reschedule>(first)
        assertEquals("STORAGE-BUSY", rescheduled.error.code.value)
        assertEquals(0, f.storage.checkpointWrites, "a failed apply must not advance the checkpoint")

        val second = f.handler.execute(entry(retryAttempt = rescheduled.retryAttempt.number)).outcome
        assertIs<QueueEntryExecutionOutcome.Completed>(second)
        assertEquals(listOf(remoteEvent), f.storage.lastApplied)
        assertEquals(2, f.storage.applyCalls)
        assertEquals(1, f.storage.checkpointWrites)
        assertEquals(1, f.resolvedStore.size(), "one decision, recorded once and replayed")
    }

    @Test
    fun exhaustedRetriesFailClosedAndNeverAdvanceTheCheckpoint() = runBlocking {
        val f = fixture(maximumRetryAttempts = 2)
        assertIs<ProviderLifecycleResult.InitializeSuccess>(f.dataLoom.initialize())
        f.storage.failNextApplies(10, transient("STORAGE-DOWN"))

        var attempt = 0
        var last: QueueEntryExecutionOutcome
        do {
            last = f.handler.execute(entry(attempt)).outcome
            if (last is QueueEntryExecutionOutcome.Reschedule) attempt = last.retryAttempt.number
        } while (last is QueueEntryExecutionOutcome.Reschedule && attempt < 10)

        val failed = assertIs<QueueEntryExecutionOutcome.Failed>(last)
        assertEquals("STORAGE-DOWN", failed.error.code.value)
        assertEquals(3, f.storage.applyCalls, "the original attempt plus two retries")
        assertEquals(0, f.storage.checkpointWrites)
    }

    @Test
    fun nonRetryableApplyFailureFailsClosedImmediately() = runBlocking {
        val f = fixture(maximumRetryAttempts = 5)
        assertIs<ProviderLifecycleResult.InitializeSuccess>(f.dataLoom.initialize())
        f.storage.failNextApplies(
            1,
            TestError(ErrorCode("STORAGE-CORRUPT"), ErrorCategory.STORAGE, Recoverability.NON_RECOVERABLE),
        )

        val outcome = f.handler.execute(entry(0)).outcome

        val failed = assertIs<QueueEntryExecutionOutcome.Failed>(outcome)
        assertEquals("STORAGE-CORRUPT", failed.error.code.value)
        assertEquals(1, f.storage.applyCalls)
        assertEquals(0, f.storage.checkpointWrites)
    }

    @Test
    fun openCircuitBlocksTheProviderThenRecoversAfterTheOpenDuration() = runBlocking {
        val f = fixture(maximumRetryAttempts = 10, circuitFailureThreshold = 2)
        assertIs<ProviderLifecycleResult.InitializeSuccess>(f.dataLoom.initialize())
        f.storage.failNextApplies(2, transient("STORAGE-BUSY"))

        val a1 = assertIs<QueueEntryExecutionOutcome.Reschedule>(f.handler.execute(entry(0)).outcome)
        val a2 = assertIs<QueueEntryExecutionOutcome.Reschedule>(f.handler.execute(entry(a1.retryAttempt.number)).outcome)
        assertEquals(2, f.storage.applyCalls)

        // Two recorded failures tripped the circuit: the next attempt is rejected
        // before the provider is invoked, and is still a retryable outcome.
        val blocked = assertIs<QueueEntryExecutionOutcome.Reschedule>(
            f.handler.execute(entry(a2.retryAttempt.number)).outcome,
        )
        assertEquals("PROVIDER_CIRCUIT_OPEN", blocked.error.code.value)
        assertEquals(2, f.storage.applyCalls, "the provider must not be called while the circuit is open")
        assertEquals(0, f.storage.checkpointWrites)

        // After the open duration a half-open probe succeeds and the replayed
        // decision is finally applied and checkpointed.
        f.clock.nowMillis += 10_001L
        val recovered = f.handler.execute(entry(blocked.retryAttempt.number)).outcome
        assertIs<QueueEntryExecutionOutcome.Completed>(recovered)
        assertEquals(3, f.storage.applyCalls)
        assertEquals(1, f.storage.checkpointWrites)
        assertEquals(listOf(remoteEvent), f.storage.lastApplied)
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private fun pullRequest() = SynchronizationRequest(
        workflowId = workflowId,
        sessionId = SynchronizationSessionId("conflict-retry-session"),
        direction = SynchronizationDirection.PULL,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("conflict-retry-execution"),
            correlationId = CorrelationId("conflict-retry-correlation"),
        ),
    )

    private fun runtimeDependencies(clock: DataLoomClock) = RuntimeDependencies(
        clock = clock,
        identifiers = RuntimeIdentifierGenerators(
            synchronizationEventIds = generator { SynchronizationEventId("event-1") },
            queueEntryIds = generator { QueueEntryId("queue-1") },
            queueLeaseIds = generator { QueueLeaseId("lease-1") },
            conflictIds = generator { ConflictId("conflict-1") },
        ),
    )

    private fun <T> generator(block: () -> T): IdentifierGenerator<T> =
        object : IdentifierGenerator<T> {
            override fun generate(): T = block()
        }

    private fun storageScopes(providerId: ProviderId): StorageCircuitScopes {
        fun scope(operation: StorageCircuitOperation) =
            CircuitBreakerScope.providerOperation(providerId, operation.retryOperation)
        return StorageCircuitScopes(
            initialization = scope(StorageCircuitOperation.INITIALIZE),
            health = scope(StorageCircuitOperation.HEALTH),
            close = scope(StorageCircuitOperation.CLOSE),
            readOutboundChanges = scope(StorageCircuitOperation.READ_OUTBOUND_CHANGES),
            applyInboundChanges = scope(StorageCircuitOperation.APPLY_INBOUND_CHANGES),
            acknowledgeOutboundChanges = scope(StorageCircuitOperation.ACKNOWLEDGE_OUTBOUND_CHANGES),
            readCheckpoint = scope(StorageCircuitOperation.READ_CHECKPOINT),
            writeCheckpoint = scope(StorageCircuitOperation.WRITE_CHECKPOINT),
            readLocalConflictCandidate = scope(StorageCircuitOperation.READ_LOCAL_CONFLICT_CANDIDATE),
        )
    }

    private fun transportScopes(providerId: ProviderId): TransportCircuitScopes {
        fun scope(operation: TransportCircuitOperation) =
            CircuitBreakerScope.providerOperation(providerId, operation.retryOperation)
        return TransportCircuitScopes(
            initialization = scope(TransportCircuitOperation.INITIALIZE),
            health = scope(TransportCircuitOperation.HEALTH),
            close = scope(TransportCircuitOperation.CLOSE),
            pushChanges = scope(TransportCircuitOperation.PUSH_CHANGES),
            pullChanges = scope(TransportCircuitOperation.PULL_CHANGES),
        )
    }

    private class InMemoryStore<S : Any, R : Any> : DurableStateStore<S, R> {
        private val records = mutableMapOf<S, DurableStateRecord<R>>()

        fun size(): Int = records.size

        override suspend fun load(scope: S): ProviderOperationResult<DurableStateLoadResult<R>> {
            val record = records[scope]
            return ProviderOperationResult.Success(
                if (record == null) DurableStateLoadResult.Missing else DurableStateLoadResult.Found(record),
            )
        }

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<S, R>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<R>> {
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

    private class InMemoryCircuitStore : CircuitBreakerStateStore {
        private val records = mutableMapOf<CircuitBreakerScope, CircuitBreakerStateRecord>()

        override suspend fun load(
            scope: CircuitBreakerScope,
        ): ProviderOperationResult<CircuitBreakerLoadResult> {
            val record = records[scope]
            return ProviderOperationResult.Success(
                if (record == null) CircuitBreakerLoadResult.Missing else CircuitBreakerLoadResult.Found(record),
            )
        }

        override suspend fun compareAndSet(
            request: CircuitBreakerCompareAndSetRequest,
        ): ProviderOperationResult<CircuitBreakerCompareAndSetResult> {
            val current = records[request.scope]
            if (current?.version != request.expectedVersion) {
                return ProviderOperationResult.Success(CircuitBreakerCompareAndSetResult.Conflict(current))
            }
            val updated = CircuitBreakerStateRecord(
                state = request.nextState,
                version = (current?.version ?: -1L) + 1L,
            )
            records[request.scope] = updated
            return ProviderOperationResult.Success(CircuitBreakerCompareAndSetResult.Updated(updated))
        }
    }

    private class ScriptedStorage(private val localCandidate: ChangeEvent) : StorageProvider {
        private var remainingFailures = 0
        private var failure: DataLoomError? = null
        var applyCalls = 0
            private set
        var checkpointWrites = 0
            private set
        var lastApplied: List<ChangeEvent> = emptyList()
            private set

        fun failNextApplies(count: Int, error: DataLoomError) {
            remainingFailures = count
            failure = error
        }

        override val descriptor = ProviderDescriptor(
            id = ProviderId("storage-e2e"),
            name = ProviderName("E2E storage"),
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

        override suspend fun applyInboundChanges(request: InboundChangeApplyRequest): ProviderOperationResult<Unit> {
            applyCalls++
            if (remainingFailures > 0) {
                remainingFailures--
                return ProviderOperationResult.Failure(checkNotNull(failure))
            }
            lastApplied = request.changeSet.events
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun acknowledgeOutboundChanges(
            request: OutboundChangeAcknowledgementRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun readCheckpoint(
            request: CheckpointReadRequest,
        ): ProviderOperationResult<SynchronizationCheckpoint?> = ProviderOperationResult.Success(null)

        override suspend fun writeCheckpoint(request: CheckpointWriteRequest): ProviderOperationResult<Unit> {
            checkpointWrites++
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun readLocalConflictCandidate(
            request: LocalConflictCandidateReadRequest,
        ): ProviderOperationResult<LocalConflictCandidateReadResult> =
            ProviderOperationResult.Success(LocalConflictCandidateReadResult.Found(localCandidate))
    }

    private class FixedPullTransport(private val pull: PullChangesResult) : TransportProvider {
        override val descriptor = ProviderDescriptor(
            id = ProviderId("transport-e2e"),
            name = ProviderName("E2E transport"),
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
        ): ProviderOperationResult<ChangeSetAcknowledgement> =
            error("push is not used by this pull-only test")

        override suspend fun pullChanges(request: PullChangesRequest): ProviderOperationResult<PullChangesResult> =
            ProviderOperationResult.Success(pull)
    }
}
