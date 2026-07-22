package io.dataloom.api.connectivity

/**
 * Closed set of network connectivity requirements for a scheduled
 * synchronization request.
 *
 * A [ConnectivityRequirement] expresses the minimum connectivity state that
 * must be present before the scheduled synchronization may execute. The
 * concrete [io.dataloom.api.scheduling.SchedulerProvider] and
 * [ConnectivityProvider] implementations are responsible for evaluating and
 * mapping these requirements to platform constraints.
 *
 * These values are not bound to WorkManager network-type constants or any
 * other platform-specific representation.
 *
 * Enum ordinals are not a compatibility contract and must not be persisted.
 */
public enum class ConnectivityRequirement {
    /**
     * Synchronization does not require network connectivity.
     *
     * The scheduled workflow may execute regardless of the current network
     * state. No connectivity query is required before execution.
     */
    NONE,

    /**
     * Some usable network connectivity is required.
     *
     * Satisfied only when the current connectivity snapshot reports
     * [io.dataloom.api.connectivity.ConnectivityStatus.AVAILABLE].
     *
     * A runtime may apply a future policy to determine whether
     * [io.dataloom.api.connectivity.ConnectivityStatus.LIMITED] is acceptable.
     * This is not defined in this contract.
     */
    AVAILABLE,

    /**
     * Usable connectivity that is not reported as metered is required.
     *
     * Satisfied only when the current connectivity snapshot reports
     * [io.dataloom.api.connectivity.ConnectivityStatus.AVAILABLE] and
     * [io.dataloom.api.connectivity.ConnectivitySnapshot.isMetered] is `false`.
     *
     * A `null` metering state must not be treated as unmetered.
     */
    UNMETERED,
}
