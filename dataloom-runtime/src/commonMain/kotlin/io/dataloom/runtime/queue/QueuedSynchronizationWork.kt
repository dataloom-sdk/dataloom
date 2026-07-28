package io.dataloom.runtime.queue

import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.SynchronizationProviderBindings

/**
 * Immutable model carrying the [SynchronizationRequest] and
 * [SynchronizationProviderBindings] resolved from a
 * [io.dataloom.api.queue.QueueEntry] for synchronization execution.
 *
 * ## Purpose
 *
 * [QueuedSynchronizationWork] is the output of
 * [QueuedSynchronizationWorkResolver.resolve] and the input to
 * [io.dataloom.runtime.execution.SynchronizationExecutionCoordinator.execute].
 * It preserves the exact request and provider bindings without modification.
 *
 * ## Preservation guarantee
 *
 * [QueuedSynchronizationExecutionHandler] forwards [request] and [bindings]
 * to the execution coordinator unchanged. No field is substituted, defaulted,
 * or reinterpreted by the handler.
 *
 * ## Construction restrictions
 *
 * Construction does not execute synchronization, read the clock, resolve
 * providers, enqueue work, or perform any I/O.
 *
 * ## Sensitive-data restrictions
 *
 * Must not expose credentials, tokens, encryption keys, personal data, or
 * raw payload bytes through [request] or [bindings].
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library, DataLoom API, and DataLoom core types only.
 * Safe for use in Kotlin Multiplatform common code.
 *
 * @param request the exact [SynchronizationRequest] carried by the queue
 *   entry. Required.
 * @param bindings the exact [SynchronizationProviderBindings] required to
 *   execute the synchronization request. Required.
 */
public class QueuedSynchronizationWork(
    /** The exact [SynchronizationRequest] carried by the queue entry. */
    public val request: SynchronizationRequest,

    /**
     * The exact [SynchronizationProviderBindings] required to execute the
     * synchronization request.
     */
    public val bindings: SynchronizationProviderBindings,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QueuedSynchronizationWork) return false
        return request == other.request && bindings == other.bindings
    }

    override fun hashCode(): Int {
        var result = request.hashCode()
        result = 31 * result + bindings.hashCode()
        return result
    }

    /**
     * Returns a safe diagnostic representation.
     *
     * Includes workflow ID and session ID from the request, and provider IDs
     * from the bindings. Does not expose credentials, tokens, encryption keys,
     * personal data, or payload bytes.
     */
    override fun toString(): String =
        "QueuedSynchronizationWork(" +
            "workflowId=${request.workflowId.value}, " +
            "sessionId=${request.sessionId.value}, " +
            "direction=${request.direction}, " +
            "storageProviderId=${bindings.storageProviderId.value}, " +
            "transportProviderId=${bindings.transportProviderId.value}" +
            ")"
}
