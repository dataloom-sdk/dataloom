package io.dataloom.runtime.retry

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
import io.dataloom.api.identifier.QueueConsumerId
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
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
import io.dataloom.api.queue.QueueProvider
import io.dataloom.api.queue.QueueRescheduleRequest
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest

class CircuitBreakerQueueOperationAdapterTest {

    @Test
    fun `all queue operations delegate exactly once after permission`() = runTest {
        val provider = RecordingQueueProvider()
        val adapter = adapter(provider)
        val scope = CircuitBreakerScope.provider(providerId)

        assertExecutedSuccess(adapter.initialize(scope, ProviderInitializationContext()))
        assertExecutedSuccess(adapter.health(scope))
        assertExecutedSuccess(adapter.close(scope))
        assertExecutedSuccess(adapter.enqueue(scope, enqueueRequest()))
        assertExecutedSuccess(adapter.acquire(scope, acquireRequest()))
        assertExecutedSuccess(adapter.complete(scope, completionRequest()))
        assertExecutedSuccess(adapter.reschedule(scope, rescheduleRequest()))
        assertExecutedSuccess(adapter.defer(scope, deferralRequest()))
        assertExecutedSuccess(adapter.fail(scope, failureRequest()))
        assertExecutedSuccess(adapter.cancel(scope, cancellationRequest()))
        assertExecutedSuccess(adapter.recoverExpiredLeases(scope, recoveryRequest()))

        assertEquals(
            listOf(
                "initialize",
                "health",
                "close",
                "enqueue",
                "acquire",
                "complete",
                "reschedule",
                "defer",
                "fail",
                "cancel",
                "recover-expired-leases",
            ),
            provider.calls,
        )
    }

    @Test
    fun `provider scope mismatch fails before state access or provider invocation`() = runTest {
        val provider = RecordingQueueProvider()
        val store = InMemoryCircuitStore()
        val adapter = adapter(provider, store)
        val mismatched = CircuitBreakerScope.provider(ProviderId("different-provider"))

        assertFailsWith<IllegalArgumentException> {
            adapter.enqueue(mismatched, enqueueRequest())
        }

        assertEquals(0, store.loadCalls)
        assertEquals(emptyList(), provider.calls)
    }

    @Test
    fun `provider-operation mismatch fails before state access or provider invocation`() = runTest {
        val provider = RecordingQueueProvider()
        val store = InMemoryCircuitStore()
        val adapter = adapter(provider, store)
        val wrongOperation = CircuitBreakerScope.providerOperation(
            providerId = providerId,
            operation = QueueCircuitOperation.ACQUIRE.retryOperation,
        )

        assertFailsWith<IllegalArgumentException> {
            adapter.enqueue(wrongOperation, enqueueRequest())
        }

        assertEquals(0, store.loadCalls)
        assertEquals(emptyList(), provider.calls)
    }

    @Test
    fun `eligible queue failure opens circuit and rejects next operation`() = runTest {
        val expected = FakeError(
            code = ErrorCode("QUEUE_UNAVAILABLE"),
            category = ErrorCategory.QUEUE,
            recoverability = Recoverability.RECOVERABLE,
        )
        val provider = RecordingQueueProvider(
            enqueueResult = ProviderOperationResult.Failure(expected),
        )
        val adapter = adapter(provider)
        val scope = CircuitBreakerScope.providerOperation(
            providerId = providerId,
            operation = QueueCircuitOperation.ENQUEUE.retryOperation,
        )

        val first = assertIs<CircuitBreakerExecutionResult.Executed<Unit>>(
            adapter.enqueue(scope, enqueueRequest()),
        )
        assertSame(
            expected,
            assertIs<CircuitProtectedOperationResult.Failure>(first.operationResult).error,
        )
        assertEquals(
            CircuitBreakerPhase.OPEN,
            assertIs<CircuitBreakerRecordResult.Recorded>(first.recordResult).record.state.phase,
        )

        val second = assertIs<CircuitBreakerExecutionResult.Rejected>(
            adapter.enqueue(scope, enqueueRequest()),
        )
        assertEquals(CircuitBreakerRejectionReason.OPEN, second.reason)
        assertEquals(1, provider.enqueueCalls)
    }

    @Test
    fun `unknown queue provider timeout opens circuit through queue classifier`() = runTest {
        val timeout = FakeError(
            code = ErrorCode("QUEUE_PROVIDER_TIMEOUT"),
            category = ErrorCategory.QUEUE,
            recoverability = Recoverability.UNKNOWN,
        )
        val provider = RecordingQueueProvider(
            enqueueResult = ProviderOperationResult.Failure(timeout),
        )
        val adapter = adapter(provider)
        val scope = CircuitBreakerScope.provider(providerId)

        val first = assertIs<CircuitBreakerExecutionResult.Executed<Unit>>(
            adapter.enqueue(scope, enqueueRequest()),
        )
        assertIs<CircuitProtectedOperationResult.Failure>(first.operationResult)
        assertEquals(
            CircuitBreakerPhase.OPEN,
            assertIs<CircuitBreakerRecordResult.Recorded>(first.recordResult).record.state.phase,
        )

        assertIs<CircuitBreakerExecutionResult.Rejected>(
            adapter.enqueue(scope, enqueueRequest()),
        )
        assertEquals(1, provider.enqueueCalls)
    }

    @Test
    fun `post-execution state persistence failure preserves exact provider failure`() = runTest {
        val providerError = FakeError(
            code = ErrorCode("QUEUE_UNAVAILABLE"),
            category = ErrorCategory.QUEUE,
            recoverability = Recoverability.RECOVERABLE,
        )
        val storeError = FakeError(
            code = ErrorCode("CIRCUIT_STORE_WRITE_FAILED"),
            category = ErrorCategory.STORAGE,
            recoverability = Recoverability.RECOVERABLE,
        )
        val store = InMemoryCircuitStore(compareFailure = storeError)
        val provider = RecordingQueueProvider(
            enqueueResult = ProviderOperationResult.Failure(providerError),
        )
        val adapter = adapter(provider, store)

        val result = assertIs<CircuitBreakerExecutionResult.Executed<Unit>>(
            adapter.enqueue(CircuitBreakerScope.provider(providerId), enqueueRequest()),
        )

        assertSame(
            providerError,
            assertIs<CircuitProtectedOperationResult.Failure>(result.operationResult).error,
        )
        assertSame(
            storeError,
            assertIs<CircuitBreakerRecordResult.PersistenceFailure>(result.recordResult).error,
        )
        assertEquals(1, provider.enqueueCalls)
    }

    @Test
    fun `permission persistence failure prevents provider invocation`() = runTest {
        val loadError = FakeError(
            code = ErrorCode("CIRCUIT_STORE_READ_FAILED"),
            category = ErrorCategory.STORAGE,
            recoverability = Recoverability.RECOVERABLE,
        )
        val store = InMemoryCircuitStore(loadFailure = loadError)
        val provider = RecordingQueueProvider()
        val adapter = adapter(provider, store)

        val result = assertIs<CircuitBreakerExecutionResult.PermissionPersistenceFailure>(
            adapter.enqueue(CircuitBreakerScope.provider(providerId), enqueueRequest()),
        )

        assertSame(loadError, result.error)
        assertEquals(0, provider.enqueueCalls)
    }

    @Test
    fun `semantic provider failure records responsive dependency`() = runTest {
        val semanticError = FakeError(
            code = ErrorCode("QUEUE_REQUEST_INVALID"),
            category = ErrorCategory.VALIDATION,
            recoverability = Recoverability.NON_RECOVERABLE,
        )
        val provider = RecordingQueueProvider(
            enqueueResult = ProviderOperationResult.Failure(semanticError),
        )
        val adapter = adapter(provider)
        val scope = CircuitBreakerScope.provider(providerId)

        val first = assertIs<CircuitBreakerExecutionResult.Executed<Unit>>(
            adapter.enqueue(scope, enqueueRequest()),
        )
        assertSame(
            semanticError,
            assertIs<CircuitProtectedOperationResult.NonCircuitFailure>(first.operationResult).error,
        )
        assertIs<CircuitBreakerRecordResult.Ignored>(first.recordResult)

        assertIs<CircuitBreakerExecutionResult.Executed<Unit>>(
            adapter.enqueue(scope, enqueueRequest()),
        )
        assertEquals(2, provider.enqueueCalls)
    }

    @Test
    fun `caller cancellation propagates without circuit translation`() = runTest {
        val provider = RecordingQueueProvider(cancelEnqueue = true)
        val adapter = adapter(provider)

        val failure = assertFailsWith<CancellationException> {
            adapter.enqueue(CircuitBreakerScope.provider(providerId), enqueueRequest())
        }

        assertEquals("caller cancelled", failure.message)
        assertEquals(1, provider.enqueueCalls)
    }

    private fun adapter(
        provider: QueueProvider,
        store: CircuitBreakerStateStore = InMemoryCircuitStore(),
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

    private fun assertExecutedSuccess(
        result: CircuitBreakerExecutionResult<*>,
    ) {
        val executed = assertIs<CircuitBreakerExecutionResult.Executed<*>>(result)
        assertIs<CircuitProtectedOperationResult.Success<*>>(executed.operationResult)
        assertIs<CircuitBreakerRecordResult.Ignored>(executed.recordResult)
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
            val next = CircuitBreakerStateRecord(
                state = request.nextState,
                version = (current?.version ?: -1L) + 1L,
            )
            record = next
            return ProviderOperationResult.Success(
                CircuitBreakerCompareAndSetResult.Updated(next),
            )
        }
    }

    private class RecordingQueueProvider(
        private val enqueueResult: ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit),
        private val cancelEnqueue: Boolean = false,
    ) : QueueProvider {
        val calls: MutableList<String> = mutableListOf()
        val enqueueCalls: Int
            get() = calls.count { it == "enqueue" }

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = providerId,
            name = ProviderName("Circuit Queue Provider"),
            type = ProviderType.QUEUE,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> {
            calls += "initialize"
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun health(): ProviderOperationResult<ProviderHealth> {
            calls += "health"
            return ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))
        }

        override suspend fun close(): ProviderOperationResult<Unit> {
            calls += "close"
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun enqueue(
            request: QueueEnqueueRequest,
        ): ProviderOperationResult<Unit> {
            calls += "enqueue"
            if (cancelEnqueue) throw CancellationException("caller cancelled")
            return enqueueResult
        }

        override suspend fun acquire(
            request: QueueAcquireRequest,
        ): ProviderOperationResult<QueueAcquireResult> {
            calls += "acquire"
            return ProviderOperationResult.Success(QueueAcquireResult.NoEntries)
        }

        override suspend fun complete(
            request: QueueCompletionRequest,
        ): ProviderOperationResult<Unit> {
            calls += "complete"
            return ProviderOperationResult.Success(Unit)
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
        ): ProviderOperationResult<ExpiredLeaseRecoveryResult> {
            calls += "recover-expired-leases"
            return ProviderOperationResult.Success(ExpiredLeaseRecoveryResult(0))
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
        override val message: String = "Queue circuit adapter test error.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private fun enqueueRequest(): QueueEnqueueRequest = QueueEnqueueRequest(
        QueueEntry(
            id = entryId,
            synchronizationRequest = io.dataloom.api.model.SynchronizationRequest(
                workflowId = io.dataloom.api.identifier.WorkflowId("workflow-queue-circuit"),
                sessionId = io.dataloom.api.identifier.SynchronizationSessionId("session-queue-circuit"),
                direction = io.dataloom.api.model.SynchronizationDirection.PUSH,
                mode = io.dataloom.api.model.SynchronizationMode.DELTA,
                context = executionContext,
            ),
            state = QueueEntryState.PENDING,
            enqueuedAt = now,
            availableAt = now,
        ),
    )

    private fun acquireRequest(): QueueAcquireRequest = QueueAcquireRequest(
        consumerId = consumerId,
        leaseId = leaseId,
        acquiredAt = now,
        leaseExpiresAt = DataLoomInstant(now.epochMilliseconds + 1_000L),
        maxEntries = 1,
    )

    private fun completionRequest(): QueueCompletionRequest = QueueCompletionRequest(
        entryId = entryId,
        leaseId = leaseId,
        completedAt = now,
    )

    private fun rescheduleRequest(): QueueRescheduleRequest = QueueRescheduleRequest(
        entryId = entryId,
        leaseId = leaseId,
        retryAttempt = RetryAttempt(1),
        availableAt = DataLoomInstant(now.epochMilliseconds + 1_000L),
        error = transitionError,
    )

    private fun deferralRequest(): QueueDeferralRequest = QueueDeferralRequest(
        entryId = entryId,
        leaseId = leaseId,
        availableAt = DataLoomInstant(now.epochMilliseconds + 1_000L),
        reason = QueueDeferralReason.CONNECTIVITY_REQUIREMENT_NOT_MET,
    )

    private fun failureRequest(): QueueFailureRequest = QueueFailureRequest(
        entryId = entryId,
        leaseId = leaseId,
        error = transitionError,
        disposition = QueueFailureDisposition.FAILED,
    )

    private fun cancellationRequest(): QueueCancellationRequest = QueueCancellationRequest(
        entryId = entryId,
        context = executionContext,
    )

    private fun recoveryRequest(): ExpiredLeaseRecoveryRequest =
        ExpiredLeaseRecoveryRequest(currentTime = now)

    private companion object {
        val providerId = ProviderId("queue-circuit-provider")
        val entryId = QueueEntryId("queue-entry")
        val leaseId = QueueLeaseId("queue-lease")
        val consumerId = QueueConsumerId("queue-consumer")
        val now = DataLoomInstant(1_000L)
        val executionContext = ExecutionContext(
            executionId = ExecutionId("queue-circuit-execution"),
            correlationId = CorrelationId("queue-circuit-correlation"),
        )
        val transitionError = FakeError(
            code = ErrorCode("QUEUE_TRANSITION_FAILURE"),
            category = ErrorCategory.QUEUE,
            recoverability = Recoverability.RECOVERABLE,
        )
    }
}
