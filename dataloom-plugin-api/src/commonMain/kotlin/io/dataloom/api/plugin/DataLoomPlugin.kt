package io.dataloom.api.plugin

/**
 * The stable identity contract every DataLoom plugin implementation exposes.
 *
 * [DataLoomPlugin] deliberately declares only [manifest] and
 * [executionBounds] — the *identity and bounds* of a plugin, discoverable
 * before any lifecycle transition. It does not declare lifecycle callback
 * methods (`initialize`, `activate`, `disable`, hook invocation, and so on):
 * those signatures depend on the execution context the plugin lifecycle
 * engine (#98) designs (least-privilege capability-scoped contexts,
 * cancellation, failure isolation) and are not yet frozen. Defining them
 * here ahead of that engine would be exactly the kind of speculative
 * infrastructure this project avoids building ahead of a concrete consumer.
 *
 * Static/application-registered plugin instances (as opposed to dynamically
 * discovered/loaded ones) are expected to implement this interface directly,
 * per #98's "static/application-registered plugins are acceptable on
 * platforms that cannot safely load code dynamically" architecture
 * constraint — this interface does not assume any particular discovery or
 * loading mechanism.
 */
public interface DataLoomPlugin {
    /** This plugin's manifest: identity, version, compatibility, capabilities, permissions, dependencies. */
    public val manifest: PluginManifest

    /** This plugin's declared execution-time and concurrency bounds. */
    public val executionBounds: PluginExecutionBounds
}
