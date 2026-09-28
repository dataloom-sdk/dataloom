package io.dataloom.runtime.observation.retry

import io.dataloom.api.conflict.ConflictAdministrationRequest
import io.dataloom.api.conflict.ConflictQuarantineReleaseRequest
import io.dataloom.api.conflict.ConflictResolutionDecision
import io.dataloom.api.conflict.SynchronizationConflict
import io.dataloom.api.conflict.UnresolvedConflictReason
import io.dataloom.api.identifier.ConflictResolverId
import io.dataloom.api.time.DataLoomClock
import io.dataloom.runtime.conflict.ConflictAdministrationCoordinator
import io.dataloom.runtime.conflict.ConflictAdministrationResult
import io.dataloom.runtime.conflict.ConflictOrchestrationRequest
import io.dataloom.runtime.conflict.ConflictOrchestrationResult
import io.dataloom.runtime.conflict.ConflictQuarantineReleaseResult
import io.dataloom.runtime.conflict.ConflictResolverSelectionContext
import io.dataloom.runtime.conflict.ConflictResolverSelectionTier
import io.dataloom.runtime.conflict.SynchronizationConflictOrchestrator

/**
 * Adds bounded conflict-engine telemetry to conflict detection and
 * resolution, through the same [BoundedRetryCircuitTelemetry] pipeline retry
 * and circuit-breaker facts already use -- see that class and
 * [RetryCircuitTelemetryEvent] for the delivery, buffering, and exporter
 * model this reuses unchanged.
 *
 * ## Emitted signals
 *
 * Exactly one [RetryCircuitTelemetrySignal.CONFLICT_DETECTED] event per actual
 * detected conflict (never for [ConflictOrchestrationResult.DetectorNotFound]
 * or [ConflictOrchestrationResult.NoConflict], since no conflict occurred),
 * plus exactly one outcome-specific event:
 *
 * - [ConflictOrchestrationResult.Resolved] with
 *   [ConflictResolutionDecision.UseLocal]/[ConflictResolutionDecision.UseRemote]/[ConflictResolutionDecision.Merge]
 *   -> [RetryCircuitTelemetrySignal.CONFLICT_RESOLVED], dimensioned by the
 *   resolver ID (a closed set for the lifetime of one running instance --
 *   see [RetryCircuitTelemetryEvent.conflictResolverId]).
 * - [ConflictOrchestrationResult.Resolved] with [ConflictResolutionDecision.Defer]
 *   -> [RetryCircuitTelemetrySignal.CONFLICT_DEFERRED], same dimension.
 * - [ConflictOrchestrationResult.Resolved] with [ConflictResolutionDecision.Fail]
 *   -> [RetryCircuitTelemetrySignal.CONFLICT_FAILED], same dimension.
 * - [ConflictOrchestrationResult.ResolverNotConfigured]/[ConflictOrchestrationResult.ResolverNotFound]
 *   -> [RetryCircuitTelemetrySignal.CONFLICT_UNRESOLVED], dimensioned by the
 *   matching [UnresolvedConflictReason].
 * - [ConflictOrchestrationResult.Quarantined] -> [RetryCircuitTelemetrySignal.CONFLICT_QUARANTINED].
 * - [ConflictOrchestrationResult.QuarantineUnavailable] -> [RetryCircuitTelemetrySignal.CONFLICT_FAILED]
 *   with no resolver dimension: the quarantine counter itself could not be
 *   evaluated, so no resolver was ever selected or invoked.
 *
 * Additionally, whenever [io.dataloom.runtime.conflict.ConflictOrchestrationBindings.selectedTier]
 * reports a non-`null` tier for the detected conflict's context (recomputed
 * here from the exact same public, pure function the orchestrator itself uses
 * for selection, so it can never disagree with what was actually selected),
 * one [RetryCircuitTelemetrySignal.CONFLICT_RESOLVER_SELECTION_TIER_HIT] event
 * is also recorded, dimensioned by the [ConflictResolverSelectionTier]. This
 * fires for every variant above except [ConflictOrchestrationResult.ResolverNotConfigured]
 * (nothing was selected there by definition).
 *
 * No entity ID, change ID, tenant ID, or free text is ever placed in a metric
 * dimension -- only the closed [ConflictResolverSelectionTier] and
 * [UnresolvedConflictReason] enums and the registry-bounded [ConflictResolverId].
 *
 * ## Isolation
 *
 * The delegate completes and its exact result is returned before telemetry is
 * assembled or recorded. A telemetry exception can never replace an
 * already-produced orchestration result, exactly like every other `Observed*`
 * wrapper in this file's sibling [ObservedSynchronizationRetryOrchestrator]
 * etc.
 */
public class ObservedSynchronizationConflictOrchestrator(
    private val delegate: SynchronizationConflictOrchestrator,
    private val clock: DataLoomClock,
    private val telemetry: BoundedRetryCircuitTelemetry,
) {
    /** Preserves the exact orchestration result and isolates telemetry failures. */
    public suspend fun detectAndResolve(
        request: ConflictOrchestrationRequest,
    ): ConflictOrchestrationResult {
        val result = delegate.detectAndResolve(request)
        observe(request, result)
        return result
    }

    private fun observe(request: ConflictOrchestrationRequest, result: ConflictOrchestrationResult) {
        try {
            when (result) {
                is ConflictOrchestrationResult.DetectorNotFound,
                is ConflictOrchestrationResult.NoConflict,
                -> Unit

                is ConflictOrchestrationResult.ResolverNotConfigured -> {
                    recordDetected()
                    recordUnresolved(UnresolvedConflictReason.RESOLVER_NOT_CONFIGURED)
                }

                is ConflictOrchestrationResult.ResolverNotFound -> {
                    recordDetected()
                    recordUnresolved(UnresolvedConflictReason.RESOLVER_NOT_FOUND)
                    recordTierHit(request, result.conflict)
                }

                is ConflictOrchestrationResult.Quarantined -> {
                    recordDetected()
                    recordSignal(RetryCircuitTelemetrySignal.CONFLICT_QUARANTINED)
                    recordTierHit(request, result.conflict)
                }

                is ConflictOrchestrationResult.QuarantineUnavailable -> {
                    recordDetected()
                    recordSignal(RetryCircuitTelemetrySignal.CONFLICT_FAILED)
                    recordTierHit(request, result.conflict)
                }

                is ConflictOrchestrationResult.Resolved -> {
                    recordDetected()
                    recordTierHit(request, result.conflict)
                    when (result.decision) {
                        is ConflictResolutionDecision.UseLocal,
                        is ConflictResolutionDecision.UseRemote,
                        is ConflictResolutionDecision.Merge,
                        -> recordSignal(RetryCircuitTelemetrySignal.CONFLICT_RESOLVED, resolverId = result.resolverId)

                        is ConflictResolutionDecision.Defer ->
                            recordSignal(RetryCircuitTelemetrySignal.CONFLICT_DEFERRED, resolverId = result.resolverId)

                        is ConflictResolutionDecision.Fail ->
                            recordSignal(RetryCircuitTelemetrySignal.CONFLICT_FAILED, resolverId = result.resolverId)
                    }
                }
            }
        } catch (_: Exception) {
            // Telemetry must never alter an already-produced orchestration result.
        }
    }

    private fun recordDetected() {
        recordSignal(RetryCircuitTelemetrySignal.CONFLICT_DETECTED)
    }

    private fun recordUnresolved(reason: UnresolvedConflictReason) {
        recordSignal(RetryCircuitTelemetrySignal.CONFLICT_UNRESOLVED, unresolvedReason = reason)
    }

    private fun recordTierHit(request: ConflictOrchestrationRequest, conflict: SynchronizationConflict) {
        val synchronizationRequest = request.detectionRequest.synchronizationRequest
        val tier = request.bindings.selectedTier(
            ConflictResolverSelectionContext(
                entityType = conflict.entity.type,
                workflowId = synchronizationRequest.workflowId,
                tenantId = synchronizationRequest.context.tenantId,
            ),
        ) ?: return
        recordSignal(RetryCircuitTelemetrySignal.CONFLICT_RESOLVER_SELECTION_TIER_HIT, tier = tier)
    }

    private fun recordSignal(
        signal: RetryCircuitTelemetrySignal,
        resolverId: ConflictResolverId? = null,
        unresolvedReason: UnresolvedConflictReason? = null,
        tier: ConflictResolverSelectionTier? = null,
    ) {
        telemetry.record(
            RetryCircuitTelemetryEvent(
                signal = signal,
                occurredAt = clock.now(),
                conflictResolverId = resolverId,
                conflictUnresolvedReason = unresolvedReason,
                conflictSelectionTier = tier,
            ),
        )
    }
}

/**
 * Adds bounded telemetry to authorized manual conflict administration,
 * through the same [BoundedRetryCircuitTelemetry] pipeline
 * [ObservedSynchronizationConflictOrchestrator] and every retry/circuit
 * `Observed*` wrapper use.
 *
 * Only [releaseQuarantine] emits telemetry, and only on
 * [ConflictQuarantineReleaseResult.Released]
 * ([RetryCircuitTelemetrySignal.CONFLICT_QUARANTINE_RELEASED]): the durable
 * operational-event outbox (see
 * [io.dataloom.runtime.observation.operational.ConflictResolutionOperationalEventBridge])
 * already covers full command audit for [execute] and every other
 * [releaseQuarantine] outcome, so this wrapper adds only the metric this file
 * is otherwise missing rather than duplicating that audit trail.
 */
public class ObservedConflictAdministrationCoordinator(
    private val delegate: ConflictAdministrationCoordinator,
    private val clock: DataLoomClock,
    private val telemetry: BoundedRetryCircuitTelemetry,
) {
    /** Preserves the exact command result unchanged; no telemetry is recorded. */
    public suspend fun execute(request: ConflictAdministrationRequest): ConflictAdministrationResult =
        delegate.execute(request)

    /** Preserves the exact release result and isolates telemetry failures. */
    public suspend fun releaseQuarantine(
        request: ConflictQuarantineReleaseRequest,
    ): ConflictQuarantineReleaseResult {
        val result = delegate.releaseQuarantine(request)
        if (result is ConflictQuarantineReleaseResult.Released) {
            try {
                telemetry.record(
                    RetryCircuitTelemetryEvent(
                        signal = RetryCircuitTelemetrySignal.CONFLICT_QUARANTINE_RELEASED,
                        occurredAt = clock.now(),
                    ),
                )
            } catch (_: Exception) {
                // Telemetry must never alter an already-produced release result.
            }
        }
        return result
    }
}
