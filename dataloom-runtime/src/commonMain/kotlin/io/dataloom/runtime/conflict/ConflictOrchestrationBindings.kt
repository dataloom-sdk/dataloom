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
 * ## Optional selection policy
 *
 * [resolverSelectionPolicy] is optional and defaults to `null`. When `null`,
 * [resolverId] is the only selection input, exactly as before the policy
 * existed. When non-`null`, the resolver for each detected conflict is chosen
 * by [selectResolverId] with strict precedence: entity-type rule, then
 * workflow rule, then tenant rule, then [resolverId] as the global default.
 * With a policy present, a `null` [resolverId] means "no global default": a
 * conflict that no policy rule matches is reported as
 * [ConflictOrchestrationResult.ResolverNotConfigured], the same as without a
 * policy.
 *
 * The chosen ID always goes through [ConflictResolverRegistry.lookup]
 * afterwards, so application registrations still override built-ins and an
 * unknown ID is still [ConflictOrchestrationResult.ResolverNotFound].
 *
 * ## Construction
 *
 * Construction preserves the IDs and policy exactly as supplied. It performs
 * no registry lookup, no detection, and no resolution.
 *
 * ## Equality
 *
 * Equality is value-based, comparing [detectorId], [resolverId] and
 * [resolverSelectionPolicy].
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 *
 * @param detectorId the [ConflictDetectorId] of the detector to use. Required.
 * @param resolverId the [ConflictResolverId] of the resolver to use, or `null`
 *   when automatic resolution is not configured. With a
 *   [resolverSelectionPolicy] it is the global-default tier.
 * @param resolverSelectionPolicy optional entity-type / workflow / tenant
 *   rules that take precedence over [resolverId]. `null` (the default) keeps
 *   exact-ID-only selection.
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

    /**
     * Optional precedence rules consulted before [resolverId]. `null` means
     * [resolverId] alone decides, as it did before this field existed.
     */
    public val resolverSelectionPolicy: ConflictResolverSelectionPolicy? = null,
) {
    /**
     * Returns the [ConflictResolverId] to look up for [context]:
     * the [resolverSelectionPolicy]'s entity-type, workflow or tenant match
     * (most specific first), otherwise [resolverId], otherwise `null`.
     *
     * Pure: performs no registry lookup and never throws.
     */
    public fun selectResolverId(context: ConflictResolverSelectionContext): ConflictResolverId? =
        resolverSelectionPolicy?.select(context) ?: resolverId
}
