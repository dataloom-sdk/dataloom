@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.runtime.queue

import io.dataloom.api.context.DataLoomMetadata
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
import io.dataloom.api.identifier.TraceId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.model.WorkflowPriority
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.queue.ExpiredLeaseRecoveryRequest
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
import io.dataloom.api.queue.QueueRescheduleRequest
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.retry.RetryBudgetState
import io.dataloom.api.retry.WorkflowTimeoutState
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomInstant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

class AppleFileQueueProviderCodecTest {

    @Test
    fun `completed failed dead letter and cancelled entries are not acquired again`() = runTest {
        val directory = uniqueDirectory()
        val provider = AppleFileQueueProvider(directory)
        listOf(
            "entry-a-complete",
            "entry-b-fail",
            "entry-c-dead",
            "entry-d-cancel",
        ).forEach { entryId ->
            provider.enqueueSuccess(entry(id = entryId))
        }

        val completed = provider.acquireEntries(1_000L, 2_000L, "lease-complete", 1).single()
        provider.complete(
            QueueCompletionRequest(completed.id, QueueLeaseId("lease-complete"), DataLoomInstant(1_100L)),
        ).assertSuccess()

        val failed = provider.acquireEntries(1_000L, 2_000L, "lease-fail", 1).single()
        provider.fail(
            QueueFailureRequest(failed.id, QueueLeaseId("lease-fail"), testError(), QueueFailureDisposition.FAILED),
        ).assertSuccess()

        val dead = provider.acquireEntries(1_000L, 2_000L, "lease-dead", 1).single()
        provider.fail(
            QueueFailureRequest(dead.id, QueueLeaseId("lease-dead"), testError(), QueueFailureDisposition.DEAD_LETTER),
        ).assertSuccess()

        provider.cancel(
            QueueCancellationRequest(
                entryId = QueueEntryId("entry-d-cancel"),
                context = executionContext(),
            ),
        ).assertSuccess()

        assertIs<QueueAcquireResult.NoEntries>(provider.acquireResult("lease-none"))
    }

    @Test
    fun `codec preserves canonical error metadata retry budget and workflow timeout`() {
        val budget = RetryBudgetState(
            windowStartedAt = DataLoomInstant(1_000L),
            lastEvaluatedAt = DataLoomInstant(1_500L),
            cumulativeDelay = SchedulingDelay(700L),
        )
        val timeout = WorkflowTimeoutState(DataLoomInstant(900L), DataLoomInstant(9_000L))
        val original = entry(
            state = QueueEntryState.RETRY_WAITING,
            availableAt = 3_000L,
            retryAttempt = RetryAttempt(3),
            retryBudgetState = budget,
            workflowTimeoutState = timeout,
            lastError = testError(),
            executionMetadata = DataLoomMetadata.of(mapOf("unicode" to "雪", "empty" to "")),
            entryMetadata = DataLoomMetadata.of(mapOf("queue" to "safe")),
        )

        val decoded = AppleQueueStateFileCodec.decode(
            AppleQueueStateFileCodec.encode(mapOf(original.id.value to original)),
        ).getValue(original.id.value)

        assertEquals(original.id, decoded.id)
        assertEquals(original.synchronizationRequest, decoded.synchronizationRequest)
        assertEquals(original.state, decoded.state)
        assertEquals(original.retryAttempt, decoded.retryAttempt)
        assertEquals(original.retryBudgetState, decoded.retryBudgetState)
        assertEquals(original.workflowTimeoutState, decoded.workflowTimeoutState)
        assertEquals(original.metadata, decoded.metadata)
        assertEquals(original.lastError?.code, decoded.lastError?.code)
        assertEquals(original.lastError?.category, decoded.lastError?.category)
        assertEquals(original.lastError?.severity, decoded.lastError?.severity)
        assertEquals(original.lastError?.recoverability, decoded.lastError?.recoverability)
        assertEquals(original.lastError?.message, decoded.lastError?.message)
        assertNull(decoded.lastError?.cause)
    }

    @Test
    fun `corrupt snapshot fails closed without leaking file content`() = runTest {
        val directory = uniqueDirectory()
        val provider = AppleFileQueueProvider(directory)
        provider.enqueueSuccess(entry())
        val dataPath = "$directory/${AppleFileQueueProvider.DEFAULT_FILE_NAME}"
        appleQueueWriteUtf8FileAtomically(
            temporaryPath = "$dataPath.test-tmp",
            destinationPath = dataPath,
            content = "not-a-queue-snapshot\ncredential-value",
        )

        val failure = assertIs<ProviderOperationResult.Failure>(
            provider.acquire(acquireRequest("lease-corrupt")),
        )
        assertEquals("QUEUE_APPLE_STATE_CORRUPT", failure.error.code.value)
        assertEquals(ErrorCategory.STATE, failure.error.category)
        assertEquals(Recoverability.NON_RECOVERABLE, failure.error.recoverability)
        assertTrue("credential-value" !in failure.error.message)
        assertNull(failure.error.cause)
    }

    @Test
    fun `cancelled caller does not enter the provider`() = runTest {
        val provider = AppleFileQueueProvider(uniqueDirectory())
        val deferred = async(start = CoroutineStart.LAZY) {
            provider.acquire(acquireRequest("lease-cancelled"))
        }
        deferred.cancel(CancellationException("caller cancelled"))

        val failure = assertFailsWith<CancellationException> { deferred.await() }
        assertEquals("caller cancelled", failure.message)
    }

    @Test
    fun `constructor rejects unsafe paths without side effects`() {
        assertFailsWith<IllegalArgumentException> {
            AppleFileQueueProvider("relative/path")
        }
        assertFailsWith<IllegalArgumentException> {
            AppleFileQueueProvider("/tmp/safe", "../unsafe")
        }
        assertFailsWith<IllegalArgumentException> {
            AppleFileQueueProvider("/tmp/../unsafe")
        }
    }

    private suspend fun AppleFileQueueProvider.enqueueSuccess(entry: QueueEntry) {
        enqueue(QueueEnqueueRequest(entry)).assertSuccess()
    }

    private suspend fun AppleFileQueueProvider.acquireEntries(
        acquiredAt: Long,
        expiresAt: Long,
        leaseId: String,
        maxEntries: Int = 10,
    ): List<QueueEntry> = assertIs<QueueAcquireResult.Entries>(
        acquire(
            acquireRequest(
                leaseId = leaseId,
                acquiredAt = acquiredAt,
                expiresAt = expiresAt,
                maxEntries = maxEntries,
            ),
        ).successValue(),
    ).entries

    private suspend fun AppleFileQueueProvider.acquireResult(
        leaseId: String,
    ): QueueAcquireResult = acquire(acquireRequest(leaseId)).successValue()

    private fun acquireRequest(
        leaseId: String,
        acquiredAt: Long = 1_000L,
        expiresAt: Long = 2_000L,
        maxEntries: Int = 10,
    ): QueueAcquireRequest = QueueAcquireRequest(
        consumerId = QueueConsumerId("consumer-1"),
        leaseId = QueueLeaseId(leaseId),
        acquiredAt = DataLoomInstant(acquiredAt),
        leaseExpiresAt = DataLoomInstant(expiresAt),
        maxEntries = maxEntries,
    )

    private fun entry(
        id: String = "entry-1",
        state: QueueEntryState = QueueEntryState.PENDING,
        enqueuedAt: Long = 1_000L,
        availableAt: Long = enqueuedAt,
        retryAttempt: RetryAttempt? = null,
        retryBudgetState: RetryBudgetState? = null,
        workflowTimeoutState: WorkflowTimeoutState? = null,
        lastError: DataLoomError? = null,
        executionMetadata: DataLoomMetadata = DataLoomMetadata.Empty,
        entryMetadata: DataLoomMetadata = DataLoomMetadata.Empty,
        lease: QueueLease? = null,
    ): QueueEntry = QueueEntry(
        id = QueueEntryId(id),
        synchronizationRequest = SynchronizationRequest(
            workflowId = WorkflowId("workflow-$id"),
            sessionId = SynchronizationSessionId("session-$id"),
            direction = SynchronizationDirection.PUSH,
            mode = SynchronizationMode.DELTA,
            priority = WorkflowPriority.NORMAL,
            context = executionContext(executionMetadata),
        ),
        state = state,
        enqueuedAt = DataLoomInstant(enqueuedAt),
        availableAt = DataLoomInstant(availableAt),
        retryAttempt = retryAttempt,
        lease = lease,
        lastError = lastError,
        metadata = entryMetadata,
        retryBudgetState = retryBudgetState,
        workflowTimeoutState = workflowTimeoutState,
    )

    private fun executionContext(
        metadata: DataLoomMetadata = DataLoomMetadata.Empty,
    ): ExecutionContext = ExecutionContext(
        executionId = ExecutionId("execution-1"),
        correlationId = CorrelationId("correlation-1"),
        traceId = TraceId("trace-1"),
        metadata = metadata,
    )

    private fun testError(): DataLoomError = TestQueueError(
        code = ErrorCode("NETWORK_TEMPORARY"),
        category = ErrorCategory.NETWORK,
        severity = ErrorSeverity.WARNING,
        recoverability = Recoverability.RECOVERABLE,
        message = "A temporary network failure occurred.",
    )

    private fun uniqueDirectory(): String = buildString {
        append(NSTemporaryDirectory().trimEnd('/'))
        append("/dataloom-apple-queue-")
        append(NSUUID().UUIDString)
    }

    private fun <T> ProviderOperationResult<T>.successValue(): T =
        assertIs<ProviderOperationResult.Success<T>>(this).value

    private fun ProviderOperationResult<Unit>.assertSuccess() {
        assertIs<ProviderOperationResult.Success<Unit>>(this)
    }

    private data class TestQueueError(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val severity: ErrorSeverity,
        override val recoverability: Recoverability,
        override val message: String,
        override val cause: Throwable? = null,
    ) : DataLoomError
}
