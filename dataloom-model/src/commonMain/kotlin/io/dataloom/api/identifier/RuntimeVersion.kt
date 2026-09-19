package io.dataloom.api.identifier

import kotlin.jvm.JvmInline

/**
 * Canonical version label for the executing DataLoom runtime.
 *
 * ## Canonical format
 *
 * A [RuntimeVersion] always holds a strictly valid Semantic Versioning 2.0.0
 * string: `MAJOR.MINOR.PATCH`, optionally followed by `-PRERELEASE` and/or
 * `+BUILD`. There is no other accepted spelling: no leading `v`, no label
 * prefix such as `runtime-`, no missing components, no surrounding
 * whitespace, and no leading zeros in `MAJOR`, `MINOR`, `PATCH`, or a numeric
 * pre-release identifier. Each core component must fit in an [Int]. The whole
 * string is at most [MAXIMUM_LENGTH] characters.
 *
 * ## Construction
 *
 * The constructor is for trusted literals and previously validated values,
 * like every other identifier type in this package: it throws
 * [IllegalArgumentException] for a value that is not canonical. Untrusted
 * input (a manifest field, a stored string, a host-supplied value) must go
 * through [parse], which returns a typed [RuntimeVersionParseResult] and never
 * throws.
 *
 * ## Ordering
 *
 * [precedenceCompareTo] implements Semantic Versioning precedence. Build
 * metadata does not affect precedence, so two values with different
 * [buildMetadata] can compare as equal by precedence while remaining unequal
 * under `==`. That is why this type deliberately does not implement
 * [Comparable].
 *
 * Ownership: DataLoom runtime.
 */
@JvmInline
public value class RuntimeVersion(
    /** Underlying runtime version value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "RuntimeVersion must not be blank." }
        val parsed = parseSemanticVersion(value)
        require(parsed is SemanticVersionParse.Valid) {
            "RuntimeVersion must be a valid semantic version (MAJOR.MINOR.PATCH with optional " +
                "-PRERELEASE and +BUILD): ${(parsed as SemanticVersionParse.Invalid).failure}."
        }
    }

    /** The `MAJOR` component. */
    public val major: Int
        get() = components().major

    /** The `MINOR` component. */
    public val minor: Int
        get() = components().minor

    /** The `PATCH` component. */
    public val patch: Int
        get() = components().patch

    /** The dot-separated pre-release identifiers, or `null` for a release version. */
    public val preRelease: String?
        get() = components().preRelease.takeIf { it.isNotEmpty() }?.joinToString(".")

    /** The dot-separated build metadata identifiers, or `null` when absent. */
    public val buildMetadata: String?
        get() = components().buildMetadata.takeIf { it.isNotEmpty() }?.joinToString(".")

    /** `true` when this version carries a pre-release suffix. */
    public val isPreRelease: Boolean
        get() = components().preRelease.isNotEmpty()

    /**
     * Compares this version with [other] by Semantic Versioning 2.0.0
     * precedence and returns a negative number, zero, or a positive number when
     * this version has lower, equal, or higher precedence. Build metadata is
     * ignored. A pre-release has lower precedence than the same core version
     * without one.
     */
    public fun precedenceCompareTo(other: RuntimeVersion): Int {
        val left = components()
        val right = other.components()
        compareValues(left.major, right.major).let { if (it != 0) return it }
        compareValues(left.minor, right.minor).let { if (it != 0) return it }
        compareValues(left.patch, right.patch).let { if (it != 0) return it }
        return comparePreRelease(left.preRelease, right.preRelease)
    }

    override fun toString(): String = value

    private fun components(): SemanticVersionComponents =
        (parseSemanticVersion(value) as SemanticVersionParse.Valid).components

    public companion object {
        /** Maximum accepted length of a runtime version string. */
        public const val MAXIMUM_LENGTH: Int = 128

        /**
         * Strictly parses [value] as a canonical runtime version.
         *
         * Never throws for any input, including blank, oversized, or otherwise
         * malformed strings; every rejection is a
         * [RuntimeVersionParseResult.Invalid] carrying a
         * [RuntimeVersionParseFailure]. The failure never echoes [value].
         */
        public fun parse(value: String): RuntimeVersionParseResult =
            when (val parsed = parseSemanticVersion(value)) {
                is SemanticVersionParse.Valid -> RuntimeVersionParseResult.Parsed(RuntimeVersion(value))
                is SemanticVersionParse.Invalid -> RuntimeVersionParseResult.Invalid(parsed.failure)
            }
    }
}

/** Outcome of [RuntimeVersion.parse]. */
public sealed interface RuntimeVersionParseResult {

    /** [value] was a canonical runtime version. */
    public data class Parsed(public val version: RuntimeVersion) : RuntimeVersionParseResult

    /** The input was rejected for [failure]. */
    public data class Invalid(public val failure: RuntimeVersionParseFailure) : RuntimeVersionParseResult
}

/** Why [RuntimeVersion.parse] rejected an input. */
public enum class RuntimeVersionParseFailure {
    /** The input was empty or only whitespace. */
    BLANK,

    /** The input exceeded [RuntimeVersion.MAXIMUM_LENGTH] characters. */
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

internal class SemanticVersionComponents(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: List<String>,
    val buildMetadata: List<String>,
)

internal sealed interface SemanticVersionParse {
    class Valid(val components: SemanticVersionComponents) : SemanticVersionParse
    class Invalid(val failure: RuntimeVersionParseFailure) : SemanticVersionParse
}

internal fun parseSemanticVersion(value: String): SemanticVersionParse {
    if (value.isBlank()) return invalid(RuntimeVersionParseFailure.BLANK)
    if (value.length > RuntimeVersion.MAXIMUM_LENGTH) return invalid(RuntimeVersionParseFailure.TOO_LONG)

    val buildSeparator = value.indexOf('+')
    val withoutBuild = if (buildSeparator >= 0) value.substring(0, buildSeparator) else value
    val buildMetadata: List<String> = if (buildSeparator >= 0) {
        val identifiers = value.substring(buildSeparator + 1).split('.')
        if (!identifiers.all(::isValidIdentifier)) return invalid(RuntimeVersionParseFailure.INVALID_BUILD_METADATA)
        identifiers
    } else {
        emptyList()
    }

    val preReleaseSeparator = withoutBuild.indexOf('-')
    val core = if (preReleaseSeparator >= 0) withoutBuild.substring(0, preReleaseSeparator) else withoutBuild
    val preRelease: List<String> = if (preReleaseSeparator >= 0) {
        val identifiers = withoutBuild.substring(preReleaseSeparator + 1).split('.')
        val valid = identifiers.all { isValidIdentifier(it) && !(isNumeric(it) && hasLeadingZero(it)) }
        if (!valid) return invalid(RuntimeVersionParseFailure.INVALID_PRE_RELEASE)
        identifiers
    } else {
        emptyList()
    }

    val parts = core.split('.')
    if (parts.size != 3 || !parts.all { it.isNotEmpty() && isNumeric(it) }) {
        return invalid(RuntimeVersionParseFailure.MALFORMED_CORE)
    }
    if (parts.any(::hasLeadingZero)) return invalid(RuntimeVersionParseFailure.LEADING_ZERO_IN_CORE)
    val numbers = parts.map { it.toIntOrNull() ?: return invalid(RuntimeVersionParseFailure.NUMBER_OUT_OF_RANGE) }

    return SemanticVersionParse.Valid(
        SemanticVersionComponents(numbers[0], numbers[1], numbers[2], preRelease, buildMetadata),
    )
}

private fun invalid(failure: RuntimeVersionParseFailure): SemanticVersionParse =
    SemanticVersionParse.Invalid(failure)

private fun isAsciiIdentifierCharacter(c: Char): Boolean =
    c in '0'..'9' || c in 'a'..'z' || c in 'A'..'Z' || c == '-'

private fun isValidIdentifier(identifier: String): Boolean =
    identifier.isNotEmpty() && identifier.all(::isAsciiIdentifierCharacter)

private fun isNumeric(identifier: String): Boolean = identifier.all { it in '0'..'9' }

private fun hasLeadingZero(numeric: String): Boolean = numeric.length > 1 && numeric[0] == '0'

private fun comparePreRelease(left: List<String>, right: List<String>): Int {
    if (left.isEmpty() && right.isEmpty()) return 0
    if (left.isEmpty()) return 1
    if (right.isEmpty()) return -1
    for (index in 0 until minOf(left.size, right.size)) {
        val result = compareIdentifier(left[index], right[index])
        if (result != 0) return result
    }
    return compareValues(left.size, right.size)
}

private fun compareIdentifier(left: String, right: String): Int {
    val leftNumeric = isNumeric(left)
    val rightNumeric = isNumeric(right)
    return when {
        leftNumeric && rightNumeric ->
            // Numeric identifiers have no leading zeros, so length then
            // lexicographic order is numeric order without integer overflow.
            compareValues(left.length, right.length).takeIf { it != 0 } ?: left.compareTo(right)
        leftNumeric -> -1
        rightNumeric -> 1
        else -> left.compareTo(right)
    }
}
