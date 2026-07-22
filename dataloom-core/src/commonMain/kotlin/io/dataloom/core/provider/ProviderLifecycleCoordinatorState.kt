package io.dataloom.core.provider

/**
 * Closed set of lifecycle states for [ProviderLifecycleCoordinator].
 *
 * This state model describes the coordinator's position in its operational
 * lifecycle. It is distinct from [io.dataloom.api.provider.ProviderLifecycleState],
 * which describes the lifecycle state of an individual [io.dataloom.api.provider.DataLoomProvider].
 *
 * Enum ordinals are not a compatibility contract and must not be persisted or
 * compared by ordinal.
 *
 * State transitions are deterministic and documented on [ProviderLifecycleCoordinator].
 */
public enum class ProviderLifecycleCoordinatorState {

    /**
     * The coordinator has been constructed but [ProviderLifecycleCoordinator.initialize]
     * has not yet been called.
     */
    NOT_INITIALIZED,

    /**
     * [ProviderLifecycleCoordinator.initialize] is in progress. Providers are
     * being initialized in registration order.
     */
    INITIALIZING,

    /**
     * All registered providers were successfully initialized.
     * [ProviderLifecycleCoordinator.shutdown] may now be called.
     */
    INITIALIZED,

    /**
     * [ProviderLifecycleCoordinator.shutdown] is in progress. Providers are
     * being shut down in reverse initialization order.
     */
    SHUTTING_DOWN,

    /**
     * All successfully initialized providers have been shut down.
     * This is a terminal state.
     */
    SHUT_DOWN,

    /**
     * The coordinator encountered a failure during initialization or shutdown.
     * This is a terminal state. See [ProviderLifecycleResult] for details.
     */
    FAILED,
}
