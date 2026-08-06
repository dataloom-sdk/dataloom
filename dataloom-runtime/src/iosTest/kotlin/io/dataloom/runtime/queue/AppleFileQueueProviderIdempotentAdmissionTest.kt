@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.runtime.queue

import io.dataloom.api.context.ExecutionContext
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
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.queue.QueueAcquireRequest
import io.dataloom.api.queue.QueueAcquireResult
import io.dataloom.api.queue.QueueEnqueueRequest
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.queue.QueueIdempotentAdmissionResult
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

class AppleFileQueueProviderIdempotentAdmissionTest {

    @Test
    fun `same durable identity survives provider restart`() = runTest {
        val directory = uniqueDirectory()
        val first = AppleFileQueueProvider(directory)
            .admit(QueueEnqueueRequest(entry()))
            .successValue()
        val repeated = AppleFileQueueProvider(directory)
            .admit(
                QueueEnqueueRequest(
                    entry(enqueuedAt = 5_000L, availableAt = 5_000L),
                ),
            )
            .successValue()

        assertIs<QueueIdempotentAdmissionResult.Accepted>(first)
        val already = assertIs<QueueIdempotentAdmissionResult.AlreadyAccepted>(repeated)
        assertEquals(QueueEntryState.PENDING, already.currentState)
    }

    @Test
    fun `same id with different immutable work fails closed`() = runTest {
        val provider = AppleFileQueueProvider(uniqueDirectory())
        provider.admit(QueueEnqueueRequest(entry())).successValue()

        val conflict = provider.admit(
            QueueEnqueueRequest(entry(workflowId = "apple-other-workflow")),
        ).successValue()

        val typed = assertIs<QueueIdempotentAdmissionResult.IdentityConflict>(conflict)
        assertEquals(QueueEntryState.PENDING, typed.currentState)
    }

    @Test
    fun `admission observes leased state without creating another entry`() = runTest {
        val directory = uniqueDirectory()
        val provider = AppleFileQueueProvider(directory)
        provider.admit(QueueEnqueueRequest(entry())).successValue()
        val acquired = assertIs<QueueAcquireResult.Entries>(
            provider.acquire(
                QueueAcquireRequest(
                    consumerId = QueueConsumerId("apple-admission-consumer"),
                    leaseId = QueueLeaseId("apple-admission-lease"),
                    acquiredAt = DataLoomInstant(1_000L),
                    leaseExpiresAt = DataLoomInstant(2_000L),
                    maxEntries = 1,
                ),
            ).successValue(),
        )
        assertEquals(1, acquired.entries.size)

        val repeated = AppleFileQueueProvider(directory).admit(
            QueueEnqueueRequest(entry(enqueuedAt = 3_000L, availableAt = 3_000L)),
        ).successValue()

        val already = assertIs<QueueIdempotentAdmissionResult.AlreadyAccepted>(repeated)
        assertEquals(QueueEntryState.LEASED, already.currentState)
    }

    @Test
    fun `cross instance contention produces one accepted result`() = runTest {
        val directory = uniqueDirectory()
        val first = AppleFileQueueProvider(directory)
        val second = AppleFileQueueProvider(directory)

        val results = listOf(
            async(Dispatchers.Default) {
                first.admit(QueueEnqueueRequest(entry())).successValue()
            },
            async(Dispatchers.Default) {
                second.admit(QueueEnqueueRequest(entry())).successValue()
            },
        ).awaitAll()

        assertEquals(1, results.count { it is QueueIdempotentAdmissionResult.Accepted })
        assertEquals(1, results.count { it is QueueIdempotentAdmissionResult.AlreadyAccepted })
    }

    private fun entry(
        workflowId: String = "apple-idempotent-workflow",
        enqueuedAt: Long = 1_000L,
        availableAt: Long = enqueuedAt,
    ): QueueEntry = QueueEntry(
        id = QueueEntryId("apple-stable-admission"),
        synchronizationRequest = SynchronizationRequest(
            workflowId = WorkflowId(workflowId),
            sessionId = SynchronizationSessionId("apple-idempotent-session"),
            direction = SynchronizationDirection.PULL,
            mode = SynchronizationMode.DELTA,
            context = ExecutionContext(
                executionId = ExecutionId("apple-idempotent-execution"),
                correlationId = CorrelationId("apple-idempotent-correlation"),
            ),
        ),
        state = QueueEntryState.PENDING,
        enqueuedAt = DataLoomInstant(enqueuedAt),
        availableAt = DataLoomInstant(availableAt),
    )

    private fun uniqueDirectory(): String = buildString {
        append(NSTemporaryDirectory().trimEnd('/'))
        append("/dataloom-apple-idempotent-admission-")
        append(NSUUID().UUIDString)
    }

    private fun <T> ProviderOperationResult<T>.successValue(): T =
        assertIs<ProviderOperationResult.Success<T>>(this).value
}
