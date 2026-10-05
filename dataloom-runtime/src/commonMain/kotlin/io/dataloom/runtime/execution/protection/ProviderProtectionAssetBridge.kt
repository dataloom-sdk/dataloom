package io.dataloom.runtime.execution.protection

import io.dataloom.api.asset.AssetManifest
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.error.safeDiagnosticString
import io.dataloom.api.identifier.AssetId
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.retry.RetryOperation
import io.dataloom.assets.AssetChunkSizeBounds
import io.dataloom.assets.AssetChunkUpload
import io.dataloom.assets.AssetProvider
import io.dataloom.assets.AssetTransferSessionId
import io.dataloom.assets.AssetUploadRequest
import io.dataloom.assets.AssetUploadStatus
import io.dataloom.runtime.retry.CircuitBreakerExecutionResult
import io.dataloom.runtime.retry.CircuitBreakerRejectionReason
import io.dataloom.runtime.retry.CircuitProtectedOperationResult
import io.dataloom.runtime.retry.ProtectedAssetOperations

/**
 * Transparent [AssetProvider] decorator that routes every call through
 * [ProtectedAssetOperations] -- the same circuit-breaker machinery already
 * protecting [io.dataloom.api.storage.StorageProvider] and
 * [io.dataloom.api.transport.TransportProvider] calls (`#94`, `#97`) -- so
 * [io.dataloom.assets.AssetTransferEngine] can be constructed over the result
 * exactly as it is over any other [AssetProvider], with no awareness that
 * protection is configured.
 *
 * A rejected or persistence-failed circuit permission, and an unconfirmed
 * circuit-state recording after a real provider failure, are reported as an
 * ordinary [ProviderOperationResult.Failure] with
 * [Recoverability.RECOVERABLE] (never [Recoverability.NON_RECOVERABLE]):
 * [io.dataloom.assets.AssetTransferEngine] treats a recoverable failure as
 * [io.dataloom.assets.AssetTransferOutcome.Interrupted], leaving the session
 * resumable, rather than failing it terminally for a transient circuit
 * condition.
 */
public class ProviderProtectionAssetBridge(
    private val protectedOperations: ProtectedAssetOperations,
    /**
     * [ProtectedAssetOperations] has no synchronous accessor for
     * [AssetChunkSizeBounds] (every method is circuit-protected), so this
     * bridge is constructed with the bounds read once from the real provider
     * before wrapping -- see [io.dataloom.runtime.facade.DataLoomBuilder].
     */
    override val chunkSizeBounds: AssetChunkSizeBounds,
) : AssetProvider {

    override val descriptor: ProviderDescriptor
        get() = protectedOperations.descriptor

    override suspend fun initialize(
        context: ProviderInitializationContext,
    ): ProviderOperationResult<Unit> =
        adapt(AssetCircuitOperationLabel.INITIALIZE, protectedOperations.initialize(context))

    override suspend fun health(): ProviderOperationResult<ProviderHealth> =
        adapt(AssetCircuitOperationLabel.HEALTH, protectedOperations.health())

    override suspend fun close(): ProviderOperationResult<Unit> =
        adapt(AssetCircuitOperationLabel.CLOSE, protectedOperations.close())

    override suspend fun openUpload(
        request: AssetUploadRequest,
    ): ProviderOperationResult<AssetUploadStatus> =
        adapt(AssetCircuitOperationLabel.OPEN_UPLOAD, protectedOperations.openUpload(request))

    override suspend fun uploadChunk(
        request: AssetChunkUpload,
    ): ProviderOperationResult<AssetUploadStatus> =
        adapt(AssetCircuitOperationLabel.UPLOAD_CHUNK, protectedOperations.uploadChunk(request))

    override suspend fun completeUpload(
        sessionId: AssetTransferSessionId,
    ): ProviderOperationResult<AssetManifest> =
        adapt(AssetCircuitOperationLabel.COMPLETE_UPLOAD, protectedOperations.completeUpload(sessionId))

    override suspend fun abortUpload(
        sessionId: AssetTransferSessionId,
    ): ProviderOperationResult<Unit> =
        adapt(AssetCircuitOperationLabel.ABORT_UPLOAD, protectedOperations.abortUpload(sessionId))

    override suspend fun readManifest(
        assetId: AssetId,
        version: Long?,
    ): ProviderOperationResult<AssetManifest> =
        adapt(AssetCircuitOperationLabel.READ_MANIFEST, protectedOperations.readManifest(assetId, version))

    override suspend fun readChunk(
        assetId: AssetId,
        version: Long,
        index: Int,
    ): ProviderOperationResult<ByteArray> =
        adapt(AssetCircuitOperationLabel.READ_CHUNK, protectedOperations.readChunk(assetId, version, index))

    private fun <T> adapt(
        operation: RetryOperation,
        result: CircuitBreakerExecutionResult<T>,
    ): ProviderOperationResult<T> = when (result) {
        is CircuitBreakerExecutionResult.Executed -> when (val operationResult = result.operationResult) {
            is CircuitProtectedOperationResult.Success -> ProviderOperationResult.Success(operationResult.value)
            is CircuitProtectedOperationResult.Failure -> ProviderOperationResult.Failure(operationResult.error)
            is CircuitProtectedOperationResult.NonCircuitFailure -> ProviderOperationResult.Failure(operationResult.error)
        }
        is CircuitBreakerExecutionResult.Rejected ->
            ProviderOperationResult.Failure(AssetCircuitErrors.circuitRejected(operation, result.reason))
        is CircuitBreakerExecutionResult.PermissionPersistenceFailure ->
            ProviderOperationResult.Failure(result.error)
        CircuitBreakerExecutionResult.PermissionContentionLimitReached ->
            ProviderOperationResult.Failure(AssetCircuitErrors.permissionContention(operation))
    }

    private object AssetCircuitOperationLabel {
        val INITIALIZE = RetryOperation("asset.initialize")
        val HEALTH = RetryOperation("asset.health")
        val CLOSE = RetryOperation("asset.close")
        val OPEN_UPLOAD = RetryOperation("asset.open-upload")
        val UPLOAD_CHUNK = RetryOperation("asset.upload-chunk")
        val COMPLETE_UPLOAD = RetryOperation("asset.complete-upload")
        val ABORT_UPLOAD = RetryOperation("asset.abort-upload")
        val READ_MANIFEST = RetryOperation("asset.read-manifest")
        val READ_CHUNK = RetryOperation("asset.read-chunk")
    }
}

/** Canonical, always-recoverable errors for a rejected or contended asset circuit permission. */
private object AssetCircuitErrors {
    fun circuitRejected(
        operation: RetryOperation,
        reason: CircuitBreakerRejectionReason,
    ): DataLoomError = Error(
        code = ErrorCode("ASSET_PROVIDER_CIRCUIT_${reason.name}"),
        // Deliberately always RECOVERABLE (unlike the storage/transport bridges'
        // equivalent mapping): AssetTransferEngine.onError treats a
        // NON_RECOVERABLE failure as terminal -- failing the session and running
        // provider abort/sink-discard cleanup. A rejected circuit permission
        // never reached the provider, so there is nothing to clean up and no
        // reason to destroy an otherwise-resumable session over a transient
        // circuit or clock condition; see this class's own KDoc.
        recoverability = Recoverability.RECOVERABLE,
        message = "Circuit permission rejected ${operation.value} before asset-provider execution.",
    )

    fun permissionContention(operation: RetryOperation): DataLoomError = Error(
        code = ErrorCode("ASSET_PROVIDER_CIRCUIT_PERMISSION_CONTENTION"),
        recoverability = Recoverability.RECOVERABLE,
        message = "Circuit permission contention prevented asset-provider $operation execution.",
    )

    private data class Error(
        override val code: ErrorCode,
        override val recoverability: Recoverability,
        override val message: String,
        override val category: ErrorCategory = ErrorCategory.PROVIDER,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val cause: Throwable? = null,
    ) : DataLoomError {
        override fun toString(): String = safeDiagnosticString()
    }
}
