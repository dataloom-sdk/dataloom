package io.dataloom.core.provider

/**
 * Closed set of provider lifecycle operations tracked by [ProviderLifecycleCoordinator].
 *
 * Used in [ProviderLifecycleFailure] to identify which operation produced a
 * canonical failure, enabling callers to distinguish initialization failures
 * from shutdown failures and rollback failures.
 *
 * Enum ordinals are not a compatibility contract and must not be persisted or
 * compared by ordinal.
 */
public enum class ProviderLifecycleOperation {

    /**
     * Provider initialization. Corresponds to
     * [io.dataloom.api.provider.DataLoomProvider.initialize].
     */
    INITIALIZE,

    /**
     * Provider shutdown or rollback. Corresponds to
     * [io.dataloom.api.provider.DataLoomProvider.close].
     */
    SHUTDOWN,
}
