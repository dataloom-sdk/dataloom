package io.dataloom.governance.policy

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.identifier.PolicyCheckId
import io.dataloom.api.identifier.PolicySetId
import io.dataloom.api.security.KeyReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class PolicyPackManifestTest {

    private fun manifest(
        version: Long = 1L,
        checkIds: List<PolicyCheckId> = listOf(PolicyCheckId("c1")),
        metadata: Map<String, String> = emptyMap(),
    ) = PolicyPackManifest(
        policySetId = PolicySetId("set"),
        version = version,
        keyId = KeyReference("key-1"),
        checkIds = checkIds,
        metadata = DataLoomMetadata.of(metadata),
    )

    // -- version ---------------------------------------------------------------

    @Test
    fun versionMustBePositive() {
        manifest(version = 1L)
        assertFailsWith<IllegalArgumentException> { manifest(version = 0L) }
        assertFailsWith<IllegalArgumentException> { manifest(version = -1L) }
    }

    // -- checkIds ----------------------------------------------------------------

    @Test
    fun checkIdsMustNotBeEmpty() {
        assertFailsWith<IllegalArgumentException> { manifest(checkIds = emptyList()) }
    }

    @Test
    fun checkIdsMustBeUnique() {
        assertFailsWith<IllegalArgumentException> {
            manifest(checkIds = listOf(PolicyCheckId("c1"), PolicyCheckId("c1")))
        }
    }

    @Test
    fun checkIdsAreBounded() {
        fun ids(count: Int) = (0 until count).map { PolicyCheckId("c$it") }
        manifest(checkIds = ids(PolicyPackManifest.MAXIMUM_CHECKS))
        assertFailsWith<IllegalArgumentException> {
            manifest(checkIds = ids(PolicyPackManifest.MAXIMUM_CHECKS + 1))
        }
    }

    @Test
    fun checkIdsPreserveSuppliedOrder() {
        val ordered = listOf(PolicyCheckId("z"), PolicyCheckId("a"), PolicyCheckId("m"))
        assertEquals(ordered, manifest(checkIds = ordered).checkIds)
    }

    @Test
    fun checkIdsAreDefensivelyCopied() {
        val mutable = mutableListOf(PolicyCheckId("c1"))
        val built = manifest(checkIds = mutable)
        mutable.add(PolicyCheckId("c2"))
        assertEquals(listOf(PolicyCheckId("c1")), built.checkIds)
    }

    // -- well-formed Unicode -----------------------------------------------------

    @Test
    fun unpairedSurrogatesAreRejectedEverywhereTheyCouldBeEncoded() {
        val lonelyHigh = "a\uD83D"
        assertFailsWith<IllegalArgumentException> {
            PolicyPackManifest(PolicySetId(lonelyHigh), 1L, KeyReference("k"), listOf(PolicyCheckId("c1")))
        }
        assertFailsWith<IllegalArgumentException> {
            PolicyPackManifest(PolicySetId("s"), 1L, KeyReference(lonelyHigh), listOf(PolicyCheckId("c1")))
        }
        assertFailsWith<IllegalArgumentException> { manifest(checkIds = listOf(PolicyCheckId(lonelyHigh))) }
        assertFailsWith<IllegalArgumentException> { manifest(metadata = mapOf(lonelyHigh to "v")) }
        assertFailsWith<IllegalArgumentException> { manifest(metadata = mapOf("k" to lonelyHigh)) }
    }

    @Test
    fun wellFormedSupplementaryCharactersAreAccepted() {
        manifest(metadata = mapOf("k😀" to "v😀"))
    }

    // -- metadata bounds -----------------------------------------------------------

    @Test
    fun metadataEntryCountIsBounded() {
        fun entries(count: Int) = (0 until count).associate { "k$it" to "v" }
        manifest(metadata = entries(PolicyPackManifest.MAXIMUM_METADATA_ENTRIES))
        assertFailsWith<IllegalArgumentException> {
            manifest(metadata = entries(PolicyPackManifest.MAXIMUM_METADATA_ENTRIES + 1))
        }
    }

    @Test
    fun metadataKeyAndValueLengthsAreBounded() {
        val max = "x".repeat(PolicyPackManifest.MAXIMUM_METADATA_ENTRY_LENGTH)
        val over = "x".repeat(PolicyPackManifest.MAXIMUM_METADATA_ENTRY_LENGTH + 1)
        manifest(metadata = mapOf(max to max))
        assertFailsWith<IllegalArgumentException> { manifest(metadata = mapOf("k" to over)) }
        assertFailsWith<IllegalArgumentException> { manifest(metadata = mapOf(over to "v")) }
    }

    // -- equality and toString ------------------------------------------------------

    @Test
    fun equalityIsStructural() {
        assertEquals(manifest(), manifest())
        assertEquals(manifest().hashCode(), manifest().hashCode())
        assertNotEquals(manifest(version = 1L), manifest(version = 2L))
        assertNotEquals(manifest(checkIds = listOf(PolicyCheckId("a"))), manifest(checkIds = listOf(PolicyCheckId("b"))))
    }

    @Test
    fun toStringDoesNotRenderCheckIdsOrMetadata() {
        val rendered = manifest(
            checkIds = listOf(PolicyCheckId("secret-check")),
            metadata = mapOf("do-not-print" to "value"),
        ).toString()
        assertFalse(rendered.contains("secret-check"))
        assertFalse(rendered.contains("do-not-print"))
        assertEquals("PolicyPackManifest(policySetId=set, version=1, keyId=key-1, checkCount=1)", rendered)
    }
}
