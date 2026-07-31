package io.dataloom.runtime.retry

import io.dataloom.api.retry.RetryOperation

/** Stable operation identities for transport provider circuit scopes. */
public enum class TransportCircuitOperation(
    public val retryOperation: RetryOperation,
) {
    INITIALIZE(RetryOperation("transport.initialize")),
    HEALTH(RetryOperation("transport.health")),
    CLOSE(RetryOperation("transport.close")),
    PUSH_CHANGES(RetryOperation("transport.push-changes")),
    PULL_CHANGES(RetryOperation("transport.pull-changes")),
}
