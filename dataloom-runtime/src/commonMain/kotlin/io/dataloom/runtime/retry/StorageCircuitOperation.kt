package io.dataloom.runtime.retry

import io.dataloom.api.retry.RetryOperation

/** Stable operation identities for storage provider circuit scopes. */
public enum class StorageCircuitOperation(
    public val retryOperation: RetryOperation,
) {
    INITIALIZE(RetryOperation("storage.initialize")),
    HEALTH(RetryOperation("storage.health")),
    CLOSE(RetryOperation("storage.close")),
    READ_OUTBOUND_CHANGES(RetryOperation("storage.read-outbound-changes")),
    APPLY_INBOUND_CHANGES(RetryOperation("storage.apply-inbound-changes")),
    ACKNOWLEDGE_OUTBOUND_CHANGES(RetryOperation("storage.acknowledge-outbound-changes")),
    READ_CHECKPOINT(RetryOperation("storage.read-checkpoint")),
    WRITE_CHECKPOINT(RetryOperation("storage.write-checkpoint")),
    READ_LOCAL_CONFLICT_CANDIDATE(RetryOperation("storage.read-local-conflict-candidate")),
}
