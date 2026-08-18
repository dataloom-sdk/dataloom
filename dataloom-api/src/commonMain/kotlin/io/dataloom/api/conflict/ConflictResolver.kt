package io.dataloom.api.conflict

import io.dataloom.api.identifier.ConflictResolverId

/**
 * Platform-independent contract for resolving a detected synchronization
 * conflict.
 *
 * A [ConflictResolver] receives a [ConflictResolutionRequest] and returns a
 * [ConflictResolutionDecision] describing how the conflict should be handled.
 *
 * ## Synchronous evaluation
 *
 * Resolution operates on already-available conflict information. It must be
 * synchronous and deterministic for the same input and configuration.
 * Resolution must not:
 * - Query storage.
 * - Call remote services.
 * - Refresh authentication.
 * - Wait for user input.
 * - Sleep or schedule background work.
 * - Apply changes.
 * - Persist decisions.
 * - Modify queues.
 *
 * This keeps resolution deterministic, fast, testable, multiplatform, and
 * independent of runtime infrastructure.
 *
 * ## Built-in and application-owned policies
 *
 * The DataLoom runtime provides deterministic reference policies selected by
 * exact [ConflictResolverId] values: client-wins, server-wins, the existing
 * last-write-wins placeholder, timestamp-evidence, reject, and manual-review.
 * Built-ins are never selected implicitly; the application still chooses the
 * exact resolver ID through conflict-orchestration bindings and may override a
 * reference policy by explicitly registering its own resolver under that ID.
 *
 * Applications continue to own domain-specific rules that require knowledge of
 * business schema or opaque payload content. Examples include field-level
 * merges, financial-operation rejection rules, application-version ordering,
 * and specialized user-review policy. A custom implementation uses this same
 * contract and does not replace the surrounding DataLoom orchestration,
 * durability, or event boundaries.
 *
 * ## Payload opacity
 *
 * Application resolvers may interpret [io.dataloom.api.payload.DataLoomPayload]
 * content only through application-controlled serialization outside DataLoom
 * core. Generic resolvers must not inspect opaque payload content.
 *
 * ## Version opacity
 *
 * [io.dataloom.api.payload.EntityVersion] is opaque. DataLoom does not assume
 * numeric ordering, timestamps, or ETag semantics. Application resolvers may
 * interpret version values according to their own contract.
 *
 * ## Retry boundary
 *
 * A deferred conflict is not automatically a retry decision. A failed conflict
 * resolution is not automatically retryable. Retry policy uses the canonical
 * [io.dataloom.api.error.DataLoomError]. The runtime may evaluate retry after a
 * conflict decision, but conflict resolvers must not call retry policy directly.
 *
 * ## Implementation requirements
 *
 * Implementations:
 * - Must expose a stable [id].
 * - Must not expose coroutine scopes or dispatchers.
 * - Must not access databases or network services.
 * - Must not call providers.
 * - Must not modify queues or apply changes.
 * - Must not automatically log payload content.
 * - Must not depend on Hilt, Koin, Dagger, or another DI framework as a
 *   hard requirement. Applications may use any DI framework for construction.
 * - Must not depend on platform-specific types.
 *
 * Applications may provide implementations using manual construction or any
 * dependency-injection framework they choose.
 */
public interface ConflictResolver {

    /**
     * Stable identifier for this resolver implementation.
     *
     * The value must be non-blank and meaningful to the host application.
     */
    public val id: ConflictResolverId

    /**
     * Evaluates the [SynchronizationConflict] in [request] and returns a
     * [ConflictResolutionDecision] describing how the conflict should be
     * handled.
     *
     * Resolution must be synchronous and deterministic for the same [request]
     * and configuration. It must not access storage, network, or perform any
     * I/O.
     *
     * @param request the [ConflictResolutionRequest] carrying the detected
     *   conflict to evaluate.
     * @return a [ConflictResolutionDecision] indicating the chosen resolution
     *   strategy: [ConflictResolutionDecision.UseLocal],
     *   [ConflictResolutionDecision.UseRemote],
     *   [ConflictResolutionDecision.Merge],
     *   [ConflictResolutionDecision.Defer], or
     *   [ConflictResolutionDecision.Fail].
     */
    public fun resolve(request: ConflictResolutionRequest): ConflictResolutionDecision
}
