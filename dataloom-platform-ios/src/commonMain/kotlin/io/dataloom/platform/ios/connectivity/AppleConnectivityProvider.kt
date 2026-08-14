package io.dataloom.platform.ios.connectivity

import io.dataloom.api.connectivity.ConnectivityCheckRequest
import io.dataloom.api.connectivity.ConnectivityProvider
import io.dataloom.api.connectivity.ConnectivitySnapshot
import io.dataloom.api.provider.ProviderCapability
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.platform.ios.connectivity.internal.ConnectivityProviderError
import io.dataloom.platform.ios.connectivity.internal.NetworkPathObservation
import io.dataloom.platform.ios.connectivity.internal.classifyPath
import io.dataloom.platform.ios.connectivity.internal.currentNetworkPathObservation
import kotlin.coroutines.cancellation.CancellationException

/**
 * iOS [ConnectivityProvider] backed by `NWPathMonitor`.
 *
 * Performs one bounded synchronous query of the current platform network
 * path. It does not poll, register a long-lived callback, expose network
 * identifiers, or own a coroutine scope -- each call to [currentConnectivity]
 * starts and immediately tears down its own `NWPathMonitor` instance.
 *
 * Streaming connectivity observation (`Flow<ConnectivitySnapshot>`) is
 * explicitly out of scope, matching
 * [ConnectivityProvider]'s documented deferred features.
 */
public class AppleConnectivityProvider internal constructor(
    private val pathObserver: () -> NetworkPathObservation,
) : ConnectivityProvider {

    public constructor() : this(pathObserver = ::currentNetworkPathObservation)

    override val descriptor: ProviderDescriptor = ProviderDescriptor(
        id = ProviderId("io.dataloom.connectivity.ios"),
        name = ProviderName("AppleConnectivityProvider"),
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
            ProviderOperationResult.Success(classifyPath(pathObserver()))
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            ProviderOperationResult.Failure(ConnectivityProviderError.platformFailure())
        }
    }
}
