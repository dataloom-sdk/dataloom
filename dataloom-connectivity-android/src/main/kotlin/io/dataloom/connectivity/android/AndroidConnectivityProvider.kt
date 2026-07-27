package io.dataloom.connectivity.android

import android.annotation.TargetApi
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
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
 * Performs one bounded synchronous query of the current platform network
 * state. It does not poll, register callbacks, expose network identifiers, or
 * own a coroutine scope.
 *
 * On API 23 and newer, usable internet is reported as [ConnectivityStatus.AVAILABLE]
 * only when both INTERNET and VALIDATED capabilities are present. On API 21–22,
 * Android cannot expose validated-network capability; a connected legacy
 * network is therefore reported conservatively as [ConnectivityStatus.LIMITED].
 */
public class AndroidConnectivityProvider internal constructor(
    context: Context,
    private val sdkInt: Int,
) : ConnectivityProvider {

    public constructor(context: Context) : this(context, Build.VERSION.SDK_INT)

    private val applicationContext: Context = context.applicationContext ?: context

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

    override suspend fun currentConnectivity(
        request: ConnectivityCheckRequest,
    ): ProviderOperationResult<ConnectivitySnapshot> {
        return try {
            ProviderOperationResult.Success(resolveSnapshot())
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            ProviderOperationResult.Failure(ConnectivityProviderError.platformFailure())
        }
    }

    private fun resolveSnapshot(): ConnectivitySnapshot {
        val connectivityManager =
            applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return unknownSnapshot()

        return if (sdkInt >= Build.VERSION_CODES.M) {
            resolveModernSnapshot(connectivityManager)
        } else {
            resolveLegacySnapshot(connectivityManager)
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    private fun resolveModernSnapshot(
        connectivityManager: ConnectivityManager,
    ): ConnectivitySnapshot {
        val activeNetwork = connectivityManager.activeNetwork
            ?: return unavailableSnapshot()
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            ?: return unknownSnapshot()

        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val isNotMetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

        return ConnectivitySnapshot(
            status = if (hasInternet && isValidated) {
                ConnectivityStatus.AVAILABLE
            } else {
                ConnectivityStatus.LIMITED
            },
            isMetered = !isNotMetered,
        )
    }

    @Suppress("DEPRECATION")
    private fun resolveLegacySnapshot(
        connectivityManager: ConnectivityManager,
    ): ConnectivitySnapshot {
        val activeNetworkInfo = connectivityManager.activeNetworkInfo
            ?: return unavailableSnapshot()

        return ConnectivitySnapshot(
            status = if (activeNetworkInfo.isConnected) {
                ConnectivityStatus.LIMITED
            } else {
                ConnectivityStatus.UNAVAILABLE
            },
            isMetered = connectivityManager.isActiveNetworkMetered,
        )
    }

    private fun unknownSnapshot(): ConnectivitySnapshot = ConnectivitySnapshot(
        status = ConnectivityStatus.UNKNOWN,
        isMetered = null,
    )

    private fun unavailableSnapshot(): ConnectivitySnapshot = ConnectivitySnapshot(
        status = ConnectivityStatus.UNAVAILABLE,
        isMetered = null,
    )
}
