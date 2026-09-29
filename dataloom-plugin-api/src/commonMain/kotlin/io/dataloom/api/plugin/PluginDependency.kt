package io.dataloom.api.plugin

/**
 * A declared dependency of one plugin on another plugin.
 *
 * Dependency-graph validation (cycle rejection, deterministic resolution
 * ordering), and the gating of lifecycle transitions on a dependency's
 * presence, version, and state, are runtime behavior owned by the plugin
 * engine (`dataloom-plugin`) — this type only declares the edge.
 *
 * @param pluginId the depended-upon plugin's identifier.
 * @param supportedVersionRange the versions of the depended-upon plugin this
 *   plugin supports. Compared against that plugin's own
 *   [PluginManifest.version], never against the SDK version.
 */
public data class PluginDependency(
    public val pluginId: PluginId,
    public val supportedVersionRange: PluginVersionRange,
)
