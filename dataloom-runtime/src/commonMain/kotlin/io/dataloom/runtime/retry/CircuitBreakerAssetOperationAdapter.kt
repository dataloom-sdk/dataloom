package io.dataloom.runtime.retry

import io.dataloom.api.asset.AssetManifest
import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.identifier.AssetId
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.assets.AssetChunkUpload
import io.dataloom.assets.AssetProvider
import io.dataloom.assets.AssetTransferSessionId
import io.dataloom.assets.AssetUploadRequest
import io.dataloom.assets.AssetUploadStatus

/**
 * Applies explicit durable circuit permission to every [AssetProvider]
 * operation while preserving the complete execution and recording result
 * (`#97`, mirroring [CircuitBreakerStorageOperationAdapter]).
 *
 * This adapter deliberately does not implement [AssetProvider]. A plain
 * provider result cannot preserve both an already-executed durable mutation
 * and a later circuit-state persistence failure without losing
 * replay-critical evidence.
 */
public class CircuitBreakerAssetOperationAdapter(
    private val assetProvider: AssetProvider,
    executionGate: CircuitBreakerExecutionGate,
    failureClassifier: CircuitBreakerFailureClassifier =
        DefaultCircuitBreakerFailureClassifier,
) {
    private val providerOperationAdapter = CircuitBreakerProviderOperationAdapter(
        executionGate = executionGate,
        failureClassifier = failureClassifier,
    )

    /** Exact descriptor of the protected asset provider. */
    public val descriptor: ProviderDescriptor
        get() = assetProvider.descriptor

    public suspend fun initialize(
        scope: CircuitBreakerScope,
        context: ProviderInitializationContext,
    ): CircuitBreakerExecutionResult<Unit> = execute(scope, AssetCircuitOperation.INITIALIZE) {
        assetProvider.initialize(context)
    }

    public suspend fun health(
        scope: CircuitBreakerScope,
    ): CircuitBreakerExecutionResult<ProviderHealth> = execute(scope, AssetCircuitOperation.HEALTH) {
        assetProvider.health()
    }

    public suspend fun close(
        scope: CircuitBreakerScope,
    ): CircuitBreakerExecutionResult<Unit> = execute(scope, AssetCircuitOperation.CLOSE) {
        assetProvider.close()
    }

    public suspend fun openUpload(
        scope: CircuitBreakerScope,
        request: AssetUploadRequest,
    ): CircuitBreakerExecutionResult<AssetUploadStatus> = execute(scope, AssetCircuitOperation.OPEN_UPLOAD) {
        assetProvider.openUpload(request)
    }

    public suspend fun uploadChunk(
        scope: CircuitBreakerScope,
        request: AssetChunkUpload,
    ): CircuitBreakerExecutionResult<AssetUploadStatus> = execute(scope, AssetCircuitOperation.UPLOAD_CHUNK) {
        assetProvider.uploadChunk(request)
    }

    public suspend fun completeUpload(
        scope: CircuitBreakerScope,
        sessionId: AssetTransferSessionId,
    ): CircuitBreakerExecutionResult<AssetManifest> = execute(scope, AssetCircuitOperation.COMPLETE_UPLOAD) {
        assetProvider.completeUpload(sessionId)
    }

    public suspend fun abortUpload(
        scope: CircuitBreakerScope,
        sessionId: AssetTransferSessionId,
    ): CircuitBreakerExecutionResult<Unit> = execute(scope, AssetCircuitOperation.ABORT_UPLOAD) {
        assetProvider.abortUpload(sessionId)
    }

    public suspend fun readManifest(
        scope: CircuitBreakerScope,
        assetId: AssetId,
        version: Long?,
    ): CircuitBreakerExecutionResult<AssetManifest> = execute(scope, AssetCircuitOperation.READ_MANIFEST) {
        assetProvider.readManifest(assetId, version)
    }

    public suspend fun readChunk(
        scope: CircuitBreakerScope,
        assetId: AssetId,
        version: Long,
        index: Int,
    ): CircuitBreakerExecutionResult<ByteArray> = execute(scope, AssetCircuitOperation.READ_CHUNK) {
        assetProvider.readChunk(assetId, version, index)
    }

    private suspend fun <T> execute(
        scope: CircuitBreakerScope,
        operation: AssetCircuitOperation,
        block: suspend () -> io.dataloom.api.provider.ProviderOperationResult<T>,
    ): CircuitBreakerExecutionResult<T> {
        require(scope.providerId == null || scope.providerId == descriptor.id) {
            "Asset circuit scope provider must match the protected asset provider."
        }
        require(scope.operation == null || scope.operation == operation.retryOperation) {
            "Asset circuit scope operation must match ${operation.retryOperation.value}."
        }
        return providerOperationAdapter.execute(scope, block)
    }
}
