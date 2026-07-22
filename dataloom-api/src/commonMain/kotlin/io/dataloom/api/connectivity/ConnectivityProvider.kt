package io.dataloom.api.connectivity

import io.dataloom.api.provider.DataLoomProvider
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType

/**
 * Platform-independent provider contract for querying the current
 * device-level network connectivity state.
 *
 * [ConnectivityProvider] is the adapter boundary between the DataLoom runtime
 * and a platform-specific connectivity mechanism such as Android
 * ConnectivityManager, Apple NWPathMonitor, or a custom implementation.
 *
 * ```text
 * ConnectivityManager / native network monitor / custom implementation
 *       ↓
 * ConnectivityProvider
 *       ↓
 * ConnectivitySnapshot
 *       ↓
 * DataLoom Runtime
 * ```
 *
 * ## Responsibilities
 *
 * Implementations observe platform connectivity state and translate it into
 * the canonical [ConnectivitySnapshot] model, keeping platform-specific APIs
 * outside the shared DataLoom public surface.
 *
 * ## Deferred features
 *
 * Streaming connectivity observation (`Flow<ConnectivitySnapshot>`) is
 * explicitly deferred to a future issue. Implementations must not
 * automatically monitor or cache connectivity state.
 *
 * ## What this provider must not do
 *
 * Implementations must not:
 * - expose ConnectivityManager, Network, NetworkCapabilities, NWPathMonitor,
 *   or other platform-specific connectivity types through the public API
 * - perform transport requests or test backend reachability
 * - expose IP addresses, interface names, SSIDs, carrier names, VPN details,
 *   or platform network handles through the public API
 * - expose [kotlinx.coroutines.CoroutineScope] or dispatcher types
 * - implement automatic retry behavior
 * - automatically register or initialize themselves
 * - log sensitive context automatically
 *
 * ## Thread safety
 *
 * Implementations are responsible for documenting and enforcing their own
 * thread-safety guarantees and additional concurrency constraints.
 *
 * ## Cancellation
 *
 * Implementations must preserve coroutine cancellation and must not convert
 * cancellation exceptions into normal failures.
 *
 * ## Constraints
 *
 * Implementations must:
 * - expose a [descriptor] whose [ProviderDescriptor.type] is
 *   [ProviderType.CONNECTIVITY]
 * - map platform failures to canonical [io.dataloom.api.error.DataLoomError]
 *   values
 * - not allow platform exceptions to escape through the public contract
 */
public interface ConnectivityProvider : DataLoomProvider {

    /**
     * Immutable descriptor for this connectivity provider.
     *
     * [ProviderDescriptor.type] must be [ProviderType.CONNECTIVITY].
     */
    override public val descriptor: ProviderDescriptor

    /**
     * Queries the current device-level network connectivity state.
     *
     * A successful result returns a [ConnectivitySnapshot] representing the
     * connectivity state observed at the time of the call. The snapshot
     * describes device-level connectivity, not backend server reachability.
     * A [ConnectivityStatus.AVAILABLE] status does not prove that any specific
     * backend endpoint is reachable.
     *
     * Implementations must:
     * - not perform transport requests or test backend reachability
     * - not expose platform-specific network types through the result
     * - preserve coroutine cancellation
     * - map platform failures to canonical [io.dataloom.api.error.DataLoomError]
     *   values
     *
     * @param request immutable connectivity check request carrying the
     *   execution context for this query.
     * @return [ProviderOperationResult.Success] with a [ConnectivitySnapshot]
     *   when the query succeeds, or [ProviderOperationResult.Failure] with a
     *   canonical DataLoom error.
     */
    public suspend fun currentConnectivity(
        request: ConnectivityCheckRequest,
    ): ProviderOperationResult<ConnectivitySnapshot>
}
