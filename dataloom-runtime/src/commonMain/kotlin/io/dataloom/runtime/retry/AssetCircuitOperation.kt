package io.dataloom.runtime.retry

import io.dataloom.api.retry.RetryOperation

/** Stable operation identities for asset-provider circuit scopes (`#97`). */
public enum class AssetCircuitOperation(
    public val retryOperation: RetryOperation,
) {
    INITIALIZE(RetryOperation("asset.initialize")),
    HEALTH(RetryOperation("asset.health")),
    CLOSE(RetryOperation("asset.close")),
    OPEN_UPLOAD(RetryOperation("asset.open-upload")),
    UPLOAD_CHUNK(RetryOperation("asset.upload-chunk")),
    COMPLETE_UPLOAD(RetryOperation("asset.complete-upload")),
    ABORT_UPLOAD(RetryOperation("asset.abort-upload")),
    READ_MANIFEST(RetryOperation("asset.read-manifest")),
    READ_CHUNK(RetryOperation("asset.read-chunk")),
}
