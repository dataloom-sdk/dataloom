package io.dataloom.runtime.facade

import io.dataloom.api.plugin.PluginId
import io.dataloom.api.plugin.PluginLifecycleState
import io.dataloom.plugin.PluginCompatibilityResult
import io.dataloom.plugin.PluginExecutionBoundsResult
import io.dataloom.plugin.PluginLifecycleTransitionRequest
import io.dataloom.plugin.PluginLifecycleTransitionResult

/**
 * Public operations capability over the configured plugin engine.
 *
 * Every method delegates to the engine types in `dataloom-plugin` and returns
 * their results unchanged; this facade adds no translation layer. It does not
 * expose the registry, the lifecycle tracker, the bounds enforcer, or the
 * configured authorizer.
 *
 * ## Concurrency
 *
 * Lifecycle state is in-memory and non-durable. Callers must serialize
 * [transition] calls; [execute] may be called concurrently and is bounded per
 * plugin by that plugin's declared concurrency ceiling.
 *
 * ## No plugin invocation yet
 *
 * [io.dataloom.api.plugin.DataLoomPlugin] declares no invocation method, and no
 * subsystem dispatches hook points to plugins. [execute] therefore wraps a
 * caller-supplied operation in a plugin's declared execution bounds; the engine
 * never invokes plugin code on its own.
 */
public interface DataLoomPluginEngine {

    /**
     * Registered plugin ids in deterministic dependency-respecting order
     * (dependencies before dependents).
     */
    public val resolutionOrder: List<PluginId>

    /**
     * Returns the current lifecycle state of [id]. Every registered plugin
     * starts in [PluginLifecycleState.LOADED].
     *
     * @throws IllegalArgumentException if [id] is not registered.
     */
    public fun stateOf(id: PluginId): PluginLifecycleState

    /**
     * Checks [id]'s declared SDK range against [DataLoomRuntimeVersion.CURRENT]
     * without changing any state. Never throws for an incompatible plugin.
     *
     * @throws IllegalArgumentException if [id] is not registered.
     */
    public fun compatibilityOf(id: PluginId): PluginCompatibilityResult

    /**
     * Attempts the lifecycle transition described by [request].
     *
     * A structurally illegal transition returns
     * [PluginLifecycleTransitionResult.Rejected] without consulting the
     * configured authorizer. A request to enter
     * [PluginLifecycleState.VALIDATED] for a plugin whose declared SDK range
     * does not admit [DataLoomRuntimeVersion.CURRENT] returns
     * [PluginLifecycleTransitionResult.IncompatibleRuntime], also without
     * consulting the authorizer. Any other structurally legal transition is
     * submitted to the authorizer supplied in
     * [DataLoomPluginSpec.lifecycleAuthorizer]; a denial returns
     * [PluginLifecycleTransitionResult.AuthorizationDenied]. Tracked state
     * changes only for [PluginLifecycleTransitionResult.Allowed]. Caller
     * cancellation propagates unchanged.
     *
     * @throws IllegalArgumentException if the request's plugin is not registered.
     */
    public suspend fun transition(
        request: PluginLifecycleTransitionRequest,
    ): PluginLifecycleTransitionResult

    /**
     * Runs [operation] under [id]'s declared execution bounds: cancelled after
     * its maximum execution time, and rejected without running when the plugin
     * is already at its concurrency ceiling. Neither outcome throws; see
     * [PluginExecutionBoundsResult].
     *
     * A plugin that is not [PluginLifecycleState.ACTIVE] when this call starts
     * is refused with [PluginExecutionBoundsResult.NotActive] and
     * [operation] never runs. An invocation already in flight when its plugin
     * leaves `ACTIVE` is not cancelled: it completes, or is cancelled by its
     * own declared timeout.
     *
     * @throws IllegalArgumentException if [id] is not registered.
     */
    public suspend fun <T> execute(
        id: PluginId,
        operation: suspend () -> T,
    ): PluginExecutionBoundsResult<T>
}
