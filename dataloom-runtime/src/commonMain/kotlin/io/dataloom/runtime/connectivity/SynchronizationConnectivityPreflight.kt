package io.dataloom.runtime.connectivity

import io.dataloom.api.connectivity.ConnectivityCheckRequest
import io.dataloom.api.connectivity.ConnectivityProvider
import io.dataloom.api.connectivity.ConnectivityRequirement
import io.dataloom.api.connectivity.ConnectivitySnapshot
import io.dataloom.api.connectivity.ConnectivityStatus
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.ProviderOperationResult

/**
 * Evaluates whether the current device connectivity satisfies a
 * [ConnectivityRequirement] before synchronization execution proceeds.
 *
 * ## Purpose
 *
 * [SynchronizationConnectivityPreflight] is a stateless component that
 * performs exactly one connectivity check per call when connectivity is
 * required, and skips the check entirely when it is not. It maps the provider
 * result to a [ConnectivityPreflightResult] without exposing provider
 * internals, network metadata, or raw exceptions.
 *
 * ## Evaluation sequence
 *
 * [evaluate] follows a strict, deterministic sequence:
 *
 * 1. If [requirement] is [ConnectivityRequirement.NONE]:
 *    - return [ConnectivityPreflightResult.NotRequired].
 *    - do not invoke [provider].
 * 2. If [requirement] is not [ConnectivityRequirement.NONE] and [provider] is
 *    `null`:
 *    - return [ConnectivityPreflightResult.ProviderNotConfigured].
 * 3. Construct a [ConnectivityCheckRequest] from [request].
 * 4. Call [ConnectivityProvider.currentConnectivity] exactly once.
 * 5. If the provider returns [ProviderOperationResult.Failure]:
 *    - return [ConnectivityPreflightResult.CheckFailed] with the exact error.
 * 6. If the provider returns [ProviderOperationResult.Success]:
 *    - evaluate the snapshot against [requirement].
 *    - return [ConnectivityPreflightResult.Satisfied] when matched.
 *    - return [ConnectivityPreflightResult.RequirementNotMet] when not matched.
 *
 * ## Requirement matching
 *
 * Matching is deterministic and uses only the current snapshot state:
 *
 * - [ConnectivityRequirement.NONE]: always satisfied (not evaluated here).
 * - [ConnectivityRequirement.AVAILABLE]: satisfied only when
 *   [ConnectivityStatus.AVAILABLE] is reported.
 * - [ConnectivityRequirement.UNMETERED]: satisfied only when
 *   [ConnectivityStatus.AVAILABLE] is reported and
 *   [ConnectivitySnapshot.isMetered] is explicitly `false`.
 *
 * [ConnectivityStatus.UNKNOWN], [ConnectivityStatus.UNAVAILABLE], and
 * [ConnectivityStatus.LIMITED] do not satisfy any network-required
 * configuration. A `null` metering state does not satisfy
 * [ConnectivityRequirement.UNMETERED].
 *
 * ## Cancellation
 *
 * [kotlinx.coroutines.CancellationException] from
 * [ConnectivityProvider.currentConnectivity] propagates normally. It is never
 * caught, converted to a [ConnectivityPreflightResult], or re-wrapped.
 *
 * ## Exception boundary
 *
 * Unexpected programming exceptions propagate to the caller. This component
 * does not catch arbitrary exceptions or assertion failures.
 *
 * ## Boundaries
 *
 * This component must not:
 * - perform polling or wait for connectivity changes
 * - invoke any scheduler, queue, or storage provider
 * - own a [kotlinx.coroutines.CoroutineScope] or select a dispatcher
 * - read the system clock
 * - generate identifiers
 * - use global state, reflection, ServiceLoader, or a DI framework
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 */
public class SynchronizationConnectivityPreflight {

    /**
     * Evaluates whether the current connectivity satisfies [requirement].
     *
     * Follows the deterministic sequence documented on
     * [SynchronizationConnectivityPreflight]:
     *
     * 1. Return [ConnectivityPreflightResult.NotRequired] when [requirement] is
     *    [ConnectivityRequirement.NONE]; [provider] is not invoked.
     * 2. Return [ConnectivityPreflightResult.ProviderNotConfigured] when
     *    [provider] is `null` and connectivity is required.
     * 3. Invoke [provider] exactly once with a [ConnectivityCheckRequest]
     *    constructed from [request].
     * 4. Return [ConnectivityPreflightResult.CheckFailed] with the exact
     *    [io.dataloom.api.error.DataLoomError] on provider failure.
     * 5. Evaluate the snapshot and return [ConnectivityPreflightResult.Satisfied]
     *    or [ConnectivityPreflightResult.RequirementNotMet].
     *
     * [kotlinx.coroutines.CancellationException] propagates normally and is
     * never converted into a [ConnectivityPreflightResult].
     *
     * @param requirement the [ConnectivityRequirement] to evaluate. Use
     *   [ConnectivityRequirement.NONE] to skip connectivity checking.
     * @param provider the optional [ConnectivityProvider] to query. Must be
     *   non-null when [requirement] is not [ConnectivityRequirement.NONE].
     * @param request the [SynchronizationRequest] whose execution context is
     *   used to construct the [ConnectivityCheckRequest].
     * @return a [ConnectivityPreflightResult] describing the outcome of the
     *   preflight check.
     */
    public suspend fun evaluate(
        requirement: ConnectivityRequirement,
        provider: ConnectivityProvider?,
        request: SynchronizationRequest,
    ): ConnectivityPreflightResult {

        // Step 1: Skip when connectivity is not required.
        if (requirement == ConnectivityRequirement.NONE) {
            return ConnectivityPreflightResult.NotRequired
        }

        // Step 2: Reject when required but no provider is configured.
        if (provider == null) {
            return ConnectivityPreflightResult.ProviderNotConfigured
        }

        // Step 3: Invoke the provider exactly once.
        val checkRequest = ConnectivityCheckRequest(context = request.context)
        val providerResult = provider.currentConnectivity(checkRequest)

        // Steps 4–6: Map the provider result.
        return when (providerResult) {
            is ProviderOperationResult.Failure ->
                ConnectivityPreflightResult.CheckFailed(error = providerResult.error)

            is ProviderOperationResult.Success -> {
                val snapshot = providerResult.value
                if (isSatisfied(requirement, snapshot)) {
                    ConnectivityPreflightResult.Satisfied(snapshot = snapshot)
                } else {
                    ConnectivityPreflightResult.RequirementNotMet(
                        requirement = requirement,
                        status = snapshot.status,
                    )
                }
            }
        }
    }

    /**
     * Returns `true` when [snapshot] satisfies [requirement].
     *
     * Matching rules:
     *
     * - [ConnectivityRequirement.NONE]: always `true` (guarded by caller).
     * - [ConnectivityRequirement.AVAILABLE]: `true` only when
     *   [ConnectivitySnapshot.status] is [ConnectivityStatus.AVAILABLE].
     * - [ConnectivityRequirement.UNMETERED]: `true` only when
     *   [ConnectivitySnapshot.status] is [ConnectivityStatus.AVAILABLE]
     *   **and** [ConnectivitySnapshot.isMetered] is explicitly `false`.
     *
     * No ordinal comparisons are used. Unknown, unavailable, and limited
     * statuses do not satisfy any network-required configuration.
     */
    private fun isSatisfied(
        requirement: ConnectivityRequirement,
        snapshot: ConnectivitySnapshot,
    ): Boolean = when (requirement) {
        ConnectivityRequirement.NONE ->
            true
        ConnectivityRequirement.AVAILABLE ->
            snapshot.status == ConnectivityStatus.AVAILABLE
        ConnectivityRequirement.UNMETERED ->
            snapshot.status == ConnectivityStatus.AVAILABLE && snapshot.isMetered == false
    }
}
