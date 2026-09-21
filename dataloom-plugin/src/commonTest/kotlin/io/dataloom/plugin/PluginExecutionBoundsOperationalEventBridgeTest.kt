package io.dataloom.plugin

import io.dataloom.api.operational.OperationalEventCategory
import io.dataloom.api.plugin.PluginId
import io.dataloom.api.plugin.PluginLifecycleState
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies [PluginExecutionBoundsOperationalEventBridge.toEnvelope] maps every
 * [PluginExecutionBoundsResult] variant to a correctly classified,
 * identity-stable envelope that never carries plugin output.
 */
class PluginExecutionBoundsOperationalEventBridgeTest {

    private val pluginId = PluginId("acme.sync")
    private val invocationId = PluginExecutionInvocationId("acme.sync.5000.1")
    private val occurredAt = DataLoomInstant(5_000L)

    private val completed: PluginExecutionBoundsResult<*> = PluginExecutionBoundsResult.Completed("done")
    private val timedOut: PluginExecutionBoundsResult<*> = PluginExecutionBoundsResult.TimedOut(pluginId, 750L)
    private val concurrencyLimit: PluginExecutionBoundsResult<*> =
        PluginExecutionBoundsResult.ConcurrencyLimitExceeded(pluginId, 3)
    private val notActive: PluginExecutionBoundsResult<*> =
        PluginExecutionBoundsResult.NotActive(pluginId, PluginLifecycleState.DISABLED)

    private fun bridge(result: PluginExecutionBoundsResult<*>, id: PluginExecutionInvocationId = invocationId) =
        PluginExecutionBoundsOperationalEventBridge.toEnvelope(pluginId, id, result, occurredAt)

    // -------------------------------------------------------------------------
    // Mapping table
    // -------------------------------------------------------------------------

    @Test
    fun `every result variant maps to its own event type`() {
        val expected = listOf(
            completed to "dataloom.plugin.execution.bounds.completed",
            timedOut to "dataloom.plugin.execution.bounds.timed_out",
            concurrencyLimit to "dataloom.plugin.execution.bounds.concurrency_limit_exceeded",
            notActive to "dataloom.plugin.execution.bounds.not_active",
        )

        for ((result, type) in expected) {
            assertEquals(type, bridge(result).type.value, "type for $result")
        }
        assertEquals(expected.size, expected.map { it.second }.toSet().size)
    }

    @Test
    fun `every result variant is an AUDIT event with the same source and content-free payload`() {
        for (result in listOf(completed, timedOut, concurrencyLimit, notActive)) {
            val envelope = bridge(result)

            assertEquals(OperationalEventCategory.AUDIT, envelope.category)
            assertEquals("dataloom.plugin.execution.bounds", envelope.source.value)
            assertEquals("dataloom.plugin.execution.bounds.event", envelope.payload.type.value)
            assertEquals(null, envelope.payload.encodedSizeBytes)
        }
    }

    @Test
    fun `attributes per variant are exactly the stable identifiers and closed values`() {
        assertEquals(setOf("request.pluginId"), bridge(completed).attributes.entries.keys)
        assertEquals(
            setOf("request.pluginId", "result.maximumExecutionMillis"),
            bridge(timedOut).attributes.entries.keys,
        )
        assertEquals(
            setOf("request.pluginId", "result.maximumConcurrentInvocations"),
            bridge(concurrencyLimit).attributes.entries.keys,
        )
        assertEquals(setOf("request.pluginId", "result.state"), bridge(notActive).attributes.entries.keys)
    }

    @Test
    fun `numeric bounds and the observed state are public while the plugin id is redacted`() {
        assertEquals("750", bridge(timedOut).attributes["result.maximumExecutionMillis"])
        assertEquals("3", bridge(concurrencyLimit).attributes["result.maximumConcurrentInvocations"])
        assertEquals("DISABLED", bridge(notActive).attributes["result.state"])
        for (result in listOf(completed, timedOut, concurrencyLimit, notActive)) {
            assertEquals("[REDACTED]", bridge(result).attributes["request.pluginId"])
        }
    }

    @Test
    fun `every observable state is bridged for NotActive`() {
        for (state in PluginLifecycleState.entries) {
            val envelope = bridge(PluginExecutionBoundsResult.NotActive(pluginId, state))

            assertEquals(state.name, envelope.attributes["result.state"])
        }
    }

    // -------------------------------------------------------------------------
    // Identity and time
    // -------------------------------------------------------------------------

    @Test
    fun `envelope id is derived from the invocation id and correlation id reuses it`() {
        val envelope = bridge(timedOut)

        assertEquals("plugin.execution.acme.sync.5000.1", envelope.id.value)
        assertEquals("acme.sync.5000.1", envelope.correlationId.value)
    }

    @Test
    fun `occurredAt is the supplied time`() {
        assertEquals(5_000L, bridge(completed).occurredAt.epochMilliseconds)
    }

    @Test
    fun `unsafe characters in the invocation id are replaced and the id is bounded`() {
        val unsafe = bridge(completed, PluginExecutionInvocationId("a béc#d"))
        val long = bridge(completed, PluginExecutionInvocationId("x".repeat(500)))

        assertEquals("plugin.execution.a_b_c_d", unsafe.id.value)
        assertEquals(128, long.id.value.length)
    }

    @Test
    fun `the mapping is deterministic`() {
        assertEquals(bridge(timedOut), bridge(timedOut))
    }

    @Test
    fun `different invocations of the same outcome get different ids`() {
        val first = bridge(timedOut, PluginExecutionInvocationId("acme.sync.5000.1"))
        val second = bridge(timedOut, PluginExecutionInvocationId("acme.sync.5000.2"))

        assertTrue(first.id != second.id)
    }

    @Test
    fun `PluginExecutionInvocationId rejects a blank value`() {
        assertFailsWith<IllegalArgumentException> { PluginExecutionInvocationId("  ") }
    }

    // -------------------------------------------------------------------------
    // Redaction: plugin output never reaches an envelope
    // -------------------------------------------------------------------------

    @Test
    fun `a Completed value is never read into the envelope`() {
        val secret = "super-secret-plugin-output-8c1f"
        val envelope = bridge(PluginExecutionBoundsResult.Completed(secret))

        assertFalse(envelope.toString().contains(secret))
        assertFalse(envelope.attributes.toString().contains(secret))
        assertFalse(envelope.attributes.entries.values.any { it.contains(secret) })
        assertFalse(envelope.attributes.entries.keys.any { it.contains("value") })
    }

    @Test
    fun `a Completed value whose toString throws does not break the bridge`() {
        class Hostile {
            override fun toString(): String = error("must not be called")

            override fun equals(other: Any?): Boolean = error("must not be called")

            override fun hashCode(): Int = error("must not be called")
        }

        val envelope = bridge(PluginExecutionBoundsResult.Completed(Hostile()))

        assertEquals("dataloom.plugin.execution.bounds.completed", envelope.type.value)
    }
}
