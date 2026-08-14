package io.dataloom.api.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class PluginIdentifiersTest {

    @Test
    fun `plugin id satisfies value-contract behavior`() {
        assertIdentifierBehavior(
            create = ::PluginId,
            extract = PluginId::value,
            valid = "plugin-id",
            different = "plugin-id-2",
        )
    }

    @Test
    fun `plugin version satisfies value-contract behavior`() {
        assertIdentifierBehavior(
            create = ::PluginVersion,
            extract = PluginVersion::value,
            valid = "1.0.0",
            different = "1.0.1",
        )
    }

    @Test
    fun `plugin vendor satisfies value-contract behavior`() {
        assertIdentifierBehavior(
            create = ::PluginVendor,
            extract = PluginVendor::value,
            valid = "Acme Corp",
            different = "Other Corp",
        )
    }

    @Test
    fun `plugin capability satisfies value-contract behavior`() {
        assertIdentifierBehavior(
            create = ::PluginCapability,
            extract = PluginCapability::value,
            valid = "custom-conflict-resolution",
            different = "custom-diagnostics-export",
        )
    }

    @Test
    fun `plugin permission satisfies value-contract behavior`() {
        assertIdentifierBehavior(
            create = ::PluginPermission,
            extract = PluginPermission::value,
            valid = "network-access",
            different = "filesystem-access",
        )
    }

    private fun <T> assertIdentifierBehavior(
        create: (String) -> T,
        extract: (T) -> String,
        valid: String,
        different: String,
    ) {
        val identifier: T = create(valid)
        val sameIdentifier: T = create(valid)
        val otherIdentifier: T = create(different)

        assertEquals(valid, extract(identifier))
        assertEquals(valid, identifier.toString())
        assertEquals(identifier, sameIdentifier)
        assertNotEquals(identifier, otherIdentifier)
        assertFailsWith<IllegalArgumentException> { create("") }
        assertFailsWith<IllegalArgumentException> { create("   ") }
    }
}
