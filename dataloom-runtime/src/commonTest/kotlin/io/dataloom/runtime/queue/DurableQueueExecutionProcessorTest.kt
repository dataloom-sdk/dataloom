package io.dataloom.runtime.queue

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
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.queue.QueueProvider
import io.dataloom.api.queue.ExpiredLeaseRecoveryRequest
import io.dataloom.api.queue.ExpiredLeaseRecoveryResult
import io.dataloom.api.queue.QueueAcquireRequest
import io.dataloom.api.queue.QueueAcquireResult
import io.dataloom.api.queue.QueueCancellationRequest
import io.dataloom.api.queue.QueueCompletionRequest
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.queue.QueueEnqueueRequest
import io.dataloom.api.queue.QueueFailureDisposition
import io.dataloom.api.queue.QueueFailureRequest
import io.dataloom.api.queue.QueueLease
import io.dataloom.api.queue.QueueRescheduleRequest
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.time.DataLoomInstant
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Deterministic common tests for DL-026 durable queue execution processor.
 *
 * All fakes are stateless or deterministically stateful. No real queue
 * provider, real database, filesystem, Thread.sleep, arbitrary delay,
 * Android APIs, JVM-only APIs, reflection, ServiceLoader, system clock,
 * random identifiers, or production credentials are used.
 *
 * Suspend functions are exercised using [kotlin.coroutines.startCoroutine]
 * primitives from the Kotlin standard library, without requiring
 * kotlinx.coroutines.
 */
class DurableQueueExecutionProcessorTest {

    // =========================================================================
    // runSuspend helper
    // =========================================================================

    private object Pending

    private fun <T> runSuspend(block: suspend () -> T): T {
        var rawResult: Any? = Pending
        var thrown: Throwable? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context: CoroutineContext = EmptyCoroutineContext
                override fun resumeWith(result: Result<T>) {
                    if (result.isSuccess) {
                        rawResult = result.getOrNull()
                    } else {
                        thrown = result.exceptionOrNull()
                    }
                }
            },
        )
        thrown?.let { throw it }
        check(rawResult !== Pending) { "Suspend block did not complete synchronously in test." }
        @Suppress("UNCHECKED_CAST")
        return rawResult as T
    }

    // =========================================================================
    // Shared test fixtures
    // =========================================================================

    private val t0 = DataLoomInstant(1_000_000L)
    private val t1 = DataLoomInstant(2_000_000L)
    private val t2 = DataLoomInstant(3_000_000L)
    private val t3 = DataLoomInstant(4_000_000L)

    private val consumerId = QueueConsumerId("consumer-001")
    private val leaseId = QueueLeaseId("lease-001")

    private val sampleLease = QueueLease(
        id = leaseId,
        consumerId = consumerId,
        acquiredAt = t0,
        expiresAt = t1,
    )

    private val sampleSyncRequest = SynchronizationRequest(
        workflowId = WorkflowId("workflow-001"),
        sessionId = SynchronizationSessionId("session-001"),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.FULL,
        context = ExecutionContext(
            executionId = ExecutionId("exec-001"),
            correlationId = CorrelationId("corr-001"),
        ),
    )

    private val sampleContext = ExecutionContext(
        executionId = ExecutionId("exec-cancel-001"),
        correlationId = CorrelationId("corr-cancel-001"),
    )

    private val sampleError: DataLoomError = FakeError(
        code = ErrorCode("DL-FAKE-001"),
        message = "Fake provider error.",
    )

    private val sampleAcquireRequest = QueueAcquireRequest(
        consumerId = consumerId,
        leaseId = leaseId,
        acquiredAt = t0,
        leaseExpiresAt = t1,
        maxEntries = 5,
    )

    private val sampleProcessingRequest = QueueProcessingRequest(sampleAcquireRequest)

    private fun leasedEntry(
        id: QueueEntryId = QueueEntryId("entry-001"),
        lease: QueueLease = sampleLease,
    ): QueueEntry = QueueEntry(
        id = id,
        synchronizationRequest = sampleSyncRequest,
        state = QueueEntryState.LEASED,
        enqueuedAt = t0,
        availableAt = t0,
        lease = lease,
    )

    // =========================================================================
    // Fake DataLoomError
    // =========================================================================

    private data class FakeError(
        override val code: ErrorCode = ErrorCode("DL-FAKE-001"),
        override val category: ErrorCategory = ErrorCategory.PROVIDER,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Fake error.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    // =========================================================================
    // Fake QueueProvider
    // =========================================================================

    private open class FakeQueueProvider(
        private val acquireResponse: ProviderOperationResult<QueueAcquireResult> =
            ProviderOperationResult.Success(QueueAcquireResult.NoEntries),
        private val completeResponse: ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit),
        private val rescheduleResponse: ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit),
        private val failResponse: ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit),
        private val cancelResponse: ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit),
    ) : QueueProvider {

        val acquireRequests = mutableListOf<QueueAcquireRequest>()
        val completionRequests = mutableListOf<QueueCompletionRequest>()
        val rescheduleRequests = mutableListOf<QueueRescheduleRequest>()
        val failureRequests = mutableListOf<QueueFailureRequest>()
        val cancellationRequests = mutableListOf<QueueCancellationRequest>()

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("fake-queue"),
            name = ProviderName("Fake Queue Provider"),
            type = ProviderType.QUEUE,
            version = ProviderVersion("0.0.1"),
        )

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(status = ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun enqueue(request: QueueEnqueueRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun acquire(
            request: QueueAcquireRequest,
        ): ProviderOperationResult<QueueAcquireResult> {
            acquireRequests.add(request)
            return acquireResponse
        }

        override suspend fun complete(
            request: QueueCompletionRequest,
        ): ProviderOperationResult<Unit> {
            completionRequests.add(request)
            return completeResponse
        }

        override suspend fun reschedule(
            request: QueueRescheduleRequest,
        ): ProviderOperationResult<Unit> {
            rescheduleRequests.add(request)
            return rescheduleResponse
        }

        override suspend fun fail(
            request: QueueFailureRequest,
        ): ProviderOperationResult<Unit> {
            failureRequests.add(request)
            return failResponse
        }

        override suspend fun cancel(
            request: QueueCancellationRequest,
        ): ProviderOperationResult<Unit> {
            cancellationRequests.add(request)
            return cancelResponse
        }

        override suspend fun recoverExpiredLeases(
            request: ExpiredLeaseRecoveryRequest,
        ): ProviderOperationResult<ExpiredLeaseRecoveryResult> =
            ProviderOperationResult.Success(ExpiredLeaseRecoveryResult(recoveredEntries = 0))
    }

    // =========================================================================
    // Fake QueueEntryExecutionHandler
    // =========================================================================

    private class CapturingHandler(
        private val outcome: QueueEntryExecutionOutcome,
    ) : QueueEntryExecutionHandler {
        val capturedEntries = mutableListOf<QueueEntry>()

        override suspend fun execute(entry: QueueEntry): QueueEntryExecutionOutcome {
            capturedEntries.add(entry)
            return outcome
        }
    }

    private class MultiOutcomeHandler(
        private val outcomes: List<QueueEntryExecutionOutcome>,
    ) : QueueEntryExecutionHandler {
        private var callCount = 0

        override suspend fun execute(entry: QueueEntry): QueueEntryExecutionOutcome {
            return outcomes[callCount++]
        }
    }

    private class ThrowingHandler(
        private val throwable: Throwable,
    ) : QueueEntryExecutionHandler {
        override suspend fun execute(entry: QueueEntry): QueueEntryExecutionOutcome {
            throw throwable
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun entriesResult(
        vararg entries: QueueEntry,
        lease: QueueLease = sampleLease,
    ): ProviderOperationResult<QueueAcquireResult> =
        ProviderOperationResult.Success(
            QueueAcquireResult.Entries(
                lease = lease,
                entries = entries.toList(),
            ),
        )

    private fun failureResult(error: DataLoomError = sampleError): ProviderOperationResult<Unit> =
        ProviderOperationResult.Failure(error)

    // =========================================================================
    // QueueEntryExecutionOutcome construction tests
    // =========================================================================

    @Test
    fun `Completed outcome preserves completedAt`() {
        val outcome = QueueEntryExecutionOutcome.Completed(completedAt = t1)
        assertEquals(t1, outcome.completedAt)
    }

    @Test
    fun `Reschedule outcome preserves all fields`() {
        val attempt = RetryAttempt(2)
        val outcome = QueueEntryExecutionOutcome.Reschedule(
            retryAttempt = attempt,
            availableAt = t2,
            error = sampleError,
        )
        assertEquals(attempt, outcome.retryAttempt)
        assertEquals(t2, outcome.availableAt)
        assertSame(sampleError, outcome.error)
    }

    @Test
    fun `Failed outcome preserves error and disposition`() {
        val outcome = QueueEntryExecutionOutcome.Failed(
            error = sampleError,
            disposition = QueueFailureDisposition.DEAD_LETTER,
        )
        assertSame(sampleError, outcome.error)
        assertEquals(QueueFailureDisposition.DEAD_LETTER, outcome.disposition)
    }

    @Test
    fun `Cancelled outcome preserves context`() {
        val outcome = QueueEntryExecutionOutcome.Cancelled(context = sampleContext)
        assertSame(sampleContext, outcome.context)
    }

    // =========================================================================
    // QueueProcessingRequest tests
    // =========================================================================

    @Test
    fun `QueueProcessingRequest preserves acquireRequest verbatim`() {
        val req = QueueProcessingRequest(sampleAcquireRequest)
        assertSame(sampleAcquireRequest, req.acquireRequest)
    }

    // =========================================================================
    // QueueProcessingSummary invariant tests
    // =========================================================================

    @Test
    fun `QueueProcessingSummary accepts all-zero counts`() {
        val summary = QueueProcessingSummary(
            acquired = 0, executed = 0, completed = 0,
            rescheduled = 0, failed = 0, cancelled = 0,
        )
        assertEquals(0, summary.acquired)
        assertEquals(0, summary.executed)
    }

    @Test
    fun `QueueProcessingSummary rejects negative acquired`() {
        assertFailsWith<IllegalArgumentException> {
            QueueProcessingSummary(
                acquired = -1, executed = 0, completed = 0,
                rescheduled = 0, failed = 0, cancelled = 0,
            )
        }
    }

    @Test
    fun `QueueProcessingSummary rejects negative executed`() {
        assertFailsWith<IllegalArgumentException> {
            QueueProcessingSummary(
                acquired = 1, executed = -1, completed = 0,
                rescheduled = 0, failed = 0, cancelled = 0,
            )
        }
    }

    @Test
    fun `QueueProcessingSummary rejects negative completed`() {
        assertFailsWith<IllegalArgumentException> {
            QueueProcessingSummary(
                acquired = 1, executed = 1, completed = -1,
                rescheduled = 0, failed = 0, cancelled = 0,
            )
        }
    }

    @Test
    fun `QueueProcessingSummary rejects negative rescheduled`() {
        assertFailsWith<IllegalArgumentException> {
            QueueProcessingSummary(
                acquired = 1, executed = 1, completed = 0,
                rescheduled = -1, failed = 0, cancelled = 0,
            )
        }
    }

    @Test
    fun `QueueProcessingSummary rejects negative failed`() {
        assertFailsWith<IllegalArgumentException> {
            QueueProcessingSummary(
                acquired = 1, executed = 1, completed = 0,
                rescheduled = 0, failed = -1, cancelled = 0,
            )
        }
    }

    @Test
    fun `QueueProcessingSummary rejects negative cancelled`() {
        assertFailsWith<IllegalArgumentException> {
            QueueProcessingSummary(
                acquired = 1, executed = 1, completed = 0,
                rescheduled = 0, failed = 0, cancelled = -1,
            )
        }
    }

    @Test
    fun `QueueProcessingSummary rejects executed exceeding acquired`() {
        assertFailsWith<IllegalArgumentException> {
            QueueProcessingSummary(
                acquired = 2, executed = 3, completed = 0,
                rescheduled = 0, failed = 0, cancelled = 0,
            )
        }
    }

    @Test
    fun `QueueProcessingSummary rejects persisted total exceeding executed`() {
        assertFailsWith<IllegalArgumentException> {
            QueueProcessingSummary(
                acquired = 5, executed = 2, completed = 1,
                rescheduled = 1, failed = 1, cancelled = 0,
            )
        }
    }

    @Test
    fun `QueueProcessingSummary accepts valid partial counts`() {
        val summary = QueueProcessingSummary(
            acquired = 5, executed = 3, completed = 1,
            rescheduled = 1, failed = 1, cancelled = 0,
        )
        assertEquals(5, summary.acquired)
        assertEquals(3, summary.executed)
        assertEquals(1, summary.completed)
        assertEquals(1, summary.rescheduled)
        assertEquals(1, summary.failed)
        assertEquals(0, summary.cancelled)
    }

    // =========================================================================
    // QueueProcessingFailureStage tests
    // =========================================================================

    @Test
    fun `QueueProcessingFailureStage contains all required stages`() {
        val stages = QueueProcessingFailureStage.entries
        assertTrue(stages.any { it == QueueProcessingFailureStage.ACQUISITION })
        assertTrue(stages.any { it == QueueProcessingFailureStage.ACQUISITION_VALIDATION })
        assertTrue(stages.any { it == QueueProcessingFailureStage.COMPLETION_TRANSITION })
        assertTrue(stages.any { it == QueueProcessingFailureStage.RESCHEDULE_TRANSITION })
        assertTrue(stages.any { it == QueueProcessingFailureStage.FAILURE_TRANSITION })
        assertTrue(stages.any { it == QueueProcessingFailureStage.CANCELLATION_TRANSITION })
    }

    // =========================================================================
    // No-work path
    // =========================================================================

    @Test
    fun `NoEntries acquisition returns NoWork without invoking handler`() {
        val provider = FakeQueueProvider(
            acquireResponse = ProviderOperationResult.Success(QueueAcquireResult.NoEntries),
        )
        val handler = CapturingHandler(QueueEntryExecutionOutcome.Completed(t1))
        val processor = DurableQueueExecutionProcessor(provider, handler)

        val result = runSuspend { processor.process(sampleProcessingRequest) }

        assertIs<QueueProcessingResult.NoWork>(result)
        assertEquals(0, handler.capturedEntries.size)
        assertEquals(1, provider.acquireRequests.size)
    }

    @Test
    fun `NoWork result invokes no provider transitions`() {
        val provider = FakeQueueProvider(
            acquireResponse = ProviderOperationResult.Success(QueueAcquireResult.NoEntries),
        )
        val processor = DurableQueueExecutionProcessor(
            provider,
            CapturingHandler(QueueEntryExecutionOutcome.Completed(t1)),
        )
        runSuspend { processor.process(sampleProcessingRequest) }

        assertEquals(0, provider.completionRequests.size)
        assertEquals(0, provider.rescheduleRequests.size)
        assertEquals(0, provider.failureRequests.size)
        assertEquals(0, provider.cancellationRequests.size)
    }

    // =========================================================================
    // Acquisition failure
    // =========================================================================

    @Test
    fun `Acquisition failure returns QueueProviderFailure with ACQUISITION stage`() {
        val error = FakeError(code = ErrorCode("DL-ACQ-FAIL"))
        val provider = FakeQueueProvider(
            acquireResponse = ProviderOperationResult.Failure(error),
        )
        val handler = CapturingHandler(QueueEntryExecutionOutcome.Completed(t1))
        val processor = DurableQueueExecutionProcessor(provider, handler)

        val result = runSuspend { processor.process(sampleProcessingRequest) }

        val failure = assertIs<QueueProcessingResult.QueueProviderFailure>(result)
        assertSame(error, failure.error)
        assertEquals(QueueProcessingFailureStage.ACQUISITION, failure.stage)
        assertEquals(0, handler.capturedEntries.size)
    }

    @Test
    fun `Acquisition failure produces zero summary`() {
        val provider = FakeQueueProvider(
            acquireResponse = ProviderOperationResult.Failure(sampleError),
        )
        val processor = DurableQueueExecutionProcessor(
            provider,
            CapturingHandler(QueueEntryExecutionOutcome.Completed(t1)),
        )

        val result = runSuspend { processor.process(sampleProcessingRequest) }

        val failure = assertIs<QueueProcessingResult.QueueProviderFailure>(result)
        assertEquals(0, failure.summary.acquired)
        assertEquals(0, failure.summary.executed)
        assertEquals(0, failure.summary.completed)
    }

    @Test
    fun `Acquisition failure does not invoke handler`() {
        val provider = FakeQueueProvider(
            acquireResponse = ProviderOperationResult.Failure(sampleError),
        )
        val handler = CapturingHandler(QueueEntryExecutionOutcome.Completed(t1))
        val processor = DurableQueueExecutionProcessor(provider, handler)

        runSuspend { processor.process(sampleProcessingRequest) }

        assertEquals(0, handler.capturedEntries.size)
    }

    // =========================================================================
    // Acquisition validation
    // =========================================================================

    @Test
    fun `Duplicate QueueEntryId values are rejected as QueueContractViolation`() {
        val duplicateEntry = leasedEntry(id = QueueEntryId("entry-dup"))
        val provider = FakeQueueProvider(
            acquireResponse = entriesResult(duplicateEntry, duplicateEntry),
        )
        val handler = CapturingHandler(QueueEntryExecutionOutcome.Completed(t1))
        val processor = DurableQueueExecutionProcessor(provider, handler)

        val result = runSuspend { processor.process(sampleProcessingRequest) }

        assertIs<QueueProcessingResult.QueueContractViolation>(result)
        assertEquals(0, handler.capturedEntries.size)
    }

    @Test
    fun `Consumer identity mismatch is rejected as QueueContractViolation`() {
        val wrongConsumerLease = QueueLease(
            id = leaseId,
            consumerId = QueueConsumerId("wrong-consumer"),
            acquiredAt = t0,
            expiresAt = t1,
        )
        val mismatchedEntry = leasedEntry(lease = wrongConsumerLease)
        val provider = FakeQueueProvider(
            acquireResponse = entriesResult(mismatchedEntry, lease = wrongConsumerLease),
        )
        val handler = CapturingHandler(QueueEntryExecutionOutcome.Completed(t1))
        val processor = DurableQueueExecutionProcessor(provider, handler)

        val result = runSuspend { processor.process(sampleProcessingRequest) }

        assertIs<QueueProcessingResult.QueueContractViolation>(result)
        assertEquals(0, handler.capturedEntries.size)
    }

    @Test
    fun `QueueContractViolation preserves acquired count and zeroes other counters`() {
        val duplicateEntry = leasedEntry(id = QueueEntryId("entry-dup"))
        val provider = FakeQueueProvider(
            acquireResponse = entriesResult(duplicateEntry, duplicateEntry),
        )
        val processor = DurableQueueExecutionProcessor(
            provider,
            CapturingHandler(QueueEntryExecutionOutcome.Completed(t1)),
        )

        val result = runSuspend { processor.process(sampleProcessingRequest) }

        val violation = assertIs<QueueProcessingResult.QueueContractViolation>(result)
        assertEquals(2, violation.summary.acquired)
        assertEquals(0, violation.summary.executed)
        assertEquals(0, violation.summary.completed)
        assertEquals(0, violation.summary.rescheduled)
        assertEquals(0, violation.summary.failed)
        assertEquals(0, violation.summary.cancelled)
    }

    @Test
    fun `Invalid acquisition performs no queue transitions`() {
        val duplicateEntry = leasedEntry(id = QueueEntryId("entry-dup"))
        val provider = FakeQueueProvider(
            acquireResponse = entriesResult(duplicateEntry, duplicateEntry),
        )
        val processor = DurableQueueExecutionProcessor(
            provider,
            CapturingHandler(QueueEntryExecutionOutcome.Completed(t1)),
        )

        runSuspend { processor.process(sampleProcessingRequest) }

        assertEquals(0, provider.completionRequests.size)
        assertEquals(0, provider.rescheduleRequests.size)
        assertEquals(0, provider.failureRequests.size)
        assertEquals(0, provider.cancellationRequests.size)
    }

    // =========================================================================
    // Transition mapping — Completed outcome
    // =========================================================================

    @Test
    fun `Completed outcome invokes only the completion transition`() {
        val entry = leasedEntry()
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val handler = CapturingHandler(QueueEntryExecutionOutcome.Completed(completedAt = t2))
        val processor = DurableQueueExecutionProcessor(provider, handler)

        runSuspend { processor.process(sampleProcessingRequest) }

        assertEquals(1, provider.completionRequests.size)
        assertEquals(0, provider.rescheduleRequests.size)
        assertEquals(0, provider.failureRequests.size)
        assertEquals(0, provider.cancellationRequests.size)
    }

    @Test
    fun `Completion transition uses exact entry ID and lease ID`() {
        val entry = leasedEntry(id = QueueEntryId("entry-complete"))
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val handler = CapturingHandler(QueueEntryExecutionOutcome.Completed(completedAt = t2))
        val processor = DurableQueueExecutionProcessor(provider, handler)

        runSuspend { processor.process(sampleProcessingRequest) }

        val req = provider.completionRequests.single()
        assertEquals(QueueEntryId("entry-complete"), req.entryId)
        assertEquals(leaseId, req.leaseId)
        assertEquals(t2, req.completedAt)
    }

    @Test
    fun `Completed transition increments completed summary count`() {
        val entry = leasedEntry()
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val processor = DurableQueueExecutionProcessor(
            provider,
            CapturingHandler(QueueEntryExecutionOutcome.Completed(completedAt = t2)),
        )

        val result = runSuspend { processor.process(sampleProcessingRequest) }

        val processed = assertIs<QueueProcessingResult.Processed>(result)
        assertEquals(1, processed.summary.completed)
        assertEquals(0, processed.summary.rescheduled)
        assertEquals(0, processed.summary.failed)
        assertEquals(0, processed.summary.cancelled)
    }

    // =========================================================================
    // Transition mapping — Reschedule outcome
    // =========================================================================

    @Test
    fun `Reschedule outcome invokes only the reschedule transition`() {
        val entry = leasedEntry()
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val handler = CapturingHandler(
            QueueEntryExecutionOutcome.Reschedule(
                retryAttempt = RetryAttempt(1),
                availableAt = t2,
                error = sampleError,
            ),
        )
        val processor = DurableQueueExecutionProcessor(provider, handler)

        runSuspend { processor.process(sampleProcessingRequest) }

        assertEquals(0, provider.completionRequests.size)
        assertEquals(1, provider.rescheduleRequests.size)
        assertEquals(0, provider.failureRequests.size)
        assertEquals(0, provider.cancellationRequests.size)
    }

    @Test
    fun rescheduleTransitionUsesExactEntryIdLeaseIdAndOutcomeFields() {
        val entry = leasedEntry(id = QueueEntryId("entry-resched"))
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val attempt = RetryAttempt(3)
        val outcome = QueueEntryExecutionOutcome.Reschedule(
            retryAttempt = attempt,
            availableAt = t2,
            error = sampleError,
        )
        val processor = DurableQueueExecutionProcessor(provider, CapturingHandler(outcome))

        runSuspend { processor.process(sampleProcessingRequest) }

        val req = provider.rescheduleRequests.single()
        assertEquals(QueueEntryId("entry-resched"), req.entryId)
        assertEquals(leaseId, req.leaseId)
        assertEquals(attempt, req.retryAttempt)
        assertEquals(t2, req.availableAt)
        assertSame(sampleError, req.error)
    }

    @Test
    fun `Reschedule transition increments rescheduled summary count`() {
        val entry = leasedEntry()
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val processor = DurableQueueExecutionProcessor(
            provider,
            CapturingHandler(
                QueueEntryExecutionOutcome.Reschedule(
                    retryAttempt = RetryAttempt(1),
                    availableAt = t2,
                    error = sampleError,
                ),
            ),
        )

        val result = runSuspend { processor.process(sampleProcessingRequest) }

        val processed = assertIs<QueueProcessingResult.Processed>(result)
        assertEquals(1, processed.summary.rescheduled)
        assertEquals(0, processed.summary.completed)
        assertEquals(0, processed.summary.failed)
        assertEquals(0, processed.summary.cancelled)
    }

    // =========================================================================
    // Transition mapping — Failed outcome
    // =========================================================================

    @Test
    fun `Failed outcome invokes only the failure transition`() {
        val entry = leasedEntry()
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val handler = CapturingHandler(
            QueueEntryExecutionOutcome.Failed(
                error = sampleError,
                disposition = QueueFailureDisposition.FAILED,
            ),
        )
        val processor = DurableQueueExecutionProcessor(provider, handler)

        runSuspend { processor.process(sampleProcessingRequest) }

        assertEquals(0, provider.completionRequests.size)
        assertEquals(0, provider.rescheduleRequests.size)
        assertEquals(1, provider.failureRequests.size)
        assertEquals(0, provider.cancellationRequests.size)
    }

    @Test
    fun failureTransitionUsesExactEntryIdLeaseIdErrorAndDisposition() {
        val entry = leasedEntry(id = QueueEntryId("entry-fail"))
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val outcome = QueueEntryExecutionOutcome.Failed(
            error = sampleError,
            disposition = QueueFailureDisposition.DEAD_LETTER,
        )
        val processor = DurableQueueExecutionProcessor(provider, CapturingHandler(outcome))

        runSuspend { processor.process(sampleProcessingRequest) }

        val req = provider.failureRequests.single()
        assertEquals(QueueEntryId("entry-fail"), req.entryId)
        assertEquals(leaseId, req.leaseId)
        assertSame(sampleError, req.error)
        assertEquals(QueueFailureDisposition.DEAD_LETTER, req.disposition)
    }

    @Test
    fun `Failed transition increments failed summary count`() {
        val entry = leasedEntry()
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val processor = DurableQueueExecutionProcessor(
            provider,
            CapturingHandler(
                QueueEntryExecutionOutcome.Failed(
                    error = sampleError,
                    disposition = QueueFailureDisposition.FAILED,
                ),
            ),
        )

        val result = runSuspend { processor.process(sampleProcessingRequest) }

        val processed = assertIs<QueueProcessingResult.Processed>(result)
        assertEquals(1, processed.summary.failed)
        assertEquals(0, processed.summary.completed)
        assertEquals(0, processed.summary.rescheduled)
        assertEquals(0, processed.summary.cancelled)
    }

    // =========================================================================
    // Transition mapping — Cancelled outcome
    // =========================================================================

    @Test
    fun `Cancelled outcome invokes only the cancellation transition`() {
        val entry = leasedEntry()
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val handler = CapturingHandler(
            QueueEntryExecutionOutcome.Cancelled(context = sampleContext),
        )
        val processor = DurableQueueExecutionProcessor(provider, handler)

        runSuspend { processor.process(sampleProcessingRequest) }

        assertEquals(0, provider.completionRequests.size)
        assertEquals(0, provider.rescheduleRequests.size)
        assertEquals(0, provider.failureRequests.size)
        assertEquals(1, provider.cancellationRequests.size)
    }

    @Test
    fun `Cancellation transition uses exact entry ID and context`() {
        val entry = leasedEntry(id = QueueEntryId("entry-cancel"))
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val processor = DurableQueueExecutionProcessor(
            provider,
            CapturingHandler(QueueEntryExecutionOutcome.Cancelled(context = sampleContext)),
        )

        runSuspend { processor.process(sampleProcessingRequest) }

        val req = provider.cancellationRequests.single()
        assertEquals(QueueEntryId("entry-cancel"), req.entryId)
        assertSame(sampleContext, req.context)
    }

    @Test
    fun `Cancelled transition increments cancelled summary count`() {
        val entry = leasedEntry()
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val processor = DurableQueueExecutionProcessor(
            provider,
            CapturingHandler(QueueEntryExecutionOutcome.Cancelled(context = sampleContext)),
        )

        val result = runSuspend { processor.process(sampleProcessingRequest) }

        val processed = assertIs<QueueProcessingResult.Processed>(result)
        assertEquals(1, processed.summary.cancelled)
        assertEquals(0, processed.summary.completed)
        assertEquals(0, processed.summary.rescheduled)
        assertEquals(0, processed.summary.failed)
    }

    // =========================================================================
    // Sequential execution
    // =========================================================================

    @Test
    fun `Entries are executed sequentially in acquisition-result order`() {
        val entry1 = leasedEntry(id = QueueEntryId("entry-001"))
        val entry2 = leasedEntry(id = QueueEntryId("entry-002"))
        val entry3 = leasedEntry(id = QueueEntryId("entry-003"))
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry1, entry2, entry3))
        val handler = CapturingHandler(QueueEntryExecutionOutcome.Completed(t2))
        val processor = DurableQueueExecutionProcessor(provider, handler)

        runSuspend { processor.process(sampleProcessingRequest) }

        assertEquals(3, handler.capturedEntries.size)
        assertEquals(QueueEntryId("entry-001"), handler.capturedEntries[0].id)
        assertEquals(QueueEntryId("entry-002"), handler.capturedEntries[1].id)
        assertEquals(QueueEntryId("entry-003"), handler.capturedEntries[2].id)
    }

    @Test
    fun `Each acquired entry reaches the handler exactly once`() {
        val entry1 = leasedEntry(id = QueueEntryId("entry-001"))
        val entry2 = leasedEntry(id = QueueEntryId("entry-002"))
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry1, entry2))
        val handler = CapturingHandler(QueueEntryExecutionOutcome.Completed(t2))
        val processor = DurableQueueExecutionProcessor(provider, handler)

        runSuspend { processor.process(sampleProcessingRequest) }

        assertEquals(2, handler.capturedEntries.size)
    }

    @Test
    fun `Exact QueueEntry reaches handler unchanged`() {
        val entry = leasedEntry(id = QueueEntryId("entry-exact"))
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val handler = CapturingHandler(QueueEntryExecutionOutcome.Completed(t2))
        val processor = DurableQueueExecutionProcessor(provider, handler)

        runSuspend { processor.process(sampleProcessingRequest) }

        assertSame(entry, handler.capturedEntries.single())
    }

    @Test
    fun `Multi-entry processing produces accurate summary counts`() {
        val entry1 = leasedEntry(id = QueueEntryId("entry-001"))
        val entry2 = leasedEntry(id = QueueEntryId("entry-002"))
        val entry3 = leasedEntry(id = QueueEntryId("entry-003"))
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry1, entry2, entry3))
        val handler = MultiOutcomeHandler(
            listOf(
                QueueEntryExecutionOutcome.Completed(t2),
                QueueEntryExecutionOutcome.Failed(sampleError, QueueFailureDisposition.FAILED),
                QueueEntryExecutionOutcome.Reschedule(RetryAttempt(1), t3, sampleError),
            ),
        )
        val processor = DurableQueueExecutionProcessor(provider, handler)

        val result = runSuspend { processor.process(sampleProcessingRequest) }

        val processed = assertIs<QueueProcessingResult.Processed>(result)
        assertEquals(3, processed.summary.acquired)
        assertEquals(3, processed.summary.executed)
        assertEquals(1, processed.summary.completed)
        assertEquals(0, processed.summary.cancelled)
        assertEquals(1, processed.summary.failed)
        assertEquals(1, processed.summary.rescheduled)
    }

    // =========================================================================
    // Acquisition is called exactly once
    // =========================================================================

    @Test
    fun `Acquisition occurs exactly once per process call`() {
        val provider = FakeQueueProvider(
            acquireResponse = ProviderOperationResult.Success(QueueAcquireResult.NoEntries),
        )
        val processor = DurableQueueExecutionProcessor(
            provider,
            CapturingHandler(QueueEntryExecutionOutcome.Completed(t1)),
        )

        runSuspend { processor.process(sampleProcessingRequest) }

        assertEquals(1, provider.acquireRequests.size)
    }

    @Test
    fun `Exact QueueAcquireRequest is forwarded to provider`() {
        val provider = FakeQueueProvider(
            acquireResponse = ProviderOperationResult.Success(QueueAcquireResult.NoEntries),
        )
        val processor = DurableQueueExecutionProcessor(
            provider,
            CapturingHandler(QueueEntryExecutionOutcome.Completed(t1)),
        )

        runSuspend { processor.process(sampleProcessingRequest) }

        assertSame(sampleAcquireRequest, provider.acquireRequests.single())
    }

    // =========================================================================
    // Transition failure handling
    // =========================================================================

    @Test
    fun `Completion transition failure returns QueueProviderFailure with COMPLETION_TRANSITION stage`() {
        val error = FakeError(code = ErrorCode("DL-COMPLETE-FAIL"))
        val entry = leasedEntry()
        val provider = FakeQueueProvider(
            acquireResponse = entriesResult(entry),
            completeResponse = failureResult(error),
        )
        val processor = DurableQueueExecutionProcessor(
            provider,
            CapturingHandler(QueueEntryExecutionOutcome.Completed(t2)),
        )

        val result = runSuspend { processor.process(sampleProcessingRequest) }

        val failure = assertIs<QueueProcessingResult.QueueProviderFailure>(result)
        assertSame(error, failure.error)
        assertEquals(QueueProcessingFailureStage.COMPLETION_TRANSITION, failure.stage)
    }

    @Test
    fun `Reschedule transition failure returns QueueProviderFailure with RESCHEDULE_TRANSITION stage`() {
        val error = FakeError(code = ErrorCode("DL-RESCHED-FAIL"))
        val entry = leasedEntry()
        val provider = FakeQueueProvider(
            acquireResponse = entriesResult(entry),
            rescheduleResponse = failureResult(error),
        )
        val processor = DurableQueueExecutionProcessor(
            provider,
            CapturingHandler(
                QueueEntryExecutionOutcome.Reschedule(RetryAttempt(1), t2, sampleError),
            ),
        )

        val result = runSuspend { processor.process(sampleProcessingRequest) }

        val failure = assertIs<QueueProcessingResult.QueueProviderFailure>(result)
        assertSame(error, failure.error)
        assertEquals(QueueProcessingFailureStage.RESCHEDULE_TRANSITION, failure.stage)
    }

    @Test
    fun `Failure transition failure returns QueueProviderFailure with FAILURE_TRANSITION stage`() {
        val error = FakeError(code = ErrorCode("DL-FAIL-FAIL"))
        val entry = leasedEntry()
        val provider = FakeQueueProvider(
            acquireResponse = entriesResult(entry),
            failResponse = failureResult(error),
        )
        val processor = DurableQueueExecutionProcessor(
            provider,
            CapturingHandler(
                QueueEntryExecutionOutcome.Failed(sampleError, QueueFailureDisposition.FAILED),
            ),
        )

        val result = runSuspend { processor.process(sampleProcessingRequest) }

        val failure = assertIs<QueueProcessingResult.QueueProviderFailure>(result)
        assertSame(error, failure.error)
        assertEquals(QueueProcessingFailureStage.FAILURE_TRANSITION, failure.stage)
    }

    @Test
    fun `Cancellation transition failure returns QueueProviderFailure with CANCELLATION_TRANSITION stage`() {
        val error = FakeError(code = ErrorCode("DL-CANCEL-FAIL"))
        val entry = leasedEntry()
        val provider = FakeQueueProvider(
            acquireResponse = entriesResult(entry),
            cancelResponse = failureResult(error),
        )
        val processor = DurableQueueExecutionProcessor(
            provider,
            CapturingHandler(QueueEntryExecutionOutcome.Cancelled(sampleContext)),
        )

        val result = runSuspend { processor.process(sampleProcessingRequest) }

        val failure = assertIs<QueueProcessingResult.QueueProviderFailure>(result)
        assertSame(error, failure.error)
        assertEquals(QueueProcessingFailureStage.CANCELLATION_TRANSITION, failure.stage)
    }

    @Test
    fun `Transition failure stops later entry execution`() {
        val entry1 = leasedEntry(id = QueueEntryId("entry-001"))
        val entry2 = leasedEntry(id = QueueEntryId("entry-002"))
        val provider = FakeQueueProvider(
            acquireResponse = entriesResult(entry1, entry2),
            completeResponse = failureResult(sampleError),
        )
        val handler = CapturingHandler(QueueEntryExecutionOutcome.Completed(t2))
        val processor = DurableQueueExecutionProcessor(provider, handler)

        runSuspend { processor.process(sampleProcessingRequest) }

        assertEquals(1, handler.capturedEntries.size)
    }

    @Test
    fun `Transition failure preserves affected entry ID`() {
        val entry = leasedEntry(id = QueueEntryId("entry-affected"))
        val provider = FakeQueueProvider(
            acquireResponse = entriesResult(entry),
            completeResponse = failureResult(sampleError),
        )
        val processor = DurableQueueExecutionProcessor(
            provider,
            CapturingHandler(QueueEntryExecutionOutcome.Completed(t2)),
        )

        val result = runSuspend { processor.process(sampleProcessingRequest) }

        val failure = assertIs<QueueProcessingResult.QueueProviderFailure>(result)
        assertEquals(QueueEntryId("entry-affected"), failure.affectedEntryId)
    }

    @Test
    fun `Transition failure preserves lease ID`() {
        val entry = leasedEntry()
        val provider = FakeQueueProvider(
            acquireResponse = entriesResult(entry),
            completeResponse = failureResult(sampleError),
        )
        val processor = DurableQueueExecutionProcessor(
            provider,
            CapturingHandler(QueueEntryExecutionOutcome.Completed(t2)),
        )

        val result = runSuspend { processor.process(sampleProcessingRequest) }

        val failure = assertIs<QueueProcessingResult.QueueProviderFailure>(result)
        assertEquals(leaseId, failure.leaseId)
    }

    @Test
    fun `Transition failure preserves truthful partial summary`() {
        val entry1 = leasedEntry(id = QueueEntryId("entry-001"))
        val entry2 = leasedEntry(id = QueueEntryId("entry-002"))
        val entry3 = leasedEntry(id = QueueEntryId("entry-003"))
        val handler = MultiOutcomeHandler(
            listOf(
                QueueEntryExecutionOutcome.Completed(t2),
                QueueEntryExecutionOutcome.Completed(t2), // will fail on transition
                QueueEntryExecutionOutcome.Completed(t2), // should never be reached
            ),
        )
        var completeCallCount = 0
        val provider = object : FakeQueueProvider(acquireResponse = entriesResult(entry1, entry2, entry3)) {
            override suspend fun complete(request: QueueCompletionRequest): ProviderOperationResult<Unit> {
                completionRequests.add(request)
                return if (completeCallCount++ == 0) {
                    ProviderOperationResult.Success(Unit)
                } else {
                    ProviderOperationResult.Failure(sampleError)
                }
            }
        }
        val processor = DurableQueueExecutionProcessor(provider, handler)

        val result = runSuspend { processor.process(sampleProcessingRequest) }

        val failure = assertIs<QueueProcessingResult.QueueProviderFailure>(result)
        assertEquals(3, failure.summary.acquired)
        assertEquals(2, failure.summary.executed)
        assertEquals(1, failure.summary.completed)
    }

    @Test
    fun `Failed transition does not increment completed count`() {
        val entry = leasedEntry()
        val provider = FakeQueueProvider(
            acquireResponse = entriesResult(entry),
            completeResponse = failureResult(sampleError),
        )
        val processor = DurableQueueExecutionProcessor(
            provider,
            CapturingHandler(QueueEntryExecutionOutcome.Completed(t2)),
        )

        val result = runSuspend { processor.process(sampleProcessingRequest) }

        val failure = assertIs<QueueProcessingResult.QueueProviderFailure>(result)
        assertEquals(0, failure.summary.completed)
    }

    // =========================================================================
    // Cancellation propagation
    // =========================================================================

    @Test
    fun `CancellationException from acquisition propagates`() {
        val cancellation = CancellationException("Cancelled during acquisition")
        val provider = object : FakeQueueProvider() {
            override suspend fun acquire(
                request: QueueAcquireRequest,
            ): ProviderOperationResult<QueueAcquireResult> {
                throw cancellation
            }
        }
        val processor = DurableQueueExecutionProcessor(
            provider,
            CapturingHandler(QueueEntryExecutionOutcome.Completed(t1)),
        )

        val thrown = assertFailsWith<CancellationException> {
            runSuspend { processor.process(sampleProcessingRequest) }
        }
        assertSame(cancellation, thrown)
    }

    @Test
    fun `CancellationException from handler propagates`() {
        val cancellation = CancellationException("Cancelled in handler")
        val entry = leasedEntry()
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val processor = DurableQueueExecutionProcessor(
            provider,
            ThrowingHandler(cancellation),
        )

        val thrown = assertFailsWith<CancellationException> {
            runSuspend { processor.process(sampleProcessingRequest) }
        }
        assertSame(cancellation, thrown)
    }

    @Test
    fun `CancellationException from completion transition propagates`() {
        val cancellation = CancellationException("Cancelled in complete")
        val entry = leasedEntry()
        val provider = object : FakeQueueProvider(acquireResponse = entriesResult(entry)) {
            override suspend fun complete(request: QueueCompletionRequest): ProviderOperationResult<Unit> {
                throw cancellation
            }
        }
        val processor = DurableQueueExecutionProcessor(
            provider,
            CapturingHandler(QueueEntryExecutionOutcome.Completed(t2)),
        )

        val thrown = assertFailsWith<CancellationException> {
            runSuspend { processor.process(sampleProcessingRequest) }
        }
        assertSame(cancellation, thrown)
    }

    @Test
    fun `CancellationException from reschedule transition propagates`() {
        val cancellation = CancellationException("Cancelled in reschedule")
        val entry = leasedEntry()
        val provider = object : FakeQueueProvider(acquireResponse = entriesResult(entry)) {
            override suspend fun reschedule(request: QueueRescheduleRequest): ProviderOperationResult<Unit> {
                throw cancellation
            }
        }
        val processor = DurableQueueExecutionProcessor(
            provider,
            CapturingHandler(
                QueueEntryExecutionOutcome.Reschedule(RetryAttempt(1), t2, sampleError),
            ),
        )

        val thrown = assertFailsWith<CancellationException> {
            runSuspend { processor.process(sampleProcessingRequest) }
        }
        assertSame(cancellation, thrown)
    }

    @Test
    fun `CancellationException from fail transition propagates`() {
        val cancellation = CancellationException("Cancelled in fail")
        val entry = leasedEntry()
        val provider = object : FakeQueueProvider(acquireResponse = entriesResult(entry)) {
            override suspend fun fail(request: QueueFailureRequest): ProviderOperationResult<Unit> {
                throw cancellation
            }
        }
        val processor = DurableQueueExecutionProcessor(
            provider,
            CapturingHandler(
                QueueEntryExecutionOutcome.Failed(sampleError, QueueFailureDisposition.FAILED),
            ),
        )

        val thrown = assertFailsWith<CancellationException> {
            runSuspend { processor.process(sampleProcessingRequest) }
        }
        assertSame(cancellation, thrown)
    }

    @Test
    fun `CancellationException from cancel transition propagates`() {
        val cancellation = CancellationException("Cancelled in cancel")
        val entry = leasedEntry()
        val provider = object : FakeQueueProvider(acquireResponse = entriesResult(entry)) {
            override suspend fun cancel(request: QueueCancellationRequest): ProviderOperationResult<Unit> {
                throw cancellation
            }
        }
        val processor = DurableQueueExecutionProcessor(
            provider,
            CapturingHandler(QueueEntryExecutionOutcome.Cancelled(sampleContext)),
        )

        val thrown = assertFailsWith<CancellationException> {
            runSuspend { processor.process(sampleProcessingRequest) }
        }
        assertSame(cancellation, thrown)
    }

    @Test
    fun `Thrown CancellationException creates no queue cancellation transition`() {
        val cancellation = CancellationException("Cancelled in handler")
        val entry = leasedEntry()
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val processor = DurableQueueExecutionProcessor(
            provider,
            ThrowingHandler(cancellation),
        )

        try {
            runSuspend { processor.process(sampleProcessingRequest) }
        } catch (_: CancellationException) {
            // expected
        }

        assertEquals(0, provider.cancellationRequests.size)
        assertEquals(0, provider.completionRequests.size)
        assertEquals(0, provider.rescheduleRequests.size)
        assertEquals(0, provider.failureRequests.size)
    }

    // =========================================================================
    // Explicit Cancelled vs thrown CancellationException distinction
    // =========================================================================

    @Test
    fun `Explicit Cancelled outcome is distinct from thrown CancellationException`() {
        val entry = leasedEntry()
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry))
        val processor = DurableQueueExecutionProcessor(
            provider,
            CapturingHandler(QueueEntryExecutionOutcome.Cancelled(sampleContext)),
        )

        val result = runSuspend { processor.process(sampleProcessingRequest) }

        // Explicit Cancelled produces a Processed result with cancellation transition
        val processed = assertIs<QueueProcessingResult.Processed>(result)
        assertEquals(1, processed.summary.cancelled)
        assertEquals(1, provider.cancellationRequests.size)
    }

    // =========================================================================
    // Business-level Failed and Cancelled may continue after transition
    // =========================================================================

    @Test
    fun `Business-level Failed outcome continues to next entry after successful transition`() {
        val entry1 = leasedEntry(id = QueueEntryId("entry-001"))
        val entry2 = leasedEntry(id = QueueEntryId("entry-002"))
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry1, entry2))
        val handler = CapturingHandler(
            QueueEntryExecutionOutcome.Failed(sampleError, QueueFailureDisposition.FAILED),
        )
        val processor = DurableQueueExecutionProcessor(provider, handler)

        val result = runSuspend { processor.process(sampleProcessingRequest) }

        val processed = assertIs<QueueProcessingResult.Processed>(result)
        assertEquals(2, processed.summary.executed)
        assertEquals(2, processed.summary.failed)
    }

    @Test
    fun `Explicit Cancelled outcome continues to next entry after successful transition`() {
        val entry1 = leasedEntry(id = QueueEntryId("entry-001"))
        val entry2 = leasedEntry(id = QueueEntryId("entry-002"))
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry1, entry2))
        val handler = CapturingHandler(QueueEntryExecutionOutcome.Cancelled(sampleContext))
        val processor = DurableQueueExecutionProcessor(provider, handler)

        val result = runSuspend { processor.process(sampleProcessingRequest) }

        val processed = assertIs<QueueProcessingResult.Processed>(result)
        assertEquals(2, processed.summary.executed)
        assertEquals(2, processed.summary.cancelled)
    }

    // =========================================================================
    // Summary — acquired count for successful processing
    // =========================================================================

    @Test
    fun `Processed result reflects correct acquired count`() {
        val entry1 = leasedEntry(id = QueueEntryId("entry-001"))
        val entry2 = leasedEntry(id = QueueEntryId("entry-002"))
        val provider = FakeQueueProvider(acquireResponse = entriesResult(entry1, entry2))
        val processor = DurableQueueExecutionProcessor(
            provider,
            CapturingHandler(QueueEntryExecutionOutcome.Completed(t2)),
        )

        val result = runSuspend { processor.process(sampleProcessingRequest) }

        val processed = assertIs<QueueProcessingResult.Processed>(result)
        assertEquals(2, processed.summary.acquired)
        assertEquals(2, processed.summary.executed)
    }
}
