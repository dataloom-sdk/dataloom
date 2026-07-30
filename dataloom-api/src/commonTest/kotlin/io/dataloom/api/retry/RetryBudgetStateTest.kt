package io.dataloom.api.retry

import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RetryBudgetStateTest {

    @Test
    fun `state preserves durable timing evidence`() {
        val state = RetryBudgetState(
            windowStartedAt = DataLoomInstant(1_000L),
            lastEvaluatedAt = DataLoomInstant(2_000L),
            cumulativeDelay = SchedulingDelay(3_000L),
        )

        assertEquals(DataLoomInstant(1_000L), state.windowStartedAt)
        assertEquals(DataLoomInstant(2_000L), state.lastEvaluatedAt)
        assertEquals(SchedulingDelay(3_000L), state.cumulativeDelay)
    }

    @Test
    fun `last evaluated instant cannot precede window start`() {
        assertFailsWith<IllegalArgumentException> {
            RetryBudgetState(
                windowStartedAt = DataLoomInstant(2_000L),
                lastEvaluatedAt = DataLoomInstant(1_999L),
                cumulativeDelay = SchedulingDelay.ZERO,
            )
        }
    }
}
