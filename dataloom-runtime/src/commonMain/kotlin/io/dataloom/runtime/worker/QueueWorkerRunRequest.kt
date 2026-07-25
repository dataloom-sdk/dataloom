package io.dataloom.runtime.worker

import io.dataloom.api.queue.ExpiredLeaseRecoveryRequest
import io.dataloom.runtime.queue.QueueProcessingRequest

/**
 * Immutable request supplied to [QueueWorkerCoordinator.run].
 *
 * [QueueWorkerRunRequest] carries the exact
 * [io.dataloom.runtime.queue.QueueProcessingRequest] and, when expired-lease
 * recovery is enabled, the exact
 * [io.dataloom.api.queue.ExpiredLeaseRecoveryRequest].
 *
 * ## Construction behaviour
 *
 * Construction preserves the caller-supplied requests exactly. No recovery
 * operation, queue processing, scheduling, or identifier generation is
 * performed during construction.
 *
 * ## Recovery presence
 *
 * When [QueueWorkerConfiguration.recoverExpiredLeasesBeforeProcessing] is
 * `true`, [recoveryRequest] must be non-null. The coordinator validates this
 * condition at the start of [QueueWorkerCoordinator.run] and throws
 * [IllegalArgumentException] when recovery is enabled but [recoveryRequest]
 * is absent.
 *
 * When [QueueWorkerConfiguration.recoverExpiredLeasesBeforeProcessing] is
 * `false`, [recoveryRequest] is not required and no recovery operation is
 * invoked.
 *
 * ## What this request must not carry
 *
 * The caller must not manufacture:
 * - recovery timestamps
 * - consumer identifiers
 * - lease identifiers
 * - acquisition limits
 * - schedule identifiers
 *
 * All of these are supplied by the caller-constructed sub-requests.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 *
 * @param processingRequest required caller-supplied queue processing request
 *   forwarded unchanged to
 *   [io.dataloom.runtime.queue.DurableQueueExecutionProcessor.process].
 * @param recoveryRequest optional caller-supplied expired-lease recovery
 *   request. Required when
 *   [QueueWorkerConfiguration.recoverExpiredLeasesBeforeProcessing] is `true`.
 *   Must be `null` when recovery is disabled.
 */
public data class QueueWorkerRunRequest(
    /**
     * Required caller-supplied queue processing request.
     *
     * Forwarded unchanged to
     * [io.dataloom.runtime.queue.DurableQueueExecutionProcessor.process].
     */
    public val processingRequest: QueueProcessingRequest,

    /**
     * Optional caller-supplied expired-lease recovery request.
     *
     * Required when [QueueWorkerConfiguration.recoverExpiredLeasesBeforeProcessing]
     * is `true`. Must be `null` when recovery is disabled.
     */
    public val recoveryRequest: ExpiredLeaseRecoveryRequest?,
)
