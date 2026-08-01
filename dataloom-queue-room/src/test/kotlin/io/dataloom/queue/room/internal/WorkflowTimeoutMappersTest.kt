package io.dataloom.queue.room.internal

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.retry.WorkflowTimeoutState
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WorkflowTimeoutMappersTest {

    @Test
    fun `workflow timeout state round trips through flat columns`() {
        val state = WorkflowTimeoutState(
            startedAt = DataLoomInstant(1_000L),
            deadline = DataLoomInstant(5_000L),
        )
        val original = entry(state)

        val entity = original.toEntity()

        assertEquals(1_000L, entity.workflowStartedAtMs)
        assertEquals(5_000L, entity.workflowDeadlineAtMs)
        assertEquals(original, entity.toDomain())
    }

    @Test
    fun `missing workflow timeout remains null`() {
        val entity = entry(null).toEntity()

        assertNull(entity.workflowStartedAtMs)
        assertNull(entity.workflowDeadlineAtMs)
        assertNull(entity.toDomain().workflowTimeoutState)
    }

    @Test
    fun `partial workflow timeout columns fail closed`() {
        val corrupt = entry(
            WorkflowTimeoutState(
                startedAt = DataLoomInstant(1_000L),
                deadline = DataLoomInstant(5_000L),
            ),
        ).toEntity().copy(workflowDeadlineAtMs = null)

        assertFailsWith<IllegalStateException> {
            corrupt.toDomain()
        }
    }

    private fun entry(state: WorkflowTimeoutState?): QueueEntry = QueueEntry(
        id = QueueEntryId("workflow-timeout-mapper"),
        synchronizationRequest = SynchronizationRequest(
            workflowId = WorkflowId("workflow-1"),
            sessionId = SynchronizationSessionId("session-1"),
            direction = SynchronizationDirection.PUSH,
            mode = SynchronizationMode.DELTA,
            context = ExecutionContext(
                executionId = ExecutionId("execution-1"),
                correlationId = CorrelationId("correlation-1"),
            ),
        ),
        state = QueueEntryState.PENDING,
        enqueuedAt = DataLoomInstant(1_000L),
        availableAt = DataLoomInstant(1_000L),
        workflowTimeoutState = state,
    )
}
