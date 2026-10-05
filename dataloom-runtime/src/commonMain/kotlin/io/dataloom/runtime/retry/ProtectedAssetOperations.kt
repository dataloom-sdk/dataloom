package io.dataloom.runtime.retry

import io.dataloom.api.asset.AssetManifest
import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.identifier.AssetId
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.assets.AssetChunkUpload
import io.dataloom.assets.AssetTransferSessionId
import io.dataloom.assets.AssetUploadRequest
import io.dataloom.assets.AssetUploadStatus

/**
 * Immutable asset-provider-operation surface with one exact scope bound to
 * every call (`#97`, mirroring [ProtectedStorageOperations]).
 *
 * Construction validates all scopes before provider or state-store activity.
 * Every method preserves the complete provider execution and later
 * circuit-recording result.
 */
public class ProtectedAssetOperations internal constructor(
    private val adapter: CircuitBreakerAssetOperationAdapter,
    public val scopes: AssetCircuitScopes,
) {
    /** Exact descriptor of the protected asset provider. */
    public val descriptor: ProviderDescriptor
        get() = adapter.descriptor

    init {
        validate(scopes.initialization, AssetCircuitOperation.INITIALIZE)
        validate(scopes.health, AssetCircuitOperation.HEALTH)
        validate(scopes.close, AssetCircuitOperation.CLOSE)
        validate(scopes.openUpload, AssetCircuitOperation.OPEN_UPLOAD)
        validate(scopes.uploadChunk, AssetCircuitOperation.UPLOAD_CHUNK)
        validate(scopes.completeUpload, AssetCircuitOperation.COMPLETE_UPLOAD)
        validate(scopes.abortUpload, AssetCircuitOperation.ABORT_UPLOAD)
        validate(scopes.readManifest, AssetCircuitOperation.READ_MANIFEST)
        validate(scopes.readChunk, AssetCircuitOperation.READ_CHUNK)
    }

    public suspend fun initialize(
        context: ProviderInitializationContext,
    ): CircuitBreakerExecutionResult<Unit> = adapter.initialize(scopes.initialization, context)

    public suspend fun health(): CircuitBreakerExecutionResult<ProviderHealth> =
        adapter.health(scopes.health)

    public suspend fun close(): CircuitBreakerExecutionResult<Unit> =
        adapter.close(scopes.close)

    public suspend fun openUpload(
        request: AssetUploadRequest,
    ): CircuitBreakerExecutionResult<AssetUploadStatus> =
        adapter.openUpload(scopes.openUpload, request)

    public suspend fun uploadChunk(
        request: AssetChunkUpload,
    ): CircuitBreakerExecutionResult<AssetUploadStatus> =
        adapter.uploadChunk(scopes.uploadChunk, request)

    public suspend fun completeUpload(
        sessionId: AssetTransferSessionId,
    ): CircuitBreakerExecutionResult<AssetManifest> =
        adapter.completeUpload(scopes.completeUpload, sessionId)

    public suspend fun abortUpload(
        sessionId: AssetTransferSessionId,
    ): CircuitBreakerExecutionResult<Unit> =
        adapter.abortUpload(scopes.abortUpload, sessionId)

    public suspend fun readManifest(
        assetId: AssetId,
        version: Long?,
    ): CircuitBreakerExecutionResult<AssetManifest> =
        adapter.readManifest(scopes.readManifest, assetId, version)

    public suspend fun readChunk(
        assetId: AssetId,
        version: Long,
        index: Int,
    ): CircuitBreakerExecutionResult<ByteArray> =
        adapter.readChunk(scopes.readChunk, assetId, version, index)

    private fun validate(
        scope: CircuitBreakerScope,
        operation: AssetCircuitOperation,
    ) {
        require(scope.providerId == null || scope.providerId == descriptor.id) {
            "Asset protection scope provider must match the protected asset provider."
        }
        require(scope.operation == null || scope.operation == operation.retryOperation) {
            "Asset protection scope operation must match ${operation.retryOperation.value}."
        }
    }
}
