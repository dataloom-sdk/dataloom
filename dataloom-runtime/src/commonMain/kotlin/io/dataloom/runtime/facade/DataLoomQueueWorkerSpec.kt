package io.dataloom.runtime.facade

import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.retry.RetryPolicy
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
 * ## Construction restrictions
 *
 * Construction performs no queue operation, no clock read, no identifier
 * generation, and no synchronization work.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 *
 * @param workResolver the application-owned resolver that maps a
 *   [io.dataloom.api.queue.QueueEntry] to a
 *   [io.dataloom.runtime.queue.QueuedSynchronizationWork]. Required.
 * @param retryPolicy the policy evaluated for each canonical error during
 *   queue processing. Required.
 * @param retryOperation the logical operation identifier passed to the retry
 *   policy evaluator. Required.
 * @param configuration the immutable scheduling and recovery configuration
 *   for the queue worker coordinator. Required.
 */
public class DataLoomQueueWorkerSpec(
    /** Application-owned resolver that maps queue entries to synchronization work. */
    public val workResolver: QueuedSynchronizationWorkResolver,

    /** Retry policy evaluated for canonical errors during queue processing. */
    public val retryPolicy: RetryPolicy,

    /**
     * Logical operation identifier passed to the retry policy on each
     * evaluation request.
     */
    public val retryOperation: RetryOperation,

    /** Scheduling and recovery configuration for the queue worker coordinator. */
    public val configuration: QueueWorkerConfiguration,
)
