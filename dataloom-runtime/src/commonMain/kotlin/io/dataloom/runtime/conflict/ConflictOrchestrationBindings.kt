package io.dataloom.runtime.conflict

import io.dataloom.api.identifier.ConflictDetectorId
import io.dataloom.api.identifier.ConflictResolverId

/**
 * Immutable binding model that associates a required [ConflictDetectorId] with
 * an optional [ConflictResolverId] for a single orchestration invocation.
 *
 * ## Purpose
 *
 * [ConflictOrchestrationBindings] declares which detector and, optionally,
 * which resolver the [SynchronizationConflictOrchestrator] must use for a
 * specific detection and resolution cycle. Bindings are application-controlled
 * and evaluated at runtime using explicit ID-based lookup.
 *
 * ## Required detector
 *
 * [detectorId] is required. The orchestrator performs exactly one detector
 * lookup using this value. When the detector is absent from the registry, the
 * orchestrator returns [ConflictOrchestrationResult.DetectorNotFound] without
 * invoking any other component.
 *
 * ## Optional resolver
 *
 * [resolverId] is optional. When `null`:
 * - Detection is still performed.
 * - Conflicts can be reported via [ConflictOrchestrationResult.ResolverNotConfigured].
 * - Automatic resolution is not configured; no resolver is invoked.
 * - DataLoom does not silently choose a resolver as a fallback.
 *
 * When non-`null`, the orchestrator performs exactly one resolver lookup using
 * this value. When the resolver is absent from the registry, the orchestrator
 * returns [ConflictOrchestrationResult.ResolverNotFound].
 *
 * ## Construction
 *
 * Construction preserves both IDs exactly as supplied. It performs no
 * registry lookup, no detection, and no resolution.
 *
 * ## Equality
 *
 * Equality is value-based, comparing [detectorId] and [resolverId].
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 *
 * @param detectorId the [ConflictDetectorId] of the detector to use. Required.
 * @param resolverId the [ConflictResolverId] of the resolver to use, or `null`
 *   when automatic resolution is not configured.
 */
public data class ConflictOrchestrationBindings(
    /** The [ConflictDetectorId] identifying the detector to invoke. */
    public val detectorId: ConflictDetectorId,

    /**
     * The [ConflictResolverId] identifying the resolver to invoke, or `null`
     * when automatic resolution is not configured for this binding.
     *
     * When `null`, a detected conflict results in
     * [ConflictOrchestrationResult.ResolverNotConfigured].
     */
    public val resolverId: ConflictResolverId?,
)
