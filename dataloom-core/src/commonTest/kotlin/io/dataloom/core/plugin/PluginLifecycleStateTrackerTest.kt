package io.dataloom.core.plugin

import io.dataloom.api.identifier.RuntimeVersion
import io.dataloom.api.plugin.DataLoomPlugin
import io.dataloom.api.plugin.PluginCompatibilityRange
import io.dataloom.api.plugin.PluginExecutionBounds
import io.dataloom.api.plugin.PluginId
import io.dataloom.api.plugin.PluginLifecycleState
import io.dataloom.api.plugin.PluginManifest
import io.dataloom.api.plugin.PluginPermission
import io.dataloom.api.plugin.PluginVendor
import io.dataloom.api.plugin.PluginVersion
import io.dataloom.api.security.Capability
import io.dataloom.api.security.GrantedCapabilities
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

    private fun plugin(id: String, permissions: Set<PluginPermission> = emptySet()): FakePlugin = FakePlugin(
        manifest = PluginManifest(
            id = PluginId(id),
            version = PluginVersion("1.0.0"),
            vendor = PluginVendor("Acme Corp"),
            compatibleSdkRange = compatibilityRange,
            permissions = permissions,
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

    // -------------------------------------------------------------------------
    // Capability-aware transition(id, target, grantedCapabilities)
    // -------------------------------------------------------------------------

    @Test
    fun `capability-aware transition allows entering ACTIVE when every permission is granted`() {
        val registry = PluginRegistry(
            listOf(plugin("a", permissions = setOf(PluginPermission("storage.read")))),
        )
        val tracker = PluginLifecycleStateTracker(registry)
        val id = PluginId("a")
        val granted = GrantedCapabilities.of(setOf(Capability("storage.read")))

        tracker.transition(id, PluginLifecycleState.VALIDATED, granted)
        tracker.transition(id, PluginLifecycleState.INITIALIZING, granted)
        val result = tracker.transition(id, PluginLifecycleState.ACTIVE, granted)

        assertTrue(result is PluginLifecycleTransitionResult.Allowed)
        assertEquals(PluginLifecycleState.ACTIVE, tracker.stateOf(id))
    }

    @Test
    fun `capability-aware transition denies entering ACTIVE when a permission is missing`() {
        val registry = PluginRegistry(
            listOf(
                plugin(
                    "a",
                    permissions = setOf(PluginPermission("storage.read"), PluginPermission("network.push")),
                ),
            ),
        )
        val tracker = PluginLifecycleStateTracker(registry)
        val id = PluginId("a")
        val granted = GrantedCapabilities.of(setOf(Capability("storage.read")))

        tracker.transition(id, PluginLifecycleState.VALIDATED, granted)
        tracker.transition(id, PluginLifecycleState.INITIALIZING, granted)
        val result = tracker.transition(id, PluginLifecycleState.ACTIVE, granted)

        assertTrue(result is PluginLifecycleTransitionResult.PermissionDenied)
        assertEquals(setOf(PluginPermission("network.push")), result.missingPermissions)
        assertEquals(PluginLifecycleState.INITIALIZING, tracker.stateOf(id))
    }

    @Test
    fun `capability-aware transition denies entering ACTIVE when nothing is granted`() {
        val registry = PluginRegistry(
            listOf(plugin("a", permissions = setOf(PluginPermission("storage.read")))),
        )
        val tracker = PluginLifecycleStateTracker(registry)
        val id = PluginId("a")

        tracker.transition(id, PluginLifecycleState.VALIDATED, GrantedCapabilities.None)
        tracker.transition(id, PluginLifecycleState.INITIALIZING, GrantedCapabilities.None)
        val result = tracker.transition(id, PluginLifecycleState.ACTIVE, GrantedCapabilities.None)

        assertTrue(result is PluginLifecycleTransitionResult.PermissionDenied)
        assertEquals(setOf(PluginPermission("storage.read")), result.missingPermissions)
        assertEquals(PluginLifecycleState.INITIALIZING, tracker.stateOf(id))
    }

    @Test
    fun `capability-aware transition allows a plugin with no declared permissions to become ACTIVE with no grant`() {
        val registry = PluginRegistry(listOf(plugin("a")))
        val tracker = PluginLifecycleStateTracker(registry)
        val id = PluginId("a")

        tracker.transition(id, PluginLifecycleState.VALIDATED, GrantedCapabilities.None)
        tracker.transition(id, PluginLifecycleState.INITIALIZING, GrantedCapabilities.None)
        val result = tracker.transition(id, PluginLifecycleState.ACTIVE, GrantedCapabilities.None)

        assertTrue(result is PluginLifecycleTransitionResult.Allowed)
        assertEquals(PluginLifecycleState.ACTIVE, tracker.stateOf(id))
    }

    @Test
    fun `capability-aware transition does not gate non-ACTIVE targets on permissions`() {
        val registry = PluginRegistry(
            listOf(plugin("a", permissions = setOf(PluginPermission("storage.read")))),
        )
        val tracker = PluginLifecycleStateTracker(registry)
        val id = PluginId("a")

        val result = tracker.transition(id, PluginLifecycleState.VALIDATED, GrantedCapabilities.None)

        assertTrue(result is PluginLifecycleTransitionResult.Allowed)
        assertEquals(PluginLifecycleState.VALIDATED, tracker.stateOf(id))
    }

    @Test
    fun `capability-aware transition reports structural rejection before checking permissions`() {
        val registry = PluginRegistry(
            listOf(plugin("a", permissions = setOf(PluginPermission("storage.read")))),
        )
        val tracker = PluginLifecycleStateTracker(registry)
        val id = PluginId("a")

        val result = tracker.transition(id, PluginLifecycleState.ACTIVE, GrantedCapabilities.None)

        assertTrue(result is PluginLifecycleTransitionResult.Rejected)
        assertEquals(PluginLifecycleState.LOADED, tracker.stateOf(id))
    }

    @Test
    fun `capability-aware transition re-checks permissions on DEGRADED to ACTIVE recovery`() {
        val registry = PluginRegistry(
            listOf(plugin("a", permissions = setOf(PluginPermission("storage.read")))),
        )
        val tracker = PluginLifecycleStateTracker(registry)
        val id = PluginId("a")
        val granted = GrantedCapabilities.of(setOf(Capability("storage.read")))

        tracker.transition(id, PluginLifecycleState.VALIDATED, granted)
        tracker.transition(id, PluginLifecycleState.INITIALIZING, granted)
        tracker.transition(id, PluginLifecycleState.ACTIVE, granted)
        tracker.transition(id, PluginLifecycleState.DEGRADED, granted)

        val deniedResult = tracker.transition(id, PluginLifecycleState.ACTIVE, GrantedCapabilities.None)
        assertTrue(deniedResult is PluginLifecycleTransitionResult.PermissionDenied)
        assertEquals(PluginLifecycleState.DEGRADED, tracker.stateOf(id))

        val allowedResult = tracker.transition(id, PluginLifecycleState.ACTIVE, granted)
        assertTrue(allowedResult is PluginLifecycleTransitionResult.Allowed)
        assertEquals(PluginLifecycleState.ACTIVE, tracker.stateOf(id))
    }

    @Test
    fun `capability-aware transition throws for an unregistered plugin id`() {
        val registry = PluginRegistry(listOf(plugin("a")))
        val tracker = PluginLifecycleStateTracker(registry)

        assertFailsWith<IllegalArgumentException> {
            tracker.transition(PluginId("missing"), PluginLifecycleState.VALIDATED, GrantedCapabilities.None)
        }
    }
}
