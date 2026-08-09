package io.dataloom.api.policy

import io.dataloom.api.identifier.PolicyCheckId
import io.dataloom.api.identifier.PolicySetId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PolicyDecisionTest {

    @Test
    fun checkEvidencePairsCheckIdWithItsOutcome() {
        val outcome = PolicyCheckOutcome.Allow(justification = "ok")
        val evidence = PolicyCheckEvidence(PolicyCheckId("check-1"), outcome)
        assertEquals(PolicyCheckId("check-1"), evidence.checkId)
        assertEquals(outcome, evidence.outcome)
    }

    @Test
    fun winningCheckIdMayBeNullForASynthesizedOutcome() {
        val decision = PolicyDecision(
            policySetId = PolicySetId("set"),
            outcome = PolicyCheckOutcome.Deny(justification = "budget exhausted"),
            winningCheckId = null,
            evidence = emptyList(),
        )
        assertNull(decision.winningCheckId)
    }

    @Test
    fun equalDecisionsCompareAsEqual() {
        val a = PolicyDecision(
            policySetId = PolicySetId("set"),
            outcome = PolicyCheckOutcome.Allow(justification = "ok"),
            winningCheckId = PolicyCheckId("check-1"),
            evidence = listOf(PolicyCheckEvidence(PolicyCheckId("check-1"), PolicyCheckOutcome.Allow(justification = "ok"))),
        )
        val b = PolicyDecision(
            policySetId = PolicySetId("set"),
            outcome = PolicyCheckOutcome.Allow(justification = "ok"),
            winningCheckId = PolicyCheckId("check-1"),
            evidence = listOf(PolicyCheckEvidence(PolicyCheckId("check-1"), PolicyCheckOutcome.Allow(justification = "ok"))),
        )
        assertEquals(a, b)
    }
}
