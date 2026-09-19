package io.dataloom.governance.audit

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.identifier.TenantId
import io.dataloom.governance.rbac.PrincipalId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AuditEventTest {

    private fun event(
        tenant: String = "t1",
        principal: String = "p1",
        details: Map<String, String> = emptyMap(),
    ) = AuditEvent(TenantId(tenant), PrincipalId(principal), AuditEventType("e"), DataLoomMetadata.of(details))

    @Test
    fun eventTypeUsesTheClosedVocabularyCharset() {
        for (value in listOf("access.denied", "role.bound", "a", "retry.override:v2")) {
            assertEquals(value, AuditEventType(value).value)
        }
        for (value in listOf("", " ", "*", "a b", "-a", "x".repeat(129))) {
            assertFailsWith<IllegalArgumentException>("AuditEventType '$value'") { AuditEventType(value) }
        }
    }

    @Test
    fun wellFormedSupplementaryCharactersAreAccepted() {
        event(tenant = "t😀", details = mapOf("k😀" to "v😀"))
    }

    @Test
    fun unpairedSurrogatesAreRejectedEverywhereTheyCouldBeEncoded() {
        val lonelyHigh = "a\uD83D"
        val lonelyLow = "\uDE00b"
        val reversedPair = "\uDE00\uD83D"
        for (bad in listOf(lonelyHigh, lonelyLow, reversedPair)) {
            assertFailsWith<IllegalArgumentException> { event(tenant = bad) }
            assertFailsWith<IllegalArgumentException> { event(principal = bad) }
            assertFailsWith<IllegalArgumentException> { event(details = mapOf("k" to bad)) }
            assertFailsWith<IllegalArgumentException> { event(details = mapOf(bad to "v")) }
        }
    }

    @Test
    fun detailEntryCountIsBounded() {
        fun details(count: Int) = (0 until count).associate { "k$it" to "v" }
        event(details = details(AuditEvent.MAXIMUM_DETAIL_ENTRIES))
        assertFailsWith<IllegalArgumentException> { event(details = details(AuditEvent.MAXIMUM_DETAIL_ENTRIES + 1)) }
    }

    @Test
    fun detailKeyAndValueLengthsAreBounded() {
        val max = "x".repeat(AuditEvent.MAXIMUM_DETAIL_LENGTH)
        val over = "x".repeat(AuditEvent.MAXIMUM_DETAIL_LENGTH + 1)
        event(details = mapOf(max to max))
        assertFailsWith<IllegalArgumentException> { event(details = mapOf("k" to over)) }
        assertFailsWith<IllegalArgumentException> { event(details = mapOf(over to "v")) }
    }

    @Test
    fun detailValuesAreNotRenderedByTheMetadataToString() {
        val rendered = event(details = mapOf("secret-ish" to "do-not-print")).details.toString()
        assertFalse(rendered.contains("do-not-print"))
    }
}
