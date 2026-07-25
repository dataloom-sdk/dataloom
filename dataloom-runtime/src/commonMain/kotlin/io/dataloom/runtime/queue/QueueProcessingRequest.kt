package io.dataloom.runtime.queue

import io.dataloom.api.queue.QueueAcquireRequest

/**
 * Thin, immutable wrapper around a [QueueAcquireRequest] supplied by the
 * caller to [DurableQueueExecutionProcessor.process].
 *
 * [QueueProcessingRequest] preserves the caller-supplied [acquireRequest]
 * exactly. The [DurableQueueExecutionProcessor] forwards it unchanged to
 * [io.dataloom.api.provider.QueueProvider.acquire].
 *
 * ## Constraints
 *
 * - [acquireRequest] is required.
 * - No defaults are supplied.
 * - No identifiers are generated.
 * - Construction does not acquire any queue entries.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 *
 * @param acquireRequest required caller-supplied acquisition request. Preserved
 *   verbatim and forwarded to [io.dataloom.api.provider.QueueProvider.acquire].
 */
public data class QueueProcessingRequest(
    /**
     * Required caller-supplied acquisition request.
     *
     * Forwarded unchanged to [io.dataloom.api.provider.QueueProvider.acquire].
     */
    public val acquireRequest: QueueAcquireRequest,
)
