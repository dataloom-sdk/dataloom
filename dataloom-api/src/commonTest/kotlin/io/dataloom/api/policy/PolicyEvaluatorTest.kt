package io.dataloom.api.policy

import io.dataloom.api.identifier.PolicyCheckId
import io.dataloom.api.identifier.PolicySetId
import io.dataloom.api.scheduling.SchedulingDelay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolicyEvaluatorTest {

    private val generousBudget = PolicyEvaluationBudget(1_000_000_000L)

    private fun evaluatorWithClock(readings: List<Long> = listOf(0L)) =
        PolicyEvaluator(ScriptedDataLoomMonotonicClock(readings))

    private fun allow(id: String, justification: String = "ok") =
        StubPolicyCheck(PolicyCheckId(id), PolicyCheckOutcome.Allow(justification))

    private fun deny(id: String, justification: String = "blocked") =
        StubPolicyCheck(PolicyCheckId(id), PolicyCheckOutcome.Deny(justification))

    private fun requireUserAction(id: String, justification: String = "needs user") =
        StubPolicyCheck(PolicyCheckId(id), PolicyCheckOutcome.RequireUserAction(justification))

    private fun defer(id: String, justification: String = "wait") =
        StubPolicyCheck(PolicyCheckId(id), PolicyCheckOutcome.Defer(SchedulingDelay(1_000L), justification))

    // -------------------------------------------------------------------------
    // All-allow
    // -------------------------------------------------------------------------

    @Test
    fun allAllowProducesAllowFromTheFirstCheck() {
        val evaluator = evaluatorWithClock()
        val set = PolicySet(PolicySetId("set"), listOf(allow("a"), allow("b"), allow("c")))
        val decision = evaluator.evaluate(set, testPolicyInput(), generousBudget)
        assertIs<PolicyCheckOutcome.Allow>(decision.outcome)
        assertEquals(PolicyCheckId("a"), decision.winningCheckId)
        assertEquals(3, decision.evidence.size)
    }

    // -------------------------------------------------------------------------
    // Deny dominates
    // -------------------------------------------------------------------------

    @Test
    fun oneDenyAmongAllowsWins() {
        val evaluator = evaluatorWithClock()
        val set = PolicySet(PolicySetId("set"), listOf(allow("a"), deny("b"), allow("c")))
        val decision = evaluator.evaluate(set, testPolicyInput(), generousBudget)
        assertIs<PolicyCheckOutcome.Deny>(decision.outcome)
        assertEquals(PolicyCheckId("b"), decision.winningCheckId)
    }

    @Test
    fun denyDominatesRequireUserActionAndDefer() {
        val evaluator = evaluatorWithClock()
        val set = PolicySet(
            PolicySetId("set"),
            listOf(requireUserAction("a"), defer("b"), deny("c")),
        )
        val decision = evaluator.evaluate(set, testPolicyInput(), generousBudget)
        assertIs<PolicyCheckOutcome.Deny>(decision.outcome)
        assertEquals(PolicyCheckId("c"), decision.winningCheckId)
    }

    @Test
    fun denyDominatesEvenWhenDeferOverrideFlagIsSet() {
        val evaluator = evaluatorWithClock()
        val set = PolicySet(
            PolicySetId("set"),
            listOf(defer("a"), requireUserAction("b"), deny("c")),
        )
        val input = testPolicyInput(deferDominatesRequireUserAction = true)
        val decision = evaluator.evaluate(set, input, generousBudget)
        assertIs<PolicyCheckOutcome.Deny>(decision.outcome)
        assertEquals(PolicyCheckId("c"), decision.winningCheckId)
    }

    @Test
    fun firstDenyInEvaluationOrderWinsWhenMultipleDeniesExist() {
        val evaluator = evaluatorWithClock()
        val set = PolicySet(PolicySetId("set"), listOf(deny("first"), deny("second")))
        val decision = evaluator.evaluate(set, testPolicyInput(), generousBudget)
        assertEquals(PolicyCheckId("first"), decision.winningCheckId)
    }

    // -------------------------------------------------------------------------
    // Exhaustiveness — no short-circuit
    // -------------------------------------------------------------------------

    @Test
    fun everyCheckIsEvaluatedEvenAfterAnEarlyDeny() {
        val evaluator = evaluatorWithClock()
        val third = allow("third")
        val set = PolicySet(PolicySetId("set"), listOf(deny("first"), allow("second"), third))
        val decision = evaluator.evaluate(set, testPolicyInput(), generousBudget)
        assertEquals(3, decision.evidence.size)
        assertEquals(1, third.receivedInputs.size)
    }

    @Test
    fun evidenceOrderMatchesPolicySetOrder() {
        val evaluator = evaluatorWithClock()
        val set = PolicySet(PolicySetId("set"), listOf(allow("a"), deny("b"), allow("c")))
        val decision = evaluator.evaluate(set, testPolicyInput(), generousBudget)
        assertEquals(listOf("a", "b", "c"), decision.evidence.map { it.checkId.value })
    }

    // -------------------------------------------------------------------------
    // RequireUserAction / Defer precedence and override
    // -------------------------------------------------------------------------

    @Test
    fun requireUserActionDominatesDeferByDefault() {
        val evaluator = evaluatorWithClock()
        val set = PolicySet(PolicySetId("set"), listOf(defer("a"), requireUserAction("b")))
        val decision = evaluator.evaluate(set, testPolicyInput(), generousBudget)
        assertIs<PolicyCheckOutcome.RequireUserAction>(decision.outcome)
        assertEquals(PolicyCheckId("b"), decision.winningCheckId)
    }

    @Test
    fun deferWinsWhenOverrideFlagIsTrue() {
        val evaluator = evaluatorWithClock()
        val set = PolicySet(PolicySetId("set"), listOf(requireUserAction("a"), defer("b")))
        val input = testPolicyInput(deferDominatesRequireUserAction = true)
        val decision = evaluator.evaluate(set, input, generousBudget)
        assertIs<PolicyCheckOutcome.Defer>(decision.outcome)
        assertEquals(PolicyCheckId("b"), decision.winningCheckId)
    }

    @Test
    fun overrideFlagFalseKeepsDefaultPrecedence() {
        val evaluator = evaluatorWithClock()
        val set = PolicySet(PolicySetId("set"), listOf(defer("a"), requireUserAction("b")))
        val input = testPolicyInput(deferDominatesRequireUserAction = false)
        val decision = evaluator.evaluate(set, input, generousBudget)
        assertIs<PolicyCheckOutcome.RequireUserAction>(decision.outcome)
    }

    @Test
    fun requireUserActionAloneWinsOverAllowWithoutAnyDefer() {
        val evaluator = evaluatorWithClock()
        val set = PolicySet(PolicySetId("set"), listOf(allow("a"), requireUserAction("b")))
        val decision = evaluator.evaluate(set, testPolicyInput(), generousBudget)
        assertIs<PolicyCheckOutcome.RequireUserAction>(decision.outcome)
    }

    @Test
    fun deferAloneWinsOverAllowWithoutAnyRequireUserAction() {
        val evaluator = evaluatorWithClock()
        val set = PolicySet(PolicySetId("set"), listOf(allow("a"), defer("b")))
        val decision = evaluator.evaluate(set, testPolicyInput(), generousBudget)
        assertIs<PolicyCheckOutcome.Defer>(decision.outcome)
    }

    // -------------------------------------------------------------------------
    // Budget exhaustion — fail closed
    // -------------------------------------------------------------------------

    @Test
    fun budgetExhaustedBeforeFirstCheckProducesSynthesizedDenyWithNoEvidence() {
        // start=0, elapsed-check-before-check-1 reads 100 -> 100 >= budget(100)
        val evaluator = evaluatorWithClock(listOf(0L, 100L))
        val set = PolicySet(PolicySetId("set"), listOf(allow("a"), allow("b")))
        val decision = evaluator.evaluate(set, testPolicyInput(), PolicyEvaluationBudget(100L))
        assertIs<PolicyCheckOutcome.Deny>(decision.outcome)
        assertNull(decision.winningCheckId)
        assertTrue(decision.evidence.isEmpty())
    }

    @Test
    fun budgetExhaustedMidwayProducesPartialEvidence() {
        // start=0, before check1: 10 (<100, run it), before check2: 200 (>=100, stop)
        val evaluator = evaluatorWithClock(listOf(0L, 10L, 200L))
        val set = PolicySet(PolicySetId("set"), listOf(allow("a"), allow("b"), allow("c")))
        val decision = evaluator.evaluate(set, testPolicyInput(), PolicyEvaluationBudget(100L))
        assertIs<PolicyCheckOutcome.Deny>(decision.outcome)
        assertNull(decision.winningCheckId)
        assertEquals(1, decision.evidence.size)
        assertEquals(PolicyCheckId("a"), decision.evidence.single().checkId)
    }

    @Test
    fun budgetExhaustionFailsClosedEvenWhenEveryCheckWouldHaveAllowed() {
        val evaluator = evaluatorWithClock(listOf(0L, 100L))
        val set = PolicySet(PolicySetId("set"), listOf(allow("a")))
        val decision = evaluator.evaluate(set, testPolicyInput(), PolicyEvaluationBudget(100L))
        assertIs<PolicyCheckOutcome.Deny>(decision.outcome)
    }

    @Test
    fun sufficientBudgetRunsEveryCheckNormally() {
        val evaluator = evaluatorWithClock(listOf(0L, 10L, 20L))
        val set = PolicySet(PolicySetId("set"), listOf(allow("a"), allow("b")))
        val decision = evaluator.evaluate(set, testPolicyInput(), PolicyEvaluationBudget(1_000L))
        assertIs<PolicyCheckOutcome.Allow>(decision.outcome)
        assertEquals(2, decision.evidence.size)
    }

    // -------------------------------------------------------------------------
    // Determinism
    // -------------------------------------------------------------------------

    @Test
    fun sameInputsProduceTheSameDecision() {
        val set = PolicySet(PolicySetId("set"), listOf(allow("a"), deny("b")))
        val input = testPolicyInput()
        val first = evaluatorWithClock().evaluate(set, input, generousBudget)
        val second = evaluatorWithClock().evaluate(set, input, generousBudget)
        assertEquals(first, second)
    }
}
