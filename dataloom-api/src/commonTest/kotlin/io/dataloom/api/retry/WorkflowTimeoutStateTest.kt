package io.dataloom.api.retry

import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkflowTimeoutStateTest {

    @Test
    fun `deadline may equal start for an immediately expired workflow`() {
        val state = WorkflowTimeoutState(
            startedAt = DataLoomInstant(1_000L),
            deadline = DataLoomInstant(1_000L),
        )

        assertEquals(DataLoomInstant(1_000L), state.deadline)
    }

    @Test
    fun `deadline earlier than start is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            WorkflowTimeoutState(
                startedAt = DataLoomInstant(1_000L),
                deadline = DataLoomInstant(999L),
            )
        }
    }

    @Test
    fun `factory derives exact absolute deadline`() {
        val state = WorkflowTimeoutState.from(
            startedAt = DataLoomInstant(1_000L),
            timeout = SchedulingDelay(2_500L),
        )

        assertEquals(DataLoomInstant(1_000L), state.startedAt)
        assertEquals(DataLoomInstant(3_500L), state.deadline)
    }

    @Test
    fun `factory saturates instead of overflowing`() {
        val state = WorkflowTimeoutState.from(
            startedAt = DataLoomInstant(Long.MAX_VALUE - 5L),
            timeout = SchedulingDelay(10L),
        )

        assertEquals(DataLoomInstant(Long.MAX_VALUE), state.deadline)
    }
}
