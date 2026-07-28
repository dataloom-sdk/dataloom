package io.dataloom.api.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class ProviderIdentifiersTest {

    @Test
    fun `provider id satisfies value-contract behavior`() {
        assertIdentifierBehavior(
            create = ::ProviderId,
            extract = ProviderId::value,
            valid = "provider-id",
            different = "provider-id-2",
        )
    }

    @Test
    fun `provider name satisfies value-contract behavior`() {
        assertIdentifierBehavior(
            create = ::ProviderName,
            extract = ProviderName::value,
            valid = "Primary Storage Provider",
            different = "Secondary Storage Provider",
        )
    }

    @Test
    fun `provider version satisfies value-contract behavior`() {
        assertIdentifierBehavior(
            create = ::ProviderVersion,
            extract = ProviderVersion::value,
            valid = "1.0.0",
            different = "1.0.1",
        )
    }

    @Test
    fun `provider capability satisfies value-contract behavior`() {
        assertIdentifierBehavior(
            create = ::ProviderCapability,
            extract = ProviderCapability::value,
            valid = "batch-write",
            different = "conflict-resolve",
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
