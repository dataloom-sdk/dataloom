package io.dataloom.api.policy

import io.dataloom.api.identifier.PolicyCheckId
import io.dataloom.api.identifier.PolicySetId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PolicySetTest {

    private fun allowCheck(id: String) = StubPolicyCheck(
        PolicyCheckId(id),
        PolicyCheckOutcome.Allow(justification = "ok"),
    )

    @Test
    fun emptyChecksAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            PolicySet(PolicySetId("set"), emptyList())
        }
    }

    @Test
    fun duplicateCheckIdsAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            PolicySet(
                PolicySetId("set"),
                listOf(allowCheck("dup"), allowCheck("dup")),
            )
        }
    }

    @Test
    fun sixtyFourChecksIsAccepted() {
        val checks = (1..64).map { allowCheck("check-$it") }
        val set = PolicySet(PolicySetId("set"), checks)
        assertEquals(64, set.checks.size)
    }

    @Test
    fun sixtyFiveChecksIsRejected() {
        val checks = (1..65).map { allowCheck("check-$it") }
        assertFailsWith<IllegalArgumentException> {
            PolicySet(PolicySetId("set"), checks)
        }
    }

    @Test
    fun mutatingTheSourceListAfterConstructionDoesNotAffectTheSet() {
        val source = mutableListOf(allowCheck("a"))
        val set = PolicySet(PolicySetId("set"), source)
        source.add(allowCheck("b"))
        assertEquals(1, set.checks.size)
    }

    @Test
    fun checksPreserveSuppliedOrder() {
        val set = PolicySet(PolicySetId("set"), listOf(allowCheck("a"), allowCheck("b"), allowCheck("c")))
        assertEquals(listOf("a", "b", "c"), set.checks.map { it.id.value })
    }

    @Test
    fun toStringNeverRendersIndividualChecks() {
        val set = PolicySet(PolicySetId("secret-set-name-is-fine"), listOf(allowCheck("check-detail-should-not-leak")))
        assertTrue(!set.toString().contains("check-detail-should-not-leak"))
    }
}
