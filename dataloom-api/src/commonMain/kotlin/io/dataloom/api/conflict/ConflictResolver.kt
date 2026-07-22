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
 * ## Application ownership
 *
 * DataLoom coordinates the conflict-resolution workflow, but the host
 * application owns the domain-specific rules that determine the correct
 * resolution. Examples of application-owned policies include:
 *
 * - Client wins
 * - Server wins
 * - Latest application version wins
 * - Merge selected fields
 * - Reject conflicting financial operations
 * - Require user review
 * - Custom domain resolver
 *
 * DataLoom does not assume one policy is correct for every application.
 * No built-in resolver strategy is provided in this release.
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
 * [io.dataloom.api.error.DataLoomError]. The future runtime may evaluate retry
 * after a conflict decision. Conflict resolvers must not call retry policy
 * directly.
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
