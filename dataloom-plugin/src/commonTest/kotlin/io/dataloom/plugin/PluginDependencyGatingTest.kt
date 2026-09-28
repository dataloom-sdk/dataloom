package io.dataloom.plugin

import io.dataloom.api.identifier.RuntimeVersion
import io.dataloom.api.plugin.DataLoomPlugin
import io.dataloom.api.plugin.PluginCompatibilityRange
import io.dataloom.api.plugin.PluginDependency
import io.dataloom.api.plugin.PluginExecutionBounds
import io.dataloom.api.plugin.PluginId
import io.dataloom.api.plugin.PluginLifecycleState
import io.dataloom.api.plugin.PluginManifest
import io.dataloom.api.plugin.PluginVendor
import io.dataloom.api.plugin.PluginVersion
import io.dataloom.api.plugin.PluginVersionRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies [PluginLifecycleStateTracker]'s dependency gate: a declared
 * [PluginDependency] must be present, in its declared [PluginVersionRange], and
 * not retired for its dependent to enter [PluginLifecycleState.VALIDATED] or
 * [PluginLifecycleState.ACTIVE].
 *
 * [PluginRegistryTest] already covers the dependency-graph *shape* (unresolved
 * ids are registered without throwing; cycles remain a construction-time
 * [IllegalArgumentException]). This suite covers the *lifecycle-gating*
 * behavior layered on top: [PluginLifecycleTransitionResult.DependencyUnsatisfied].
 */
class PluginDependencyGatingTest {

    private val sdk = RuntimeVersion("1.0.0")
    private val wideOpenSdkRange = PluginCompatibilityRange(minimumSdkVersion = RuntimeVersion("0.0.0"))

    private class FakePlugin(
        override val manifest: PluginManifest,
        override val executionBounds: PluginExecutionBounds,
    ) : DataLoomPlugin

    private fun plugin(
        id: String,
        version: String = "1.0.0",
        dependencies: Set<PluginDependency> = emptySet(),
    ): DataLoomPlugin = FakePlugin(
        manifest = PluginManifest(
            id = PluginId(id),
            version = PluginVersion(version),
            vendor = PluginVendor("Acme Corp"),
            compatibleSdkRange = wideOpenSdkRange,
            dependencies = dependencies,
        ),
        executionBounds = PluginExecutionBounds(maximumExecutionMillis = 1_000L, maximumConcurrentInvocations = 1),
    )

    private fun dependency(id: String, minimum: String = "1.0.0", maximum: String? = null) = PluginDependency(
        pluginId = PluginId(id),
        supportedVersionRange = PluginVersionRange(PluginVersion(minimum), maximum?.let(::PluginVersion)),
    )

    /** Walks `LOADED -> VALIDATED -> INITIALIZING -> ACTIVE`, asserting `Allowed` at every step. */
    private fun activate(tracker: PluginLifecycleStateTracker, id: PluginId) {
        for (target in listOf(
            PluginLifecycleState.VALIDATED,
            PluginLifecycleState.INITIALIZING,
            PluginLifecycleState.ACTIVE,
        )) {
            assertIs<PluginLifecycleTransitionResult.Allowed>(tracker.transition(id, target), "transition to $target")
        }
    }

    // -------------------------------------------------------------------------
    // Missing dependency
    // -------------------------------------------------------------------------

    @Test
    fun `entering VALIDATED is refused when a dependency is not registered`() {
        val registry = PluginRegistry(listOf(plugin("app", dependencies = setOf(dependency("missing")))))
        val tracker = PluginLifecycleStateTracker(registry, sdk)

        val result = tracker.transition(PluginId("app"), PluginLifecycleState.VALIDATED)

        assertEquals(
            PluginLifecycleTransitionResult.DependencyUnsatisfied(
                from = PluginLifecycleState.LOADED,
                to = PluginLifecycleState.VALIDATED,
                issues = listOf(PluginDependencyIssue(PluginId("missing"), PluginDependencyIssueReason.NOT_REGISTERED)),
            ),
            result,
        )
        assertEquals(PluginLifecycleState.LOADED, tracker.stateOf(PluginId("app")))
    }

    // -------------------------------------------------------------------------
    // Out-of-range version
    // -------------------------------------------------------------------------

    @Test
    fun `entering VALIDATED is refused when a dependency's version is below the declared minimum`() {
        val registry = PluginRegistry(
            listOf(plugin("base", version = "1.0.0"), plugin("app", dependencies = setOf(dependency("base", minimum = "2.0.0")))),
        )
        val tracker = PluginLifecycleStateTracker(registry, sdk)

        val result = tracker.transition(PluginId("app"), PluginLifecycleState.VALIDATED)

        val unsatisfied = assertIs<PluginLifecycleTransitionResult.DependencyUnsatisfied>(result)
        assertEquals(
            listOf(PluginDependencyIssue(PluginId("base"), PluginDependencyIssueReason.VERSION_BELOW_MINIMUM)),
            unsatisfied.issues,
        )
    }

    @Test
    fun `entering VALIDATED is refused when a dependency's version is above the declared maximum`() {
        val registry = PluginRegistry(
            listOf(
                plugin("base", version = "3.0.0"),
                plugin("app", dependencies = setOf(dependency("base", minimum = "1.0.0", maximum = "2.0.0"))),
            ),
        )
        val tracker = PluginLifecycleStateTracker(registry, sdk)

        val result = tracker.transition(PluginId("app"), PluginLifecycleState.VALIDATED)

        val unsatisfied = assertIs<PluginLifecycleTransitionResult.DependencyUnsatisfied>(result)
        assertEquals(
            listOf(PluginDependencyIssue(PluginId("base"), PluginDependencyIssueReason.VERSION_ABOVE_MAXIMUM)),
            unsatisfied.issues,
        )
    }

    @Test
    fun `an inverted declared range is EMPTY_VERSION_RANGE for any dependency version`() {
        val registry = PluginRegistry(
            listOf(
                plugin("base", version = "1.5.0"),
                plugin("app", dependencies = setOf(dependency("base", minimum = "2.0.0", maximum = "1.0.0"))),
            ),
        )
        val tracker = PluginLifecycleStateTracker(registry, sdk)

        val result = tracker.transition(PluginId("app"), PluginLifecycleState.VALIDATED)

        val unsatisfied = assertIs<PluginLifecycleTransitionResult.DependencyUnsatisfied>(result)
        assertEquals(PluginDependencyIssueReason.EMPTY_VERSION_RANGE, unsatisfied.issues.single().reason)
    }

    @Test
    fun `a dependency version inside an inclusive range validates normally`() {
        val registry = PluginRegistry(
            listOf(
                plugin("base", version = "1.5.0"),
                plugin("app", dependencies = setOf(dependency("base", minimum = "1.0.0", maximum = "2.0.0"))),
            ),
        )
        val tracker = PluginLifecycleStateTracker(registry, sdk)

        val result = tracker.transition(PluginId("app"), PluginLifecycleState.VALIDATED)

        assertIs<PluginLifecycleTransitionResult.Allowed>(result)
    }

    // -------------------------------------------------------------------------
    // Disabled / unloaded dependency
    // -------------------------------------------------------------------------

    @Test
    fun `entering VALIDATED is refused when a dependency is DISABLED`() {
        val registry = PluginRegistry(listOf(plugin("base"), plugin("app", dependencies = setOf(dependency("base")))))
        val tracker = PluginLifecycleStateTracker(registry, sdk)
        assertIs<PluginLifecycleTransitionResult.Allowed>(
            tracker.transition(PluginId("base"), PluginLifecycleState.DISABLED),
        )

        val result = tracker.transition(PluginId("app"), PluginLifecycleState.VALIDATED)

        val unsatisfied = assertIs<PluginLifecycleTransitionResult.DependencyUnsatisfied>(result)
        assertEquals(
            listOf(PluginDependencyIssue(PluginId("base"), PluginDependencyIssueReason.DISABLED)),
            unsatisfied.issues,
        )
    }

    @Test
    fun `entering VALIDATED is refused when a dependency is UNLOADED`() {
        val registry = PluginRegistry(listOf(plugin("base"), plugin("app", dependencies = setOf(dependency("base")))))
        val tracker = PluginLifecycleStateTracker(registry, sdk)
        tracker.transition(PluginId("base"), PluginLifecycleState.DISABLED)
        assertIs<PluginLifecycleTransitionResult.Allowed>(
            tracker.transition(PluginId("base"), PluginLifecycleState.UNLOADED),
        )

        val result = tracker.transition(PluginId("app"), PluginLifecycleState.VALIDATED)

        val unsatisfied = assertIs<PluginLifecycleTransitionResult.DependencyUnsatisfied>(result)
        assertEquals(PluginDependencyIssueReason.UNLOADED, unsatisfied.issues.single().reason)
    }

    // -------------------------------------------------------------------------
    // VALIDATED only needs presence/version/not-retired; ACTIVE needs ACTIVE too
    // -------------------------------------------------------------------------

    @Test
    fun `entering VALIDATED does not require a dependency to be ACTIVE yet`() {
        val registry = PluginRegistry(listOf(plugin("base"), plugin("app", dependencies = setOf(dependency("base")))))
        val tracker = PluginLifecycleStateTracker(registry, sdk)

        // "base" is still LOADED (never activated), yet "app" may still validate.
        val result = tracker.transition(PluginId("app"), PluginLifecycleState.VALIDATED)

        assertIs<PluginLifecycleTransitionResult.Allowed>(result)
    }

    @Test
    fun `entering ACTIVE is refused while a dependency is not yet ACTIVE`() {
        val registry = PluginRegistry(listOf(plugin("base"), plugin("app", dependencies = setOf(dependency("base")))))
        val tracker = PluginLifecycleStateTracker(registry, sdk)
        tracker.transition(PluginId("app"), PluginLifecycleState.VALIDATED)
        tracker.transition(PluginId("app"), PluginLifecycleState.INITIALIZING)

        val result = tracker.transition(PluginId("app"), PluginLifecycleState.ACTIVE)

        val unsatisfied = assertIs<PluginLifecycleTransitionResult.DependencyUnsatisfied>(result)
        assertEquals(
            listOf(PluginDependencyIssue(PluginId("base"), PluginDependencyIssueReason.NOT_ACTIVE)),
            unsatisfied.issues,
        )
        assertEquals(PluginLifecycleState.INITIALIZING, tracker.stateOf(PluginId("app")))
    }

    @Test
    fun `entering ACTIVE succeeds once the dependency is itself ACTIVE`() {
        val registry = PluginRegistry(listOf(plugin("base"), plugin("app", dependencies = setOf(dependency("base")))))
        val tracker = PluginLifecycleStateTracker(registry, sdk)
        activate(tracker, PluginId("base"))
        tracker.transition(PluginId("app"), PluginLifecycleState.VALIDATED)
        tracker.transition(PluginId("app"), PluginLifecycleState.INITIALIZING)

        val result = tracker.transition(PluginId("app"), PluginLifecycleState.ACTIVE)

        assertIs<PluginLifecycleTransitionResult.Allowed>(result)
        assertEquals(PluginLifecycleState.ACTIVE, tracker.stateOf(PluginId("app")))
    }

    @Test
    fun `the DEGRADED to ACTIVE recovery edge re-checks the dependency gate`() {
        val registry = PluginRegistry(listOf(plugin("base"), plugin("app", dependencies = setOf(dependency("base")))))
        val tracker = PluginLifecycleStateTracker(registry, sdk)
        activate(tracker, PluginId("base"))
        activate(tracker, PluginId("app"))
        tracker.transition(PluginId("app"), PluginLifecycleState.DEGRADED)
        tracker.transition(PluginId("base"), PluginLifecycleState.DISABLED)

        val result = tracker.transition(PluginId("app"), PluginLifecycleState.ACTIVE)

        val unsatisfied = assertIs<PluginLifecycleTransitionResult.DependencyUnsatisfied>(result)
        assertEquals(PluginDependencyIssueReason.DISABLED, unsatisfied.issues.single().reason)
        assertEquals(PluginLifecycleState.DEGRADED, tracker.stateOf(PluginId("app")))
    }

    @Test
    fun `entering DISABLED is never gated on dependencies`() {
        val registry = PluginRegistry(listOf(plugin("app", dependencies = setOf(dependency("missing")))))
        val tracker = PluginLifecycleStateTracker(registry, sdk)

        val result = tracker.transition(PluginId("app"), PluginLifecycleState.DISABLED)

        assertIs<PluginLifecycleTransitionResult.Allowed>(result)
    }

    // -------------------------------------------------------------------------
    // Transitive chain
    // -------------------------------------------------------------------------

    @Test
    fun `a transitive dependency chain requires every link to be ACTIVE`() {
        // top depends on mid depends on base. base is never activated.
        val registry = PluginRegistry(
            listOf(
                plugin("base"),
                plugin("mid", dependencies = setOf(dependency("base"))),
                plugin("top", dependencies = setOf(dependency("mid"))),
            ),
        )
        val tracker = PluginLifecycleStateTracker(registry, sdk)
        tracker.transition(PluginId("mid"), PluginLifecycleState.VALIDATED)
        tracker.transition(PluginId("mid"), PluginLifecycleState.INITIALIZING)
        // "mid" cannot reach ACTIVE while "base" is not ACTIVE.
        assertIs<PluginLifecycleTransitionResult.DependencyUnsatisfied>(
            tracker.transition(PluginId("mid"), PluginLifecycleState.ACTIVE),
        )
        tracker.transition(PluginId("top"), PluginLifecycleState.VALIDATED)
        tracker.transition(PluginId("top"), PluginLifecycleState.INITIALIZING)

        val result = tracker.transition(PluginId("top"), PluginLifecycleState.ACTIVE)

        // "mid" is not ACTIVE (its own gate on "base" never passed), so "top" is refused too.
        val unsatisfied = assertIs<PluginLifecycleTransitionResult.DependencyUnsatisfied>(result)
        assertEquals(PluginId("mid"), unsatisfied.issues.single().dependencyId)
        assertEquals(PluginDependencyIssueReason.NOT_ACTIVE, unsatisfied.issues.single().reason)
    }

    @Test
    fun `a full transitive chain activates end to end when every link is driven in order`() {
        val registry = PluginRegistry(
            listOf(
                plugin("base"),
                plugin("mid", dependencies = setOf(dependency("base"))),
                plugin("top", dependencies = setOf(dependency("mid"))),
            ),
        )
        val tracker = PluginLifecycleStateTracker(registry, sdk)

        activate(tracker, PluginId("base"))
        activate(tracker, PluginId("mid"))
        activate(tracker, PluginId("top"))

        assertTrue(
            listOf("base", "mid", "top").all { tracker.stateOf(PluginId(it)) == PluginLifecycleState.ACTIVE },
        )
    }

    // -------------------------------------------------------------------------
    // Multiple dependencies and determinism
    // -------------------------------------------------------------------------

    @Test
    fun `every unsatisfied dependency is reported and not only the first`() {
        val registry = PluginRegistry(
            listOf(
                plugin("app", dependencies = setOf(dependency("missing-a"), dependency("missing-b"))),
            ),
        )
        val tracker = PluginLifecycleStateTracker(registry, sdk)

        val result = tracker.transition(PluginId("app"), PluginLifecycleState.VALIDATED)

        val unsatisfied = assertIs<PluginLifecycleTransitionResult.DependencyUnsatisfied>(result)
        assertEquals(
            listOf(
                PluginDependencyIssue(PluginId("missing-a"), PluginDependencyIssueReason.NOT_REGISTERED),
                PluginDependencyIssue(PluginId("missing-b"), PluginDependencyIssueReason.NOT_REGISTERED),
            ),
            unsatisfied.issues,
        )
    }

    @Test
    fun `issues are ordered by dependency id regardless of declaration order`() {
        val registry = PluginRegistry(
            listOf(plugin("app", dependencies = setOf(dependency("zzz-missing"), dependency("aaa-missing")))),
        )
        val tracker = PluginLifecycleStateTracker(registry, sdk)

        val result = tracker.transition(PluginId("app"), PluginLifecycleState.VALIDATED)

        val unsatisfied = assertIs<PluginLifecycleTransitionResult.DependencyUnsatisfied>(result)
        assertEquals(listOf(PluginId("aaa-missing"), PluginId("zzz-missing")), unsatisfied.issues.map { it.dependencyId })
    }

    @Test
    fun `a plugin with no dependencies is never subject to DependencyUnsatisfied`() {
        val registry = PluginRegistry(listOf(plugin("solo")))
        val tracker = PluginLifecycleStateTracker(registry, sdk)

        assertIs<PluginLifecycleTransitionResult.Allowed>(
            tracker.transition(PluginId("solo"), PluginLifecycleState.VALIDATED),
        )
    }

    // -------------------------------------------------------------------------
    // Check ordering: structural, then SDK compatibility, then dependencies
    // -------------------------------------------------------------------------

    @Test
    fun `an incompatible SDK range is reported before dependencies are even inspected`() {
        val incompatibleRange = PluginCompatibilityRange(minimumSdkVersion = RuntimeVersion("9.0.0"))
        val app = FakePlugin(
            manifest = PluginManifest(
                id = PluginId("app"),
                version = PluginVersion("1.0.0"),
                vendor = PluginVendor("Acme Corp"),
                compatibleSdkRange = incompatibleRange,
                dependencies = setOf(dependency("missing")),
            ),
            executionBounds = PluginExecutionBounds(maximumExecutionMillis = 1_000L, maximumConcurrentInvocations = 1),
        )
        val tracker = PluginLifecycleStateTracker(PluginRegistry(listOf(app)), sdk)

        val result = tracker.transition(PluginId("app"), PluginLifecycleState.VALIDATED)

        assertIs<PluginLifecycleTransitionResult.IncompatibleRuntime>(result)
    }

    @Test
    fun `a structurally illegal transition is rejected before dependencies are inspected`() {
        val registry = PluginRegistry(listOf(plugin("app", dependencies = setOf(dependency("missing")))))
        val tracker = PluginLifecycleStateTracker(registry, sdk)

        val result = tracker.transition(PluginId("app"), PluginLifecycleState.ACTIVE)

        assertIs<PluginLifecycleTransitionResult.Rejected>(result)
    }
}
