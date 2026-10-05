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
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ChangeSetId
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.IdentifierGenerator
import io.dataloom.api.identifier.QueueConsumerId
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.RetryPolicyId
import io.dataloom.api.identifier.ScheduleId
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
import io.dataloom.api.queue.ExpiredLeaseRecoveryRequest
import io.dataloom.api.queue.ExpiredLeaseRecoveryResult
import io.dataloom.api.queue.QueueAcquireRequest
import io.dataloom.api.queue.QueueAcquireResult
import io.dataloom.api.queue.QueueCancellationRequest
import io.dataloom.api.queue.QueueCompletionRequest
import io.dataloom.api.queue.QueueDeferralRequest
import io.dataloom.api.queue.QueueEnqueueRequest
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.queue.QueueFailureRequest
import io.dataloom.api.queue.QueueLease
import io.dataloom.api.queue.QueueProvider
import io.dataloom.api.queue.QueueRescheduleRequest
import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.runtime.RuntimeIdentifierGenerators
import io.dataloom.api.scheduling.ExistingSchedulePolicy
import io.dataloom.api.scheduling.ScheduleConstraints
import io.dataloom.api.scheduling.SchedulingDelay
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
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.runtime.queue.CircuitBreakerQueueProcessingResult
import io.dataloom.runtime.queue.QueueProcessingCircuitScopes
import io.dataloom.runtime.queue.QueueProcessingRequest
import io.dataloom.runtime.queue.QueueProcessingResult
import io.dataloom.runtime.queue.QueuedSynchronizationWork
import io.dataloom.runtime.queue.QueuedSynchronizationWorkResolution
import io.dataloom.runtime.queue.QueuedSynchronizationWorkResolver
import io.dataloom.runtime.retry.CircuitBreakerConfiguration
import io.dataloom.runtime.retry.QueueCircuitOperation
import io.dataloom.runtime.retry.RetryBackoffStrategy
import io.dataloom.runtime.retry.StandardRetryPolicy
import io.dataloom.runtime.retry.StorageCircuitOperation
import io.dataloom.runtime.retry.StorageCircuitScopes
import io.dataloom.runtime.retry.TransportCircuitOperation
import io.dataloom.runtime.retry.TransportCircuitScopes
import io.dataloom.runtime.worker.QueueWorkerConfiguration
import io.dataloom.runtime.worker.CircuitBreakerQueueWorkerRunResult
import io.dataloom.runtime.worker.QueueWorkerRunRequest
import io.dataloom.runtime.worker.QueueWorkerRunResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * End-to-end proof, through a real [DataLoomBuilder]-assembled queue worker,
 * that when [DataLoomBuilder.providerProtectionConfiguration] is also
 * configured, queued replay runs through the same transport circuit breaker
 * as direct protected synchronization:
 *
 * 1. A failing transport (first replay attempt) is rescheduled with the retry
 *    attempt advanced and opens the circuit once the threshold is reached.
 * 2. A later queued entry is rejected by the circuit gate (`PROVIDER_CIRCUIT_OPEN`)
 *    BEFORE the transport is invoked, and is rescheduled rather than failed.
 * 3. After the circuit's open duration has really elapsed (real wall-clock
 *    time, [runBlocking] with real [delay], never virtual time) the half-open
 *    probe reaches the transport, succeeds, the circuit closes, and both
 *    entries complete.
 *
 * The same scenario is run through both [DataLoom.queueWorker] and
 * [DataLoom.circuitQueueWorker]. A control test proves that without
 * `providerProtectionConfiguration` queued replay stays unprotected (every
 * entry reaches the failing transport), i.e. the wiring is opt-in.
 *
 * The queue is a small in-memory [QueueProvider] that honors `availableAt`
 * on acquire like the real Room/Apple-file queues.
 */
class DataLoomBuilderProtectedQueueReplayTest {

    private val openDurationMillis = 1_500L

    @Test
    fun directWorkerReplayOpensCircuitRejectsBeforeTransportAndRecovers() = runBlocking {
        runScenario(WorkerKind.DIRECT)
    }

    @Test
    fun circuitWorkerReplayOpensCircuitRejectsBeforeTransportAndRecovers() = runBlocking {
        runScenario(WorkerKind.CIRCUIT_AWARE)
    }

    @Test
    fun withoutProviderProtectionQueuedReplayStaysUnprotected() = runBlocking {
        val f = fixture(WorkerKind.DIRECT, protect = false, transportFailures = Int.MAX_VALUE)
        assertIs<ProviderLifecycleResult.InitializeSuccess>(f.dataLoom.initialize())

        f.queue.enqueue("e1")
        f.queue.enqueue("e2")
        val summary = f.cycle("u1")

        // Both entries reach the failing transport: no gate, no circuit.
        assertEquals(2, summary.rescheduled)
        assertEquals(2, f.transport.pullCalls)
        assertEquals(INJECTED, f.queue.lastErrorCode("e1"))
        assertEquals(INJECTED, f.queue.lastErrorCode("e2"))
        val again = f.cycle("u2")
        assertEquals(2, again.rescheduled)
        assertEquals(4, f.transport.pullCalls)
    }

    private suspend fun runScenario(kind: WorkerKind) {
        val f = fixture(kind, protect = true, transportFailures = 2)
        assertIs<ProviderLifecycleResult.InitializeSuccess>(f.dataLoom.initialize())

        // Cycle 1: first replay attempt fails (below the circuit threshold of 2)
        // and is rescheduled.
        f.queue.enqueue("e1")
        val c1 = f.cycle("c1")
        assertEquals(1, c1.rescheduled)
        assertEquals(0, c1.completed)
        assertEquals(1, f.transport.pullCalls)
        assertEquals(INJECTED, f.queue.lastErrorCode("e1"))
        assertEquals(1, f.queue.retryAttempt("e1"))

        // Cycle 2: e1's second failure trips the circuit; the later entry e2 in
        // the same cycle is rejected by the gate before reaching the transport.
        f.queue.enqueue("e2")
        val c2 = f.cycle("c2")
        val openedAt = testClock.now().epochMilliseconds
        assertEquals(2, c2.rescheduled)
        assertEquals(0, c2.failed)
        assertEquals(2, f.transport.pullCalls, "e2 must be rejected by the gate, not reach the transport")
        assertEquals(INJECTED, f.queue.lastErrorCode("e1"))
        assertEquals("PROVIDER_CIRCUIT_OPEN", f.queue.lastErrorCode("e2"))
        assertEquals(2, f.queue.retryAttempt("e1"))
        assertEquals(1, f.queue.retryAttempt("e2"))

        // Cycle 3: still inside the open window (checked against the real clock).
        val c3 = f.cycle("c3")
        assertTrue(
            testClock.now().epochMilliseconds < openedAt + openDurationMillis,
            "cycle 3 must run inside the circuit's open window",
        )
        assertEquals(2, c3.rescheduled)
        assertEquals(2, f.transport.pullCalls)

        // Cycle 4: after the real open duration the probe reaches the transport,
        // succeeds, the circuit closes, and both entries complete.
        delay(openDurationMillis + 250L)
        val c4 = f.cycle("c4")
        assertEquals(2, c4.completed)
        assertEquals(0, c4.rescheduled)
        assertEquals(4, f.transport.pullCalls)
        assertEquals(QueueEntryState.COMPLETED, f.queue.state("e1"))
        assertEquals(QueueEntryState.COMPLETED, f.queue.state("e2"))
        assertEquals(ProviderLifecycleResult.ShutdownSuccess, f.dataLoom.shutdown())
    }

    // -------------------------------------------------------------------------
    // Fixture
    // -------------------------------------------------------------------------

    private enum class WorkerKind { DIRECT, CIRCUIT_AWARE }

    private data class CycleSummary(val completed: Int, val rescheduled: Int, val failed: Int)

    private inner class Fixture(
        val dataLoom: DataLoom,
        val queue: InMemoryQueue,
        val transport: ScriptedTransport,
        val kind: WorkerKind,
    ) {
        suspend fun cycle(tag: String): CycleSummary {
            val acquiredAt = testClock.now().epochMilliseconds
            val request = QueueWorkerRunRequest(
                processingRequest = QueueProcessingRequest(
                    acquireRequest = QueueAcquireRequest(
                        consumerId = QueueConsumerId("consumer"),
                        leaseId = QueueLeaseId("lease-$tag"),
                        acquiredAt = DataLoomInstant(acquiredAt),
                        leaseExpiresAt = DataLoomInstant(acquiredAt + 60_000L),
                        maxEntries = 10,
                    ),
                ),
                recoveryRequest = null,
            )
            return when (kind) {
                WorkerKind.DIRECT -> {
                    val run = assertIs<QueueWorkerRunResult.ProcessingCompleted>(
                        checkNotNull(dataLoom.queueWorker).run(request),
                    )
                    when (val result = run.processingResult) {
                        is QueueProcessingResult.NoWork -> CycleSummary(0, 0, 0)
                        is QueueProcessingResult.Processed ->
                            CycleSummary(result.summary.completed, result.summary.rescheduled, result.summary.failed)
                        else -> error("Unexpected queue processing result: $result")
                    }
                }
                WorkerKind.CIRCUIT_AWARE -> {
                    val run = assertIs<CircuitBreakerQueueWorkerRunResult.ProcessingCompleted>(
                        checkNotNull(dataLoom.circuitQueueWorker).run(request),
                    )
                    when (val result = run.processingResult) {
                        is CircuitBreakerQueueProcessingResult.NoWork -> CycleSummary(0, 0, 0)
                        is CircuitBreakerQueueProcessingResult.Processed ->
                            CycleSummary(result.summary.completed, result.summary.rescheduled, result.summary.failed)
                        else -> error("Unexpected queue processing result: $result")
                    }
                }
            }
        }
    }

    private fun fixture(kind: WorkerKind, protect: Boolean, transportFailures: Int): Fixture {
        val storage = FakeStorage()
        val transport = ScriptedTransport(failuresBeforeSuccess = transportFailures)
        val queue = InMemoryQueue()
        val bindings = SynchronizationProviderBindings(
            storageProviderId = storage.descriptor.id,
            transportProviderId = transport.descriptor.id,
            queueProviderId = queue.descriptor.id,
        )
        val builder = DataLoomBuilder()
            .runtimeDependencies(runtimeDependencies())
            .providers(storage, transport, queue)
            .defaultProviderBindings(bindings)
        if (protect) {
            val circuitConfiguration = CircuitBreakerConfiguration(
                failureThreshold = 2,
                failureWindow = SchedulingDelay(600_000L),
                openDuration = SchedulingDelay(openDurationMillis),
            )
            builder.providerProtectionConfiguration(
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
        }
        val workerSpec = DataLoomQueueWorkerSpec(
            workResolver = QueuedSynchronizationWorkResolver { entry ->
                QueuedSynchronizationWorkResolution.Resolved(
                    QueuedSynchronizationWork(entry.synchronizationRequest, bindings),
                )
            },
            retryPolicy = StandardRetryPolicy(
                id = RetryPolicyId("protected-queue-replay"),
                strategy = RetryBackoffStrategy.Immediate,
                maximumAttempts = 10,
            ),
            retryOperation = RetryOperation("protected-queue-replay.pull"),
            configuration = QueueWorkerConfiguration(
                scheduleId = ScheduleId("protected-queue-replay-worker"),
                constraints = ScheduleConstraints(),
                existingSchedulePolicy = ExistingSchedulePolicy.REPLACE,
                continuationDelay = SchedulingDelay.ZERO,
                recoverExpiredLeasesBeforeProcessing = false,
            ),
        )
        when (kind) {
            WorkerKind.DIRECT -> builder.queueWorkerConfiguration(workerSpec)
            WorkerKind.CIRCUIT_AWARE -> builder.circuitQueueWorkerConfiguration(
                DataLoomCircuitQueueWorkerSpec(
                    workerSpec = workerSpec,
                    circuitBreakerConfiguration = CircuitBreakerConfiguration(
                        failureThreshold = 100,
                        failureWindow = SchedulingDelay(600_000L),
                        openDuration = SchedulingDelay(1_000L),
                    ),
                    circuitBreakerStateStore = InMemoryCircuitStore(),
                    recoveryScope = queueScope(queue.descriptor.id, QueueCircuitOperation.RECOVER_EXPIRED_LEASES),
                    processingScopes = QueueProcessingCircuitScopes(
                        acquisition = queueScope(queue.descriptor.id, QueueCircuitOperation.ACQUIRE),
                        completion = queueScope(queue.descriptor.id, QueueCircuitOperation.COMPLETE),
                        reschedule = queueScope(queue.descriptor.id, QueueCircuitOperation.RESCHEDULE),
                        deferral = queueScope(queue.descriptor.id, QueueCircuitOperation.DEFER),
                        failure = queueScope(queue.descriptor.id, QueueCircuitOperation.FAIL),
                        cancellation = queueScope(queue.descriptor.id, QueueCircuitOperation.CANCEL),
                    ),
                ),
            )
        }
        return Fixture(builder.build(), queue, transport, kind)
    }

    private fun queueScope(providerId: ProviderId, operation: QueueCircuitOperation) =
        CircuitBreakerScope.providerOperation(providerId, operation.retryOperation)

    private fun runtimeDependencies() = RuntimeDependencies(
        clock = testClock,
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

    private data class TestError(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val recoverability: Recoverability,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val message: String = "test",
        override val cause: Throwable? = null,
    ) : DataLoomError

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

    /** Minimal in-memory queue that honors `availableAt` on acquire. */
    private class InMemoryQueue : QueueProvider {
        private val entries = linkedMapOf<QueueEntryId, QueueEntry>()

        override val descriptor = ProviderDescriptor(
            id = ProviderId("queue-protected-replay"),
            name = ProviderName("Protected replay in-memory queue"),
            type = ProviderType.QUEUE,
            version = ProviderVersion("1.0.0"),
        )

        fun enqueue(id: String) {
            val now = testClock.now()
            val entry = QueueEntry(
                id = QueueEntryId(id),
                synchronizationRequest = SynchronizationRequest(
                    workflowId = WorkflowId("workflow-$id"),
                    sessionId = SynchronizationSessionId("session-$id"),
                    direction = SynchronizationDirection.PULL,
                    mode = SynchronizationMode.DELTA,
                    context = ExecutionContext(
                        executionId = ExecutionId("execution-$id"),
                        correlationId = CorrelationId("correlation-$id"),
                    ),
                ),
                state = QueueEntryState.PENDING,
                enqueuedAt = now,
                availableAt = now,
            )
            entries[entry.id] = entry
        }

        fun state(id: String): QueueEntryState = checkNotNull(entries[QueueEntryId(id)]).state
        fun retryAttempt(id: String): Int? = entries[QueueEntryId(id)]?.retryAttempt?.number
        fun lastErrorCode(id: String): String? = entries[QueueEntryId(id)]?.lastError?.code?.value

        override suspend fun initialize(context: ProviderInitializationContext) =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close() = ProviderOperationResult.Success(Unit)

        override suspend fun enqueue(request: QueueEnqueueRequest): ProviderOperationResult<Unit> {
            entries[request.entry.id] = request.entry
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun acquire(
            request: QueueAcquireRequest,
        ): ProviderOperationResult<QueueAcquireResult> {
            val eligible = entries.values
                .filter {
                    (it.state == QueueEntryState.PENDING || it.state == QueueEntryState.RETRY_WAITING) &&
                        it.availableAt.epochMilliseconds <= request.acquiredAt.epochMilliseconds
                }
                .take(request.maxEntries)
            if (eligible.isEmpty()) return ProviderOperationResult.Success(QueueAcquireResult.NoEntries)
            val lease = QueueLease(
                id = request.leaseId,
                consumerId = request.consumerId,
                acquiredAt = request.acquiredAt,
                expiresAt = request.leaseExpiresAt,
            )
            val leased = eligible.map { it.copy(state = QueueEntryState.LEASED, lease = lease, lastError = null) }
            leased.forEach { entries[it.id] = it }
            return ProviderOperationResult.Success(QueueAcquireResult.Entries(lease, leased))
        }

        override suspend fun complete(request: QueueCompletionRequest): ProviderOperationResult<Unit> {
            entries[request.entryId] = checkNotNull(entries[request.entryId])
                .copy(state = QueueEntryState.COMPLETED, lease = null)
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun reschedule(request: QueueRescheduleRequest): ProviderOperationResult<Unit> {
            entries[request.entryId] = checkNotNull(entries[request.entryId]).copy(
                state = QueueEntryState.RETRY_WAITING,
                lease = null,
                retryAttempt = request.retryAttempt,
                availableAt = request.availableAt,
                lastError = request.error,
                retryBudgetState = request.retryBudgetState,
            )
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun defer(request: QueueDeferralRequest): ProviderOperationResult<Unit> =
            error("defer is not used by this test")

        override suspend fun fail(request: QueueFailureRequest): ProviderOperationResult<Unit> {
            entries[request.entryId] = checkNotNull(entries[request.entryId])
                .copy(state = QueueEntryState.FAILED, lease = null, lastError = request.error)
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun cancel(request: QueueCancellationRequest): ProviderOperationResult<Unit> =
            error("cancel is not used by this test")

        override suspend fun recoverExpiredLeases(
            request: ExpiredLeaseRecoveryRequest,
        ): ProviderOperationResult<ExpiredLeaseRecoveryResult> =
            error("recovery is not used by this test")
    }

    private class FakeStorage : StorageProvider {
        override val descriptor = ProviderDescriptor(
            id = ProviderId("storage-protected-replay"),
            name = ProviderName("Protected replay storage"),
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

        override suspend fun readLocalConflictCandidate(
            request: LocalConflictCandidateReadRequest,
        ): ProviderOperationResult<LocalConflictCandidateReadResult> =
            ProviderOperationResult.Success(LocalConflictCandidateReadResult.NotFound)
    }

    private class ScriptedTransport(private val failuresBeforeSuccess: Int) : TransportProvider {
        var pullCalls = 0
            private set

        override val descriptor = ProviderDescriptor(
            id = ProviderId("transport-protected-replay"),
            name = ProviderName("Protected replay transport"),
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

        override suspend fun pullChanges(request: PullChangesRequest): ProviderOperationResult<PullChangesResult> {
            pullCalls++
            if (pullCalls <= failuresBeforeSuccess) {
                return ProviderOperationResult.Failure(
                    TestError(ErrorCode(INJECTED), ErrorCategory.NETWORK, Recoverability.RECOVERABLE),
                )
            }
            val entity = EntityReference(EntityType("invoice"), EntityId("inv-$pullCalls"))
            return ProviderOperationResult.Success(
                PullChangesResult.Changes(
                    changeSet = ChangeSet(
                        ChangeSetId("batch-$pullCalls"),
                        listOf(ChangeEvent(ChangeEventId("event-$pullCalls"), entity, ChangeOperation.CREATE)),
                    ),
                    hasMore = false,
                ),
            )
        }
    }

    private companion object {
        const val INJECTED = "PROTECTED-REPLAY-NETWORK-DOWN"
    }
}

/**
 * Real elapsed-time [DataLoomClock] built on [TimeSource.Monotonic], so the
 * circuit's open duration genuinely elapses (no virtual time) while staying
 * in multiplatform common code: this source set is also compiled for iOS, so
 * JVM-only clocks such as `SystemDataLoomClock` or `System` are unavailable.
 */
private class MonotonicTestClock : DataLoomClock {
    private val origin = TimeSource.Monotonic.markNow()

    override fun now(): DataLoomInstant =
        DataLoomInstant(BASE_MILLIS + origin.elapsedNow().inWholeMilliseconds)

    private companion object {
        const val BASE_MILLIS = 1_700_000_000_000L
    }
}

private val testClock: DataLoomClock = MonotonicTestClock()
