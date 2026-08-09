package io.dataloom.api.policy

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.scheduling.SchedulingDelay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class PolicyCheckOutcomeTest {

    @Test
    fun allowRejectsBlankJustification() {
        assertFailsWith<IllegalArgumentException> {
            PolicyCheckOutcome.Allow(justification = "")
        }
    }

    @Test
    fun denyRejectsBlankJustification() {
        assertFailsWith<IllegalArgumentException> {
            PolicyCheckOutcome.Deny(justification = "   ")
        }
    }

    @Test
    fun requireUserActionRejectsBlankJustification() {
        assertFailsWith<IllegalArgumentException> {
            PolicyCheckOutcome.RequireUserAction(justification = "")
        }
    }

    @Test
    fun deferRejectsBlankJustification() {
        assertFailsWith<IllegalArgumentException> {
            PolicyCheckOutcome.Defer(delay = SchedulingDelay.ZERO, justification = "")
        }
    }

    @Test
    fun metadataDefaultsToEmpty() {
        val outcome = PolicyCheckOutcome.Allow(justification = "no objection")
        assertEquals(DataLoomMetadata.Empty, outcome.metadata)
    }

    @Test
    fun deferCarriesItsDelay() {
        val delay = SchedulingDelay(5_000L)
        val outcome = PolicyCheckOutcome.Defer(delay = delay, justification = "retry later")
        val defer = assertIs<PolicyCheckOutcome.Defer>(outcome)
        assertEquals(delay, defer.delay)
    }

    @Test
    fun equalOutcomesCompareAsEqual() {
        val a = PolicyCheckOutcome.Deny(justification = "blocked")
        val b = PolicyCheckOutcome.Deny(justification = "blocked")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun outcomesOfDifferentVariantsAreNeverEqual() {
        val allow: PolicyCheckOutcome = PolicyCheckOutcome.Allow(justification = "same text")
        val deny: PolicyCheckOutcome = PolicyCheckOutcome.Deny(justification = "same text")
        kotlin.test.assertNotEquals(allow, deny)
    }
}
