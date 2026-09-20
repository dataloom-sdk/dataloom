package io.dataloom.plugin

import io.dataloom.api.identifier.RuntimeVersion
import io.dataloom.api.plugin.DataLoomPlugin
import io.dataloom.api.plugin.PluginCompatibilityRange
import io.dataloom.api.plugin.PluginExecutionBounds
import io.dataloom.api.plugin.PluginId
import io.dataloom.api.plugin.PluginLifecycleState
import io.dataloom.api.plugin.PluginManifest
import io.dataloom.api.plugin.PluginVendor
import io.dataloom.api.plugin.PluginVersion
import io.dataloom.api.security.GrantedCapabilities
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Verifies [PluginCompatibilityValidator]'s range semantics and that
 * [PluginLifecycleStateTracker] refuses to validate an incompatible plugin
 * through every `transition` overload.
 */
class PluginCompatibilityTest {

    private fun v(value: String) = RuntimeVersion(value)

    private fun range(minimum: String, maximum: String? = null) =
        PluginCompatibilityRange(v(minimum), maximum?.let(::v))

    private fun validate(range: PluginCompatibilityRange, sdk: String): PluginCompatibilityResult =
        PluginCompatibilityValidator.validate(range, v(sdk))

    private fun assertIncompatible(
        reason: PluginIncompatibilityReason,
        range: PluginCompatibilityRange,
        sdk: String,
    ) {
        assertEquals(
            PluginCompatibilityResult.Incompatible(sdkVersion = v(sdk), range = range, reason = reason),
            validate(range, sdk),
        )
    }

    // -------------------------------------------------------------------------
    // Validator semantics
    // -------------------------------------------------------------------------

    @Test
    fun `both bounds are inclusive`() {
        val bounded = range("1.0.0", "2.0.0")

        assertEquals(PluginCompatibilityResult.Compatible, validate(bounded, "1.0.0"))
        assertEquals(PluginCompatibilityResult.Compatible, validate(bounded, "1.5.3"))
        assertEquals(PluginCompatibilityResult.Compatible, validate(bounded, "2.0.0"))
    }

    @Test
    fun `a version below the minimum is BELOW_MINIMUM`() {
        assertIncompatible(PluginIncompatibilityReason.BELOW_MINIMUM, range("1.0.0", "2.0.0"), "0.9.9")
    }

    @Test
    fun `a version above the maximum is ABOVE_MAXIMUM`() {
        assertIncompatible(PluginIncompatibilityReason.ABOVE_MAXIMUM, range("1.0.0", "2.0.0"), "2.0.1")
    }

    @Test
    fun `an absent maximum imposes no upper bound`() {
        assertEquals(PluginCompatibilityResult.Compatible, validate(range("1.0.0"), "999.0.0"))
    }

    @Test
    fun `a range whose minimum exceeds its maximum is EMPTY_RANGE for every version`() {
        val inverted = range("2.0.0", "1.0.0")

        assertIncompatible(PluginIncompatibilityReason.EMPTY_RANGE, inverted, "1.5.0")
        assertIncompatible(PluginIncompatibilityReason.EMPTY_RANGE, inverted, "0.1.0")
        assertIncompatible(PluginIncompatibilityReason.EMPTY_RANGE, inverted, "3.0.0")
    }

    @Test
    fun `a pre-release SDK is below a minimum of its own release`() {
        assertIncompatible(PluginIncompatibilityReason.BELOW_MINIMUM, range("1.0.0"), "1.0.0-rc.1")
    }

    @Test
    fun `a pre-release SDK satisfies a pre-release minimum it has reached`() {
        assertEquals(PluginCompatibilityResult.Compatible, validate(range("1.0.0-alpha"), "1.0.0-beta"))
    }

    @Test
    fun `a pre-release of the maximum release is within the maximum`() {
        assertEquals(PluginCompatibilityResult.Compatible, validate(range("1.0.0", "2.0.0"), "2.0.0-rc.1"))
    }

    @Test
    fun `build metadata does not affect compatibility`() {
        assertEquals(PluginCompatibilityResult.Compatible, validate(range("1.0.0+a", "1.0.0+b"), "1.0.0+zzz"))
    }

    // -------------------------------------------------------------------------
    // Tracker gating
    // -------------------------------------------------------------------------

    private class FakePlugin(
        override val manifest: PluginManifest,
        override val executionBounds: PluginExecutionBounds,
    ) : DataLoomPlugin

    private fun plugin(id: String, compatibleSdkRange: PluginCompatibilityRange): DataLoomPlugin = FakePlugin(
        manifest = PluginManifest(
            id = PluginId(id),
            version = PluginVersion("1.0.0"),
            vendor = PluginVendor("Acme Corp"),
            compatibleSdkRange = compatibleSdkRange,
        ),
        executionBounds = PluginExecutionBounds(maximumExecutionMillis = 1_000L, maximumConcurrentInvocations = 1),
    )

    private fun trackerFor(sdk: String, vararg plugins: DataLoomPlugin) =
        PluginLifecycleStateTracker(PluginRegistry(plugins.toList()), v(sdk))

    private val id = PluginId("acme")

    private class CountingAuthorizer : PluginLifecycleAdministrationAuthorizer {
        var calls: Int = 0

        override suspend fun authorize(
            request: PluginLifecycleTransitionRequest,
        ): PluginLifecycleAdministrationAuthorizationDecision {
            calls++
            return PluginLifecycleAdministrationAuthorizationDecision.Authorized
        }
    }

    private fun request(target: PluginLifecycleState) = PluginLifecycleTransitionRequest(
        commandId = PluginLifecycleAdministrationCommandId("cmd"),
        pluginId = id,
        target = target,
        principalId = PluginLifecycleAdministrationPrincipalId("operator"),
        requestedAt = DataLoomInstant(1_000L),
        reason = PluginLifecycleAdministrationReason("test"),
    )

    @Test
    fun `compatibilityOf reports without changing state`() {
        val tracker = trackerFor("1.0.0", plugin("acme", range("2.0.0")))

        val result = tracker.compatibilityOf(id)

        assertIs<PluginCompatibilityResult.Incompatible>(result)
        assertEquals(PluginLifecycleState.LOADED, tracker.stateOf(id))
    }

    @Test
    fun `compatibilityOf throws for an unregistered plugin id`() {
        val tracker = trackerFor("1.0.0", plugin("acme", range("1.0.0")))

        assertFailsWith<IllegalArgumentException> { tracker.compatibilityOf(PluginId("missing")) }
    }

    @Test
    fun `a compatible plugin still validates through the plain overload`() {
        val tracker = trackerFor("1.5.0", plugin("acme", range("1.0.0", "2.0.0")))

        val result = tracker.transition(id, PluginLifecycleState.VALIDATED)

        assertIs<PluginLifecycleTransitionResult.Allowed>(result)
        assertEquals(PluginLifecycleState.VALIDATED, tracker.stateOf(id))
    }

    @Test
    fun `the plain overload refuses VALIDATED for an incompatible plugin and leaves state unchanged`() {
        val incompatibleRange = range("2.0.0")
        val tracker = trackerFor("1.5.0", plugin("acme", incompatibleRange))

        val result = tracker.transition(id, PluginLifecycleState.VALIDATED)

        assertEquals(
            PluginLifecycleTransitionResult.IncompatibleRuntime(
                from = PluginLifecycleState.LOADED,
                to = PluginLifecycleState.VALIDATED,
                incompatibility = PluginCompatibilityResult.Incompatible(
                    sdkVersion = v("1.5.0"),
                    range = incompatibleRange,
                    reason = PluginIncompatibilityReason.BELOW_MINIMUM,
                ),
            ),
            result,
        )
        assertEquals(PluginLifecycleState.LOADED, tracker.stateOf(id))
    }

    @Test
    fun `the capability-aware overload refuses VALIDATED for an incompatible plugin`() {
        val tracker = trackerFor("3.0.0", plugin("acme", range("1.0.0", "2.0.0")))

        val result = tracker.transition(id, PluginLifecycleState.VALIDATED, GrantedCapabilities.of(emptySet()))

        val incompatible = assertIs<PluginLifecycleTransitionResult.IncompatibleRuntime>(result)
        assertEquals(PluginIncompatibilityReason.ABOVE_MAXIMUM, incompatible.incompatibility.reason)
        assertEquals(PluginLifecycleState.LOADED, tracker.stateOf(id))
    }

    @Test
    fun `the authorizer-aware overload refuses an incompatible plugin without consulting the authorizer`() = runTest {
        val tracker = trackerFor("1.5.0", plugin("acme", range("2.0.0")))
        val authorizer = CountingAuthorizer()

        val result = tracker.transition(request(PluginLifecycleState.VALIDATED), authorizer)

        assertIs<PluginLifecycleTransitionResult.IncompatibleRuntime>(result)
        assertEquals(0, authorizer.calls)
        assertEquals(PluginLifecycleState.LOADED, tracker.stateOf(id))
    }

    @Test
    fun `a compatible plugin is authorized normally through the authorizer-aware overload`() = runTest {
        val tracker = trackerFor("1.5.0", plugin("acme", range("1.0.0")))
        val authorizer = CountingAuthorizer()

        val result = tracker.transition(request(PluginLifecycleState.VALIDATED), authorizer)

        assertIs<PluginLifecycleTransitionResult.Allowed>(result)
        assertEquals(1, authorizer.calls)
    }

    @Test
    fun `an incompatible plugin can never reach ACTIVE because every path passes through VALIDATED`() {
        val tracker = trackerFor("1.5.0", plugin("acme", range("2.0.0")))

        for (target in listOf(PluginLifecycleState.INITIALIZING, PluginLifecycleState.ACTIVE)) {
            assertIs<PluginLifecycleTransitionResult.Rejected>(tracker.transition(id, target))
        }
        assertIs<PluginLifecycleTransitionResult.IncompatibleRuntime>(
            tracker.transition(id, PluginLifecycleState.VALIDATED),
        )
        assertEquals(PluginLifecycleState.LOADED, tracker.stateOf(id))
    }

    @Test
    fun `an incompatible plugin can still be disabled`() {
        val tracker = trackerFor("1.5.0", plugin("acme", range("2.0.0")))

        val result = tracker.transition(id, PluginLifecycleState.DISABLED)

        assertIs<PluginLifecycleTransitionResult.Allowed>(result)
        assertEquals(PluginLifecycleState.DISABLED, tracker.stateOf(id))
    }

    @Test
    fun `compatibility is judged per plugin`() {
        val tracker = trackerFor(
            "1.5.0",
            plugin("old", range("1.0.0", "1.2.0")),
            plugin("current", range("1.0.0", "2.0.0")),
        )

        assertIs<PluginLifecycleTransitionResult.IncompatibleRuntime>(
            tracker.transition(PluginId("old"), PluginLifecycleState.VALIDATED),
        )
        assertIs<PluginLifecycleTransitionResult.Allowed>(
            tracker.transition(PluginId("current"), PluginLifecycleState.VALIDATED),
        )
        assertTrue(tracker.stateOf(PluginId("old")) == PluginLifecycleState.LOADED)
    }
}
