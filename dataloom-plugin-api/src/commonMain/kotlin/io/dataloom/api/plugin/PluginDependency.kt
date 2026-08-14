package io.dataloom.api.plugin

/**
 * A declared dependency of one plugin on another plugin.
 *
 * Dependency-graph validation (cycle rejection, deterministic resolution
 * ordering) is runtime behavior owned by the plugin lifecycle engine (#98)
 * per its "dependency validation, and cycle rejection" acceptance
 * criterion — this type only declares the edge.
 *
 * @param pluginId the depended-upon plugin's identifier.
 * @param compatibilityRange the depended-upon plugin's required version range.
 */
public data class PluginDependency(
    public val pluginId: PluginId,
    public val compatibilityRange: PluginCompatibilityRange,
)
