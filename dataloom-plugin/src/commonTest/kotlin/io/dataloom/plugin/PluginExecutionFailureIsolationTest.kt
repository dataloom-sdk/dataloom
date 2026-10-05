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
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Failure isolation of [PluginExecutionBoundsEnforcer]: a plugin operation
 * that throws, or that hangs, must not take down the caller, the engine, or
 * any other plugin.
 *
 * Uses `runBlocking` and the real wall clock, never `runTest`: virtual time
 * would skip the delays and make every timeout proof here meaningless.
 */
class PluginExecutionFailureIsolationTest {

    private class FakePlugin(
        override val manifest: PluginManifest,
        override val executionBounds: PluginExecutionBounds,
    ) : DataLoomPlugin

    private class FatalError : Error("simulated fatal error")

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

    private fun fixture(vararg plugins: FakePlugin): Fixture {
        val tracker = PluginLifecycleStateTracker(PluginRegistry(plugins.toList()), RuntimeVersion("1.5.0"))
        for (plugin in plugins) {
            for (state in listOf(
                PluginLifecycleState.VALIDATED,
                PluginLifecycleState.INITIALIZING,
                PluginLifecycleState.ACTIVE,
            )) {
                tracker.transition(plugin.manifest.id, state)
            }
        }
        return Fixture(tracker, PluginExecutionBoundsEnforcer(tracker))
    }

    @Test
    fun `an exception thrown by the operation is returned as Failed carrying its type and message`() = runBlocking {
        val id = PluginId("thrower")
        val f = fixture(plugin(id.value))

        val result = f.enforcer.execute<Unit>(id) { throw IllegalStateException("plugin bug") }

        assertIs<PluginExecutionBoundsResult.Failed>(result)
        assertEquals(id, result.pluginId)
        // Not assertSame: on the JVM, coroutine stack-trace recovery may hand back a copy.
        assertIs<IllegalStateException>(result.cause)
        assertEquals("plugin bug", result.cause.message)
    }

    @Test
    fun `a failing invocation releases its concurrency slot every time`() = runBlocking {
        val id = PluginId("always-throws")
        val f = fixture(plugin(id.value, maximumConcurrentInvocations = 1))

        repeat(5) {
            // With a ceiling of one, a leaked slot would turn the second call into
            // ConcurrencyLimitExceeded instead of Failed.
            assertIs<PluginExecutionBoundsResult.Failed>(f.enforcer.execute<Unit>(id) { error("boom $it") })
        }

        assertEquals(PluginExecutionBoundsResult.Completed("ok"), f.enforcer.execute(id) { "ok" })
    }

    @Test
    fun `a failure leaves the failing plugin ACTIVE and does not change any lifecycle state`() = runBlocking {
        val failing = PluginId("failing")
        val other = PluginId("other")
        val f = fixture(plugin(failing.value), plugin(other.value))

        f.enforcer.execute<Unit>(failing) { error("boom") }

        assertEquals(PluginLifecycleState.ACTIVE, f.tracker.stateOf(failing))
        assertEquals(PluginLifecycleState.ACTIVE, f.tracker.stateOf(other))
    }

    @Test
    fun `a failure in one plugin does not affect another plugin's invocations`() = runBlocking {
        val failing = PluginId("failing")
        val healthy = PluginId("healthy")
        val f = fixture(plugin(failing.value), plugin(healthy.value))

        assertIs<PluginExecutionBoundsResult.Failed>(f.enforcer.execute<Unit>(failing) { error("boom") })

        assertEquals(PluginExecutionBoundsResult.Completed("fine"), f.enforcer.execute(healthy) { "fine" })
    }

    @Test
    fun `a failure does not disturb a sibling invocation of the same plugin that is in flight`() = runBlocking {
        val id = PluginId("shared")
        val f = fixture(plugin(id.value, maximumConcurrentInvocations = 2))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val sibling = async {
            f.enforcer.execute(id) {
                started.complete(Unit)
                release.await()
                "sibling finished"
            }
        }
        started.await()

        val failed = f.enforcer.execute<Unit>(id) { error("boom") }
        release.complete(Unit)

        assertIs<PluginExecutionBoundsResult.Failed>(failed)
        assertEquals(PluginExecutionBoundsResult.Completed("sibling finished"), sibling.await())
    }

    @Test
    fun `a hung plugin is timed out in real time while an unrelated failing plugin is contained`() = runBlocking {
        val hung = PluginId("hung")
        val failing = PluginId("failing")
        val f = fixture(plugin(hung.value, maximumExecutionMillis = 300L), plugin(failing.value))
        val started = TimeSource.Monotonic.markNow()
        val hungCall = async { f.enforcer.execute<Unit>(hung) { awaitCancellation() } }

        // Runs while the hung invocation is still waiting out its real 300 ms timeout.
        val failedCall = f.enforcer.execute<Unit>(failing) { error("boom") }
        val failedAfter = started.elapsedNow()
        val hungResult = hungCall.await()
        val hungAfter = started.elapsedNow()

        assertIs<PluginExecutionBoundsResult.Failed>(failedCall)
        assertTrue(failedAfter.inWholeMilliseconds < 250, "the failing plugin was held up by the hung one: $failedAfter")
        assertEquals(PluginExecutionBoundsResult.TimedOut(hung, 300L), hungResult)
        assertTrue(hungAfter.inWholeMilliseconds >= 250, "the timeout fired early, so it was not real time: $hungAfter")
    }

    @Test
    fun `a CancellationException raised by the operation while the caller is active is contained`() = runBlocking {
        val id = PluginId("cancels-itself")
        val f = fixture(plugin(id.value, maximumConcurrentInvocations = 1))

        val explicit = f.enforcer.execute<Unit>(id) { throw CancellationException("plugin gave up") }
        // A plugin's own withTimeout leaks a TimeoutCancellationException out of the operation.
        val leakedInnerTimeout = f.enforcer.execute<Unit>(id) { withTimeout(20L) { awaitCancellation() } }

        assertIs<PluginExecutionBoundsResult.Failed>(explicit)
        assertIs<PluginExecutionBoundsResult.Failed>(leakedInnerTimeout)
        assertEquals(PluginExecutionBoundsResult.Completed("still usable"), f.enforcer.execute(id) { "still usable" })
    }

    @Test
    fun `cancellation of the caller still propagates and is not reported as Failed`() = runBlocking {
        val id = PluginId("caller-cancelled")
        val f = fixture(plugin(id.value, maximumConcurrentInvocations = 1))
        val started = CompletableDeferred<Unit>()
        val call = async {
            f.enforcer.execute<Unit>(id) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()

        call.cancel()

        assertFailsWith<CancellationException> { call.await() }
        assertEquals(PluginExecutionBoundsResult.Completed("slot freed"), f.enforcer.execute(id) { "slot freed" })
    }

    @Test
    fun `an Error is not contained and still frees the slot`() = runBlocking {
        val id = PluginId("fatal")
        val f = fixture(plugin(id.value, maximumConcurrentInvocations = 1))

        assertFailsWith<FatalError> { f.enforcer.execute<Unit>(id) { throw FatalError() } }

        assertFalse(f.enforcer.execute(id) { "after" } is PluginExecutionBoundsResult.ConcurrencyLimitExceeded)
    }
}
