package io.dataloom.api.queue

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.retry.WorkflowTimeoutState
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueueIdempotentAdmissionProviderTest {

    @Test
    fun `admission identity ignores mutable queue execution state`() {
        val candidate = entry(
            state = QueueEntryState.PENDING,
            enqueuedAt = 1_000L,
            availableAt = 1_000L,
        )
        val existing = entry(
            state = QueueEntryState.COMPLETED,
            enqueuedAt = 2_000L,
            availableAt = 3_000L,
        )

        assertTrue(existing.hasSameQueueAdmissionIdentityAs(candidate))
        assertTrue(candidate.hasSameQueueAdmissionIdentityAs(existing))
    }

    @Test
    fun `different synchronization request fails admission identity`() {
        val original = entry()
        val different = entry(
            workflowId = "different-workflow",
        )

        assertFalse(original.hasSameQueueAdmissionIdentityAs(different))
    }

    @Test
    fun `different metadata or workflow deadline fails admission identity`() {
        val original = entry()
        val metadataChanged = entry(
            metadata = DataLoomMetadata.of(mapOf("tenant-scope" to "two")),
        )
        val deadlineChanged = entry(
            timeout = WorkflowTimeoutState(
                startedAt = DataLoomInstant(1_000L),
                deadline = DataLoomInstant(20_000L),
            ),
        )

        assertFalse(original.hasSameQueueAdmissionIdentityAs(metadataChanged))
        assertFalse(original.hasSameQueueAdmissionIdentityAs(deadlineChanged))
    }

    @Test
    fun `typed results expose state without rendering queue identity`() {
        val id = QueueEntryId("sensitive-admission-id")
        val accepted = QueueIdempotentAdmissionResult.Accepted(id)
        val existing = QueueIdempotentAdmissionResult.AlreadyAccepted(
            queueEntryId = id,
            currentState = QueueEntryState.LEASED,
        )
        val conflict = QueueIdempotentAdmissionResult.IdentityConflict(
            queueEntryId = id,
            currentState = QueueEntryState.COMPLETED,
        )

        assertEquals(QueueEntryState.PENDING, accepted.currentState)
        assertEquals(QueueEntryState.LEASED, existing.currentState)
        assertEquals(QueueEntryState.COMPLETED, conflict.currentState)
        assertFalse(accepted.toString().contains(id.value))
        assertFalse(existing.toString().contains(id.value))
        assertFalse(conflict.toString().contains(id.value))
    }

    private fun entry(
        workflowId: String = "admission-workflow",
        state: QueueEntryState = QueueEntryState.PENDING,
        enqueuedAt: Long = 1_000L,
        availableAt: Long = enqueuedAt,
        metadata: DataLoomMetadata =
            DataLoomMetadata.of(mapOf("tenant-scope" to "one")),
        timeout: WorkflowTimeoutState = WorkflowTimeoutState(
            startedAt = DataLoomInstant(1_000L),
            deadline = DataLoomInstant(10_000L),
        ),
    ): QueueEntry = QueueEntry(
        id = QueueEntryId("stable-admission-id"),
        synchronizationRequest = SynchronizationRequest(
            workflowId = WorkflowId(workflowId),
            sessionId = SynchronizationSessionId("admission-session"),
            direction = SynchronizationDirection.PULL,
            mode = SynchronizationMode.DELTA,
            context = ExecutionContext(
                executionId = ExecutionId("admission-execution"),
                correlationId = CorrelationId("admission-correlation"),
            ),
        ),
        state = state,
        enqueuedAt = DataLoomInstant(enqueuedAt),
        availableAt = DataLoomInstant(availableAt),
        metadata = metadata,
        workflowTimeoutState = timeout,
    )
}
