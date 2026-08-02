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

class AppleFileQueueProviderLifecycleTest {

    @Test
    fun `descriptor is a queue provider`() {
        assertEquals(
            ProviderType.QUEUE,
            AppleFileQueueProvider(uniqueDirectory()).descriptor.type,
        )
    }

    @Test
    fun `enqueued workflow and timeout evidence survive a new provider instance`() = runTest {
        val directory = uniqueDirectory()
        val original = entry(
            id = "entry-restart",
            executionMetadata = DataLoomMetadata.of(mapOf("source" to "ios-雪")),
            entryMetadata = DataLoomMetadata.of(mapOf("safe" to "value")),
            workflowTimeoutState = WorkflowTimeoutState(
                startedAt = DataLoomInstant(1_000L),
                deadline = DataLoomInstant(9_000L),
            ),
        )
        AppleFileQueueProvider(directory).enqueueSuccess(original)

        val acquired = AppleFileQueueProvider(directory).acquireEntries(
            acquiredAt = 1_000L,
            expiresAt = 2_000L,
            leaseId = "lease-restart",
        ).single()

        assertEquals(original.id, acquired.id)
        assertEquals(original.synchronizationRequest, acquired.synchronizationRequest)
        assertEquals(original.metadata, acquired.metadata)
        assertEquals(original.workflowTimeoutState, acquired.workflowTimeoutState)
        assertEquals(QueueEntryState.LEASED, acquired.state)
        assertEquals("lease-restart", acquired.lease?.id?.value)
    }

    @Test
    fun `duplicate enqueue returns the canonical duplicate error`() = runTest {
        val provider = AppleFileQueueProvider(uniqueDirectory())
        val original = entry()
        provider.enqueueSuccess(original)

        val failure = assertIs<ProviderOperationResult.Failure>(
            provider.enqueue(QueueEnqueueRequest(original)),
        )

        assertEquals("QUEUE_DUPLICATE_ENTRY", failure.error.code.value)
        assertEquals(ErrorCategory.QUEUE, failure.error.category)
        assertEquals(null, failure.error.cause)
    }

    @Test
    fun `batch acquisition preserves deterministic availability order and exact limit`() = runTest {
        val provider = AppleFileQueueProvider(uniqueDirectory())
        provider.enqueueSuccess(entry(id = "entry-c", enqueuedAt = 900L, availableAt = 1_000L))
        provider.enqueueSuccess(entry(id = "entry-b", enqueuedAt = 800L, availableAt = 900L))
        provider.enqueueSuccess(entry(id = "entry-a", enqueuedAt = 700L, availableAt = 900L))

        val acquired = provider.acquireEntries(
            acquiredAt = 1_000L,
            expiresAt = 2_000L,
            leaseId = "lease-order",
            maxEntries = 2,
        )

        assertEquals(listOf("entry-a", "entry-b"), acquired.map { it.id.value })
        val remaining = provider.acquireEntries(
            acquiredAt = 1_000L,
            expiresAt = 2_000L,
            leaseId = "lease-order-2",
            maxEntries = 2,
        )
        assertEquals(listOf("entry-c"), remaining.map { it.id.value })
    }

    @Test
    fun `two provider instances cannot acquire the same entry`() = runTest {
        val directory = uniqueDirectory()
        AppleFileQueueProvider(directory).enqueueSuccess(entry(id = "entry-contention"))
        val first = AppleFileQueueProvider(directory)
        val second = AppleFileQueueProvider(directory)

        val results = listOf(
            async(Dispatchers.Default) {
                first.acquireResult("lease-a")
            },
            async(Dispatchers.Default) {
                second.acquireResult("lease-b")
            },
        ).awaitAll()

        val acquiredCount = results.sumOf { result ->
            when (result) {
                QueueAcquireResult.NoEntries -> 0
                is QueueAcquireResult.Entries -> result.entries.size
            }
        }
        assertEquals(1, acquiredCount)
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
