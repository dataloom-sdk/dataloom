package io.dataloom.testing.connectivity

import io.dataloom.api.connectivity.ConnectivityCheckRequest
import io.dataloom.api.connectivity.ConnectivityProvider
import io.dataloom.api.connectivity.ConnectivitySnapshot
import io.dataloom.api.provider.ProviderCapability
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.testing.provider.TestProviderLifecycleController

/**
 * Mutable in-memory [ConnectivityProvider] for deterministic common tests.
 *
 * The provider returns a mutable snapshot unless a constant failure result is
 * configured. Every connectivity check request is recorded in call order.
 *
 * @param initialSnapshot initial connectivity snapshot returned when no failure is configured.
 * @param descriptor provider descriptor exposed through [ConnectivityProvider.descriptor].
 * @param failureResult constant failure returned from [currentConnectivity] when non-null.
 * @param lifecycleController shared lifecycle controller used by provider tests.
 */
public class MutableConnectivityProvider(
    initialSnapshot: ConnectivitySnapshot,
    override val descriptor: ProviderDescriptor = defaultDescriptor(),
    private val failureResult: ProviderOperationResult<ConnectivitySnapshot>? = null,
    private val lifecycleController: TestProviderLifecycleController = TestProviderLifecycleController(),
) : ConnectivityProvider {
    private val initialSnapshotValue: ConnectivitySnapshot = initialSnapshot
    private var currentSnapshotValue: ConnectivitySnapshot = initialSnapshot
    private val recordedConnectivityRequests: MutableList<ConnectivityCheckRequest> = mutableListOf()

    /** Recorded connectivity check requests in call order. */
    public val connectivityRequests: List<ConnectivityCheckRequest>
        get() = recordedConnectivityRequests.toList()

    /**
     * Replaces the current snapshot returned by [currentConnectivity].
     *
     * @param snapshot new snapshot to return when no failure result is configured.
     */
    public fun setSnapshot(snapshot: ConnectivitySnapshot) {
        currentSnapshotValue = snapshot
    }

    override suspend fun initialize(
        context: ProviderInitializationContext,
    ): ProviderOperationResult<Unit> = lifecycleController.initialize(context)

    override suspend fun health(): ProviderOperationResult<ProviderHealth> = lifecycleController.health()

    override suspend fun close(): ProviderOperationResult<Unit> = lifecycleController.close()

    override suspend fun currentConnectivity(
        request: ConnectivityCheckRequest,
    ): ProviderOperationResult<ConnectivitySnapshot> {
        recordedConnectivityRequests += request
        return failureResult ?: ProviderOperationResult.Success(currentSnapshotValue)
    }

    /** Clears recorded requests and lifecycle recordings without changing the snapshot. */
    public fun clearRecordings() {
        recordedConnectivityRequests.clear()
        lifecycleController.clearRecordings()
    }

    /** Resets the current snapshot to the initial value and clears recordings. */
    public fun resetState() {
        currentSnapshotValue = initialSnapshotValue
        clearRecordings()
    }
}

private fun defaultDescriptor(): ProviderDescriptor = ProviderDescriptor(
    id = ProviderId("testing.connectivity.mutable"),
    name = ProviderName("MutableConnectivityProvider"),
    type = ProviderType.CONNECTIVITY,
    version = ProviderVersion("1.0.0"),
    capabilities = setOf(
        ProviderCapability("testing"),
        ProviderCapability("mutable"),
    ),
)
