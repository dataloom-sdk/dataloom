package io.dataloom.core.plugin

import io.dataloom.api.identifier.RuntimeVersion
import io.dataloom.api.plugin.DataLoomPlugin
import io.dataloom.api.plugin.PluginCompatibilityRange
import io.dataloom.api.plugin.PluginDependency
import io.dataloom.api.plugin.PluginExecutionBounds
import io.dataloom.api.plugin.PluginId
import io.dataloom.api.plugin.PluginManifest
import io.dataloom.api.plugin.PluginVendor
import io.dataloom.api.plugin.PluginVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the [PluginRegistry] contract: duplicate rejection, dependency
 * resolution, cycle rejection, and deterministic resolution ordering.
 *
 * Uses private deterministic fake [DataLoomPlugin] implementations. No real
 * plugin loading, execution, or external service is required.
 */
class PluginRegistryTest {

    // -------------------------------------------------------------------------
    // Shared fake infrastructure
    // -------------------------------------------------------------------------

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

    private fun plugin(
        id: String,
        dependsOn: Set<String> = emptySet(),
    ): FakePlugin = FakePlugin(
        manifest = PluginManifest(
            id = PluginId(id),
            version = PluginVersion("1.0.0"),
            vendor = PluginVendor("Acme Corp"),
            compatibleSdkRange = compatibilityRange,
            dependencies = dependsOn.map { dependencyId ->
                PluginDependency(
                    pluginId = PluginId(dependencyId),
                    compatibilityRange = compatibilityRange,
                )
            }.toSet(),
        ),
        executionBounds = defaultBounds,
    )

    // -------------------------------------------------------------------------
    // Empty registry
    // -------------------------------------------------------------------------

    @Test
    fun `empty registry has size zero`() {
        val registry = PluginRegistry(emptyList())

        assertEquals(0, registry.size)
        assertTrue(registry.isEmpty())
        assertEquals(emptyList(), registry.plugins)
        assertEquals(emptyList(), registry.resolutionOrder)
    }

    @Test
    fun `empty registry findById returns null`() {
        val registry = PluginRegistry(emptyList())

        assertNull(registry.findById(PluginId("missing")))
    }

    // -------------------------------------------------------------------------
    // Basic registration
    // -------------------------------------------------------------------------

    @Test
    fun `one plugin registry exposes the plugin`() {
        val a = plugin("a")
        val registry = PluginRegistry(listOf(a))

        assertEquals(1, registry.size)
        assertEquals(listOf(a), registry.plugins)
        assertEquals(a, registry.findById(PluginId("a")))
    }

    @Test
    fun `registration order is preserved in plugins list`() {
        val a = plugin("a")
        val b = plugin("b")
        val c = plugin("c")
        val registry = PluginRegistry(listOf(a, b, c))

        assertEquals(listOf(a, b, c), registry.plugins)
    }

    @Test
    fun `findById returns null for missing id`() {
        val registry = PluginRegistry(listOf(plugin("a")))

        assertNull(registry.findById(PluginId("missing")))
    }

    // -------------------------------------------------------------------------
    // Duplicate PluginId rejection
    // -------------------------------------------------------------------------

    @Test
    fun `duplicate PluginId throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            PluginRegistry(listOf(plugin("dup"), plugin("dup")))
        }
    }

    @Test
    fun `three plugins with same id throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            PluginRegistry(listOf(plugin("same"), plugin("same"), plugin("same")))
        }
    }

    // -------------------------------------------------------------------------
    // Unresolved dependency rejection
    // -------------------------------------------------------------------------

    @Test
    fun `dependency on unregistered plugin throws IllegalArgumentException`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            PluginRegistry(listOf(plugin("a", dependsOn = setOf("missing"))))
        }

        assertTrue(exception.message!!.contains("missing"))
        assertTrue(exception.message!!.contains("a"))
    }

    // -------------------------------------------------------------------------
    // Dependency cycle rejection
    // -------------------------------------------------------------------------

    @Test
    fun `self dependency is rejected as a cycle`() {
        assertFailsWith<IllegalArgumentException> {
            PluginRegistry(listOf(plugin("a", dependsOn = setOf("a"))))
        }
    }

    @Test
    fun `two-plugin cycle is rejected`() {
        val a = plugin("a", dependsOn = setOf("b"))
        val b = plugin("b", dependsOn = setOf("a"))

        assertFailsWith<IllegalArgumentException> {
            PluginRegistry(listOf(a, b))
        }
    }

    @Test
    fun `three-plugin transitive cycle is rejected`() {
        val a = plugin("a", dependsOn = setOf("b"))
        val b = plugin("b", dependsOn = setOf("c"))
        val c = plugin("c", dependsOn = setOf("a"))

        val exception = assertFailsWith<IllegalArgumentException> {
            PluginRegistry(listOf(a, b, c))
        }

        assertTrue(exception.message!!.contains("cycle"))
    }

    // -------------------------------------------------------------------------
    // Deterministic resolution ordering
    // -------------------------------------------------------------------------

    @Test
    fun `plugin with no dependencies resolves in registration order`() {
        val a = plugin("a")
        val b = plugin("b")
        val c = plugin("c")
        val registry = PluginRegistry(listOf(a, b, c))

        assertEquals(
            listOf(PluginId("a"), PluginId("b"), PluginId("c")),
            registry.resolutionOrder,
        )
    }

    @Test
    fun `dependency is resolved before its dependent`() {
        val base = plugin("base")
        val dependent = plugin("dependent", dependsOn = setOf("base"))
        // Registered in an order that would be wrong without dependency resolution.
        val registry = PluginRegistry(listOf(dependent, base))

        val order = registry.resolutionOrder
        assertTrue(order.indexOf(PluginId("base")) < order.indexOf(PluginId("dependent")))
    }

    @Test
    fun `diamond dependency graph resolves each node exactly once deps before dependents`() {
        // d depends on b and c; b and c both depend on a.
        val a = plugin("a")
        val b = plugin("b", dependsOn = setOf("a"))
        val c = plugin("c", dependsOn = setOf("a"))
        val d = plugin("d", dependsOn = setOf("b", "c"))
        val registry = PluginRegistry(listOf(d, c, b, a))

        val order = registry.resolutionOrder
        assertEquals(4, order.size)
        assertEquals(4, order.toSet().size)
        assertTrue(order.indexOf(PluginId("a")) < order.indexOf(PluginId("b")))
        assertTrue(order.indexOf(PluginId("a")) < order.indexOf(PluginId("c")))
        assertTrue(order.indexOf(PluginId("b")) < order.indexOf(PluginId("d")))
        assertTrue(order.indexOf(PluginId("c")) < order.indexOf(PluginId("d")))
    }

    @Test
    fun `resolutionOrder contains every registered plugin exactly once`() {
        val a = plugin("a")
        val b = plugin("b", dependsOn = setOf("a"))
        val registry = PluginRegistry(listOf(a, b))

        assertEquals(setOf(PluginId("a"), PluginId("b")), registry.resolutionOrder.toSet())
    }

    // -------------------------------------------------------------------------
    // Defensive copy of source collection
    // -------------------------------------------------------------------------

    @Test
    fun `mutation of source list after construction does not affect registry`() {
        val a = plugin("a")
        val source = mutableListOf<DataLoomPlugin>(a)
        val registry = PluginRegistry(source)

        source.add(plugin("b"))

        assertEquals(1, registry.size)
        assertEquals(listOf(a), registry.plugins)
    }
}
