package io.dataloom.api.identifier

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Verifies the canonical Semantic Versioning format, strict parsing, and precedence of [RuntimeVersion]. */
class RuntimeVersionTest {

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
    fun parsesBuildMetadataWithLeadingZerosAndHyphenatedIdentifiers() {
        assertEquals("001", parsed("1.0.0+001").buildMetadata)
        assertEquals("rc-1", parsed("1.0.0-rc-1").preRelease)
        assertEquals("0.0.0", parsed("0.0.0").value)
    }

    @Test
    fun acceptsTheLargestIntCoreComponent() {
        assertEquals(Int.MAX_VALUE, parsed("${Int.MAX_VALUE}.0.0").major)
    }

    // -------------------------------------------------------------------------
    // Rejected values: typed failure, never an exception from parse
    // -------------------------------------------------------------------------

    @Test
    fun rejectsBlankInput() {
        assertFailure(RuntimeVersionParseFailure.BLANK, "")
        assertFailure(RuntimeVersionParseFailure.BLANK, "   ")
    }

    @Test
    fun rejectsOversizedInput() {
        assertFailure(RuntimeVersionParseFailure.TOO_LONG, "1.0.0+" + "a".repeat(RuntimeVersion.MAXIMUM_LENGTH))
        parsed("1.0.0+" + "a".repeat(RuntimeVersion.MAXIMUM_LENGTH - 6))
    }

    @Test
    fun rejectsMalformedCores() {
        for (input in listOf("1", "1.0", "1.0.0.0", "1..0", ".1.0", "1.0.", "a.b.c", "1.0.x", "v1.0.0", "runtime-1.0.0")) {
            val result = RuntimeVersion.parse(input)
            assertIs<RuntimeVersionParseResult.Invalid>(result, "expected rejection of '$input'")
        }
        assertFailure(RuntimeVersionParseFailure.MALFORMED_CORE, "1.0")
        assertFailure(RuntimeVersionParseFailure.MALFORMED_CORE, "1.0.0.0")
        assertFailure(RuntimeVersionParseFailure.MALFORMED_CORE, "+1.0.0")
    }

    @Test
    fun rejectsSurroundingAndEmbeddedWhitespaceAndSigns() {
        assertFailure(RuntimeVersionParseFailure.MALFORMED_CORE, " 1.0.0")
        assertFailure(RuntimeVersionParseFailure.MALFORMED_CORE, "1.0.0 ")
        assertFailure(RuntimeVersionParseFailure.MALFORMED_CORE, "1. 0.0")
        assertFailure(RuntimeVersionParseFailure.MALFORMED_CORE, "1.-1.0")
        assertFailure(RuntimeVersionParseFailure.INVALID_BUILD_METADATA, "1.0.0+a+b")
    }

    @Test
    fun rejectsLeadingZerosInTheCore() {
        assertFailure(RuntimeVersionParseFailure.LEADING_ZERO_IN_CORE, "01.0.0")
        assertFailure(RuntimeVersionParseFailure.LEADING_ZERO_IN_CORE, "1.00.0")
        assertFailure(RuntimeVersionParseFailure.LEADING_ZERO_IN_CORE, "1.0.007")
    }

    @Test
    fun rejectsCoreComponentsBeyondIntRange() {
        assertFailure(RuntimeVersionParseFailure.NUMBER_OUT_OF_RANGE, "2147483648.0.0")
        assertFailure(RuntimeVersionParseFailure.NUMBER_OUT_OF_RANGE, "1.0.99999999999999999999")
    }

    @Test
    fun rejectsInvalidPreRelease() {
        assertFailure(RuntimeVersionParseFailure.INVALID_PRE_RELEASE, "1.0.0-")
        assertFailure(RuntimeVersionParseFailure.INVALID_PRE_RELEASE, "1.0.0-alpha..1")
        assertFailure(RuntimeVersionParseFailure.INVALID_PRE_RELEASE, "1.0.0-alpha.01")
        assertFailure(RuntimeVersionParseFailure.INVALID_PRE_RELEASE, "1.0.0-al pha")
        assertFailure(RuntimeVersionParseFailure.INVALID_PRE_RELEASE, "1.0.0-é")
    }

    @Test
    fun rejectsInvalidBuildMetadata() {
        assertFailure(RuntimeVersionParseFailure.INVALID_BUILD_METADATA, "1.0.0+")
        assertFailure(RuntimeVersionParseFailure.INVALID_BUILD_METADATA, "1.0.0+a..b")
        assertFailure(RuntimeVersionParseFailure.INVALID_BUILD_METADATA, "1.0.0+a_b")
    }

    @Test
    fun failuresNeverEchoTheRejectedInput() {
        val result = RuntimeVersion.parse("secret-token")

        assertFalse(result.toString().contains("secret-token"))
    }

    @Test
    fun theConstructorThrowsForNonCanonicalValues() {
        assertFailsWith<IllegalArgumentException> { RuntimeVersion("runtime-1.0.0") }
        assertFailsWith<IllegalArgumentException> { RuntimeVersion("1.0") }
        assertFailsWith<IllegalArgumentException> { RuntimeVersion("") }
        assertFailsWith<IllegalArgumentException> { RuntimeVersion("v1.0.0") }
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
    fun hugeNumericPreReleaseIdentifiersCompareWithoutOverflow() {
        val small = parsed("1.0.0-99999999999999999999")
        val large = parsed("1.0.0-100000000000000000000")

        assertTrue(small.precedenceCompareTo(large) < 0)
        assertTrue(large.precedenceCompareTo(small) > 0)
    }

    @Test
    fun buildMetadataDoesNotAffectPrecedenceButDoesAffectEquality() {
        val a = parsed("1.0.0+build.1")
        val b = parsed("1.0.0+build.2")

        assertEquals(0, a.precedenceCompareTo(b))
        assertNotEquals(a, b)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun parsed(input: String): RuntimeVersion {
        val result = RuntimeVersion.parse(input)
        return assertIs<RuntimeVersionParseResult.Parsed>(result, "expected '$input' to parse").version
    }

    private fun assertFailure(expected: RuntimeVersionParseFailure, input: String) {
        val result = RuntimeVersion.parse(input)
        assertEquals(RuntimeVersionParseResult.Invalid(expected), result, "input '$input'")
    }
}
