package io.dataloom.core.plugin

import io.dataloom.api.identifier.RuntimeVersion
import io.dataloom.api.plugin.DataLoomPlugin
import io.dataloom.api.plugin.PluginCompatibilityRange
import io.dataloom.api.plugin.PluginExecutionBounds
import io.dataloom.api.plugin.PluginId
import io.dataloom.api.plugin.PluginManifest
import io.dataloom.api.plugin.PluginVendor
import io.dataloom.api.plugin.PluginVersion
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

/**
 * Verifies [PluginExecutionBoundsEnforcer]'s timeout cancellation and
 * concurrency-limiting behavior over a fake registry. Uses fake
 * [DataLoomPlugin] instances and plain coroutine primitives only — no real
 * plugin loading or external service is required.
 */
class PluginExecutionBoundsEnforcerTest {

    // -------------------------------------------------------------------------
    // Shared fake infrastructure
    // -------------------------------------------------------------------------

    private val compatibilityRange = PluginCompatibilityRange(
        minimumSdkVersion = RuntimeVersion("1.0.0"),
    )

    private class FakePlugin(
        override val manifest: PluginManifest,
        override val executionBounds: PluginExecutionBounds,
    ) : DataLoomPlugin

    private fun plugin(
        id: String,
        maximumExecutionMillis: Long = 1_000L,
        maximumConcurrentInvocations: Int = 1,
    ): FakePlugin = FakePlugin(
        manifest = PluginManifest(
            id = PluginId(id),
            version = PluginVersion("1.0.0"),
            vendor = PluginVendor("Acme Corp"),
            compatibleSdkRange = compatibilityRange,
        ),
        executionBounds = PluginExecutionBounds(
            maximumExecutionMillis = maximumExecutionMillis,
            maximumConcurrentInvocations = maximumConcurrentInvocations,
        ),
    )

    // -------------------------------------------------------------------------
    // Unregistered plugin
    // -------------------------------------------------------------------------

    @Test
    fun `execute throws for an unregistered plugin id`() = runTest {
        val enforcer = PluginExecutionBoundsEnforcer(PluginRegistry(emptyList()))

        assertFailsWith<IllegalArgumentException> {
            enforcer.execute(PluginId("missing")) { "unreachable" }
        }
    }

    // -------------------------------------------------------------------------
    // Successful completion
    // -------------------------------------------------------------------------

    @Test
    fun `operation completing within the timeout returns Completed with its value`() = runTest {
        val id = PluginId("plugin-a")
        val enforcer = PluginExecutionBoundsEnforcer(PluginRegistry(listOf(plugin(id.value))))

        val result = enforcer.execute(id) { "done" }

        assertEquals(PluginExecutionBoundsResult.Completed("done"), result)
    }

    @Test
    fun `a null operation result is preserved exactly`() = runTest {
        val id = PluginId("plugin-a")
        val enforcer = PluginExecutionBoundsEnforcer(PluginRegistry(listOf(plugin(id.value))))

        val result: PluginExecutionBoundsResult<String?> = enforcer.execute(id) { null }

        assertNull(assertIs<PluginExecutionBoundsResult.Completed<String?>>(result).value)
    }

    @Test
    fun `the concurrency slot is released after a normal completion`() = runTest {
        val id = PluginId("plugin-a")
        val enforcer = PluginExecutionBoundsEnforcer(
            PluginRegistry(listOf(plugin(id.value, maximumConcurrentInvocations = 1))),
        )

        val first = enforcer.execute(id) { "first" }
        val second = enforcer.execute(id) { "second" }

        assertEquals(PluginExecutionBoundsResult.Completed("first"), first)
        assertEquals(PluginExecutionBoundsResult.Completed("second"), second)
    }

    // -------------------------------------------------------------------------
    // Timeout enforcement
    // -------------------------------------------------------------------------

    @Test
    fun `own timeout cancels the operation and returns TimedOut`() = runTest {
        val id = PluginId("plugin-a")
        val enforcer = PluginExecutionBoundsEnforcer(
            PluginRegistry(listOf(plugin(id.value, maximumExecutionMillis = 100L))),
        )
        var finallyExecuted = false

        val result = enforcer.execute(id) {
            try {
                delay(1_000L)
                "late"
            } finally {
                finallyExecuted = true
            }
        }

        val timedOut = assertIs<PluginExecutionBoundsResult.TimedOut>(result)
        assertEquals(id, timedOut.pluginId)
        assertEquals(100L, timedOut.maximumExecutionMillis)
        assertTrue(finallyExecuted)
    }

    @Test
    fun `the concurrency slot is released after a timeout`() = runTest {
        val id = PluginId("plugin-a")
        val enforcer = PluginExecutionBoundsEnforcer(
            PluginRegistry(
                listOf(plugin(id.value, maximumExecutionMillis = 100L, maximumConcurrentInvocations = 1)),
            ),
        )

        val timedOutResult = enforcer.execute(id) {
            delay(1_000L)
            "late"
        }
        assertIs<PluginExecutionBoundsResult.TimedOut>(timedOutResult)

        val followUp = enforcer.execute(id) { "recovered" }
        assertEquals(PluginExecutionBoundsResult.Completed("recovered"), followUp)
    }

    @Test
    fun `caller cancellation propagates instead of becoming TimedOut`() = runTest {
        val id = PluginId("plugin-a")
        val enforcer = PluginExecutionBoundsEnforcer(
            PluginRegistry(listOf(plugin(id.value, maximumExecutionMillis = 10_000L))),
        )
        val started = CompletableDeferred<Unit>()

        val execution = backgroundScope.async {
            enforcer.execute(id) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()

        execution.cancel(CancellationException("caller cancelled"))
        val thrown = captureFailure { execution.await() }

        assertIs<CancellationException>(thrown)
        assertEquals("caller cancelled", thrown.message)
    }

    // -------------------------------------------------------------------------
    // Concurrency limiting
    // -------------------------------------------------------------------------

    @Test
    fun `a call beyond the concurrency ceiling is rejected without invoking the operation`() = runTest {
        val id = PluginId("plugin-a")
        val enforcer = PluginExecutionBoundsEnforcer(
            PluginRegistry(listOf(plugin(id.value, maximumConcurrentInvocations = 1))),
        )
        val started = CompletableDeferred<Unit>()
        var secondInvoked = false

        val inFlight = backgroundScope.async {
            enforcer.execute(id) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()

        val rejected = enforcer.execute(id) {
            secondInvoked = true
            "unreachable"
        }

        assertEquals(
            PluginExecutionBoundsResult.ConcurrencyLimitExceeded(pluginId = id, maximumConcurrentInvocations = 1),
            rejected,
        )
        assertFalse(secondInvoked)
        inFlight.cancel()
    }

    @Test
    fun `up to the declared ceiling of concurrent invocations are admitted`() = runTest {
        val id = PluginId("plugin-a")
        val enforcer = PluginExecutionBoundsEnforcer(
            PluginRegistry(listOf(plugin(id.value, maximumConcurrentInvocations = 2))),
        )
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()

        val first = backgroundScope.async {
            enforcer.execute(id) {
                firstStarted.complete(Unit)
                awaitCancellation()
            }
        }
        firstStarted.await()

        val second = backgroundScope.async {
            enforcer.execute(id) {
                secondStarted.complete(Unit)
                awaitCancellation()
            }
        }
        secondStarted.await()

        var thirdInvoked = false
        val third = enforcer.execute(id) {
            thirdInvoked = true
            "unreachable"
        }

        assertEquals(
            PluginExecutionBoundsResult.ConcurrencyLimitExceeded(pluginId = id, maximumConcurrentInvocations = 2),
            third,
        )
        assertFalse(thirdInvoked)
        first.cancel()
        second.cancel()
    }

    @Test
    fun `each plugin has an independent concurrency ceiling`() = runTest {
        val busyId = PluginId("busy-plugin")
        val idleId = PluginId("idle-plugin")
        val enforcer = PluginExecutionBoundsEnforcer(
            PluginRegistry(
                listOf(
                    plugin(busyId.value, maximumConcurrentInvocations = 1),
                    plugin(idleId.value, maximumConcurrentInvocations = 1),
                ),
            ),
        )
        val busyStarted = CompletableDeferred<Unit>()

        val busy = backgroundScope.async {
            enforcer.execute(busyId) {
                busyStarted.complete(Unit)
                awaitCancellation()
            }
        }
        busyStarted.await()

        val idleResult = enforcer.execute(idleId) { "idle plugin unaffected" }

        assertEquals(PluginExecutionBoundsResult.Completed("idle plugin unaffected"), idleResult)
        busy.cancel()
    }

    private suspend fun captureFailure(block: suspend () -> Unit): Throwable {
        return try {
            block()
            error("Expected block to fail.")
        } catch (failure: Throwable) {
            failure
        }
    }
}
