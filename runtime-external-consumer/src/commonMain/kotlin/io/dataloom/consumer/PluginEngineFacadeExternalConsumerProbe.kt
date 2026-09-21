package io.dataloom.consumer

import io.dataloom.api.operational.OperationalEventOutboxScope
import io.dataloom.api.operational.OperationalEventOutboxState
import io.dataloom.api.plugin.DataLoomPlugin
import io.dataloom.api.plugin.PluginId
import io.dataloom.api.state.DurableStateStore
import io.dataloom.plugin.PluginExecutionBoundsResult
import io.dataloom.plugin.PluginLifecycleAdministrationAuthorizer
import io.dataloom.plugin.PluginLifecycleTransitionRequest
import io.dataloom.plugin.PluginLifecycleTransitionResult
import io.dataloom.runtime.facade.DataLoom
import io.dataloom.runtime.facade.DataLoomBuilder
import io.dataloom.runtime.facade.DataLoomPluginOperationalEventOutboxSpec
import io.dataloom.runtime.facade.DataLoomPluginSpec

/**
 * External JVM and Apple consumer probe for the opt-in plugin-engine capability.
 * Compiles against the public runtime surface without a `dataloom-core`
 * dependency.
 */
public object PluginEngineFacadeExternalConsumerProbe {

    public fun configure(
        builder: DataLoomBuilder,
        plugins: List<DataLoomPlugin>,
        authorizer: PluginLifecycleAdministrationAuthorizer,
    ): DataLoomBuilder = builder.pluginConfiguration(DataLoomPluginSpec(plugins, authorizer))

    public fun configureAudit(
        builder: DataLoomBuilder,
        store: DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState>,
    ): DataLoomBuilder =
        builder.pluginOperationalEventOutboxConfiguration(DataLoomPluginOperationalEventOutboxSpec(store))

    public suspend fun transition(
        dataLoom: DataLoom,
        request: PluginLifecycleTransitionRequest,
    ): PluginLifecycleTransitionResult? = dataLoom.pluginEngine?.transition(request)

    public suspend fun <T> execute(
        dataLoom: DataLoom,
        id: PluginId,
        operation: suspend () -> T,
    ): PluginExecutionBoundsResult<T>? = dataLoom.pluginEngine?.execute(id, operation)
}
