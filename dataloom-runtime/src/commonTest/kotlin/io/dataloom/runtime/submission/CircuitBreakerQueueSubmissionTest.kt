package io.dataloom.runtime.submission

import io.dataloom.api.circuit.CircuitBreakerCompareAndSetRequest
import io.dataloom.api.circuit.CircuitBreakerCompareAndSetResult
import io.dataloom.api.circuit.CircuitBreakerLoadResult
import io.dataloom.api.circuit.CircuitBreakerPhase
import io.dataloom.api.circuit.CircuitBreakerScope
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
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
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
import io.dataloom.api.queue.QueueProvider
import io.dataloom.api.queue.QueueRescheduleRequest
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.queue.QueuedSynchronizationWork
import io.dataloom.runtime.retry.CircuitBreakerConfiguration
import io.dataloom.runtime.retry.CircuitBreakerCoordinator
import io.dataloom.runtime.retry.CircuitBreakerExecutionGate
import io.dataloom.runtime.retry.CircuitBreakerExecutionResult
import io.dataloom.runtime.retry.CircuitBreakerQueueOperationAdapter
import io.dataloom.runtime.retry.CircuitBreakerRecordResult
import io.dataloom.runtime.retry.CircuitBreakerRejectionReason
import io.dataloom.runtime.retry.CircuitProtectedOperationResult
import io.dataloom.runtime.retry.CoroutineRetryTimeoutExecutor
import io.dataloom.runtime.retry.QueueCircuitOperation
import io.dataloom.runtime.retry.RetryTimeoutConfiguration
import io.dataloom.runtime.retry.RetryTimeoutCoordinator
import io.dataloom.runtime.retry.TimeoutEnforcingQueueProvider
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest

class CircuitBreakerQueueSubmissionTest {

    @Test
    fun `encoding rejection occurs before circuit access or provider invocation`() = runTest {
        val expected = FakeError(
            code = ErrorCode("ENCODER_REJECTED"),
            category = ErrorCategory.SERIALIZATION,
            recoverability = Recoverability.NON_RECOVERABLE,
        )
        val encoder = RecordingEncoder(
            resultFactory = { QueuedSynchronizationWorkEncodingResult.Rejected(expected) },
        )
        val store = InMemoryCircuitStore()
        val provider = RecordingQueueProvider()
        val subject = subject(provider, encoder, store)

        val result = assertIs<CircuitBreakerQueueSubmissionResult.EncodingRejected>(
            subject.submit(submission()),
        )

        assertSame(expected, result.error)
        assertEquals(1, encoder.callCount)
        assertEquals(0, store.loadCalls)
        assertEquals(0, provider.enqueueCalls)
    }

    @Test
    fun `contract violation occurs before circuit access or provider invocation`() = runTest {
        val encoder = RecordingEncoder(
            resultFactory = { submitted ->
                QueuedSynchronizationWorkEncodingResult.Encoded(
                    enqueueRequest(
                        submission = submitted,
                        entryId = QueueEntryId("wrong-entry"),
                    ),
                )
            },
        )
        val store = InMemoryCircuitStore()
        val provider = RecordingQueueProvider()
        val subject = subject(provider, encoder, store)

        val result = assertIs<CircuitBreakerQueueSubmissionResult.ContractViolation>(
            subject.submit(submission()),
        )

        assertEquals(entryId, result.queueEntryId)
        assertEquals("DL-Q-SUBMISSION-CONTRACT-VIOLATION", result.error.code.value)
        assertEquals(1, encoder.callCount)
        assertEquals(0, store.loadCalls)
        assertEquals(0, provider.enqueueCalls)
    }

    @Test
    fun `successful enqueue preserves circuit execution and recording evidence`() = runTest {
        val encoder = RecordingEncoder()
        val store = InMemoryCircuitStore()
        val provider = RecordingQueueProvider()
        val subject = subject(provider, encoder, store)

        val evaluated = assertIs<CircuitBreakerQueueSubmissionResult.EnqueueEvaluated>(
            subject.submit(submission()),
        )
        val executed = assertIs<CircuitBreakerExecutionResult.Executed<Unit>>(
            evaluated.executionResult,
        )

        assertEquals(entryId, evaluated.queueEntryId)
        assertIs<CircuitProtectedOperationResult.Success<Unit>>(executed.operationResult)
        assertIs<CircuitBreakerRecordResult.Ignored>(executed.recordResult)
        assertEquals(1, encoder.callCount)
        assertEquals(1, provider.enqueueCalls)
    }

    @Test
    fun `open circuit rejects second enqueue after local preflight`() = runTest {
        val providerError = FakeError(
            code = ErrorCode("QUEUE_UNAVAILABLE"),
            category = ErrorCategory.QUEUE,
            recoverability = Recoverability.RECOVERABLE,
        )
        val encoder = RecordingEncoder()
        val store = InMemoryCircuitStore()
        val provider = RecordingQueueProvider(
            enqueueResult = ProviderOperationResult.Failure(providerError),
        )
        val subject = subject(provider, encoder, store)

        val first = assertIs<CircuitBreakerQueueSubmissionResult.EnqueueEvaluated>(
            subject.submit(submission()),
        )
        val firstExecuted = assertIs<CircuitBreakerExecutionResult.Executed<Unit>>(
            first.executionResult,
        )
        assertSame(
            providerError,
            assertIs<CircuitProtectedOperationResult.Failure>(firstExecuted.operationResult).error,
        )
        assertEquals(
            CircuitBreakerPhase.OPEN,
            assertIs<CircuitBreakerRecordResult.Recorded>(firstExecuted.recordResult)
                .record.state.phase,
        )

        val second = assertIs<CircuitBreakerQueueSubmissionResult.EnqueueEvaluated>(
            subject.submit(submission()),
        )
        val rejected = assertIs<CircuitBreakerExecutionResult.Rejected>(
            second.executionResult,
        )

        assertEquals(CircuitBreakerRejectionReason.OPEN, rejected.reason)
        assertEquals(2, encoder.callCount, "Preflight must complete before circuit permission.")
        assertEquals(1, provider.enqueueCalls)
    }

    @Test
    fun `permission persistence failure occurs after preflight and before enqueue`() = runTest {
        val storeError = FakeError(
            code = ErrorCode("CIRCUIT_READ_FAILED"),
            category = ErrorCategory.STORAGE,
            recoverability = Recoverability.RECOVERABLE,
        )
        val encoder = RecordingEncoder()
        val store = InMemoryCircuitStore(loadFailure = storeError)
        val provider = RecordingQueueProvider()
        val subject = subject(provider, encoder, store)

        val evaluated = assertIs<CircuitBreakerQueueSubmissionResult.EnqueueEvaluated>(
            subject.submit(submission()),
        )
        val failure = assertIs<CircuitBreakerExecutionResult.PermissionPersistenceFailure>(
            evaluated.executionResult,
        )

        assertSame(storeError, failure.error)
        assertEquals(1, encoder.callCount)
        assertEquals(1, store.loadCalls)
        assertEquals(0, provider.enqueueCalls)
    }

    @Test
    fun `post-execution recording failure preserves exact provider failure`() = runTest {
        val providerError = FakeError(
            code = ErrorCode("QUEUE_UNAVAILABLE"),
            category = ErrorCategory.QUEUE,
            recoverability = Recoverability.RECOVERABLE,
        )
        val storeError = FakeError(
            code = ErrorCode("CIRCUIT_WRITE_FAILED"),
            category = ErrorCategory.STORAGE,
            recoverability = Recoverability.RECOVERABLE,
        )
        val store = InMemoryCircuitStore(compareFailure = storeError)
        val provider = RecordingQueueProvider(
            enqueueResult = ProviderOperationResult.Failure(providerError),
        )
        val subject = subject(provider, RecordingEncoder(), store)

        val evaluated = assertIs<CircuitBreakerQueueSubmissionResult.EnqueueEvaluated>(
            subject.submit(submission()),
        )
        val executed = assertIs<CircuitBreakerExecutionResult.Executed<Unit>>(
            evaluated.executionResult,
        )

        assertSame(
            providerError,
            assertIs<CircuitProtectedOperationResult.Failure>(executed.operationResult).error,
        )
        assertSame(
            storeError,
            assertIs<CircuitBreakerRecordResult.PersistenceFailure>(executed.recordResult).error,
        )
        assertEquals(1, provider.enqueueCalls)
    }

    @Test
    fun `timeout and circuit composition opens circuit without invoking raw provider`() = runTest {
        val rawProvider = RecordingQueueProvider()
        val protectedProvider = TimeoutEnforcingQueueProvider(
            delegate = rawProvider,
            timeoutCoordinator = RetryTimeoutCoordinator(
                configuration = RetryTimeoutConfiguration(
                    providerTimeout = SchedulingDelay.ZERO,
                ),
                clock = FixedClock(now),
                executor = CoroutineRetryTimeoutExecutor(),
            ),
        )
        val store = InMemoryCircuitStore()
        val subject = subject(
            provider = protectedProvider,
            encoder = RecordingEncoder(),
            store = store,
        )

        val evaluated = assertIs<CircuitBreakerQueueSubmissionResult.EnqueueEvaluated>(
            subject.submit(submission()),
        )
        val executed = assertIs<CircuitBreakerExecutionResult.Executed<Unit>>(
            evaluated.executionResult,
        )
        val timeout = assertIs<CircuitProtectedOperationResult.Failure>(
            executed.operationResult,
        )

        assertEquals("QUEUE_PROVIDER_TIMEOUT", timeout.error.code.value)
        assertEquals(Recoverability.UNKNOWN, timeout.error.recoverability)
        assertEquals(
            CircuitBreakerPhase.OPEN,
            assertIs<CircuitBreakerRecordResult.Recorded>(executed.recordResult)
                .record.state.phase,
        )
        assertEquals(0, rawProvider.enqueueCalls)
    }

    @Test
    fun `invalid operation scope is rejected during construction before preflight`() {
        val encoder = RecordingEncoder()
        val store = InMemoryCircuitStore()
        val provider = RecordingQueueProvider()
        val adapter = operationAdapter(provider, store)
        val wrongScope = CircuitBreakerScope.providerOperation(
            providerId = providerId,
            operation = QueueCircuitOperation.ACQUIRE.retryOperation,
        )

        assertFailsWith<IllegalArgumentException> {
            CircuitBreakerQueueSubmission(
                encoder = encoder,
                queueOperationAdapter = adapter,
                scope = wrongScope,
            )
        }

        assertEquals(0, encoder.callCount)
        assertEquals(0, store.loadCalls)
        assertEquals(0, provider.enqueueCalls)
    }

    @Test
    fun `unexpected encoder exception leaves circuit and provider untouched`() = runTest {
        val encoder = RecordingEncoder(throwOnEncode = true)
        val store = InMemoryCircuitStore()
        val provider = RecordingQueueProvider()
        val subject = subject(provider, encoder, store)

        val failure = assertFailsWith<IllegalStateException> {
            subject.submit(submission())
        }

        assertEquals("encoder failure", failure.message)
        assertEquals(1, encoder.callCount)
        assertEquals(0, store.loadCalls)
        assertEquals(0, provider.enqueueCalls)
    }

    @Test
    fun `caller cancellation propagates without conversion`() = runTest {
        val provider = RecordingQueueProvider(cancelEnqueue = true)
        val subject = subject(provider, RecordingEncoder(), InMemoryCircuitStore())

        val failure = assertFailsWith<CancellationException> {
            subject.submit(submission())
        }

        assertEquals("caller cancelled", failure.message)
        assertEquals(1, provider.enqueueCalls)
    }

    private fun subject(
        provider: QueueProvider,
        encoder: QueuedSynchronizationWorkEncoder,
        store: CircuitBreakerStateStore,
        scope: CircuitBreakerScope = CircuitBreakerScope.providerOperation(
            providerId = providerId,
            operation = QueueCircuitOperation.ENQUEUE.retryOperation,
        ),
    ): CircuitBreakerQueueSubmission = CircuitBreakerQueueSubmission(
        encoder = encoder,
        queueOperationAdapter = operationAdapter(provider, store),
        scope = scope,
    )

    private fun operationAdapter(
        provider: QueueProvider,
        store: CircuitBreakerStateStore,
    ): CircuitBreakerQueueOperationAdapter {
        val coordinator = CircuitBreakerCoordinator(
            configuration = CircuitBreakerConfiguration(
                failureThreshold = 1,
                failureWindow = SchedulingDelay(1_000L),
                openDuration = SchedulingDelay(10_000L),
            ),
            clock = FixedClock(now),
            stateStore = store,
        )
        return CircuitBreakerQueueOperationAdapter(
            queueProvider = provider,
            executionGate = CircuitBreakerExecutionGate(coordinator),
        )
    }

    private class RecordingEncoder(
        private val resultFactory: (QueuedSynchronizationSubmission) ->
            QueuedSynchronizationWorkEncodingResult = { submitted ->
                QueuedSynchronizationWorkEncodingResult.Encoded(
                    enqueueRequest(submitted),
                )
            },
        private val throwOnEncode: Boolean = false,
    ) : QueuedSynchronizationWorkEncoder {
        var callCount: Int = 0
            private set

        override fun encode(
            submission: QueuedSynchronizationSubmission,
        ): QueuedSynchronizationWorkEncodingResult {
            callCount++
            if (throwOnEncode) error("encoder failure")
            return resultFactory(submission)
        }
    }

    private class RecordingQueueProvider(
        private val enqueueResult: ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit),
        private val cancelEnqueue: Boolean = false,
    ) : QueueProvider {
        var enqueueCalls: Int = 0
            private set

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = providerId,
            name = ProviderName("Circuit Submission Queue"),
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
        ): ProviderOperationResult<Unit> {
            enqueueCalls++
            if (cancelEnqueue) throw CancellationException("caller cancelled")
            return enqueueResult
        }

        override suspend fun acquire(
            request: QueueAcquireRequest,
        ): ProviderOperationResult<QueueAcquireResult> =
            ProviderOperationResult.Success(QueueAcquireResult.NoEntries)

        override suspend fun complete(
            request: QueueCompletionRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun reschedule(
            request: QueueRescheduleRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun defer(
            request: QueueDeferralRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun fail(
            request: QueueFailureRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun cancel(
            request: QueueCancellationRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun recoverExpiredLeases(
            request: ExpiredLeaseRecoveryRequest,
        ): ProviderOperationResult<ExpiredLeaseRecoveryResult> =
            ProviderOperationResult.Success(ExpiredLeaseRecoveryResult(0))
    }

    private class InMemoryCircuitStore(
        private val loadFailure: DataLoomError? = null,
        private val compareFailure: DataLoomError? = null,
    ) : CircuitBreakerStateStore {
        var loadCalls: Int = 0
            private set
        private var record: CircuitBreakerStateRecord? = null

        override suspend fun load(
            scope: CircuitBreakerScope,
        ): ProviderOperationResult<CircuitBreakerLoadResult> {
            loadCalls++
            loadFailure?.let { return ProviderOperationResult.Failure(it) }
            val current = record
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
            compareFailure?.let { return ProviderOperationResult.Failure(it) }
            val current = record
            if (current?.version != request.expectedVersion) {
                return ProviderOperationResult.Success(
                    CircuitBreakerCompareAndSetResult.Conflict(current),
                )
            }
            val updated = CircuitBreakerStateRecord(
                state = request.nextState,
                version = (current?.version ?: -1L) + 1L,
            )
            record = updated
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

    private data class FakeError(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val recoverability: Recoverability,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val message: String = "Circuit queue submission test error.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private companion object {
        val providerId = ProviderId("circuit-submission-queue")
        val entryId = QueueEntryId("circuit-submission-entry")
        val now = DataLoomInstant(1_000L)

        fun submission(): QueuedSynchronizationSubmission =
            QueuedSynchronizationSubmission(
                queueEntryId = entryId,
                work = QueuedSynchronizationWork(
                    request = SynchronizationRequest(
                        workflowId = WorkflowId("circuit-submission-workflow"),
                        sessionId = SynchronizationSessionId("circuit-submission-session"),
                        direction = SynchronizationDirection.PUSH,
                        mode = SynchronizationMode.DELTA,
                        context = ExecutionContext(
                            executionId = ExecutionId("circuit-submission-execution"),
                            correlationId = CorrelationId("circuit-submission-correlation"),
                        ),
                    ),
                    bindings = SynchronizationProviderBindings(
                        storageProviderId = ProviderId("storage"),
                        transportProviderId = ProviderId("transport"),
                    ),
                ),
                availableAt = now,
            )

        fun enqueueRequest(
            submission: QueuedSynchronizationSubmission,
            entryId: QueueEntryId = submission.queueEntryId,
        ): QueueEnqueueRequest = QueueEnqueueRequest(
            QueueEntry(
                id = entryId,
                synchronizationRequest = submission.work.request,
                state = QueueEntryState.PENDING,
                enqueuedAt = submission.availableAt,
                availableAt = submission.availableAt,
            ),
        )
    }
}
