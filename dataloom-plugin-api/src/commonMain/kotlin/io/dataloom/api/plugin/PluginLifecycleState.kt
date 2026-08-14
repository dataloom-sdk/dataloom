package io.dataloom.api.plugin

/**
 * Closed set of plugin lifecycle labels, matching #98's required
 * "load, validate, initialize, active, degraded, disabled, and unload"
 * lifecycle.
 *
 * This type documents lifecycle states only and does not enforce
 * transitions, timing, or authorization — the plugin lifecycle engine (#98)
 * owns the actual state machine, hot-disable authorization, and audit
 * records. Enum ordinals are not a compatibility contract and must not be
 * persisted, matching [io.dataloom.api.provider.ProviderLifecycleState]'s
 * own documented rule.
 */
public enum class PluginLifecycleState {
    /** The plugin's manifest has been discovered or registered but not yet validated. */
    LOADED,

    /** Manifest, compatibility, and permission validation is in progress or complete. */
    VALIDATED,

    /** Plugin initialization is in progress. */
    INITIALIZING,

    /** The plugin is enabled and available for its declared capabilities. */
    ACTIVE,

    /** The plugin is partially usable with reduced capability or reliability. */
    DEGRADED,

    /** The plugin is registered but not currently enabled. */
    DISABLED,

    /** The plugin has been unloaded and must not accept new invocations. */
    UNLOADED,
}
