package io.dataloom.runtime.facade

import io.dataloom.api.plugin.DataLoomPlugin
import io.dataloom.plugin.PluginLifecycleAdministrationAuthorizer

/**
 * Immutable configuration for the optional plugin-engine capability.
 *
 * Supplying this spec to [DataLoomBuilder.pluginConfiguration] makes
 * [DataLoom.pluginEngine] non-null. Omitting it leaves [DataLoom] behavior
 * unchanged and [DataLoom.pluginEngine] `null`.
 *
 * ## Deny by default
 *
 * [plugins] are registered but never activated: every plugin starts in
 * [io.dataloom.api.plugin.PluginLifecycleState.LOADED]. The only way to change
 * lifecycle state through [DataLoomPluginEngine] is
 * [DataLoomPluginEngine.transition], and every transition it attempts is gated
 * by [lifecycleAuthorizer]. There is no authorizer-free path.
 *
 * ## Validation
 *
 * The spec itself performs no plugin-graph validation. Duplicate
 * [io.dataloom.api.plugin.PluginId] values, unresolved dependencies, and
 * dependency cycles are rejected by [io.dataloom.plugin.PluginRegistry] with an
 * [IllegalArgumentException] from [DataLoomBuilder.build], the same behavior
 * the provider registry has for duplicate provider ids. An empty [plugins]
 * list is valid.
 */
public class DataLoomPluginSpec(
    /** Plugins to register, in registration order. Copied defensively. */
    plugins: List<DataLoomPlugin>,

    /** Host-owned, deny-by-default authorization boundary for lifecycle transitions. */
    public val lifecycleAuthorizer: PluginLifecycleAdministrationAuthorizer,
) {
    /** Immutable snapshot of the plugins supplied at construction. */
    public val plugins: List<DataLoomPlugin> = plugins.toList()

    /** Avoids rendering plugin or authorizer implementation state in diagnostics. */
    override fun toString(): String = "DataLoomPluginSpec(pluginCount=${this.plugins.size})"
}
