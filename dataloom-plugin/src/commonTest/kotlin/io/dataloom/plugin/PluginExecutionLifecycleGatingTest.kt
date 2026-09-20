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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

/**
 * Verifies decision D13: [PluginExecutionBoundsEnforcer] refuses new
 * invocations of a plugin that is not `ACTIVE`, while an invocation already in
 * flight when its plugin leaves `ACTIVE` is neither cancelled nor shortened.
 */
class PluginExecutionLifecycleGatingTest {

    private val id = PluginId("acme")

    private class FakePlugin(
        override val manifest: PluginManifest,
        override val executionBounds: PluginExecutionBounds,
    ) : DataLoomPlugin

    private fun plugin(
        pluginId: PluginId = id,
        maximumExecutionMillis: Long = 1_000L,
        maximumConcurrentInvocations: Int = 1,
    ): DataLoomPlugin = FakePlugin(
        manifest = PluginManifest(
            id = pluginId,
            version = PluginVersion("1.0.0"),
            vendor = PluginVendor("Acme Corp"),
            compatibleSdkRange = PluginCompatibilityRange(RuntimeVersion("1.0.0")),
        ),
        executionBounds = PluginExecutionBounds(maximumExecutionMillis, maximumConcurrentInvocations),
    )

    private class Fixture(val tracker: PluginLifecycleStateTracker, val enforcer: PluginExecutionBoundsEnforcer)

    private fun fixture(vararg plugins: DataLoomPlugin): Fixture {
        val tracker = PluginLifecycleStateTracker(PluginRegistry(plugins.toList()), RuntimeVersion("1.5.0"))
        return Fixture(tracker, PluginExecutionBoundsEnforcer(tracker))
    }

    private fun Fixture.moveTo(vararg states: PluginLifecycleState, pluginId: PluginId = id) {
        for (state in states) {
            assertIs<PluginLifecycleTransitionResult.Allowed>(tracker.transition(pluginId, state))
        }
    }

    private fun Fixture.activate(pluginId: PluginId = id) = moveTo(
        PluginLifecycleState.VALIDATED,
        PluginLifecycleState.INITIALIZING,
        PluginLifecycleState.ACTIVE,
        pluginId = pluginId,
    )

    // -------------------------------------------------------------------------
    // New invocations
    // -------------------------------------------------------------------------

    @Test
    fun `a newly registered plugin is not invocable until it is ACTIVE`() = runTest {
        val f = fixture(plugin())
        var invoked = false

        val result = f.enforcer.execute(id) {
            invoked = true
            "unreachable"
        }

        assertEquals(PluginExecutionBoundsResult.NotActive(id, PluginLifecycleState.LOADED), result)
        assertFalse(invoked)
    }

    @Test
    fun `every pre-active state refuses new invocations`() = runTest {
        val f = fixture(plugin())
        val expectedStates = listOf(
            PluginLifecycleState.LOADED,
            PluginLifecycleState.VALIDATED,
            PluginLifecycleState.INITIALIZING,
        )

        for ((index, expected) in expectedStates.withIndex()) {
            if (index > 0) f.moveTo(expected)
            assertEquals(PluginExecutionBoundsResult.NotActive(id, expected), f.enforcer.execute(id) { "x" })
        }
    }

    @Test
    fun `an ACTIVE plugin runs its invocation`() = runTest {
        val f = fixture(plugin())
        f.activate()

        assertEquals(PluginExecutionBoundsResult.Completed("ok"), f.enforcer.execute(id) { "ok" })
    }

    @Test
    fun `a DEGRADED plugin refuses new invocations and is admitted again once ACTIVE`() = runTest {
        val f = fixture(plugin())
        f.activate()
        f.moveTo(PluginLifecycleState.DEGRADED)

        assertEquals(
            PluginExecutionBoundsResult.NotActive(id, PluginLifecycleState.DEGRADED),
            f.enforcer.execute(id) { "x" },
        )

        f.moveTo(PluginLifecycleState.ACTIVE)

        assertEquals(PluginExecutionBoundsResult.Completed("back"), f.enforcer.execute(id) { "back" })
    }

    @Test
    fun `a DISABLED plugin refuses new invocations`() = runTest {
        val f = fixture(plugin())
        f.activate()
        f.moveTo(PluginLifecycleState.DISABLED)

        assertEquals(
            PluginExecutionBoundsResult.NotActive(id, PluginLifecycleState.DISABLED),
            f.enforcer.execute(id) { "x" },
        )
    }

    @Test
    fun `a refused invocation consumes no concurrency slot`() = runTest {
        val f = fixture(plugin(maximumConcurrentInvocations = 1))

        repeat(3) { assertIs<PluginExecutionBoundsResult.NotActive>(f.enforcer.execute(id) { "x" }) }
        f.activate()

        assertEquals(PluginExecutionBoundsResult.Completed("ok"), f.enforcer.execute(id) { "ok" })
    }

    @Test
    fun `gating is per plugin`() = runTest {
        val other = PluginId("other")
        val f = fixture(plugin(), plugin(other))
        f.activate()

        assertEquals(PluginExecutionBoundsResult.Completed("ok"), f.enforcer.execute(id) { "ok" })
        assertEquals(
            PluginExecutionBoundsResult.NotActive(other, PluginLifecycleState.LOADED),
            f.enforcer.execute(other) { "x" },
        )
    }

    @Test
    fun `an unregistered plugin still throws`() = runTest {
        val f = fixture(plugin())

        assertFailsWith<IllegalArgumentException> { f.enforcer.execute(PluginId("missing")) { "x" } }
    }

    // -------------------------------------------------------------------------
    // In-flight invocations
    // -------------------------------------------------------------------------

    @Test
    fun `an in-flight invocation completes after its plugin is disabled while new ones are refused`() = runTest {
        val f = fixture(plugin())
        f.activate()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<String>()
        val inFlight = backgroundScope.async {
            f.enforcer.execute(id) {
                started.complete(Unit)
                release.await()
            }
        }
        started.await()

        f.moveTo(PluginLifecycleState.DISABLED)

        assertEquals(
            PluginExecutionBoundsResult.NotActive(id, PluginLifecycleState.DISABLED),
            f.enforcer.execute(id) { "new" },
        )
        assertTrue(inFlight.isActive, "the in-flight invocation must not be cancelled by the transition")
        release.complete("finished")
        assertEquals(PluginExecutionBoundsResult.Completed("finished"), inFlight.await())
    }

    @Test
    fun `an in-flight invocation completes after its plugin is degraded`() = runTest {
        val f = fixture(plugin())
        f.activate()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<String>()
        val inFlight = backgroundScope.async {
            f.enforcer.execute(id) {
                started.complete(Unit)
                release.await()
            }
        }
        started.await()

        f.moveTo(PluginLifecycleState.DEGRADED)
        release.complete("finished")

        assertEquals(PluginExecutionBoundsResult.Completed("finished"), inFlight.await())
    }

    @Test
    fun `an in-flight invocation is still cancelled by its own timeout after a state change`() = runTest {
        val f = fixture(plugin(maximumExecutionMillis = 500L))
        f.activate()
        val started = CompletableDeferred<Unit>()
        val inFlight = backgroundScope.async {
            f.enforcer.execute(id) {
                started.complete(Unit)
                delay(10_000L)
                "late"
            }
        }
        started.await()

        f.moveTo(PluginLifecycleState.DISABLED)

        assertEquals(PluginExecutionBoundsResult.TimedOut(id, 500L), inFlight.await())
    }

    @Test
    fun `the in-flight invocation keeps its concurrency slot until it finishes`() = runTest {
        val f = fixture(plugin(maximumConcurrentInvocations = 1))
        f.activate()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<String>()
        val inFlight = backgroundScope.async {
            f.enforcer.execute(id) {
                started.complete(Unit)
                release.await()
            }
        }
        started.await()
        f.moveTo(PluginLifecycleState.DEGRADED)
        f.moveTo(PluginLifecycleState.ACTIVE)

        val rejected = f.enforcer.execute(id) { "second" }

        assertEquals(PluginExecutionBoundsResult.ConcurrencyLimitExceeded(id, 1), rejected)
        release.complete("finished")
        inFlight.await()
        assertEquals(PluginExecutionBoundsResult.Completed("after"), f.enforcer.execute(id) { "after" })
    }

    @Test
    fun `state is checked before the concurrency ceiling`() = runTest {
        val f = fixture(plugin(maximumConcurrentInvocations = 1))
        f.activate()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<String>()
        val inFlight = backgroundScope.async {
            f.enforcer.execute(id) {
                started.complete(Unit)
                release.await()
            }
        }
        started.await()
        f.moveTo(PluginLifecycleState.DISABLED)

        val result = f.enforcer.execute(id) { "x" }

        assertEquals(PluginExecutionBoundsResult.NotActive(id, PluginLifecycleState.DISABLED), result)
        release.complete("done")
        inFlight.await()
    }
}
