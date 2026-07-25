package io.dataloom.runtime.connectivity

import io.dataloom.api.connectivity.ConnectivityRequirement
import io.dataloom.api.scheduling.SchedulingDelay

/**
 * Immutable configuration that controls connectivity-awareness during
 * synchronization execution.
 *
 * ## Purpose
 *
 * [SynchronizationConnectivityConfiguration] declares the minimum connectivity
 * state required before a synchronization pipeline may execute, and the delay
 * applied when a queued synchronization is deferred because its connectivity
 * requirement is not currently satisfied.
 *
 * ## Connectivity requirement
 *
 * [requirement] selects the [ConnectivityRequirement] that the runtime
 * evaluates before each execution. The preflight check is skipped entirely
 * when [requirement] is [ConnectivityRequirement.NONE], meaning no
 * [io.dataloom.api.connectivity.ConnectivityProvider] is invoked.
 *
 * ## Offline reschedule delay
 *
 * [offlineRescheduleDelay] is used only by queued execution when the
 * connectivity requirement is not satisfied. The
 * [io.dataloom.runtime.queue.QueuedSynchronizationExecutionHandler] reads an
 * injected [io.dataloom.api.time.DataLoomClock] and adds this delay to the
 * current instant to produce the next available-at timestamp.
 *
 * This field is not used by direct synchronization execution; direct execution
 * returns a structured rejection instead of being re-enqueued.
 *
 * ## Construction restrictions
 *
 * Construction preserves the supplied values exactly. It does not call any
 * [io.dataloom.api.connectivity.ConnectivityProvider], read the clock,
 * generate an identifier, or perform any I/O.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 *
 * ## Value semantics
 *
 * Equality compares [requirement] and [offlineRescheduleDelay] by value.
 *
 * @param requirement the [ConnectivityRequirement] that must be satisfied
 *   before synchronization execution proceeds. Use [ConnectivityRequirement.NONE]
 *   to disable connectivity checking entirely (default backward-compatible
 *   behavior).
 * @param offlineRescheduleDelay the [SchedulingDelay] applied to queued
 *   synchronization entries that are deferred because the connectivity
 *   requirement is not met. Not used for direct execution. Use
 *   [SchedulingDelay.ZERO] when no offline delay is desired.
 */
public class SynchronizationConnectivityConfiguration(
    /** The connectivity requirement evaluated before each pipeline execution. */
    public val requirement: ConnectivityRequirement,

    /**
     * The delay applied to queued entries deferred due to unsatisfied
     * connectivity.
     *
     * Used only by
     * [io.dataloom.runtime.queue.QueuedSynchronizationExecutionHandler].
     * Direct synchronization execution is not affected by this value.
     */
    public val offlineRescheduleDelay: SchedulingDelay,
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SynchronizationConnectivityConfiguration) return false
        return requirement == other.requirement &&
            offlineRescheduleDelay == other.offlineRescheduleDelay
    }

    override fun hashCode(): Int {
        var result = requirement.hashCode()
        result = 31 * result + offlineRescheduleDelay.hashCode()
        return result
    }

    /**
     * Returns a safe diagnostic representation.
     *
     * Includes only the structural [requirement] and [offlineRescheduleDelay].
     * Does not expose provider references, credentials, or platform details.
     */
    override fun toString(): String =
        "SynchronizationConnectivityConfiguration(" +
            "requirement=$requirement, " +
            "offlineRescheduleDelay=$offlineRescheduleDelay" +
            ")"

    public companion object {

        /**
         * Default configuration representing no connectivity requirement.
         *
         * Equivalent to constructing with [ConnectivityRequirement.NONE] and
         * [SchedulingDelay.ZERO]. No [io.dataloom.api.connectivity.ConnectivityProvider]
         * is invoked when this configuration is active.
         *
         * Use this as the default value in any component that accepts a
         * [SynchronizationConnectivityConfiguration] to preserve backward-compatible
         * behavior.
         */
        public val NONE: SynchronizationConnectivityConfiguration =
            SynchronizationConnectivityConfiguration(
                requirement = ConnectivityRequirement.NONE,
                offlineRescheduleDelay = SchedulingDelay.ZERO,
            )
    }
}
