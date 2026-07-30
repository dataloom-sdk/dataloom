package io.dataloom.runtime.facade

import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.retry.RetryPolicy
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.runtime.queue.QueuedSynchronizationWorkResolver
import io.dataloom.runtime.worker.QueueWorkerConfiguration

/**
 * Immutable value carrying all required queue-worker dependencies for assembly
 * by [DataLoomBuilder].
 *
 * ## Purpose
 *
 * [DataLoomQueueWorkerSpec] groups the application-supplied dependencies that
 * [DataLoomBuilder] requires to assemble a [DataLoomQueueWorker]. When this spec
 * is absent, [DataLoom.queueWorker] is `null`.
 *
 * The original four-argument constructor preserves the existing unbounded
 * scheduler-provider behavior. The five-argument constructor enables one
 * explicit provider-invocation timeout for queue-worker wake-up scheduling.
 * DataLoom uses the production cooperative coroutine timeout executor for that
 * scheduler call. It does not reuse this duration as a connection, request,
 * idle, policy, or complete-workflow timeout.
 *
 * ## Construction restrictions
 *
 * Construction performs no queue operation, no scheduler operation, no clock
 * read, no identifier generation, and no synchronization work.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API/runtime types only. Safe for use
 * in Kotlin Multiplatform common code.
 *
 * @param workResolver the application-owned resolver that maps a
 *   [io.dataloom.api.queue.QueueEntry] to a
 *   [io.dataloom.runtime.queue.QueuedSynchronizationWork]. Required.
 * @param retryPolicy the policy evaluated for each canonical error during queue
 *   processing. Required.
 * @param retryOperation the logical operation identifier passed to the retry
 *   policy evaluator. Required.
 * @param configuration the immutable scheduling and recovery configuration for
 *   the queue worker coordinator. Required.
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
) {
    private var storedSchedulerProviderTimeout: SchedulingDelay? = null

    /**
     * Optional timeout applied only to queue-worker scheduler-provider calls.
     *
     * A null value preserves the historical direct scheduler invocation. A zero
     * duration prevents the scheduler call from starting and returns the
     * canonical `SCHEDULER_PROVIDER_TIMEOUT` failure through the queue-worker
     * scheduling result.
     */
    public val schedulerProviderTimeout: SchedulingDelay?
        get() = storedSchedulerProviderTimeout

    /**
     * Creates a queue-worker specification with explicit scheduler-provider
     * timeout enforcement.
     */
    public constructor(
        workResolver: QueuedSynchronizationWorkResolver,
        retryPolicy: RetryPolicy,
        retryOperation: RetryOperation,
        configuration: QueueWorkerConfiguration,
        schedulerProviderTimeout: SchedulingDelay,
    ) : this(
        workResolver = workResolver,
        retryPolicy = retryPolicy,
        retryOperation = retryOperation,
        configuration = configuration,
    ) {
        storedSchedulerProviderTimeout = schedulerProviderTimeout
    }
}
