package io.dataloom.api.provider

/**
 * Optional provider bindings evaluated against an immutable strategy plan.
 *
 * Unlike [SynchronizationProviderBindings], this contract does not require a
 * universal storage-plus-transport pair. The runtime resolves only the roles
 * required by the evaluated plan. This is what allows a network-only plan to
 * resolve transport without reading, resolving, or invoking storage or queue.
 */
public data class StrategyProviderBindings(
    public val storageProviderId: ProviderId? = null,
    public val transportProviderId: ProviderId? = null,
    public val schedulerProviderId: ProviderId? = null,
    public val connectivityProviderId: ProviderId? = null,
    public val queueProviderId: ProviderId? = null,
)
