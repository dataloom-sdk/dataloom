package io.dataloom.api.plugin

import io.dataloom.api.identifier.RuntimeVersion
import io.dataloom.api.identifier.RuntimeVersionParseFailure
import io.dataloom.api.identifier.RuntimeVersionParseResult
import kotlin.jvm.JvmInline

/**
 * Canonical version of a plugin itself, as opposed to the DataLoom runtime
 * version the plugin runs on (that is [RuntimeVersion], compared against a
 * [PluginCompatibilityRange]).
 *
 * ## Canonical format
 *
 * A [PluginVersion] always holds a strictly valid Semantic Versioning 2.0.0
 * string: `MAJOR.MINOR.PATCH`, optionally followed by `-PRERELEASE` and/or
 * `+BUILD`. There is no other accepted spelling: no leading `v`, no label
 * prefix, no missing components, no surrounding whitespace, and no leading
 * zeros in `MAJOR`, `MINOR`, `PATCH`, or a numeric pre-release identifier. Each
 * core component must fit in an [Int]. The whole string is at most
 * [MAXIMUM_LENGTH] characters.
 *
 * This is exactly the grammar of [RuntimeVersion], and it is enforced by
 * delegating to [RuntimeVersion] rather than by a second parser, so the two
 * cannot drift apart. The delegation is an implementation detail: none of
 * [RuntimeVersion]'s types appears in this type's API.
 *
 * ## Construction
 *
 * The constructor is for trusted literals and previously validated values,
 * like every other identifier type in this package: it throws
 * [IllegalArgumentException] for a value that is not canonical. Untrusted
 * input (a manifest field, a stored string, a host-supplied value) must go
 * through [parse], which returns a typed [PluginVersionParseResult] and never
 * throws.
 *
 * ## Ordering
 *
 * [precedenceCompareTo] implements Semantic Versioning precedence. Build
 * metadata does not affect precedence, so two values with different
 * [buildMetadata] can compare as equal by precedence while remaining unequal
 * under `==`. That is why this type deliberately does not implement
 * [Comparable]. Whether a version satisfies a [PluginVersionRange] is decided
 * by the plugin engine (`dataloom-plugin`), not by this contract module.
 */
@JvmInline
public value class PluginVersion(
    /** Underlying plugin version value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "PluginVersion must not be blank." }
        val parsed = RuntimeVersion.parse(value)
        require(parsed is RuntimeVersionParseResult.Parsed) {
            "PluginVersion must be a valid semantic version (MAJOR.MINOR.PATCH with optional " +
                "-PRERELEASE and +BUILD): ${(parsed as RuntimeVersionParseResult.Invalid).failure}."
        }
    }

    /** The `MAJOR` component. */
    public val major: Int
        get() = semanticVersion().major

    /** The `MINOR` component. */
    public val minor: Int
        get() = semanticVersion().minor

    /** The `PATCH` component. */
    public val patch: Int
        get() = semanticVersion().patch

    /** The dot-separated pre-release identifiers, or `null` for a release version. */
    public val preRelease: String?
        get() = semanticVersion().preRelease

    /** The dot-separated build metadata identifiers, or `null` when absent. */
    public val buildMetadata: String?
        get() = semanticVersion().buildMetadata

    /** `true` when this version carries a pre-release suffix. */
    public val isPreRelease: Boolean
        get() = semanticVersion().isPreRelease

    /**
     * Compares this version with [other] by Semantic Versioning 2.0.0
     * precedence and returns a negative number, zero, or a positive number when
     * this version has lower, equal, or higher precedence. Build metadata is
     * ignored. A pre-release has lower precedence than the same core version
     * without one.
     */
    public fun precedenceCompareTo(other: PluginVersion): Int =
        semanticVersion().precedenceCompareTo(other.semanticVersion())

    override fun toString(): String = value

    private fun semanticVersion(): RuntimeVersion = RuntimeVersion(value)

    public companion object {
        /** Maximum accepted length of a plugin version string. */
        public const val MAXIMUM_LENGTH: Int = RuntimeVersion.MAXIMUM_LENGTH

        /**
         * Strictly parses [value] as a canonical plugin version.
         *
         * Never throws for any input, including blank, oversized, or otherwise
         * malformed strings; every rejection is a
         * [PluginVersionParseResult.Invalid] carrying a
         * [PluginVersionParseFailure]. The failure never echoes [value].
         */
        public fun parse(value: String): PluginVersionParseResult =
            when (val parsed = RuntimeVersion.parse(value)) {
                is RuntimeVersionParseResult.Parsed -> PluginVersionParseResult.Parsed(PluginVersion(value))
                is RuntimeVersionParseResult.Invalid ->
                    PluginVersionParseResult.Invalid(parsed.failure.toPluginVersionParseFailure())
            }
    }
}

/** Outcome of [PluginVersion.parse]. */
public sealed interface PluginVersionParseResult {

    /** The input was a canonical plugin version. */
    public data class Parsed(public val version: PluginVersion) : PluginVersionParseResult

    /** The input was rejected for [failure]. */
    public data class Invalid(public val failure: PluginVersionParseFailure) : PluginVersionParseResult
}

/** Why [PluginVersion.parse] rejected an input. */
public enum class PluginVersionParseFailure {
    /** The input was empty or only whitespace. */
    BLANK,

    /** The input exceeded [PluginVersion.MAXIMUM_LENGTH] characters. */
    TOO_LONG,

    /** The core was not exactly three dot-separated all-digit components. */
    MALFORMED_CORE,

    /** A core component had a leading zero. */
    LEADING_ZERO_IN_CORE,

    /** A core component did not fit in an [Int]. */
    NUMBER_OUT_OF_RANGE,

    /** The pre-release part was empty, had an empty identifier, an illegal character, or a numeric identifier with a leading zero. */
    INVALID_PRE_RELEASE,

    /** The build-metadata part was empty, had an empty identifier, or an illegal character. */
    INVALID_BUILD_METADATA,
}

// Exhaustive on purpose: a new RuntimeVersionParseFailure must be given an explicit plugin-side meaning.
private fun RuntimeVersionParseFailure.toPluginVersionParseFailure(): PluginVersionParseFailure = when (this) {
    RuntimeVersionParseFailure.BLANK -> PluginVersionParseFailure.BLANK
    RuntimeVersionParseFailure.TOO_LONG -> PluginVersionParseFailure.TOO_LONG
    RuntimeVersionParseFailure.MALFORMED_CORE -> PluginVersionParseFailure.MALFORMED_CORE
    RuntimeVersionParseFailure.LEADING_ZERO_IN_CORE -> PluginVersionParseFailure.LEADING_ZERO_IN_CORE
    RuntimeVersionParseFailure.NUMBER_OUT_OF_RANGE -> PluginVersionParseFailure.NUMBER_OUT_OF_RANGE
    RuntimeVersionParseFailure.INVALID_PRE_RELEASE -> PluginVersionParseFailure.INVALID_PRE_RELEASE
    RuntimeVersionParseFailure.INVALID_BUILD_METADATA -> PluginVersionParseFailure.INVALID_BUILD_METADATA
}
