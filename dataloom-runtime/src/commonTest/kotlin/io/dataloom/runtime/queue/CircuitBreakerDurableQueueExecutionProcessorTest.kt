package io.dataloom.runtime.queue

import io.dataloom.api.circuit.CircuitBreakerCompareAndSetRequest
import io.dataloom.api.circuit.CircuitBreakerCompareAndSetResult
import io.dataloom.api.circuit.CircuitBreakerLoadResult
import io.dataloom.api.circuit.CircuitBreakerPhase
import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.circuit.CircuitBreakerState
import io.dataloom.api.circuit.CircuitBreakerStateRecord
import io.dataloom.api.circuit.CircuitBreakerStateStore
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.QueueConsumerId
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.queue.ExpiredLeaseRecoveryRequest
import io.dataloom.api.queue.ExpiredLeaseRecoveryResult
import io.dataloom.api.queue.QueueAcquireRequest
import io.dataloom.api.queue.QueueAcquireResult
import io.dataloom.api.queue.QueueCancellationRequest
import io.dataloom.api.queue.QueueCompletionRequest
import io.dataloom.api.queue.QueueDeferralReason
import io.dataloom.api.queue.QueueDeferralRequest
import io.dataloom.api.queue.QueueEnqueueRequest
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.queue.QueueFailureDisposition
import io.dataloom.api.queue.QueueFailureRequest
import io.dataloom.api.queue.QueueLease
import io.dataloom.api.queue.QueueProvider
import io.dataloom.api.queue.QueueRescheduleRequest
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.retry.CircuitBreakerConfiguration
import io.dataloom.runtime.retry.CircuitBreakerCoordinator
import io.dataloom.runtime.retry.CircuitBreakerExecutionGate
import io.dataloom.runtime.retry.CircuitBreakerQueueOperationAdapter
import io.dataloom.runtime.retry.CircuitBreakerRecordResult
import io.dataloom.runtime.retry.CircuitBreakerRejectionReason
import io.dataloom.runtime.retry.QueueCircuitOperation
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest

class CircuitBreakerDurableQueueExecutionProcessorTest {

    @Test
    fun `constructor rejects mismatched operation scope without state or provider access`() {
        val provider = RecordingQueueProvider(QueueAcquireResult.NoEntries)
        val store = MapCircuitStore()
        val adapter = adapter(provider, store)
        val invalid = scopes().copy(
            completion = CircuitBreakerScope.providerOperation(
                providerId = providerId,
                operation = QueueCircuitOperation.ACQUIRE.retryOperation,
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            CircuitBreakerDurableQueueExecutionProcessor(
                queueOperationAdapter = adapter,
                executionHandler = RecordingHandler(emptyList()),
                scopes = invalid,
            )
        }

        assertEquals(0, store.loadCalls)
        assertEquals(emptyList(), provider.calls)
    }

    @Test
    fun `open acquisition circuit stops before provider invocation`() = runTest {
        val acquisitionScope = scopes().acquisition
        val store = MapCircuitStore(
            initialRecords = mapOf(acquisitionScope to openRecord(acquisitionScope)),
        )
        val provider = RecordingQueueProvider(QueueAcquireResult.NoEntries)
        val processor = processor(provider, RecordingHandler(emptyList()), store)

        val result = assertIs<CircuitBreakerQueueProcessingResult.PreExecutionStopped>(
            processor.process(processingRequest(maxEntries = 1)),
        )

        assertEquals(QueueCircuitOperation.ACQUIRE, result.operation)
        assertEquals(QueueProcessingFailureStage.ACQUISITION, result.stage)
        assertEquals(
            CircuitBreakerRejectionReason.OPEN,
            assertIs<QueueCircuitPreExecutionDecision.Rejected>(result.decision).reason,
        )
        assertEquals(zeroSummary(), result.summary)
        assertEquals(0, provider.acquireCalls)
    }

    @Test
    fun `acquisition success with failed circuit recording leases entries but invokes no handler`() = runTest {
        val acquisitionScope = scopes().acquisition
        val storeError = error("CIRCUIT_WRITE_FAILED", ErrorCategory.STORAGE)
        val store = MapCircuitStore(
            initialRecords = mapOf(
                acquisitionScope to closedFailureRecord(acquisitionScope),
            ),
            compareFailures = mapOf(acquisitionScope to storeError),
        )
        val entries = acquiredEntries(2)
        val provider = RecordingQueueProvider(entries)
        val handler = RecordingHandler(
            listOf(
                QueueEntryExecutionOutcome.Completed(completedAt),
                QueueEntryExecutionOutcome.Completed(completedAt),
            ),
        )
        val processor = processor(provider, handler, store)

        val result = assertIs<CircuitBreakerQueueProcessingResult.CircuitRecordingUnconfirmed>(
            processor.process(processingRequest(maxEntries = 2)),
        )

        assertEquals(QueueCircuitOperation.ACQUIRE, result.operation)
        assertEquals(summary(acquired = 2), result.summary)
        assertEquals(entries.entries.map { it.id }, result.affectedEntryIds)
        assertEquals(leaseId, result.leaseId)
        assertSame(
            storeError,
            assertIs<CircuitBreakerRecordResult.PersistenceFailure>(result.recordResult).error,
        )
        assertEquals(0, handler.calls)
    }

    @Test
    fun `all successful transition kinds produce truthful counts and ordered records`() = runTest {
        val entries = acquiredEntries(5)
        val provider = RecordingQueueProvider(entries)
        val handler = RecordingHandler(
            listOf(
                QueueEntryExecutionOutcome.Completed(completedAt),
                QueueEntryExecutionOutcome.Reschedule(
                    retryAttempt = RetryAttempt(1),
                    availableAt = DataLoomInstant(7_000L),
                    error = transitionError,
                ),
                QueueEntryExecutionOutcome.Deferred(
                    availableAt = DataLoomInstant(6_000L),
                    reason = QueueDeferralReason.CONNECTIVITY_REQUIREMENT_NOT_MET,
                ),
                QueueEntryExecutionOutcome.Failed(
                    error = transitionError,
                    disposition = QueueFailureDisposition.DEAD_LETTER,
                ),
                QueueEntryExecutionOutcome.Cancelled(executionContext),
            ),
        )
        val processor = processor(provider, handler, MapCircuitStore())

        val result = assertIs<CircuitBreakerQueueProcessingResult.Processed>(
            processor.process(processingRequest(maxEntries = 5)),
        )

        assertEquals(
            summary(
                acquired = 5,
                executed = 5,
                completed = 1,
                rescheduled = 1,
                deferred = 1,
                failed = 1,
                cancelled = 1,
            ),
            result.summary,
        )
        assertEquals(DataLoomInstant(7_000L), result.earliestRescheduledAt)
        assertEquals(DataLoomInstant(6_000L), result.earliestDeferredAt)
        assertEquals(DataLoomInstant(6_000L), result.earliestNextAvailableAt)
        assertEquals(
            listOf(
                QueueCircuitOperation.ACQUIRE,
                QueueCircuitOperation.COMPLETE,
                QueueCircuitOperation.RESCHEDULE,
                QueueCircuitOperation.DEFER,
                QueueCircuitOperation.FAIL,
                QueueCircuitOperation.CANCEL,
            ),
            result.operationRecords.map { it.operation },
        )
        assertEquals(
            listOf("acquire", "complete", "reschedule", "defer", "fail", "cancel"),
            provider.calls,
        )
    }

    @Test
    fun `transition circuit rejection stops after handler and before provider transition`() = runTest {
        val completionScope = scopes().completion
        val store = MapCircuitStore(
            initialRecords = mapOf(completionScope to openRecord(completionScope)),
        )
        val entries = acquiredEntries(2)
        val provider = RecordingQueueProvider(entries)
        val handler = RecordingHandler(
            listOf(
                QueueEntryExecutionOutcome.Completed(completedAt),
                QueueEntryExecutionOutcome.Completed(completedAt),
            ),
        )
        val processor = processor(provider, handler, store)

        val result = assertIs<CircuitBreakerQueueProcessingResult.PreExecutionStopped>(
            processor.process(processingRequest(maxEntries = 2)),
        )

        assertEquals(QueueCircuitOperation.COMPLETE, result.operation)
        assertEquals(QueueProcessingFailureStage.COMPLETION_TRANSITION, result.stage)
        assertEquals(summary(acquired = 2, executed = 1), result.summary)
        assertEquals(entries.entries.first().id, result.affectedEntryId)
        assertEquals(1, handler.calls)
        assertEquals(0, provider.completeCalls)
    }

    @Test
    fun `provider transition failure preserves classification and partial progress`() = runTest {
        val providerError = error("QUEUE_COMPLETE_FAILED", ErrorCategory.QUEUE)
        val entries = acquiredEntries(2)
        val provider = RecordingQueueProvider(
            acquireResult = entries,
            completeResult = ProviderOperationResult.Failure(providerError),
        )
        val handler = RecordingHandler(
            listOf(
                QueueEntryExecutionOutcome.Completed(completedAt),
                QueueEntryExecutionOutcome.Completed(completedAt),
            ),
        )
        val processor = processor(provider, handler, MapCircuitStore())

        val result = assertIs<CircuitBreakerQueueProcessingResult.ProviderFailure>(
            processor.process(processingRequest(maxEntries = 2)),
        )

        assertSame(providerError, result.error)
        assertEquals(QueueCircuitProviderFailureDisposition.CIRCUIT_FAILURE, result.disposition)
        assertEquals(summary(acquired = 2, executed = 1), result.summary)
        assertEquals(entries.entries.first().id, result.affectedEntryId)
        assertEquals(1, provider.completeCalls)
        assertEquals(1, handler.calls)
    }

    @Test
    fun `successful transition with failed circuit recording is counted then stops`() = runTest {
        val completionScope = scopes().completion
        val storeError = error("CIRCUIT_COMPLETE_WRITE_FAILED", ErrorCategory.STORAGE)
        val store = MapCircuitStore(
            initialRecords = mapOf(
                completionScope to closedFailureRecord(completionScope),
            ),
            compareFailures = mapOf(completionScope to storeError),
        )
        val entries = acquiredEntries(2)
        val provider = RecordingQueueProvider(entries)
        val handler = RecordingHandler(
            listOf(
                QueueEntryExecutionOutcome.Completed(completedAt),
                QueueEntryExecutionOutcome.Completed(completedAt),
            ),
        )
        val processor = processor(provider, handler, store)

        val result = assertIs<CircuitBreakerQueueProcessingResult.CircuitRecordingUnconfirmed>(
            processor.process(processingRequest(maxEntries = 2)),
        )

        assertEquals(QueueCircuitOperation.COMPLETE, result.operation)
        assertEquals(summary(acquired = 2, executed = 1, completed = 1), result.summary)
        assertEquals(listOf(entries.entries.first().id), result.affectedEntryIds)
        assertSame(
            storeError,
            assertIs<CircuitBreakerRecordResult.PersistenceFailure>(result.recordResult).error,
        )
        assertEquals(1, provider.completeCalls)
        assertEquals(1, handler.calls)
    }

    @Test
    fun `invalid acquired consumer returns contract violation without handler or transition`() = runTest {
        val wrongLease = QueueLease(
            id = leaseId,
            consumerId = QueueConsumerId("wrong-consumer"),
            acquiredAt = now,
            expiresAt = DataLoomInstant(5_000L),
        )
        val invalid = QueueAcquireResult.Entries(
            lease = wrongLease,
            entries = listOf(leasedEntry(1, wrongLease)),
        )
        val provider = RecordingQueueProvider(invalid)
        val handler = RecordingHandler(
            listOf(QueueEntryExecutionOutcome.Completed(completedAt)),
        )
        val processor = processor(provider, handler, MapCircuitStore())

        val result = assertIs<CircuitBreakerQueueProcessingResult.QueueContractViolation>(
            processor.process(processingRequest(maxEntries = 1)),
        )

        assertEquals(summary(acquired = 1), result.summary)
        assertEquals("DL-Q-CONTRACT-VIOLATION", result.error.code.value)
        assertEquals(0, handler.calls)
        assertEquals(0, provider.completeCalls)
    }

    @Test
    fun `handler cancellation propagates before transition`() = runTest {
        val provider = RecordingQueueProvider(acquiredEntries(1))
        val handler = QueueEntryExecutionHandler {
            throw CancellationException("handler cancelled")
        }
        val processor = processor(provider, handler, MapCircuitStore())

        val failure = assertFailsWith<CancellationException> {
            processor.process(processingRequest(maxEntries = 1))
        }

        assertEquals("handler cancelled", failure.message)
        assertEquals(0, provider.completeCalls)
    }

    private fun processor(
        provider: QueueProvider,
        handler: QueueEntryExecutionHandler,
        store: CircuitBreakerStateStore,
    ): CircuitBreakerDurableQueueExecutionProcessor =
        CircuitBreakerDurableQueueExecutionProcessor(
            queueOperationAdapter = adapter(provider, store),
            executionHandler = handler,
            scopes = scopes(),
        )

    private fun adapter(
        provider: QueueProvider,
        store: CircuitBreakerStateStore,
    ): CircuitBreakerQueueOperationAdapter = CircuitBreakerQueueOperationAdapter(
        queueProvider = provider,
        executionGate = CircuitBreakerExecutionGate(
            CircuitBreakerCoordinator(
                configuration = CircuitBreakerConfiguration(
                    failureThreshold = 1,
                    failureWindow = SchedulingDelay(1_000L),
                    openDuration = SchedulingDelay(10_000L),
                ),
                clock = FixedClock(now),
                stateStore = store,
            ),
        ),
    )

    private class RecordingHandler(
        private val outcomes: List<QueueEntryExecutionOutcome>,
    ) : QueueEntryExecutionHandler {
        var calls: Int = 0
            private set

        override suspend fun execute(entry: QueueEntry): QueueEntryExecutionOutcome {
            val outcome = outcomes[calls]
            calls++
            return outcome
        }
    }

    private class RecordingQueueProvider(
        private val acquireResult: QueueAcquireResult,
        private val completeResult: ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit),
    ) : QueueProvider {
        val calls: MutableList<String> = mutableListOf()
        val acquireCalls: Int get() = calls.count { it == "acquire" }
        val completeCalls: Int get() = calls.count { it == "complete" }

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = providerId,
            name = ProviderName("Circuit Processor Queue"),
            type = ProviderType.QUEUE,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun enqueue(
            request: QueueEnqueueRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun acquire(
            request: QueueAcquireRequest,
        ): ProviderOperationResult<QueueAcquireResult> {
            calls += "acquire"
            return ProviderOperationResult.Success(acquireResult)
        }

        override suspend fun complete(
            request: QueueCompletionRequest,
        ): ProviderOperationResult<Unit> {
            calls += "complete"
            return completeResult
        }

        override suspend fun reschedule(
            request: QueueRescheduleRequest,
        ): ProviderOperationResult<Unit> {
            calls += "reschedule"
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun defer(
            request: QueueDeferralRequest,
        ): ProviderOperationResult<Unit> {
            calls += "defer"
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun fail(
            request: QueueFailureRequest,
        ): ProviderOperationResult<Unit> {
            calls += "fail"
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun cancel(
            request: QueueCancellationRequest,
        ): ProviderOperationResult<Unit> {
            calls += "cancel"
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun recoverExpiredLeases(
            request: ExpiredLeaseRecoveryRequest,
        ): ProviderOperationResult<ExpiredLeaseRecoveryResult> =
            ProviderOperationResult.Success(ExpiredLeaseRecoveryResult(0))
    }

    private class MapCircuitStore(
        initialRecords: Map<CircuitBreakerScope, CircuitBreakerStateRecord> = emptyMap(),
        private val loadFailures: Map<CircuitBreakerScope, DataLoomError> = emptyMap(),
        private val compareFailures: Map<CircuitBreakerScope, DataLoomError> = emptyMap(),
    ) : CircuitBreakerStateStore {
        private val records = initialRecords.toMutableMap()
        var loadCalls: Int = 0
            private set

        override suspend fun load(
            scope: CircuitBreakerScope,
        ): ProviderOperationResult<CircuitBreakerLoadResult> {
            loadCalls++
            loadFailures[scope]?.let { return ProviderOperationResult.Failure(it) }
            val current = records[scope]
            return ProviderOperationResult.Success(
                if (current == null) {
                    CircuitBreakerLoadResult.Missing
                } else {
                    CircuitBreakerLoadResult.Found(current)
                },
            )
        }

        override suspend fun compareAndSet(
            request: CircuitBreakerCompareAndSetRequest,
        ): ProviderOperationResult<CircuitBreakerCompareAndSetResult> {
            compareFailures[request.scope]?.let { return ProviderOperationResult.Failure(it) }
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

    private companion object {
        val providerId = ProviderId("circuit-processor-queue")
        val consumerId = QueueConsumerId("circuit-processor-consumer")
        val leaseId = QueueLeaseId("circuit-processor-lease")
        val now = DataLoomInstant(1_000L)
        val completedAt = DataLoomInstant(2_000L)
        val transitionError = error("SYNC_FAILED", ErrorCategory.NETWORK)
        val executionContext = ExecutionContext(
            executionId = ExecutionId("circuit-processor-execution"),
            correlationId = CorrelationId("circuit-processor-correlation"),
        )

        fun scopes(): QueueProcessingCircuitScopes = QueueProcessingCircuitScopes(
            acquisition = scope(QueueCircuitOperation.ACQUIRE),
            completion = scope(QueueCircuitOperation.COMPLETE),
            reschedule = scope(QueueCircuitOperation.RESCHEDULE),
            deferral = scope(QueueCircuitOperation.DEFER),
            failure = scope(QueueCircuitOperation.FAIL),
            cancellation = scope(QueueCircuitOperation.CANCEL),
        )

        fun scope(operation: QueueCircuitOperation): CircuitBreakerScope =
            CircuitBreakerScope.providerOperation(providerId, operation.retryOperation)

        fun processingRequest(maxEntries: Int): QueueProcessingRequest = QueueProcessingRequest(
            QueueAcquireRequest(
                consumerId = consumerId,
                leaseId = leaseId,
                acquiredAt = now,
                leaseExpiresAt = DataLoomInstant(5_000L),
                maxEntries = maxEntries,
            ),
        )

        fun acquiredEntries(count: Int): QueueAcquireResult.Entries {
            val lease = QueueLease(
                id = leaseId,
                consumerId = consumerId,
                acquiredAt = now,
                expiresAt = DataLoomInstant(5_000L),
            )
            return QueueAcquireResult.Entries(
                lease = lease,
                entries = (1..count).map { leasedEntry(it, lease) },
            )
        }

        fun leasedEntry(index: Int, lease: QueueLease): QueueEntry = QueueEntry(
            id = QueueEntryId("circuit-entry-$index"),
            synchronizationRequest = SynchronizationRequest(
                workflowId = WorkflowId("circuit-workflow-$index"),
                sessionId = SynchronizationSessionId("circuit-session-$index"),
                direction = SynchronizationDirection.PUSH,
                mode = SynchronizationMode.DELTA,
                context = executionContext,
            ),
            state = QueueEntryState.LEASED,
            enqueuedAt = now,
            availableAt = now,
            lease = lease,
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

        fun error(code: String, category: ErrorCategory): DataLoomError = TestError(
            code = ErrorCode(code),
            category = category,
        )

        fun zeroSummary(): QueueProcessingSummary = summary()

        fun summary(
            acquired: Int = 0,
            executed: Int = 0,
            completed: Int = 0,
            rescheduled: Int = 0,
            deferred: Int = 0,
            failed: Int = 0,
            cancelled: Int = 0,
        ): QueueProcessingSummary = QueueProcessingSummary(
            acquired = acquired,
            executed = executed,
            completed = completed,
            rescheduled = rescheduled,
            failed = failed,
            cancelled = cancelled,
            deferred = deferred,
        )
    }

    private data class TestError(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val message: String = "Circuit queue processor test error.",
        override val cause: Throwable? = null,
    ) : DataLoomError
}
