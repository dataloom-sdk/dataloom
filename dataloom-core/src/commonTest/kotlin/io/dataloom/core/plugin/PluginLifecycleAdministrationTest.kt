package io.dataloom.core.plugin

import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Verifies validation on the value types
 * [PluginLifecycleTransitionRequest]'s authorizer-aware
 * [PluginLifecycleStateTracker.transition] overload accepts -- see
 * [PluginLifecycleStateTrackerTest] for behavioral coverage of the overload
 * itself.
 */
class PluginLifecycleAdministrationTest {

    // -------------------------------------------------------------------------
    // PluginLifecycleAdministrationCommandId
    // -------------------------------------------------------------------------

    @Test
    fun `PluginLifecycleAdministrationCommandId rejects a blank value`() {
        assertFailsWith<IllegalArgumentException> {
            PluginLifecycleAdministrationCommandId("")
        }
    }

    @Test
    fun `PluginLifecycleAdministrationCommandId preserves its value`() {
        val id = PluginLifecycleAdministrationCommandId("cmd-1")
        kotlin.test.assertEquals("cmd-1", id.value)
        kotlin.test.assertEquals("cmd-1", id.toString())
    }

    // -------------------------------------------------------------------------
    // PluginLifecycleAdministrationPrincipalId
    // -------------------------------------------------------------------------

    @Test
    fun `PluginLifecycleAdministrationPrincipalId rejects a blank value`() {
        assertFailsWith<IllegalArgumentException> {
            PluginLifecycleAdministrationPrincipalId("   ")
        }
    }

    // -------------------------------------------------------------------------
    // PluginLifecycleAdministrationReason
    // -------------------------------------------------------------------------

    @Test
    fun `PluginLifecycleAdministrationReason rejects a blank value`() {
        assertFailsWith<IllegalArgumentException> {
            PluginLifecycleAdministrationReason("")
        }
    }

    @Test
    fun `PluginLifecycleAdministrationReason rejects a value exceeding the maximum length`() {
        assertFailsWith<IllegalArgumentException> {
            PluginLifecycleAdministrationReason("x".repeat(513))
        }
    }

    @Test
    fun `PluginLifecycleAdministrationReason accepts a value at the maximum length`() {
        PluginLifecycleAdministrationReason("x".repeat(512))
    }

    // -------------------------------------------------------------------------
    // PluginLifecycleAdministrationAuthorizationDecision.Denied
    // -------------------------------------------------------------------------

    @Test
    fun `Denied rejects a blank reason code`() {
        assertFailsWith<IllegalArgumentException> {
            PluginLifecycleAdministrationAuthorizationDecision.Denied("")
        }
    }

    @Test
    fun `Denied rejects a reason code exceeding the maximum length`() {
        assertFailsWith<IllegalArgumentException> {
            PluginLifecycleAdministrationAuthorizationDecision.Denied("x".repeat(129))
        }
    }

    @Test
    fun `Denied accepts a reason code at the maximum length`() {
        PluginLifecycleAdministrationAuthorizationDecision.Denied("x".repeat(128))
    }
}
