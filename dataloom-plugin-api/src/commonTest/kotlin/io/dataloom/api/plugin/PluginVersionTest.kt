package io.dataloom.api.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the canonical Semantic Versioning format, strict parsing, and
 * precedence of [PluginVersion] -- the plugin-side counterpart of
 * `io.dataloom.api.identifier.RuntimeVersionTest`. [PluginVersion] delegates
 * its grammar to `RuntimeVersion` internally, but this suite re-verifies the
 * observable contract directly against [PluginVersion]'s own API rather than
 * trusting that delegation from outside the module.
 */
class PluginVersionTest {

    // -------------------------------------------------------------------------
    // Accepted values
    // -------------------------------------------------------------------------

    @Test
    fun parsesAPlainReleaseVersion() {
        val version = parsed("1.2.3")

        assertEquals(1, version.major)
        assertEquals(2, version.minor)
        assertEquals(3, version.patch)
        assertNull(version.preRelease)
        assertNull(version.buildMetadata)
        assertFalse(version.isPreRelease)
        assertEquals("1.2.3", version.value)
    }

    @Test
    fun parsesPreReleaseAndBuildMetadata() {
        val version = parsed("1.0.0-alpha.1+build.5-x")

        assertEquals("alpha.1", version.preRelease)
        assertEquals("build.5-x", version.buildMetadata)
        assertTrue(version.isPreRelease)
    }

    @Test
    fun acceptsTheLargestIntCoreComponent() {
        assertEquals(Int.MAX_VALUE, parsed("${Int.MAX_VALUE}.0.0").major)
    }

    @Test
    fun theConstructorAcceptsExactlyWhatParseAccepts() {
        for (input in listOf("1.0.0", "0.0.1", "2.1.0-rc.1", "1.0.0+build.7")) {
            assertEquals(input, PluginVersion(input).value)
        }
    }

    // -------------------------------------------------------------------------
    // Rejected values: typed failure, never an exception from parse
    // -------------------------------------------------------------------------

    @Test
    fun rejectsBlankInput() {
        assertFailure(PluginVersionParseFailure.BLANK, "")
        assertFailure(PluginVersionParseFailure.BLANK, "   ")
    }

    @Test
    fun rejectsOversizedInput() {
        assertFailure(PluginVersionParseFailure.TOO_LONG, "1.0.0+" + "a".repeat(PluginVersion.MAXIMUM_LENGTH))
    }

    @Test
    fun rejectsMalformedCores() {
        for (input in listOf("1", "1.0", "1.0.0.0", "1..0", "a.b.c", "v1.0.0", "plugin-1.0.0")) {
            assertIs<PluginVersionParseResult.Invalid>(PluginVersion.parse(input), "expected rejection of '$input'")
        }
        assertFailure(PluginVersionParseFailure.MALFORMED_CORE, "1.0")
    }

    @Test
    fun rejectsLeadingZerosInTheCore() {
        assertFailure(PluginVersionParseFailure.LEADING_ZERO_IN_CORE, "01.0.0")
        assertFailure(PluginVersionParseFailure.LEADING_ZERO_IN_CORE, "1.00.0")
    }

    @Test
    fun rejectsCoreComponentsBeyondIntRange() {
        assertFailure(PluginVersionParseFailure.NUMBER_OUT_OF_RANGE, "2147483648.0.0")
    }

    @Test
    fun rejectsInvalidPreRelease() {
        assertFailure(PluginVersionParseFailure.INVALID_PRE_RELEASE, "1.0.0-")
        assertFailure(PluginVersionParseFailure.INVALID_PRE_RELEASE, "1.0.0-alpha.01")
    }

    @Test
    fun rejectsInvalidBuildMetadata() {
        assertFailure(PluginVersionParseFailure.INVALID_BUILD_METADATA, "1.0.0+")
        assertFailure(PluginVersionParseFailure.INVALID_BUILD_METADATA, "1.0.0+a_b")
    }

    @Test
    fun failuresNeverEchoTheRejectedInput() {
        val result = PluginVersion.parse("secret-token")

        assertFalse(result.toString().contains("secret-token"))
    }

    @Test
    fun theConstructorThrowsForNonCanonicalValues() {
        assertFailsWith<IllegalArgumentException> { PluginVersion("plugin-1.0.0") }
        assertFailsWith<IllegalArgumentException> { PluginVersion("1.0") }
        assertFailsWith<IllegalArgumentException> { PluginVersion("") }
        assertFailsWith<IllegalArgumentException> { PluginVersion("v1.0.0") }
    }

    // -------------------------------------------------------------------------
    // Precedence
    // -------------------------------------------------------------------------

    @Test
    fun orderingFollowsTheSemverSpecificationExample() {
        val ordered = listOf(
            "1.0.0-alpha",
            "1.0.0-alpha.1",
            "1.0.0-alpha.beta",
            "1.0.0-beta",
            "1.0.0-beta.2",
            "1.0.0-beta.11",
            "1.0.0-rc.1",
            "1.0.0",
            "2.0.0",
            "2.1.0",
            "2.1.1",
        ).map(::parsed)

        for (i in ordered.indices) {
            for (j in ordered.indices) {
                val expected = i.compareTo(j)
                val actual = ordered[i].precedenceCompareTo(ordered[j])
                assertEquals(expected.coerceIn(-1, 1), actual.coerceIn(-1, 1), "${ordered[i]} vs ${ordered[j]}")
            }
        }
    }

    @Test
    fun coreComparisonIsNumericNotLexicographic() {
        assertTrue(parsed("1.10.0").precedenceCompareTo(parsed("1.9.0")) > 0)
        assertTrue(parsed("10.0.0").precedenceCompareTo(parsed("9.9.9")) > 0)
    }

    @Test
    fun buildMetadataDoesNotAffectPrecedenceButDoesAffectEquality() {
        val a = parsed("1.0.0+build.1")
        val b = parsed("1.0.0+build.2")

        assertEquals(0, a.precedenceCompareTo(b))
        assertNotEquals(a, b)
    }

    @Test
    fun aPreReleaseHasLowerPrecedenceThanItsOwnRelease() {
        assertTrue(parsed("1.0.0-rc.1").precedenceCompareTo(parsed("1.0.0")) < 0)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun parsed(input: String): PluginVersion {
        val result = PluginVersion.parse(input)
        return assertIs<PluginVersionParseResult.Parsed>(result, "expected '$input' to parse").version
    }

    private fun assertFailure(expected: PluginVersionParseFailure, input: String) {
        val result = PluginVersion.parse(input)
        assertEquals(PluginVersionParseResult.Invalid(expected), result, "input '$input'")
    }
}
