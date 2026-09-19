package io.dataloom.runtime.facade

import io.dataloom.api.plugin.PluginId
import io.dataloom.api.plugin.PluginLifecycleState
import io.dataloom.plugin.PluginExecutionBoundsEnforcer
import io.dataloom.plugin.PluginExecutionBoundsResult
import io.dataloom.plugin.PluginLifecycleAdministrationAuthorizer
import io.dataloom.plugin.PluginLifecycleStateTracker
import io.dataloom.plugin.PluginLifecycleTransitionRequest
import io.dataloom.plugin.PluginLifecycleTransitionResult
import io.dataloom.plugin.PluginRegistry

/**
 * Immutable facade adapter over the `dataloom-plugin` engine.
 *
 * Pure delegation: results are the engine's own types, returned unchanged.
 */
internal class DefaultDataLoomPluginEngine(
    private val registry: PluginRegistry,
    private val tracker: PluginLifecycleStateTracker,
    private val enforcer: PluginExecutionBoundsEnforcer,
    private val authorizer: PluginLifecycleAdministrationAuthorizer,
) : DataLoomPluginEngine {

    override val resolutionOrder: List<PluginId>
        get() = registry.resolutionOrder

    override fun stateOf(id: PluginId): PluginLifecycleState = tracker.stateOf(id)

    override suspend fun transition(
        request: PluginLifecycleTransitionRequest,
    ): PluginLifecycleTransitionResult = tracker.transition(request, authorizer)

    override suspend fun <T> execute(
        id: PluginId,
        operation: suspend () -> T,
    ): PluginExecutionBoundsResult<T> = enforcer.execute(id, operation)
}
