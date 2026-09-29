package io.dataloom.api.plugin

/**
 * Declared inclusive bounds on the versions of one plugin that a dependent
 * plugin supports.
 *
 * This is the plugin-to-plugin counterpart of [PluginCompatibilityRange]: that
 * type bounds the DataLoom runtime version a plugin runs on, this one bounds
 * the [PluginVersion] of a plugin another plugin depends on. They are separate
 * types so an SDK range can never be supplied where a plugin range belongs.
 *
 * This is a data shape only. Both bounds are compared by
 * [PluginVersion.precedenceCompareTo]; deciding whether a specific version
 * satisfies a range is behavior owned by the plugin engine (`dataloom-plugin`),
 * which also reports a range whose [minimum] is above its [maximum] instead of
 * rejecting it here.
 *
 * @param minimum the lowest supported plugin version, inclusive.
 * @param maximum the highest supported plugin version, inclusive, or `null`
 *   when no upper bound is declared.
 */
public data class PluginVersionRange(
    public val minimum: PluginVersion,
    public val maximum: PluginVersion? = null,
)
