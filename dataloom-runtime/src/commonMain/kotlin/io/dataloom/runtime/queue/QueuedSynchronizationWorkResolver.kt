package io.dataloom.runtime.queue

import io.dataloom.api.queue.QueueEntry

/**
 * Application-owned contract for resolving a
 * [QueuedSynchronizationWork] from an acquired [QueueEntry].
 *
 * ## Purpose
 *
 * [QueuedSynchronizationWorkResolver] gives the host application control over
 * how a [QueueEntry] is mapped to a [QueuedSynchronizationWork]. The resolver
 * extracts the exact [io.dataloom.api.model.SynchronizationRequest] and
 * [io.dataloom.core.provider.SynchronizationProviderBindings] needed for the
 * synchronization coordinator to execute the entry.
 *
 * ## Responsibilities
 *
 * Implementations must:
 * - Accept the exact [QueueEntry] passed by the
 *   [QueuedSynchronizationExecutionHandler] unchanged.
 * - Return a [QueuedSynchronizationWorkResolution.Resolved] when the entry
 *   can be mapped to a valid [QueuedSynchronizationWork].
 * - Return a [QueuedSynchronizationWorkResolution.Rejected] with a canonical
 *   [io.dataloom.api.error.DataLoomError] when the entry cannot be mapped.
 *
 * ## What this resolver must not do
 *
 * Implementations must not:
 * - Access [io.dataloom.api.provider.QueueProvider] directly.
 * - Invoke the [io.dataloom.runtime.execution.SynchronizationExecutionCoordinator].
 * - Own a [kotlinx.coroutines.CoroutineScope] or select a dispatcher.
 * - Expose credentials, tokens, encryption keys, personal data, or full
 *   payloads through the resolution result.
 * - Increment retry attempt numbers or modify queue entry state.
 *
 * ## Cancellation
 *
 * [resolve] is a non-suspending function. Coroutine cancellation is not
 * applicable.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 */
public fun interface QueuedSynchronizationWorkResolver {

    /**
     * Resolves a [QueuedSynchronizationWork] from the supplied acquired
     * [entry].
     *
     * The [entry] is the exact [QueueEntry] returned by the provider. The
     * resolver must not mutate it and must not acquire a new queue entry.
     *
     * @param entry the exact acquired [QueueEntry] to resolve.
     * @return a [QueuedSynchronizationWorkResolution] describing either the
     *   resolved work or the rejection reason.
     */
    public fun resolve(entry: QueueEntry): QueuedSynchronizationWorkResolution
}
