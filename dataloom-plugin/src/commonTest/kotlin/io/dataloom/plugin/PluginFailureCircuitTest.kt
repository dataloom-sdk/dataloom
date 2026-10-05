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
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * The opt-in [PluginFailureCircuitPolicy]: a repeatedly failing plugin is moved
 * `ACTIVE` -> `DEGRADED` automatically, recovery is manual, and the automatic
 * transition never races a manual one.
 *
 * Uses `runBlocking` and the real wall clock, never `runTest`: virtual time
 * would skip the delays and make every timeout proof here meaningless.
 */
@OptIn(ExperimentalAtomicApi::class)
class PluginFailureCircuitTest {

    private class FakePlugin(
        override val manifest: PluginManifest,
        override val executionBounds: PluginExecutionBounds,
    ) : DataLoomPlugin

    private fun plugin(id: String, maximumExecutionMillis: Long = 5_000L, maximumConcurrentInvocations: Int = 1) =
        FakePlugin(
            manifest = PluginManifest(
                id = PluginId(id),
                version = PluginVersion("1.0.0"),
                vendor = PluginVendor("Acme Corp"),
                compatibleSdkRange = PluginCompatibilityRange(minimumSdkVersion = RuntimeVersion("1.0.0")),
            ),
            executionBounds = PluginExecutionBounds(maximumExecutionMillis, maximumConcurrentInvocations),
        )

    private class Fixture(val tracker: PluginLifecycleStateTracker, val enforcer: PluginExecutionBoundsEnforcer)

    private fun fixture(policy: PluginFailureCircuitPolicy?, vararg plugins: FakePlugin): Fixture {
        val tracker = PluginLifecycleStateTracker(PluginRegistry(plugins.toList()), RuntimeVersion("1.5.0"))
        for (plugin in plugins) {
            activate(tracker, plugin.manifest.id)
        }
        return Fixture(tracker, PluginExecutionBoundsEnforcer(tracker, policy))
    }

    private fun activate(tracker: PluginLifecycleStateTracker, id: PluginId) {
        for (state in listOf(
            PluginLifecycleState.VALIDATED,
            PluginLifecycleState.INITIALIZING,
            PluginLifecycleState.ACTIVE,
        )) {
            assertIs<PluginLifecycleTransitionResult.Allowed>(tracker.transition(id, state))
        }
    }

    private suspend fun PluginExecutionBoundsEnforcer.fail(id: PluginId): PluginExecutionBoundsResult<Unit> =
        execute<Unit>(id) { error("boom") }

    private fun PluginExecutionBoundsResult<*>.degraded(): Boolean = when (this) {
        is PluginExecutionBoundsResult.Failed -> degradedPlugin
        is PluginExecutionBoundsResult.TimedOut -> degradedPlugin
        else -> false
    }

    // -------------------------------------------------------------------------
    // Policy
    // -------------------------------------------------------------------------

    @Test
    fun `the policy default threshold is five and a non-positive threshold is rejected`() {
        assertEquals(5, PluginFailureCircuitPolicy().consecutiveFailureThreshold)
        assertEquals(5, PluginFailureCircuitPolicy.DEFAULT_CONSECUTIVE_FAILURE_THRESHOLD)
        assertFailsWith<IllegalArgumentException> { PluginFailureCircuitPolicy(0) }
        assertFailsWith<IllegalArgumentException> { PluginFailureCircuitPolicy(-1) }
    }

    @Test
    fun `the default policy needs exactly five consecutive failures`() = runBlocking {
        val id = PluginId("default-threshold")
        val f = fixture(PluginFailureCircuitPolicy(), plugin(id.value))

        repeat(4) { assertFalse(f.enforcer.fail(id).degraded()) }
        assertEquals(PluginLifecycleState.ACTIVE, f.tracker.stateOf(id))
        assertTrue(f.enforcer.fail(id).degraded())
        assertEquals(PluginLifecycleState.DEGRADED, f.tracker.stateOf(id))
    }

    // -------------------------------------------------------------------------
    // Opt-in
    // -------------------------------------------------------------------------

    @Test
    fun `without a policy any number of failures leaves the plugin ACTIVE`() = runBlocking {
        val id = PluginId("no-policy")
        val f = fixture(null, plugin(id.value))

        repeat(50) { assertFalse(f.enforcer.fail(id).degraded()) }

        assertEquals(PluginLifecycleState.ACTIVE, f.tracker.stateOf(id))
        assertEquals(PluginExecutionBoundsResult.Completed("ok"), f.enforcer.execute(id) { "ok" })
    }

    // -------------------------------------------------------------------------
    // Tripping
    // -------------------------------------------------------------------------

    @Test
    fun `the failure that reaches the threshold degrades the plugin and only that result is flagged`() = runBlocking {
        val id = PluginId("trips")
        val f = fixture(PluginFailureCircuitPolicy(3), plugin(id.value))

        val first = f.enforcer.fail(id)
        val second = f.enforcer.fail(id)
        assertEquals(PluginLifecycleState.ACTIVE, f.tracker.stateOf(id))
        val third = f.enforcer.fail(id)

        assertFalse(first.degraded())
        assertFalse(second.degraded())
        assertTrue(third.degraded())
        assertEquals(PluginLifecycleState.DEGRADED, f.tracker.stateOf(id))
    }

    @Test
    fun `a degraded plugin refuses new invocations without running them`() = runBlocking {
        val id = PluginId("refuses")
        val f = fixture(PluginFailureCircuitPolicy(1), plugin(id.value))
        f.enforcer.fail(id)
        var invoked = false

        val result = f.enforcer.execute(id) { invoked = true }

        assertEquals(PluginExecutionBoundsResult.NotActive(id, PluginLifecycleState.DEGRADED), result)
        assertFalse(invoked)
    }

    @Test
    fun `a timeout counts as a failure and is measured in real time`() = runBlocking {
        val id = PluginId("slow")
        val f = fixture(PluginFailureCircuitPolicy(2), plugin(id.value, maximumExecutionMillis = 150L))
        val started = TimeSource.Monotonic.markNow()

        val first = f.enforcer.execute<Unit>(id) { awaitCancellation() }
        val second = f.enforcer.execute<Unit>(id) { awaitCancellation() }
        val elapsed = started.elapsedNow()

        assertEquals(PluginExecutionBoundsResult.TimedOut(id, 150L, degradedPlugin = false), first)
        assertEquals(PluginExecutionBoundsResult.TimedOut(id, 150L, degradedPlugin = true), second)
        assertEquals(PluginLifecycleState.DEGRADED, f.tracker.stateOf(id))
        assertTrue(elapsed.inWholeMilliseconds >= 250, "two real 150 ms timeouts took only $elapsed")
    }

    @Test
    fun `failures and timeouts add up together`() = runBlocking {
        val id = PluginId("mixed")
        val f = fixture(PluginFailureCircuitPolicy(3), plugin(id.value, maximumExecutionMillis = 50L))

        f.enforcer.fail(id)
        f.enforcer.execute<Unit>(id) { awaitCancellation() }
        val third = f.enforcer.fail(id)

        assertTrue(third.degraded())
        assertEquals(PluginLifecycleState.DEGRADED, f.tracker.stateOf(id))
    }

    @Test
    fun `a CancellationException raised by the plugin counts as a failure`() = runBlocking {
        val id = PluginId("self-cancels")
        val f = fixture(PluginFailureCircuitPolicy(1), plugin(id.value))

        val result = f.enforcer.execute<Unit>(id) { throw kotlin.coroutines.cancellation.CancellationException("x") }

        assertIs<PluginExecutionBoundsResult.Failed>(result)
        assertTrue(result.degradedPlugin)
    }

    // -------------------------------------------------------------------------
    // Resetting and what does not count
    // -------------------------------------------------------------------------

    @Test
    fun `a success resets the consecutive count`() = runBlocking {
        val id = PluginId("resets")
        val f = fixture(PluginFailureCircuitPolicy(3), plugin(id.value))

        f.enforcer.fail(id)
        f.enforcer.fail(id)
        assertEquals(PluginExecutionBoundsResult.Completed("ok"), f.enforcer.execute(id) { "ok" })
        f.enforcer.fail(id)
        f.enforcer.fail(id)
        assertEquals(PluginLifecycleState.ACTIVE, f.tracker.stateOf(id))

        assertTrue(f.enforcer.fail(id).degraded())
        assertEquals(PluginLifecycleState.DEGRADED, f.tracker.stateOf(id))
    }

    @Test
    fun `concurrency rejections neither count nor reset`() = runBlocking {
        val id = PluginId("backpressure")
        val f = fixture(PluginFailureCircuitPolicy(2), plugin(id.value, maximumConcurrentInvocations = 1))
        f.enforcer.fail(id)
        val started = CompletableDeferred<Unit>()
        val holder = async {
            f.enforcer.execute<Unit>(id) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()

        repeat(10) {
            assertIs<PluginExecutionBoundsResult.ConcurrencyLimitExceeded>(f.enforcer.execute(id) { "never" })
        }
        holder.cancel()
        holder.join()

        // One failure was already counted before the rejections: a rejection that reset the
        // counter would need two more, one that counted would have degraded the plugin already.
        assertEquals(PluginLifecycleState.ACTIVE, f.tracker.stateOf(id))
        assertTrue(f.enforcer.fail(id).degraded())
    }

    @Test
    fun `refusals while not ACTIVE do not count so a recovered plugin starts afresh`() = runBlocking {
        val id = PluginId("recovers")
        val f = fixture(PluginFailureCircuitPolicy(2), plugin(id.value))
        f.enforcer.fail(id)
        assertTrue(f.enforcer.fail(id).degraded())

        repeat(10) { assertIs<PluginExecutionBoundsResult.NotActive>(f.enforcer.fail(id)) }
        assertIs<PluginLifecycleTransitionResult.Allowed>(f.tracker.transition(id, PluginLifecycleState.ACTIVE))

        assertFalse(f.enforcer.fail(id).degraded())
        assertEquals(PluginLifecycleState.ACTIVE, f.tracker.stateOf(id))
        assertTrue(f.enforcer.fail(id).degraded())
    }

    @Test
    fun `caller cancellation of an in-flight invocation is not a failure`() = runBlocking {
        val id = PluginId("caller-cancel")
        val f = fixture(PluginFailureCircuitPolicy(1), plugin(id.value, maximumConcurrentInvocations = 2))
        val started = CompletableDeferred<Unit>()
        val call = async {
            f.enforcer.execute<Unit>(id) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()

        call.cancel()
        call.join()

        assertEquals(PluginLifecycleState.ACTIVE, f.tracker.stateOf(id))
    }

    @Test
    fun `plugins are counted independently`() = runBlocking {
        val a = PluginId("plugin-a")
        val b = PluginId("plugin-b")
        val f = fixture(PluginFailureCircuitPolicy(2), plugin(a.value), plugin(b.value))

        f.enforcer.fail(a)
        f.enforcer.fail(b)
        assertTrue(f.enforcer.fail(a).degraded())

        assertEquals(PluginLifecycleState.DEGRADED, f.tracker.stateOf(a))
        assertEquals(PluginLifecycleState.ACTIVE, f.tracker.stateOf(b))
        assertEquals(PluginExecutionBoundsResult.Completed("fine"), f.enforcer.execute(b) { "fine" })
    }

    // -------------------------------------------------------------------------
    // Concurrency safety and manual transitions
    // -------------------------------------------------------------------------

    @Test
    fun `many concurrent failures degrade the plugin exactly once`() = runBlocking {
        val id = PluginId("stampede")
        val invocations = 40
        val f = fixture(PluginFailureCircuitPolicy(10), plugin(id.value, maximumConcurrentInvocations = invocations))
        val gate = CompletableDeferred<Unit>()
        val inFlight = AtomicInt(0)

        val results = withContext(Dispatchers.Default) {
            val calls = List(invocations) {
                async {
                    f.enforcer.execute<Unit>(id) {
                        // The gate opens only once every invocation has passed the ACTIVE check,
                        // so none can be refused as NotActive after the circuit trips.
                        if (inFlight.addAndFetch(1) == invocations) gate.complete(Unit)
                        gate.await()
                        error("boom")
                    }
                }
            }
            calls.awaitAll()
        }

        assertTrue(results.all { it is PluginExecutionBoundsResult.Failed })
        assertEquals(1, results.count { it.degraded() }, "exactly one failure may report the degradation")
        assertEquals(PluginLifecycleState.DEGRADED, f.tracker.stateOf(id))
    }

    @Test
    fun `a failure finishing after a manual disable does not overwrite the newer state`() = runBlocking {
        val id = PluginId("raced")
        val f = fixture(PluginFailureCircuitPolicy(1), plugin(id.value, maximumConcurrentInvocations = 2))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val inFlight = async {
            f.enforcer.execute<Unit>(id) {
                started.complete(Unit)
                release.await()
                error("boom")
            }
        }
        started.await()

        assertIs<PluginLifecycleTransitionResult.Allowed>(f.tracker.transition(id, PluginLifecycleState.DISABLED))
        release.complete(Unit)
        val result = inFlight.await()

        assertIs<PluginExecutionBoundsResult.Failed>(result)
        assertFalse(result.degradedPlugin)
        assertEquals(PluginLifecycleState.DISABLED, f.tracker.stateOf(id))
    }

    // -------------------------------------------------------------------------
    // PluginLifecycleStateTracker.degradeIfActive
    // -------------------------------------------------------------------------

    @Test
    fun `degradeIfActive moves ACTIVE to DEGRADED once and then is a no-op`() {
        val id = PluginId("cas")
        val f = fixture(null, plugin(id.value))

        assertTrue(f.tracker.degradeIfActive(id))
        assertEquals(PluginLifecycleState.DEGRADED, f.tracker.stateOf(id))
        assertFalse(f.tracker.degradeIfActive(id))
        assertEquals(PluginLifecycleState.DEGRADED, f.tracker.stateOf(id))
    }

    @Test
    fun `degradeIfActive never changes a plugin that is not ACTIVE`() {
        val id = PluginId("not-active")
        val tracker = PluginLifecycleStateTracker(PluginRegistry(listOf(plugin(id.value))), RuntimeVersion("1.5.0"))

        assertFalse(tracker.degradeIfActive(id))
        assertEquals(PluginLifecycleState.LOADED, tracker.stateOf(id))

        activate(tracker, id)
        tracker.transition(id, PluginLifecycleState.DISABLED)
        assertFalse(tracker.degradeIfActive(id))
        assertEquals(PluginLifecycleState.DISABLED, tracker.stateOf(id))
    }

    @Test
    fun `degradeIfActive throws for an unregistered plugin`() {
        val tracker = PluginLifecycleStateTracker(PluginRegistry(emptyList()), RuntimeVersion("1.5.0"))

        assertFailsWith<IllegalArgumentException> { tracker.degradeIfActive(PluginId("missing")) }
    }
}
