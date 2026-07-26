package io.dataloom.connectivity.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.dataloom.api.connectivity.ConnectivityCheckRequest
import io.dataloom.api.connectivity.ConnectivityProvider
import io.dataloom.api.connectivity.ConnectivitySnapshot
import io.dataloom.api.connectivity.ConnectivityStatus
import io.dataloom.api.provider.ProviderCapability
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.connectivity.android.internal.ConnectivityProviderError
import java.util.concurrent.CancellationException

/**
 * Android [ConnectivityProvider] backed by [ConnectivityManager].
 *
 * Performs one bounded synchronous query of the platform [ConnectivityManager]
 * to determine the current device-level network state. This implementation
 * does not poll, observe callbacks, register a [ConnectivityManager.NetworkCallback],
 * hold a coroutine scope, or perform any background work.
 *
 * ## Status mapping
 *
 * | ConnectivityManager state                                | [ConnectivityStatus] |
 * |----------------------------------------------------------|----------------------|
 * | ConnectivityManager unavailable                          | UNKNOWN              |
 * | No active network                                        | UNAVAILABLE          |
 * | Active network, capabilities unavailable                 | UNKNOWN              |
 * | NET_CAPABILITY_INTERNET + NET_CAPABILITY_VALIDATED       | AVAILABLE            |
 * | NET_CAPABILITY_INTERNET without NET_CAPABILITY_VALIDATED | LIMITED              |
 * | No NET_CAPABILITY_INTERNET                               | LIMITED              |
 *
 * ## Metering
 *
 * `isMetered` is `false` when [NetworkCapabilities.NET_CAPABILITY_NOT_METERED]
 * is present on the active network, `true` when it is absent, and `null` when
 * capabilities cannot be obtained.
 *
 * ## Privacy
 *
 * No SSID, carrier name, IP address, interface name, VPN detail, MAC address,
 * or platform network handle is included in the returned [ConnectivitySnapshot].
 *
 * ## Thread safety
 *
 * This implementation is safe to call from any thread. [ConnectivityManager]
 * queries are synchronous and do not block. The provider holds no mutable state.
 *
 * ## Cancellation
 *
 * Coroutine cancellation propagates normally. The synchronous system-service
 * call inside [currentConnectivity] does not block indefinitely.
 *
 * @param context Android application context or a context whose lifecycle
 *   equals or exceeds the provider usage period.
 */
public class AndroidConnectivityProvider(
    private val context: Context,
) : ConnectivityProvider {

    /**
     * Immutable descriptor for this connectivity provider.
     *
     * [ProviderDescriptor.type] is [ProviderType.CONNECTIVITY].
     */
    override val descriptor: ProviderDescriptor = ProviderDescriptor(
        id = ProviderId("io.dataloom.connectivity.android"),
        name = ProviderName("AndroidConnectivityProvider"),
        type = ProviderType.CONNECTIVITY,
        version = ProviderVersion("1.0.0"),
        capabilities = setOf(ProviderCapability("bounded-query")),
    )

    override suspend fun initialize(
        context: ProviderInitializationContext,
    ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

    override suspend fun health(): ProviderOperationResult<ProviderHealth> =
        ProviderOperationResult.Success(ProviderHealth(status = ProviderHealthStatus.HEALTHY))

    override suspend fun close(): ProviderOperationResult<Unit> =
        ProviderOperationResult.Success(Unit)

    /**
     * Performs one bounded query of [ConnectivityManager] and returns the
     * current device-level network connectivity state.
     *
     * Does not perform transport requests, register callbacks, or poll.
     * Maps [ConnectivityManager] state to canonical [ConnectivitySnapshot].
     * Platform failures are mapped to [ProviderOperationResult.Failure] with a
     * canonical [io.dataloom.api.error.DataLoomError]; they do not escape the
     * public contract.
     *
     * @param request immutable connectivity check request.
     * @return [ProviderOperationResult.Success] with a [ConnectivitySnapshot]
     *   on success, or [ProviderOperationResult.Failure] on platform failure.
     */
    override suspend fun currentConnectivity(
        request: ConnectivityCheckRequest,
    ): ProviderOperationResult<ConnectivitySnapshot> {
        return try {
            ProviderOperationResult.Success(resolveSnapshot())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ProviderOperationResult.Failure(ConnectivityProviderError.platformFailure(cause = e))
        }
    }

    private fun resolveSnapshot(): ConnectivitySnapshot {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return ConnectivitySnapshot(
                    status = ConnectivityStatus.UNKNOWN,
                    isMetered = null,
                )

        val activeNetwork = connectivityManager.activeNetwork
            ?: return ConnectivitySnapshot(
                status = ConnectivityStatus.UNAVAILABLE,
                isMetered = null,
            )

        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            ?: return ConnectivitySnapshot(
                status = ConnectivityStatus.UNKNOWN,
                isMetered = null,
            )

        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val isNotMetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

        val status = when {
            hasInternet && isValidated -> ConnectivityStatus.AVAILABLE
            else -> ConnectivityStatus.LIMITED
        }

        return ConnectivitySnapshot(
            status = status,
            isMetered = !isNotMetered,
        )
    }
}
