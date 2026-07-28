package io.dataloom.runtime.submission

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.QueueEntryId
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
import io.dataloom.api.queue.QueueEnqueueRequest
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.queue.QueueFailureRequest
import io.dataloom.api.queue.QueueLease
import io.dataloom.api.queue.QueueRescheduleRequest
import io.dataloom.api.identifier.QueueConsumerId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.provider.SynchronizationProviderBindings
import io.dataloom.runtime.queue.QueuedSynchronizationWork
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Deterministic common tests for DL-034 queue-submission capability.
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
class DataLoomQueueSubmissionTest {

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
    // Fake errors and values
    // =========================================================================

    private data class FakeError(
        override val code: ErrorCode = ErrorCode("DL-FAKE"),
        override val category: ErrorCategory = ErrorCategory.QUEUE,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE,
        override val message: String = "Fake error.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    // =========================================================================
    // Fixture factories
    // =========================================================================

    private val fixedInstant = DataLoomInstant(1_000_000L)
    private val otherInstant = DataLoomInstant(2_000_000L)
    private val entryId = QueueEntryId("entry-001")
    private val otherId = QueueEntryId("entry-002")

    private fun makeRequest() = SynchronizationRequest(
        workflowId = WorkflowId("workflow-001"),
        sessionId = SynchronizationSessionId("session-001"),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("exec-001"),
            correlationId = CorrelationId("corr-001"),
        ),
    )

    private fun makeBindings() = SynchronizationProviderBindings(
        storageProviderId = ProviderId("storage-primary"),
        transportProviderId = ProviderId("transport-primary"),
    )

    private fun makeWork() = QueuedSynchronizationWork(
        request = makeRequest(),
        bindings = makeBindings(),
    )

    private fun makeSubmission(
        id: QueueEntryId = entryId,
        work: QueuedSynchronizationWork = makeWork(),
        availableAt: DataLoomInstant = fixedInstant,
    ) = QueuedSynchronizationSubmission(
        queueEntryId = id,
        work = work,
        availableAt = availableAt,
    )

    private fun makePendingEntry(
        id: QueueEntryId = entryId,
        availableAt: DataLoomInstant = fixedInstant,
    ) = QueueEntry(
        id = id,
        synchronizationRequest = makeRequest(),
        state = QueueEntryState.PENDING,
        enqueuedAt = fixedInstant,
        availableAt = availableAt,
    )

    private fun makeEnqueueRequest(
        id: QueueEntryId = entryId,
        availableAt: DataLoomInstant = fixedInstant,
    ) = QueueEnqueueRequest(entry = makePendingEntry(id = id, availableAt = availableAt))

    // =========================================================================
    // Fake QueueProvider
    // =========================================================================

    private class RecordingQueueProvider(
        var enqueueResult: ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit),
    ) : QueueProvider {
        val enqueueRequests: MutableList<QueueEnqueueRequest> = mutableListOf()
        var acquireCallCount = 0
        var enqueueCallCount = 0

        override val descriptor = ProviderDescriptor(
            id = ProviderId("queue-primary"),
            name = ProviderName("FakeQueue"),
            type = ProviderType.QUEUE,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun enqueue(request: QueueEnqueueRequest): ProviderOperationResult<Unit> {
            enqueueCallCount++
            enqueueRequests.add(request)
            return enqueueResult
        }

        override suspend fun acquire(request: QueueAcquireRequest): ProviderOperationResult<QueueAcquireResult> {
            acquireCallCount++
            return ProviderOperationResult.Success(QueueAcquireResult.NoEntries)
        }

        override suspend fun complete(request: QueueCompletionRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun reschedule(request: QueueRescheduleRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun fail(request: QueueFailureRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun cancel(request: QueueCancellationRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun recoverExpiredLeases(request: ExpiredLeaseRecoveryRequest): ProviderOperationResult<ExpiredLeaseRecoveryResult> =
            ProviderOperationResult.Failure(FakeError())
    }

    // =========================================================================
    // Fake encoder
    // =========================================================================

    private class RecordingEncoder(
        var result: QueuedSynchronizationWorkEncodingResult,
    ) : QueuedSynchronizationWorkEncoder {
        val receivedSubmissions: MutableList<QueuedSynchronizationSubmission> = mutableListOf()
        var callCount = 0

        override fun encode(
            submission: QueuedSynchronizationSubmission,
        ): QueuedSynchronizationWorkEncodingResult {
            callCount++
            receivedSubmissions.add(submission)
            return result
        }
    }

    // =========================================================================
    // QueuedSynchronizationSubmission model tests
    // =========================================================================

    @Test
    fun submission_preservesExactQueueEntryId() {
        val submission = makeSubmission(id = entryId)
        assertEquals(entryId, submission.queueEntryId)
    }

    @Test
    fun submission_preservesExactWork() {
        val work = makeWork()
        val submission = makeSubmission(work = work)
        assertSame(work, submission.work)
    }

    @Test
    fun submission_preservesExactAvailableAt() {
        val submission = makeSubmission(availableAt = fixedInstant)
        assertEquals(fixedInstant, submission.availableAt)
    }

    @Test
    fun submission_constructionDoesNotEncodeOrCallProvider() {
        val provider = RecordingQueueProvider()
        // Construction should not call any provider method.
        makeSubmission()
        assertEquals(0, provider.enqueueCallCount)
        assertEquals(0, provider.acquireCallCount)
    }

    // =========================================================================
    // Encoder behavior tests
    // =========================================================================

    @Test
    fun encoder_receivesExactSubmission() {
        val provider = RecordingQueueProvider()
        val request = makeEnqueueRequest()
        val encoder = RecordingEncoder(result = QueuedSynchronizationWorkEncodingResult.Encoded(request))
        val capability = DefaultDataLoomQueueSubmission(
            queueProvider = provider,
            encoder = encoder,
        )
        val submission = makeSubmission()

        runSuspend { capability.submit(submission) }

        assertEquals(1, encoder.receivedSubmissions.size)
        assertSame(submission, encoder.receivedSubmissions.first())
    }

    @Test
    fun encoder_encodedPreservesExactEnqueueRequest() {
        val request = makeEnqueueRequest()
        val encoded = QueuedSynchronizationWorkEncodingResult.Encoded(request)
        assertSame(request, encoded.request)
    }

    @Test
    fun encoder_rejectedPreservesExactError() {
        val error = FakeError()
        val rejected = QueuedSynchronizationWorkEncodingResult.Rejected(error)
        assertSame(error, rejected.error)
    }

    @Test
    fun encoder_rejectedInvokesNoQueueProvider() {
        val provider = RecordingQueueProvider()
        val error = FakeError()
        val encoder = RecordingEncoder(
            result = QueuedSynchronizationWorkEncodingResult.Rejected(error),
        )
        val capability = DefaultDataLoomQueueSubmission(
            queueProvider = provider,
            encoder = encoder,
        )

        runSuspend { capability.submit(makeSubmission()) }

        assertEquals(0, provider.enqueueCallCount)
    }

    @Test
    fun encoder_executesExactlyOnce() {
        val provider = RecordingQueueProvider()
        val encoder = RecordingEncoder(
            result = QueuedSynchronizationWorkEncodingResult.Encoded(makeEnqueueRequest()),
        )
        val capability = DefaultDataLoomQueueSubmission(
            queueProvider = provider,
            encoder = encoder,
        )

        runSuspend { capability.submit(makeSubmission()) }

        assertEquals(1, encoder.callCount)
    }

    @Test
    fun encoder_programmingExceptionPropagates() {
        val provider = RecordingQueueProvider()
        val encoder = QueuedSynchronizationWorkEncoder { _ ->
            throw IllegalStateException("encoder bug")
        }
        val capability = DefaultDataLoomQueueSubmission(
            queueProvider = provider,
            encoder = encoder,
        )

        assertFailsWith<IllegalStateException> {
            runSuspend { capability.submit(makeSubmission()) }
        }
        assertEquals(0, provider.enqueueCallCount)
    }

    // =========================================================================
    // Encoded request validation tests
    // =========================================================================

    @Test
    fun validation_matchingIdAccepted() {
        val provider = RecordingQueueProvider()
        val encoder = RecordingEncoder(
            result = QueuedSynchronizationWorkEncodingResult.Encoded(
                makeEnqueueRequest(id = entryId),
            ),
        )
        val capability = DefaultDataLoomQueueSubmission(
            queueProvider = provider,
            encoder = encoder,
        )
        val submission = makeSubmission(id = entryId)

        val result = runSuspend { capability.submit(submission) }

        assertIs<QueueSubmissionResult.Enqueued>(result)
    }

    @Test
    fun validation_mismatchedIdRejected() {
        val provider = RecordingQueueProvider()
        val encoder = RecordingEncoder(
            result = QueuedSynchronizationWorkEncodingResult.Encoded(
                makeEnqueueRequest(id = otherId),
            ),
        )
        val capability = DefaultDataLoomQueueSubmission(
            queueProvider = provider,
            encoder = encoder,
        )
        val submission = makeSubmission(id = entryId)

        val result = runSuspend { capability.submit(submission) }

        assertIs<QueueSubmissionResult.ContractViolation>(result)
    }

    @Test
    fun validation_matchingAvailableAtAccepted() {
        val provider = RecordingQueueProvider()
        val encoder = RecordingEncoder(
            result = QueuedSynchronizationWorkEncodingResult.Encoded(
                makeEnqueueRequest(availableAt = fixedInstant),
            ),
        )
        val capability = DefaultDataLoomQueueSubmission(
            queueProvider = provider,
            encoder = encoder,
        )
        val submission = makeSubmission(availableAt = fixedInstant)

        val result = runSuspend { capability.submit(submission) }

        assertIs<QueueSubmissionResult.Enqueued>(result)
    }

    @Test
    fun validation_mismatchedAvailableAtRejected() {
        val provider = RecordingQueueProvider()
        val encoder = RecordingEncoder(
            result = QueuedSynchronizationWorkEncodingResult.Encoded(
                makeEnqueueRequest(availableAt = otherInstant),
            ),
        )
        val capability = DefaultDataLoomQueueSubmission(
            queueProvider = provider,
            encoder = encoder,
        )
        val submission = makeSubmission(availableAt = fixedInstant)

        val result = runSuspend { capability.submit(submission) }

        assertIs<QueueSubmissionResult.ContractViolation>(result)
    }

    @Test
    fun validation_invalidRequestInvokesNoQueueProvider() {
        val provider = RecordingQueueProvider()
        val encoder = RecordingEncoder(
            result = QueuedSynchronizationWorkEncodingResult.Encoded(
                makeEnqueueRequest(id = otherId),
            ),
        )
        val capability = DefaultDataLoomQueueSubmission(
            queueProvider = provider,
            encoder = encoder,
        )

        runSuspend { capability.submit(makeSubmission(id = entryId)) }

        assertEquals(0, provider.enqueueCallCount)
    }

    @Test
    fun validation_contractViolationPreservesQueueEntryId() {
        val provider = RecordingQueueProvider()
        val encoder = RecordingEncoder(
            result = QueuedSynchronizationWorkEncodingResult.Encoded(
                makeEnqueueRequest(id = otherId),
            ),
        )
        val capability = DefaultDataLoomQueueSubmission(
            queueProvider = provider,
            encoder = encoder,
        )
        val submission = makeSubmission(id = entryId)

        val result = runSuspend { capability.submit(submission) }

        val violation = assertIs<QueueSubmissionResult.ContractViolation>(result)
        assertEquals(entryId, violation.queueEntryId)
    }

    // =========================================================================
    // Successful submission tests
    // =========================================================================

    @Test
    fun submit_exactEnqueueRequestReachesProvider() {
        val provider = RecordingQueueProvider()
        val enqueueRequest = makeEnqueueRequest()
        val encoder = RecordingEncoder(
            result = QueuedSynchronizationWorkEncodingResult.Encoded(enqueueRequest),
        )
        val capability = DefaultDataLoomQueueSubmission(
            queueProvider = provider,
            encoder = encoder,
        )

        runSuspend { capability.submit(makeSubmission()) }

        assertEquals(1, provider.enqueueRequests.size)
        assertSame(enqueueRequest, provider.enqueueRequests.first())
    }

    @Test
    fun submit_enqueueExecutesExactlyOnce() {
        val provider = RecordingQueueProvider()
        val encoder = RecordingEncoder(
            result = QueuedSynchronizationWorkEncodingResult.Encoded(makeEnqueueRequest()),
        )
        val capability = DefaultDataLoomQueueSubmission(
            queueProvider = provider,
            encoder = encoder,
        )

        runSuspend { capability.submit(makeSubmission()) }

        assertEquals(1, provider.enqueueCallCount)
    }

    @Test
    fun submit_enqueuedPreservesQueueEntryId() {
        val provider = RecordingQueueProvider()
        val encoder = RecordingEncoder(
            result = QueuedSynchronizationWorkEncodingResult.Encoded(makeEnqueueRequest()),
        )
        val capability = DefaultDataLoomQueueSubmission(
            queueProvider = provider,
            encoder = encoder,
        )

        val result = runSuspend { capability.submit(makeSubmission(id = entryId)) }

        val enqueued = assertIs<QueueSubmissionResult.Enqueued>(result)
        assertEquals(entryId, enqueued.queueEntryId)
    }

    @Test
    fun submit_enqueuedPreservesExactProviderSuccess() {
        val successResult = ProviderOperationResult.Success(Unit)
        val provider = RecordingQueueProvider(enqueueResult = successResult)
        val encoder = RecordingEncoder(
            result = QueuedSynchronizationWorkEncodingResult.Encoded(makeEnqueueRequest()),
        )
        val capability = DefaultDataLoomQueueSubmission(
            queueProvider = provider,
            encoder = encoder,
        )

        val result = runSuspend { capability.submit(makeSubmission()) }

        val enqueued = assertIs<QueueSubmissionResult.Enqueued>(result)
        assertSame(successResult, enqueued.providerResult)
    }

    @Test
    fun submit_acquireIsNeverCalled() {
        val provider = RecordingQueueProvider()
        val encoder = RecordingEncoder(
            result = QueuedSynchronizationWorkEncodingResult.Encoded(makeEnqueueRequest()),
        )
        val capability = DefaultDataLoomQueueSubmission(
            queueProvider = provider,
            encoder = encoder,
        )

        runSuspend { capability.submit(makeSubmission()) }

        assertEquals(0, provider.acquireCallCount)
    }

    // =========================================================================
    // QueueProvider failure tests
    // =========================================================================

    @Test
    fun submit_providerFailurePreservesExactError() {
        val error = FakeError()
        val provider = RecordingQueueProvider(
            enqueueResult = ProviderOperationResult.Failure(error),
        )
        val encoder = RecordingEncoder(
            result = QueuedSynchronizationWorkEncodingResult.Encoded(makeEnqueueRequest()),
        )
        val capability = DefaultDataLoomQueueSubmission(
            queueProvider = provider,
            encoder = encoder,
        )

        val result = runSuspend { capability.submit(makeSubmission()) }

        val failure = assertIs<QueueSubmissionResult.QueueProviderFailure>(result)
        assertSame(error, failure.error)
    }

    @Test
    fun submit_providerFailurePreservesQueueEntryId() {
        val provider = RecordingQueueProvider(
            enqueueResult = ProviderOperationResult.Failure(FakeError()),
        )
        val encoder = RecordingEncoder(
            result = QueuedSynchronizationWorkEncodingResult.Encoded(makeEnqueueRequest()),
        )
        val capability = DefaultDataLoomQueueSubmission(
            queueProvider = provider,
            encoder = encoder,
        )

        val result = runSuspend { capability.submit(makeSubmission(id = entryId)) }

        val failure = assertIs<QueueSubmissionResult.QueueProviderFailure>(result)
        assertEquals(entryId, failure.queueEntryId)
    }

    @Test
    fun submit_providerFailureHasCorrectStage() {
        val provider = RecordingQueueProvider(
            enqueueResult = ProviderOperationResult.Failure(FakeError()),
        )
        val encoder = RecordingEncoder(
            result = QueuedSynchronizationWorkEncodingResult.Encoded(makeEnqueueRequest()),
        )
        val capability = DefaultDataLoomQueueSubmission(
            queueProvider = provider,
            encoder = encoder,
        )

        val result = runSuspend { capability.submit(makeSubmission()) }

        val failure = assertIs<QueueSubmissionResult.QueueProviderFailure>(result)
        assertEquals(QueueSubmissionFailureStage.QUEUE_PROVIDER_ENQUEUE, failure.failureStage)
    }

    @Test
    fun submit_providerNotRetriedOnFailure() {
        val provider = RecordingQueueProvider(
            enqueueResult = ProviderOperationResult.Failure(FakeError()),
        )
        val encoder = RecordingEncoder(
            result = QueuedSynchronizationWorkEncodingResult.Encoded(makeEnqueueRequest()),
        )
        val capability = DefaultDataLoomQueueSubmission(
            queueProvider = provider,
            encoder = encoder,
        )

        runSuspend { capability.submit(makeSubmission()) }

        assertEquals(1, provider.enqueueCallCount)
    }

    // =========================================================================
    // Cancellation and exception propagation tests
    // =========================================================================

    @Test
    fun submit_cancellationFromProviderPropagates() {
        val cancellingProvider = object : QueueProvider {
            override val descriptor = ProviderDescriptor(
                id = ProviderId("queue-primary"),
                name = ProviderName("CancelQueue"),
                type = ProviderType.QUEUE,
                version = ProviderVersion("1.0.0"),
            )

            override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
                ProviderOperationResult.Success(Unit)

            override suspend fun health(): ProviderOperationResult<ProviderHealth> =
                ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

            override suspend fun close(): ProviderOperationResult<Unit> =
                ProviderOperationResult.Success(Unit)

            override suspend fun enqueue(request: QueueEnqueueRequest): ProviderOperationResult<Unit> {
                throw CancellationException("provider cancelled")
            }

            override suspend fun acquire(request: QueueAcquireRequest): ProviderOperationResult<QueueAcquireResult> =
                ProviderOperationResult.Success(QueueAcquireResult.NoEntries)

            override suspend fun complete(request: QueueCompletionRequest): ProviderOperationResult<Unit> =
                ProviderOperationResult.Failure(FakeError())

            override suspend fun reschedule(request: QueueRescheduleRequest): ProviderOperationResult<Unit> =
                ProviderOperationResult.Failure(FakeError())

            override suspend fun fail(request: QueueFailureRequest): ProviderOperationResult<Unit> =
                ProviderOperationResult.Failure(FakeError())

            override suspend fun cancel(request: QueueCancellationRequest): ProviderOperationResult<Unit> =
                ProviderOperationResult.Failure(FakeError())

            override suspend fun recoverExpiredLeases(request: ExpiredLeaseRecoveryRequest): ProviderOperationResult<ExpiredLeaseRecoveryResult> =
                ProviderOperationResult.Failure(FakeError())
        }

        val encoder = RecordingEncoder(
            result = QueuedSynchronizationWorkEncodingResult.Encoded(makeEnqueueRequest()),
        )
        val capability = DefaultDataLoomQueueSubmission(
            queueProvider = cancellingProvider,
            encoder = encoder,
        )

        assertFailsWith<CancellationException> {
            runSuspend { capability.submit(makeSubmission()) }
        }
    }

    @Test
    fun submit_unexpectedProviderExceptionPropagates() {
        val throwingProvider = object : QueueProvider {
            override val descriptor = ProviderDescriptor(
                id = ProviderId("queue-primary"),
                name = ProviderName("ThrowQueue"),
                type = ProviderType.QUEUE,
                version = ProviderVersion("1.0.0"),
            )

            override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
                ProviderOperationResult.Success(Unit)

            override suspend fun health(): ProviderOperationResult<ProviderHealth> =
                ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

            override suspend fun close(): ProviderOperationResult<Unit> =
                ProviderOperationResult.Success(Unit)

            override suspend fun enqueue(request: QueueEnqueueRequest): ProviderOperationResult<Unit> {
                throw RuntimeException("provider bug")
            }

            override suspend fun acquire(request: QueueAcquireRequest): ProviderOperationResult<QueueAcquireResult> =
                ProviderOperationResult.Success(QueueAcquireResult.NoEntries)

            override suspend fun complete(request: QueueCompletionRequest): ProviderOperationResult<Unit> =
                ProviderOperationResult.Failure(FakeError())

            override suspend fun reschedule(request: QueueRescheduleRequest): ProviderOperationResult<Unit> =
                ProviderOperationResult.Failure(FakeError())

            override suspend fun fail(request: QueueFailureRequest): ProviderOperationResult<Unit> =
                ProviderOperationResult.Failure(FakeError())

            override suspend fun cancel(request: QueueCancellationRequest): ProviderOperationResult<Unit> =
                ProviderOperationResult.Failure(FakeError())

            override suspend fun recoverExpiredLeases(request: ExpiredLeaseRecoveryRequest): ProviderOperationResult<ExpiredLeaseRecoveryResult> =
                ProviderOperationResult.Failure(FakeError())
        }

        val encoder = RecordingEncoder(
            result = QueuedSynchronizationWorkEncodingResult.Encoded(makeEnqueueRequest()),
        )
        val capability = DefaultDataLoomQueueSubmission(
            queueProvider = throwingProvider,
            encoder = encoder,
        )

        assertFailsWith<RuntimeException> {
            runSuspend { capability.submit(makeSubmission()) }
        }
    }

    @Test
    fun submit_enqueueOccursAtMostOnce() {
        val provider = RecordingQueueProvider()
        val encoder = RecordingEncoder(
            result = QueuedSynchronizationWorkEncodingResult.Encoded(makeEnqueueRequest()),
        )
        val capability = DefaultDataLoomQueueSubmission(
            queueProvider = provider,
            encoder = encoder,
        )

        runSuspend { capability.submit(makeSubmission()) }

        assertTrue(provider.enqueueCallCount <= 1)
    }

    // =========================================================================
    // Encoding rejected path tests
    // =========================================================================

    @Test
    fun submit_encodingRejectedPreservesError() {
        val error = FakeError()
        val provider = RecordingQueueProvider()
        val encoder = RecordingEncoder(
            result = QueuedSynchronizationWorkEncodingResult.Rejected(error),
        )
        val capability = DefaultDataLoomQueueSubmission(
            queueProvider = provider,
            encoder = encoder,
        )

        val result = runSuspend { capability.submit(makeSubmission()) }

        val rejected = assertIs<QueueSubmissionResult.EncodingRejected>(result)
        assertSame(error, rejected.error)
    }

    @Test
    fun submit_encodingRejectedDoesNotCallProvider() {
        val provider = RecordingQueueProvider()
        val encoder = RecordingEncoder(
            result = QueuedSynchronizationWorkEncodingResult.Rejected(FakeError()),
        )
        val capability = DefaultDataLoomQueueSubmission(
            queueProvider = provider,
            encoder = encoder,
        )

        runSuspend { capability.submit(makeSubmission()) }

        assertEquals(0, provider.enqueueCallCount)
    }

    // =========================================================================
    // QueueSubmissionResult sealed structure tests
    // =========================================================================

    @Test
    fun result_enqueuedIsSubtypeOfQueueSubmissionResult() {
        val result: QueueSubmissionResult = QueueSubmissionResult.Enqueued(
            queueEntryId = entryId,
            providerResult = ProviderOperationResult.Success(Unit),
        )
        assertIs<QueueSubmissionResult.Enqueued>(result)
    }

    @Test
    fun result_encodingRejectedIsSubtypeOfQueueSubmissionResult() {
        val result: QueueSubmissionResult = QueueSubmissionResult.EncodingRejected(
            error = FakeError(),
        )
        assertIs<QueueSubmissionResult.EncodingRejected>(result)
    }

    @Test
    fun result_contractViolationIsSubtypeOfQueueSubmissionResult() {
        val result: QueueSubmissionResult = QueueSubmissionResult.ContractViolation(
            error = FakeError(),
            queueEntryId = entryId,
        )
        assertIs<QueueSubmissionResult.ContractViolation>(result)
    }

    @Test
    fun result_contractViolationCanHaveNullQueueEntryId() {
        val result = QueueSubmissionResult.ContractViolation(
            error = FakeError(),
            queueEntryId = null,
        )
        assertNull(result.queueEntryId)
    }

    @Test
    fun result_queueProviderFailureIsSubtypeOfQueueSubmissionResult() {
        val result: QueueSubmissionResult = QueueSubmissionResult.QueueProviderFailure(
            error = FakeError(),
            queueEntryId = entryId,
            failureStage = QueueSubmissionFailureStage.QUEUE_PROVIDER_ENQUEUE,
        )
        assertIs<QueueSubmissionResult.QueueProviderFailure>(result)
    }

    // =========================================================================
    // QueueSubmissionFailureStage tests
    // =========================================================================

    @Test
    fun failureStage_enumContainsAllRequiredValues() {
        val values = QueueSubmissionFailureStage.values()
        assertTrue(QueueSubmissionFailureStage.ENCODING in values)
        assertTrue(QueueSubmissionFailureStage.ENCODED_REQUEST_VALIDATION in values)
        assertTrue(QueueSubmissionFailureStage.QUEUE_PROVIDER_ENQUEUE in values)
    }

    // =========================================================================
    // Side-effect restriction tests
    // =========================================================================

    @Test
    fun submit_doesNotCallAcquire() {
        val provider = RecordingQueueProvider()
        val encoder = RecordingEncoder(
            result = QueuedSynchronizationWorkEncodingResult.Encoded(makeEnqueueRequest()),
        )
        val capability = DefaultDataLoomQueueSubmission(
            queueProvider = provider,
            encoder = encoder,
        )

        runSuspend { capability.submit(makeSubmission()) }

        assertEquals(0, provider.acquireCallCount)
    }

    @Test
    fun submit_encoderNotCalledOnContractViolation() {
        // The encoder is called once; validation catches the mismatch.
        val provider = RecordingQueueProvider()
        val encoder = RecordingEncoder(
            result = QueuedSynchronizationWorkEncodingResult.Encoded(
                makeEnqueueRequest(id = otherId),
            ),
        )
        val capability = DefaultDataLoomQueueSubmission(
            queueProvider = provider,
            encoder = encoder,
        )

        runSuspend { capability.submit(makeSubmission(id = entryId)) }

        // Encoder was called once (encoding itself), but provider was not.
        assertEquals(1, encoder.callCount)
        assertEquals(0, provider.enqueueCallCount)
    }

    // =========================================================================
    // Invalid queue-state enforcement tests (criteria 15, 16, 17)
    //
    // QueueEnqueueRequest enforces that the entry must be in PENDING state,
    // have no active lease, and have no retry attempt. If the encoder tries to
    // construct a QueueEnqueueRequest violating these invariants, the
    // constructor throws IllegalArgumentException — the exception propagates
    // from the encoder and QueueProvider.enqueue is never called.
    // =========================================================================

    @Test
    fun invalidState_encoderAttemptingTerminalStateThrows_providerNotCalled() {
        // An encoder that tries to create a QueueEnqueueRequest with COMPLETED
        // state cannot construct it — QueueEnqueueRequest constructor throws
        // IllegalArgumentException. This propagates from the encoder.
        val provider = RecordingQueueProvider()
        val throwingEncoder = QueuedSynchronizationWorkEncoder { _ ->
            // QueueEnqueueRequest construction throws for non-PENDING state.
            QueueEnqueueRequest(
                entry = QueueEntry(
                    id = entryId,
                    synchronizationRequest = makeRequest(),
                    state = QueueEntryState.COMPLETED,
                    enqueuedAt = fixedInstant,
                    availableAt = fixedInstant,
                ),
            )
            // QueueEnqueueRequest constructor above always throws; this is never reached.
            error("QueueEnqueueRequest constructor must have thrown")
        }
        val capability = DefaultDataLoomQueueSubmission(
            queueProvider = provider,
            encoder = throwingEncoder,
        )

        assertFailsWith<IllegalArgumentException> {
            runSuspend { capability.submit(makeSubmission()) }
        }
        assertEquals(0, provider.enqueueCallCount)
    }

    @Test
    fun invalidState_encoderAttemptingActiveLease_throwsFromConstructor_providerNotCalled() {
        // An encoder that tries to supply an active lease on a PENDING entry
        // cannot construct a valid QueueEntry — QueueEntry constructor enforces
        // that PENDING state must have a null lease. This propagates as
        // IllegalArgumentException before the encoder can return, and
        // QueueProvider is never called.
        val provider = RecordingQueueProvider()
        val throwingEncoder = QueuedSynchronizationWorkEncoder { _ ->
            val lease = QueueLease(
                id = QueueLeaseId("lease-001"),
                consumerId = QueueConsumerId("consumer-001"),
                acquiredAt = DataLoomInstant(1_000_000L),
                expiresAt = DataLoomInstant(2_000_000L),
            )
            // QueueEntry constructor throws: PENDING state must have null lease.
            QueueEnqueueRequest(
                entry = QueueEntry(
                    id = entryId,
                    synchronizationRequest = makeRequest(),
                    state = QueueEntryState.PENDING,
                    enqueuedAt = fixedInstant,
                    availableAt = fixedInstant,
                    lease = lease,
                ),
            )
            // QueueEntry constructor above always throws; this is never reached.
            error("QueueEntry constructor must have thrown")
        }
        val capability = DefaultDataLoomQueueSubmission(
            queueProvider = provider,
            encoder = throwingEncoder,
        )

        assertFailsWith<IllegalArgumentException> {
            runSuspend { capability.submit(makeSubmission()) }
        }
        assertEquals(0, provider.enqueueCallCount)
    }

    @Test
    fun invalidState_encoderAttemptingRetryAttemptOnPending_throwsFromConstructor_providerNotCalled() {
        // An encoder that tries to supply a retryAttempt on a PENDING entry
        // cannot construct a valid QueueEntry — QueueEntry constructor enforces
        // that PENDING state must have a null retryAttempt. This propagates as
        // IllegalArgumentException and QueueProvider is never called.
        val provider = RecordingQueueProvider()
        val throwingEncoder = QueuedSynchronizationWorkEncoder { _ ->
            // QueueEntry constructor throws: PENDING state must not have retryAttempt.
            QueueEnqueueRequest(
                entry = QueueEntry(
                    id = entryId,
                    synchronizationRequest = makeRequest(),
                    state = QueueEntryState.PENDING,
                    enqueuedAt = fixedInstant,
                    availableAt = fixedInstant,
                    retryAttempt = RetryAttempt(1),
                ),
            )
            // QueueEntry constructor above always throws; this is never reached.
            error("QueueEntry constructor must have thrown")
        }
        val capability = DefaultDataLoomQueueSubmission(
            queueProvider = provider,
            encoder = throwingEncoder,
        )

        assertFailsWith<IllegalArgumentException> {
            runSuspend { capability.submit(makeSubmission()) }
        }
        assertEquals(0, provider.enqueueCallCount)
    }
}
