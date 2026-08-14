package io.dataloom.api.plugin

import io.dataloom.api.identifier.RuntimeVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PluginManifestTest {

    private val compatibilityRange = PluginCompatibilityRange(
        minimumSdkVersion = RuntimeVersion("1.0.0"),
        maximumSdkVersion = RuntimeVersion("2.0.0"),
    )

    private fun manifest(
        capabilities: Set<PluginCapability> = emptySet(),
        permissions: Set<PluginPermission> = emptySet(),
        dependencies: Set<PluginDependency> = emptySet(),
    ): PluginManifest = PluginManifest(
        id = PluginId("example-plugin"),
        version = PluginVersion("1.0.0"),
        vendor = PluginVendor("Acme Corp"),
        compatibleSdkRange = compatibilityRange,
        capabilities = capabilities,
        permissions = permissions,
        dependencies = dependencies,
    )

    @Test
    fun `defaults to empty capabilities and permissions and dependencies`() {
        val result = manifest()

        assertTrue(result.capabilities.isEmpty())
        assertTrue(result.permissions.isEmpty())
        assertTrue(result.dependencies.isEmpty())
    }

    @Test
    fun `preserves supplied capabilities and permissions and dependencies`() {
        val capability = PluginCapability("custom-conflict-resolution")
        val permission = PluginPermission("network-access")
        val dependency = PluginDependency(
            pluginId = PluginId("other-plugin"),
            compatibilityRange = compatibilityRange,
        )

        val result = manifest(
            capabilities = setOf(capability),
            permissions = setOf(permission),
            dependencies = setOf(dependency),
        )

        assertEquals(setOf(capability), result.capabilities)
        assertEquals(setOf(permission), result.permissions)
        assertEquals(setOf(dependency), result.dependencies)
    }

    @Test
    fun `rejects more than 64 capabilities`() {
        val tooMany = (1..65).map { PluginCapability("capability-$it") }.toSet()

        assertFailsWith<IllegalArgumentException> {
            manifest(capabilities = tooMany)
        }
    }

    @Test
    fun `rejects more than 64 permissions`() {
        val tooMany = (1..65).map { PluginPermission("permission-$it") }.toSet()

        assertFailsWith<IllegalArgumentException> {
            manifest(permissions = tooMany)
        }
    }

    @Test
    fun `rejects more than 64 dependencies`() {
        val tooMany = (1..65).map {
            PluginDependency(
                pluginId = PluginId("plugin-$it"),
                compatibilityRange = compatibilityRange,
            )
        }.toSet()

        assertFailsWith<IllegalArgumentException> {
            manifest(dependencies = tooMany)
        }
    }

    @Test
    fun `accepts exactly 64 entries`() {
        val exactlyMax = (1..64).map { PluginCapability("capability-$it") }.toSet()

        val result = manifest(capabilities = exactlyMax)

        assertEquals(64, result.capabilities.size)
    }

    @Test
    fun `equality compares all properties by value`() {
        val first = manifest(capabilities = setOf(PluginCapability("cap")))
        val second = manifest(capabilities = setOf(PluginCapability("cap")))
        val different = manifest(capabilities = setOf(PluginCapability("other-cap")))

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertTrue(first != different)
    }

    @Test
    fun `toString renders bounded counts rather than full collection contents`() {
        val result = manifest(
            capabilities = setOf(PluginCapability("example-capability-name")),
            permissions = setOf(PluginPermission("example-permission-name")),
        )

        val rendered = result.toString()

        assertTrue(!rendered.contains("example-capability-name"))
        assertTrue(!rendered.contains("example-permission-name"))
        assertTrue(rendered.contains("capabilityCount=1"))
        assertTrue(rendered.contains("permissionCount=1"))
    }
}
