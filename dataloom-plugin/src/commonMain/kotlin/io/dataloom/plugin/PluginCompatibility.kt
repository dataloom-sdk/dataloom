package io.dataloom.plugin

import io.dataloom.api.identifier.RuntimeVersion
import io.dataloom.api.plugin.PluginCompatibilityRange

/**
 * Why a [PluginCompatibilityRange] does not admit a running SDK version.
 */
public enum class PluginIncompatibilityReason {
    /** The range's minimum is above its maximum, so it admits no version at all. */
    EMPTY_RANGE,

    /** The running SDK version has lower precedence than the range's minimum. */
    BELOW_MINIMUM,

    /** The running SDK version has higher precedence than the range's maximum. */
    ABOVE_MAXIMUM,
}

/**
 * Outcome of checking a plugin's declared [PluginCompatibilityRange] against the
 * running SDK version. Never thrown.
 */
public sealed interface PluginCompatibilityResult {

    /** The running SDK version is inside the declared range. */
    public data object Compatible : PluginCompatibilityResult

    /**
     * The running SDK version is outside the declared range.
     *
     * @property sdkVersion the running SDK version that was checked.
     * @property range the plugin's declared range.
     * @property reason which bound (or the range itself) rejected [sdkVersion].
     */
    public data class Incompatible(
        public val sdkVersion: RuntimeVersion,
        public val range: PluginCompatibilityRange,
        public val reason: PluginIncompatibilityReason,
    ) : PluginCompatibilityResult
}

/**
 * Pure, deterministic compatibility check of a plugin's declared SDK range
 * against the running SDK version.
 *
 * ## Semantics
 *
 * Both bounds of [PluginCompatibilityRange] are inclusive and are compared with
 * Semantic Versioning precedence ([RuntimeVersion.precedenceCompareTo]): build
 * metadata is ignored, and a pre-release sorts below its release. A `null`
 * maximum means no upper bound. Consequently a pre-release SDK such as
 * `1.0.0-rc.1` is below a minimum of `1.0.0`, and a maximum of `2.0.0` excludes
 * `2.0.1`.
 *
 * A range whose minimum is above its maximum is reported as
 * [PluginIncompatibilityReason.EMPTY_RANGE] rather than as a bound violation,
 * since no SDK version could satisfy it.
 *
 * The check has no I/O, clock read, or state.
 */
public object PluginCompatibilityValidator {

    /** Checks whether [sdkVersion] satisfies [range]. */
    public fun validate(
        range: PluginCompatibilityRange,
        sdkVersion: RuntimeVersion,
    ): PluginCompatibilityResult {
        val maximum = range.maximumSdkVersion
        val reason = when {
            maximum != null && range.minimumSdkVersion.precedenceCompareTo(maximum) > 0 ->
                PluginIncompatibilityReason.EMPTY_RANGE
            sdkVersion.precedenceCompareTo(range.minimumSdkVersion) < 0 ->
                PluginIncompatibilityReason.BELOW_MINIMUM
            maximum != null && sdkVersion.precedenceCompareTo(maximum) > 0 ->
                PluginIncompatibilityReason.ABOVE_MAXIMUM
            else -> return PluginCompatibilityResult.Compatible
        }
        return PluginCompatibilityResult.Incompatible(sdkVersion = sdkVersion, range = range, reason = reason)
    }
}
