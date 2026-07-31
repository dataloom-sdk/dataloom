package io.dataloom.runtime.retry

import io.dataloom.api.retry.RetryOperation

/** Stable scheduler operations that may own an explicit circuit scope. */
public enum class SchedulerCircuitOperation(
    /** Stable operation identity used by provider-operation circuit scopes. */
    public val retryOperation: RetryOperation,
) {
    SCHEDULE(RetryOperation("scheduler.schedule")),
}
