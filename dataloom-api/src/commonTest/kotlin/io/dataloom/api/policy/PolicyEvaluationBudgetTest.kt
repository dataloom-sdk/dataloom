package io.dataloom.api.policy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PolicyEvaluationBudgetTest {

    @Test
    fun zeroIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            PolicyEvaluationBudget(0L)
        }
    }

    @Test
    fun negativeIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            PolicyEvaluationBudget(-1L)
        }
    }

    @Test
    fun positiveIsAccepted() {
        val budget = PolicyEvaluationBudget(1_000_000L)
        assertEquals(1_000_000L, budget.maxElapsedNanoseconds)
    }

    @Test
    fun equalBudgetsCompareAsEqual() {
        assertEquals(PolicyEvaluationBudget(500L), PolicyEvaluationBudget(500L))
    }
}
