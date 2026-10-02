package io.dataloom.plugin.reference

import io.dataloom.api.identifier.RuntimeVersion
import io.dataloom.api.plugin.PluginCompatibilityRange
import io.dataloom.api.plugin.PluginExecutionBounds
import io.dataloom.api.plugin.PluginId
import io.dataloom.api.plugin.PluginLifecycleState
import io.dataloom.api.plugin.PluginPermission
import io.dataloom.api.plugin.PluginVendor
import io.dataloom.api.plugin.PluginVersion
import io.dataloom.api.security.Capability
import io.dataloom.api.security.GrantedCapabilities
import io.dataloom.plugin.PluginExecutionBoundsEnforcer
import io.dataloom.plugin.PluginExecutionBoundsResult
import io.dataloom.plugin.PluginLifecycleStateTracker
import io.dataloom.plugin.PluginLifecycleTransitionResult
import io.dataloom.plugin.PluginRegistry
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises [DiagnosticsCounterPlugin] through the real
 * [PluginRegistry] / [PluginLifecycleStateTracker] /
 * [PluginExecutionBoundsEnforcer] engine directly -- not only as a
 * [io.dataloom.plugin.testkit.PluginCertificationKit] input (see
 * `DiagnosticsCounterPluginCertificationTest`) -- proving it behaves
 * correctly as a genuinely registered, permission-gated, activated,
 * invoked, and disabled plugin, the same way a real host application would
 * use it.
 */
class DiagnosticsCounterPluginIntegrationTest {

    private val sdkVersion = RuntimeVersion("1.0.0")
    private val id = PluginId("dataloom.reference.diagnostics-counter")

    private fun newPlugin(
        bounds: PluginExecutionBounds = PluginExecutionBounds(maximumExecutionMillis = 5_000L, maximumConcurrentInvocations = 2),
    ): DiagnosticsCounterPlugin {
        val manifest = DiagnosticsCounterPlugin.manifestFor(
            id = id,
            version = PluginVersion("1.0.0"),
            vendor = PluginVendor("dataloom"),
            compatibleSdkRange = PluginCompatibilityRange(minimumSdkVersion = sdkVersion),
        )
        return DiagnosticsCounterPlugin(manifest, bounds)
    }

    @Test
    fun `registers and activates once its declared permission is granted then records events through the enforcer and drains on disable`() = runTest {
        val plugin = newPlugin()
        val registry = PluginRegistry(listOf(plugin))
        val tracker = PluginLifecycleStateTracker(registry, sdkVersion)
        val enforcer = PluginExecutionBoundsEnforcer(tracker)
        val granted = GrantedCapabilities.of(setOf(Capability(DiagnosticsCounterPlugin.RECORD_PERMISSION)))

        assertEquals(PluginLifecycleState.LOADED, tracker.stateOf(id))

        // A freshly registered, not-yet-activated plugin must refuse invocations
        // without running them at all.
        val tooEarly = enforcer.execute(id) { plugin.recordEvent("too-early") }
        assertTrue(tooEarly is PluginExecutionBoundsResult.NotActive, "expected NotActive before activation, got $tooEarly")
        assertEquals(emptyMap(), plugin.snapshot())

        assertTrue(tracker.transition(id, PluginLifecycleState.VALIDATED, granted) is PluginLifecycleTransitionResult.Allowed)
        assertTrue(tracker.transition(id, PluginLifecycleState.INITIALIZING, granted) is PluginLifecycleTransitionResult.Allowed)
        assertTrue(tracker.transition(id, PluginLifecycleState.ACTIVE, granted) is PluginLifecycleTransitionResult.Allowed)
        assertEquals(PluginLifecycleState.ACTIVE, tracker.stateOf(id))

        val first = enforcer.execute(id) { plugin.recordEvent("sync.pull.completed") }
        val second = enforcer.execute(id) { plugin.recordEvent("sync.pull.completed") }
        val third = enforcer.execute(id) { plugin.recordEvent("sync.push.completed") }
        assertTrue(first is PluginExecutionBoundsResult.Completed, "expected Completed, got $first")
        assertTrue(second is PluginExecutionBoundsResult.Completed, "expected Completed, got $second")
        assertTrue(third is PluginExecutionBoundsResult.Completed, "expected Completed, got $third")

        assertEquals(mapOf("sync.pull.completed" to 2L, "sync.push.completed" to 1L), plugin.snapshot())

        assertTrue(tracker.transition(id, PluginLifecycleState.DISABLED, granted) is PluginLifecycleTransitionResult.Allowed)
        val afterDisable = enforcer.execute(id) { plugin.recordEvent("should-not-run") }
        assertTrue(afterDisable is PluginExecutionBoundsResult.NotActive, "expected NotActive after DISABLED, got $afterDisable")
        // The disabled plugin's in-memory state is untouched by the refused call.
        assertEquals(mapOf("sync.pull.completed" to 2L, "sync.push.completed" to 1L), plugin.snapshot())
    }

    @Test
    fun `activation is denied without the declared permission and the enforcer never invokes it`() = runTest {
        val plugin = newPlugin()
        val registry = PluginRegistry(listOf(plugin))
        val tracker = PluginLifecycleStateTracker(registry, sdkVersion)
        val enforcer = PluginExecutionBoundsEnforcer(tracker)
        val noGrant = GrantedCapabilities.of(emptySet())

        tracker.transition(id, PluginLifecycleState.VALIDATED, noGrant)
        tracker.transition(id, PluginLifecycleState.INITIALIZING, noGrant)
        val denied = tracker.transition(id, PluginLifecycleState.ACTIVE, noGrant)

        assertTrue(denied is PluginLifecycleTransitionResult.PermissionDenied, "expected PermissionDenied, got $denied")
        assertEquals(
            setOf(PluginPermission(DiagnosticsCounterPlugin.RECORD_PERMISSION)),
            denied.missingPermissions,
        )
        assertEquals(PluginLifecycleState.INITIALIZING, tracker.stateOf(id))

        val result = enforcer.execute(id) { plugin.recordEvent("unauthorized") }
        assertTrue(result is PluginExecutionBoundsResult.NotActive, "expected NotActive, got $result")
        assertEquals(emptyMap(), plugin.snapshot())
    }

    @Test
    fun `execution-bounds enforcement applies to this plugin's own invocations exactly as it does to any other`() = runTest {
        val plugin = newPlugin(bounds = PluginExecutionBounds(maximumExecutionMillis = 50L, maximumConcurrentInvocations = 1))
        val registry = PluginRegistry(listOf(plugin))
        val tracker = PluginLifecycleStateTracker(registry, sdkVersion)
        val enforcer = PluginExecutionBoundsEnforcer(tracker)
        val granted = GrantedCapabilities.of(setOf(Capability(DiagnosticsCounterPlugin.RECORD_PERMISSION)))
        tracker.transition(id, PluginLifecycleState.VALIDATED, granted)
        tracker.transition(id, PluginLifecycleState.INITIALIZING, granted)
        tracker.transition(id, PluginLifecycleState.ACTIVE, granted)

        val timedOut = enforcer.execute(id) {
            awaitCancellation()
        }

        assertTrue(timedOut is PluginExecutionBoundsResult.TimedOut, "expected TimedOut, got $timedOut")
        assertEquals(50L, timedOut.maximumExecutionMillis)
    }
}
