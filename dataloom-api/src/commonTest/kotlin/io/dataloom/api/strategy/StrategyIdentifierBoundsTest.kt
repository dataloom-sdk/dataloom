package io.dataloom.api.strategy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StrategyIdentifierBoundsTest {

    @Test
    fun identifiersAcceptTheMaximumBound() {
        val value = "s".repeat(256)

        assertEquals(value, StrategyProfileId(value).value)
        assertEquals(value, StrategyDecisionId(value).value)
        assertEquals(value, StrategyPlanId(value).value)
    }

    @Test
    fun identifiersRejectValuesBeyondTheMaximumBound() {
        val value = "s".repeat(257)

        assertFailsWith<IllegalArgumentException> { StrategyProfileId(value) }
        assertFailsWith<IllegalArgumentException> { StrategyDecisionId(value) }
        assertFailsWith<IllegalArgumentException> { StrategyPlanId(value) }
    }
}
