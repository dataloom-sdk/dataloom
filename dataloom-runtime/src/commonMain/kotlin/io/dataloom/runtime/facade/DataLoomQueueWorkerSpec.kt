package io.dataloom.runtime.facade

import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.retry.RetryPolicy
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.runtime.queue.QueuedSynchronizationWorkResolver
import io.dataloom.runtime.worker.QueueWorkerConfiguration

/**
 * Immutable value carrying all required queue-worker dependencies for
 * assembly by [DataLoomBuilder].
 *
 * ## Purpose
 *
 * [DataLoomQueueWorkerSpec] groups the application-supplied dependencies
 * that [DataLoomBuilder] requires to assemble a [DataLoomQueueWorker]. When
 * this spec is absent, [DataLoom.queueWorker] is `null`.
 *
 * ## Queue-provider timeout
 *
 * [queueProviderTimeout] optionally applies the production cooperative
 * provider-timeout boundary to expired-lease recovery, atomic acquisition, and
 * every lease-guarded queue transition used by the assembled worker. The same
 * protected queue-provider instance is used for the complete worker cycle.
 *
 * A null timeout preserves the historical direct provider path. A zero timeout
 * rejects each protected queue-provider operation before delegate invocation.
 * Timed-out queue mutations remain durably ambiguous and are never replayed
 * automatically by the builder or worker runtime.
 *
 * ## Construction restrictions
 *
 * Construction performs no queue operation, no clock read, no timeout
 * execution, no identifier generation, and no synchronization work.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 */
public class DataLoomQueueWorkerSpec(
    /** Application-owned resolver that maps queue entries to synchronization work. */
    public val workResolver: QueuedSynchronizationWorkResolver,

    /** Retry policy evaluated for canonical errors during queue processing. */
    public val retryPolicy: RetryPolicy,

    /** Logical operation identifier passed to the retry policy on each evaluation request. */
    public val retryOperation: RetryOperation,

    /** Scheduling and recovery configuration for the queue worker coordinator. */
    public val configuration: QueueWorkerConfiguration,

    /** Optional timeout applied to queue-provider recovery, acquisition, and transitions. */
    public val queueProviderTimeout: SchedulingDelay?,
) {
    /**
     * Preserves the original constructor and historical direct queue-provider
     * behavior.
     */
    public constructor(
        workResolver: QueuedSynchronizationWorkResolver,
        retryPolicy: RetryPolicy,
        retryOperation: RetryOperation,
        configuration: QueueWorkerConfiguration,
    ) : this(
        workResolver = workResolver,
        retryPolicy = retryPolicy,
        retryOperation = retryOperation,
        configuration = configuration,
        queueProviderTimeout = null,
    )
}
