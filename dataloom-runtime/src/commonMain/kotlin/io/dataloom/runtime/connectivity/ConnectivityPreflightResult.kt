package io.dataloom.runtime.connectivity

import io.dataloom.api.connectivity.ConnectivityRequirement
import io.dataloom.api.connectivity.ConnectivitySnapshot
import io.dataloom.api.connectivity.ConnectivityStatus
import io.dataloom.api.error.DataLoomError

/**
 * Sealed result of a connectivity preflight evaluation performed by
 * [SynchronizationConnectivityPreflight].
 *
 * ## Variants
 *
 * | Variant                | Meaning                                                        |
 * |------------------------|----------------------------------------------------------------|
 * | [NotRequired]          | The configured requirement is [ConnectivityRequirement.NONE]; no provider was invoked. |
 * | [Satisfied]            | The connectivity snapshot satisfies the requirement.           |
 * | [ProviderNotConfigured]| Connectivity is required but no provider is registered.       |
 * | [RequirementNotMet]    | The snapshot does not satisfy the requirement.                 |
 * | [CheckFailed]          | The provider returned a canonical [DataLoomError].             |
 *
 * ## Security restrictions
 *
 * No variant exposes network names, SSIDs, carrier details, IP addresses,
 * credentials, authorization headers, encryption keys, payload bytes,
 * provider implementation instances, raw [Throwable] instances, or stack
 * traces.
 *
 * [RequirementNotMet] exposes only the structural [ConnectivityStatus] from
 * the snapshot, not the full [ConnectivitySnapshot].
 *
 * [CheckFailed] preserves only the canonical [DataLoomError] returned by the
 * provider and does not add a raw throwable.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 */
public sealed interface ConnectivityPreflightResult {

    /**
     * The configured requirement is [ConnectivityRequirement.NONE].
     *
     * No [io.dataloom.api.connectivity.ConnectivityProvider] was invoked.
     * Execution may proceed unconditionally without a connectivity check.
     */
    public data object NotRequired : ConnectivityPreflightResult

    /**
     * The current connectivity snapshot satisfies the configured requirement.
     *
     * [snapshot] is the exact [ConnectivitySnapshot] returned by the provider
     * for diagnostic purposes. It must not be used to re-evaluate requirements
     * or to bypass the preflight result.
     *
     * @param snapshot the [ConnectivitySnapshot] that satisfied the requirement.
     */
    public data class Satisfied(
        /** The exact [ConnectivitySnapshot] returned by the connectivity provider. */
        public val snapshot: ConnectivitySnapshot,
    ) : ConnectivityPreflightResult

    /**
     * Connectivity is required but no [io.dataloom.api.connectivity.ConnectivityProvider]
     * is registered for this execution.
     *
     * This variant is returned when [SynchronizationConnectivityPreflight.evaluate]
     * is called with a non-[ConnectivityRequirement.NONE] requirement and a
     * `null` provider reference.
     */
    public data object ProviderNotConfigured : ConnectivityPreflightResult

    /**
     * The current connectivity snapshot does not satisfy the configured
     * requirement.
     *
     * [requirement] is the [ConnectivityRequirement] that was not met.
     * [status] is the structural [ConnectivityStatus] from the snapshot.
     * The full snapshot is not exposed to avoid leaking network metadata.
     *
     * @param requirement the [ConnectivityRequirement] that was not satisfied.
     * @param status the structural [ConnectivityStatus] observed at check time.
     */
    public data class RequirementNotMet(
        /** The [ConnectivityRequirement] that was not satisfied. */
        public val requirement: ConnectivityRequirement,

        /**
         * The structural [ConnectivityStatus] observed at check time.
         *
         * The full snapshot is not exposed to prevent network-identifying
         * metadata from leaking.
         */
        public val status: ConnectivityStatus,
    ) : ConnectivityPreflightResult

    /**
     * The [io.dataloom.api.connectivity.ConnectivityProvider] returned a
     * [io.dataloom.api.provider.ProviderOperationResult.Failure].
     *
     * [error] is the exact canonical [DataLoomError] returned by the provider.
     * No raw [Throwable] is wrapped or re-thrown.
     *
     * @param error the exact canonical [DataLoomError] returned by the provider.
     */
    public data class CheckFailed(
        /** The exact canonical [DataLoomError] returned by the connectivity provider. */
        public val error: DataLoomError,
    ) : ConnectivityPreflightResult
}
