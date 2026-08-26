package io.dataloom.core.plugin

import io.dataloom.api.plugin.PluginId
import io.dataloom.api.plugin.PluginLifecycleState

/**
 * Tracks each plugin registered in a [PluginRegistry] through its
 * [PluginLifecycleState], enforcing [PluginLifecycleTransitions]'s legality
 * rules on every requested transition.
 *
 * ## Deny-by-default
 *
 * Every plugin registered in [registry] starts tracked at
 * [PluginLifecycleState.LOADED] — "the plugin's manifest has been
 * discovered or registered but not yet validated," per
 * [PluginLifecycleState]'s own KDoc. No plugin is ever implicitly granted
 * [PluginLifecycleState.ACTIVE] (or any state past `LOADED`) by
 * registration alone: reaching `ACTIVE` requires an explicit, individually
 * legal `LOADED -> VALIDATED -> INITIALIZING -> ACTIVE` sequence of
 * [transition] calls. This is the deny-by-default registration/enablement
 * behavior `#98`'s own acceptance criteria require and
 * `dataloom-plugin-api`'s contract types deliberately do not implement
 * themselves.
 *
 * ## What this does not do
 *
 * [PluginLifecycleStateTracker] enforces transition *legality* only. It
 * does not decide whether the *caller* requesting a transition is
 * authorized to do so (`#98`'s "authorized hot disable" acceptance
 * criterion is still open), does not perform any actual plugin
 * initialization/shutdown work (there is no [io.dataloom.api.plugin.DataLoomPlugin]
 * lifecycle callback to invoke — those signatures are not yet frozen, per
 * `docs/api/plugin-api.md`), and does not write audit records.
 *
 * ## Thread-safety boundary
 *
 * [PluginLifecycleStateTracker] does not provide concurrency control.
 * Callers must serialize [transition] calls per plugin ID; concurrent
 * calls without external coordination produce undefined behavior — the
 * same boundary `io.dataloom.core.provider.ProviderLifecycleCoordinator`
 * documents for itself.
 *
 * @param registry the plugin registry whose registered plugins this tracker
 *   tracks lifecycle state for.
 */
public class PluginLifecycleStateTracker(private val registry: PluginRegistry) {

    private val states: MutableMap<PluginId, PluginLifecycleState> =
        registry.plugins.associate { it.manifest.id to PluginLifecycleState.LOADED }.toMutableMap()

    /**
     * Returns the current tracked [PluginLifecycleState] for [id].
     *
     * @throws IllegalArgumentException if [id] is not registered in
     *   [registry].
     */
    public fun stateOf(id: PluginId): PluginLifecycleState =
        states[id] ?: throw IllegalArgumentException(
            "PluginLifecycleStateTracker: '$id' is not registered in this tracker's registry.",
        )

    /**
     * Requests a transition of [id]'s tracked state to [target].
     *
     * When [PluginLifecycleTransitions.validate] reports the transition as
     * [io.dataloom.core.plugin.PluginLifecycleTransitionResult.Allowed],
     * the tracked state for [id] is updated to [target] and the same
     * result is returned. When it reports
     * [io.dataloom.core.plugin.PluginLifecycleTransitionResult.Rejected],
     * the tracked state is left unchanged and that result is returned —
     * this method never throws for an illegal transition.
     *
     * @throws IllegalArgumentException if [id] is not registered in
     *   [registry].
     */
    public fun transition(id: PluginId, target: PluginLifecycleState): PluginLifecycleTransitionResult {
        val current = stateOf(id)
        val result = PluginLifecycleTransitions.validate(current, target)
        if (result is PluginLifecycleTransitionResult.Allowed) {
            states[id] = target
        }
        return result
    }
}
