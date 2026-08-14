package io.dataloom.api.plugin

import io.dataloom.api.identifier.RuntimeVersion

/**
 * Declared inclusive SDK-version compatibility bounds for a plugin.
 *
 * This is a data shape only — it does not parse, order, or compare
 * [RuntimeVersion] values. Deciding whether a specific running SDK version
 * satisfies a declared range is compatibility-validation *behavior*, owned
 * by the plugin lifecycle engine (#98) per its "compatibility validation
 * before activation" acceptance criterion, not by this contract module.
 *
 * @param minimumSdkVersion the lowest compatible runtime version, inclusive.
 * @param maximumSdkVersion the highest compatible runtime version, inclusive,
 *   or `null` when the plugin declares no known upper bound.
 */
public data class PluginCompatibilityRange(
    public val minimumSdkVersion: RuntimeVersion,
    public val maximumSdkVersion: RuntimeVersion? = null,
)
