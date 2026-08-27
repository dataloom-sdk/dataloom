package io.dataloom.core.plugin

import io.dataloom.api.plugin.PluginPermission
import io.dataloom.api.security.Capability
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies [asCapability] maps a [PluginPermission] onto a [Capability] of
 * the exact same label, with no transformation.
 */
class PluginPermissionEnforcementTest {

    @Test
    fun `asCapability preserves the permission label exactly`() {
        assertEquals(Capability("storage.read"), PluginPermission("storage.read").asCapability())
    }

    @Test
    fun `asCapability produces distinct capabilities for distinct permission labels`() {
        val a = PluginPermission("storage.read").asCapability()
        val b = PluginPermission("network.push").asCapability()

        assertEquals(false, a == b)
    }
}
