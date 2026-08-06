package io.dataloom.testing.queue

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
import io.dataloom.api.queue.QueueCompletionRequest
import io.dataloom.api.queue.QueueEnqueueRequest
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.queue.QueueIdempotentAdmissionResult
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

class InMemoryQueueIdempotentAdmissionTest {

    @Test
    fun `first admission accepts and same identity returns current state`() = runTest {
        val provider = InMemoryQueueProvider()
        val original = entry()

        val first = provider.admit(QueueEnqueueRequest(original)).successValue()
        val duplicate = provider.admit(
            QueueEnqueueRequest(
                entry(enqueuedAt = 2_000L, availableAt = 3_000L),
            ),
        ).successValue()

        assertIs<QueueIdempotentAdmissionResult.Accepted>(first)
        val already = assertIs<QueueIdempotentAdmissionResult.AlreadyAccepted>(duplicate)
        assertEquals(QueueEntryState.PENDING, already.currentState)
        assertEquals(1, provider.entryCount)
    }

    @Test
    fun `same id with different immutable request fails closed`() = runTest {
        val provider = InMemoryQueueProvider()
        provider.admit(QueueEnqueueRequest(entry())).successValue()

        val conflict = provider.admit(
            QueueEnqueueRequest(entry(workflowId = "different-workflow")),
        ).successValue()

        val typed = assertIs<QueueIdempotentAdmissionResult.IdentityConflict>(conflict)
        assertEquals(QueueEntryState.PENDING, typed.currentState)
        assertEquals(1, provider.entryCount)
    }

    @Test
    fun `reconciliation after completion reports completed without creating work`() = runTest {
        val provider = InMemoryQueueProvider()
        provider.admit(QueueEnqueueRequest(entry())).successValue()
        val acquired = assertIs<QueueAcquireResult.Entries>(
            provider.acquire(
                QueueAcquireRequest(
                    consumerId = QueueConsumerId("admission-consumer"),
                    leaseId = QueueLeaseId("admission-lease"),
                    acquiredAt = DataLoomInstant(1_000L),
                    leaseExpiresAt = DataLoomInstant(2_000L),
                    maxEntries = 1,
                ),
            ).successValue(),
        )
        provider.complete(
            QueueCompletionRequest(
                entryId = acquired.entries.single().id,
                leaseId = acquired.lease.id,
                completedAt = DataLoomInstant(1_500L),
            ),
        ).successValue()

        val duplicate = provider.admit(
            QueueEnqueueRequest(entry(enqueuedAt = 5_000L, availableAt = 5_000L)),
        ).successValue()

        val already = assertIs<QueueIdempotentAdmissionResult.AlreadyAccepted>(duplicate)
        assertEquals(QueueEntryState.COMPLETED, already.currentState)
        assertEquals(1, provider.entryCount)
    }

    private fun entry(
        workflowId: String = "idempotent-workflow",
        enqueuedAt: Long = 1_000L,
        availableAt: Long = enqueuedAt,
    ): QueueEntry = QueueEntry(
        id = QueueEntryId("stable-idempotent-entry"),
        synchronizationRequest = SynchronizationRequest(
            workflowId = WorkflowId(workflowId),
            sessionId = SynchronizationSessionId("idempotent-session"),
            direction = SynchronizationDirection.PULL,
            mode = SynchronizationMode.DELTA,
            context = ExecutionContext(
                executionId = ExecutionId("idempotent-execution"),
                correlationId = CorrelationId("idempotent-correlation"),
            ),
        ),
        state = QueueEntryState.PENDING,
        enqueuedAt = DataLoomInstant(enqueuedAt),
        availableAt = DataLoomInstant(availableAt),
    )

    private fun <T> ProviderOperationResult<T>.successValue(): T =
        assertIs<ProviderOperationResult.Success<T>>(this).value
}
