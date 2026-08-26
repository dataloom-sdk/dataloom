package io.dataloom.core.plugin

import io.dataloom.api.plugin.PluginLifecycleState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies [PluginLifecycleTransitions]'s pure transition-legality graph.
 */
class PluginLifecycleTransitionsTest {

    // -------------------------------------------------------------------------
    // Legal main-path transitions
    // -------------------------------------------------------------------------

    @Test
    fun `LOADED to VALIDATED is legal`() {
        assertTrue(PluginLifecycleTransitions.isLegal(PluginLifecycleState.LOADED, PluginLifecycleState.VALIDATED))
    }

    @Test
    fun `VALIDATED to INITIALIZING is legal`() {
        assertTrue(
            PluginLifecycleTransitions.isLegal(PluginLifecycleState.VALIDATED, PluginLifecycleState.INITIALIZING),
        )
    }

    @Test
    fun `INITIALIZING to ACTIVE is legal`() {
        assertTrue(
            PluginLifecycleTransitions.isLegal(PluginLifecycleState.INITIALIZING, PluginLifecycleState.ACTIVE),
        )
    }

    @Test
    fun `ACTIVE to DEGRADED and back is legal`() {
        assertTrue(PluginLifecycleTransitions.isLegal(PluginLifecycleState.ACTIVE, PluginLifecycleState.DEGRADED))
        assertTrue(PluginLifecycleTransitions.isLegal(PluginLifecycleState.DEGRADED, PluginLifecycleState.ACTIVE))
    }

    @Test
    fun `DISABLED to UNLOADED is legal`() {
        assertTrue(PluginLifecycleTransitions.isLegal(PluginLifecycleState.DISABLED, PluginLifecycleState.UNLOADED))
    }

    // -------------------------------------------------------------------------
    // Legal failure-escape transitions to DISABLED
    // -------------------------------------------------------------------------

    @Test
    fun `every pre-ACTIVE state can escape to DISABLED`() {
        assertTrue(PluginLifecycleTransitions.isLegal(PluginLifecycleState.LOADED, PluginLifecycleState.DISABLED))
        assertTrue(PluginLifecycleTransitions.isLegal(PluginLifecycleState.VALIDATED, PluginLifecycleState.DISABLED))
        assertTrue(
            PluginLifecycleTransitions.isLegal(PluginLifecycleState.INITIALIZING, PluginLifecycleState.DISABLED),
        )
    }

    @Test
    fun `ACTIVE and DEGRADED can transition to DISABLED`() {
        assertTrue(PluginLifecycleTransitions.isLegal(PluginLifecycleState.ACTIVE, PluginLifecycleState.DISABLED))
        assertTrue(PluginLifecycleTransitions.isLegal(PluginLifecycleState.DEGRADED, PluginLifecycleState.DISABLED))
    }

    // -------------------------------------------------------------------------
    // Illegal transitions
    // -------------------------------------------------------------------------

    @Test
    fun `UNLOADED is terminal`() {
        for (target in PluginLifecycleState.entries) {
            assertFalse(PluginLifecycleTransitions.isLegal(PluginLifecycleState.UNLOADED, target))
        }
    }

    @Test
    fun `cannot skip VALIDATED from LOADED to INITIALIZING`() {
        assertFalse(
            PluginLifecycleTransitions.isLegal(PluginLifecycleState.LOADED, PluginLifecycleState.INITIALIZING),
        )
    }

    @Test
    fun `cannot skip straight to ACTIVE from LOADED`() {
        assertFalse(PluginLifecycleTransitions.isLegal(PluginLifecycleState.LOADED, PluginLifecycleState.ACTIVE))
    }

    @Test
    fun `DISABLED cannot re-enable directly to ACTIVE`() {
        assertFalse(PluginLifecycleTransitions.isLegal(PluginLifecycleState.DISABLED, PluginLifecycleState.ACTIVE))
    }

    @Test
    fun `DISABLED cannot re-enable directly to VALIDATED`() {
        assertFalse(
            PluginLifecycleTransitions.isLegal(PluginLifecycleState.DISABLED, PluginLifecycleState.VALIDATED),
        )
    }

    @Test
    fun `same-state transitions are illegal for every state`() {
        for (state in PluginLifecycleState.entries) {
            assertFalse(PluginLifecycleTransitions.isLegal(state, state), "$state -> $state must be illegal")
        }
    }

    @Test
    fun `cannot go backwards from INITIALIZING to VALIDATED`() {
        assertFalse(
            PluginLifecycleTransitions.isLegal(PluginLifecycleState.INITIALIZING, PluginLifecycleState.VALIDATED),
        )
    }

    @Test
    fun `cannot jump directly to UNLOADED from ACTIVE`() {
        assertFalse(PluginLifecycleTransitions.isLegal(PluginLifecycleState.ACTIVE, PluginLifecycleState.UNLOADED))
    }

    // -------------------------------------------------------------------------
    // validate() result shape
    // -------------------------------------------------------------------------

    @Test
    fun `validate returns Allowed for a legal transition`() {
        val result = PluginLifecycleTransitions.validate(PluginLifecycleState.LOADED, PluginLifecycleState.VALIDATED)

        assertEquals(
            PluginLifecycleTransitionResult.Allowed(PluginLifecycleState.LOADED, PluginLifecycleState.VALIDATED),
            result,
        )
    }

    @Test
    fun `validate returns Rejected with a reason for an illegal transition`() {
        val result = PluginLifecycleTransitions.validate(PluginLifecycleState.LOADED, PluginLifecycleState.ACTIVE)

        assertTrue(result is PluginLifecycleTransitionResult.Rejected)
        assertEquals(PluginLifecycleState.LOADED, result.from)
        assertEquals(PluginLifecycleState.ACTIVE, result.to)
        assertTrue(result.reason.isNotBlank())
    }
}
