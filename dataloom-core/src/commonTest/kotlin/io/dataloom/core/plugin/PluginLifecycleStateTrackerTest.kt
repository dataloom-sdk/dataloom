package io.dataloom.core.plugin

import io.dataloom.api.identifier.RuntimeVersion
import io.dataloom.api.plugin.DataLoomPlugin
import io.dataloom.api.plugin.PluginCompatibilityRange
import io.dataloom.api.plugin.PluginExecutionBounds
import io.dataloom.api.plugin.PluginId
import io.dataloom.api.plugin.PluginLifecycleState
import io.dataloom.api.plugin.PluginManifest
import io.dataloom.api.plugin.PluginVendor
import io.dataloom.api.plugin.PluginVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Verifies [PluginLifecycleStateTracker]'s deny-by-default default state and
 * its enforcement of [PluginLifecycleTransitions] on every requested
 * transition.
 */
class PluginLifecycleStateTrackerTest {

    private val compatibilityRange = PluginCompatibilityRange(
        minimumSdkVersion = RuntimeVersion("1.0.0"),
    )

    private val defaultBounds = PluginExecutionBounds(
        maximumExecutionMillis = 1_000L,
        maximumConcurrentInvocations = 1,
    )

    private class FakePlugin(
        override val manifest: PluginManifest,
        override val executionBounds: PluginExecutionBounds,
    ) : DataLoomPlugin

    private fun plugin(id: String): FakePlugin = FakePlugin(
        manifest = PluginManifest(
            id = PluginId(id),
            version = PluginVersion("1.0.0"),
            vendor = PluginVendor("Acme Corp"),
            compatibleSdkRange = compatibilityRange,
        ),
        executionBounds = defaultBounds,
    )

    // -------------------------------------------------------------------------
    // Deny-by-default initial state
    // -------------------------------------------------------------------------

    @Test
    fun `newly tracked plugin starts at LOADED`() {
        val registry = PluginRegistry(listOf(plugin("a")))
        val tracker = PluginLifecycleStateTracker(registry)

        assertEquals(PluginLifecycleState.LOADED, tracker.stateOf(PluginId("a")))
    }

    @Test
    fun `multiple plugins each independently start at LOADED`() {
        val registry = PluginRegistry(listOf(plugin("a"), plugin("b")))
        val tracker = PluginLifecycleStateTracker(registry)

        assertEquals(PluginLifecycleState.LOADED, tracker.stateOf(PluginId("a")))
        assertEquals(PluginLifecycleState.LOADED, tracker.stateOf(PluginId("b")))
    }

    @Test
    fun `stateOf throws for an unregistered plugin id`() {
        val registry = PluginRegistry(listOf(plugin("a")))
        val tracker = PluginLifecycleStateTracker(registry)

        assertFailsWith<IllegalArgumentException> {
            tracker.stateOf(PluginId("missing"))
        }
    }

    // -------------------------------------------------------------------------
    // Legal transitions update tracked state
    // -------------------------------------------------------------------------

    @Test
    fun `legal transition updates tracked state and returns Allowed`() {
        val registry = PluginRegistry(listOf(plugin("a")))
        val tracker = PluginLifecycleStateTracker(registry)

        val result = tracker.transition(PluginId("a"), PluginLifecycleState.VALIDATED)

        assertTrue(result is PluginLifecycleTransitionResult.Allowed)
        assertEquals(PluginLifecycleState.VALIDATED, tracker.stateOf(PluginId("a")))
    }

    @Test
    fun `full happy path reaches ACTIVE`() {
        val registry = PluginRegistry(listOf(plugin("a")))
        val tracker = PluginLifecycleStateTracker(registry)
        val id = PluginId("a")

        tracker.transition(id, PluginLifecycleState.VALIDATED)
        tracker.transition(id, PluginLifecycleState.INITIALIZING)
        val result = tracker.transition(id, PluginLifecycleState.ACTIVE)

        assertTrue(result is PluginLifecycleTransitionResult.Allowed)
        assertEquals(PluginLifecycleState.ACTIVE, tracker.stateOf(id))
    }

    @Test
    fun `one plugin transition does not affect another plugin's state`() {
        val registry = PluginRegistry(listOf(plugin("a"), plugin("b")))
        val tracker = PluginLifecycleStateTracker(registry)

        tracker.transition(PluginId("a"), PluginLifecycleState.VALIDATED)

        assertEquals(PluginLifecycleState.VALIDATED, tracker.stateOf(PluginId("a")))
        assertEquals(PluginLifecycleState.LOADED, tracker.stateOf(PluginId("b")))
    }

    // -------------------------------------------------------------------------
    // Illegal transitions leave state unchanged and return Rejected
    // -------------------------------------------------------------------------

    @Test
    fun `illegal transition leaves tracked state unchanged and returns Rejected`() {
        val registry = PluginRegistry(listOf(plugin("a")))
        val tracker = PluginLifecycleStateTracker(registry)
        val id = PluginId("a")

        val result = tracker.transition(id, PluginLifecycleState.ACTIVE)

        assertTrue(result is PluginLifecycleTransitionResult.Rejected)
        assertEquals(PluginLifecycleState.LOADED, tracker.stateOf(id))
    }

    @Test
    fun `transition throws for an unregistered plugin id`() {
        val registry = PluginRegistry(listOf(plugin("a")))
        val tracker = PluginLifecycleStateTracker(registry)

        assertFailsWith<IllegalArgumentException> {
            tracker.transition(PluginId("missing"), PluginLifecycleState.VALIDATED)
        }
    }

    @Test
    fun `disabled plugin cannot be re-activated`() {
        val registry = PluginRegistry(listOf(plugin("a")))
        val tracker = PluginLifecycleStateTracker(registry)
        val id = PluginId("a")

        tracker.transition(id, PluginLifecycleState.DISABLED)
        val result = tracker.transition(id, PluginLifecycleState.ACTIVE)

        assertTrue(result is PluginLifecycleTransitionResult.Rejected)
        assertEquals(PluginLifecycleState.DISABLED, tracker.stateOf(id))
    }

    @Test
    fun `disabled plugin can be unloaded`() {
        val registry = PluginRegistry(listOf(plugin("a")))
        val tracker = PluginLifecycleStateTracker(registry)
        val id = PluginId("a")

        tracker.transition(id, PluginLifecycleState.DISABLED)
        val result = tracker.transition(id, PluginLifecycleState.UNLOADED)

        assertTrue(result is PluginLifecycleTransitionResult.Allowed)
        assertEquals(PluginLifecycleState.UNLOADED, tracker.stateOf(id))
    }
}
