package io.dataloom.runtime.conflict

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.conflict.ConflictResolutionDecision
import io.dataloom.api.conflict.DurableResolvedConflictDecisionLog
import io.dataloom.api.conflict.DurableResolvedConflictDecisionRecordOutcome
import io.dataloom.api.conflict.DurableUnresolvedConflictLog
import io.dataloom.api.conflict.DurableUnresolvedConflictRecordOutcome
import io.dataloom.api.conflict.ResolvedConflictDecisionKind
import io.dataloom.api.conflict.ResolvedConflictDecisionRecord
import io.dataloom.api.conflict.SynchronizationConflict
import io.dataloom.api.conflict.UnresolvedConflictChangeSummary
import io.dataloom.api.conflict.UnresolvedConflictReason
import io.dataloom.api.conflict.UnresolvedConflictRecord
import io.dataloom.api.identifier.ConflictResolverId
import io.dataloom.api.time.DataLoomClock

/**
 * Composes deterministic conflict detection/resolution with durable unresolved
 * and optional resolved-decision recording.
 *
 * The coordinator does not apply a decision to application storage. It returns
 * the exact orchestration result together with the exact durable-record
 * outcome, allowing a caller to establish a fail-closed application barrier.
 */
public class DurableConflictDetectionCoordinator(
    private val orchestrator: SynchronizationConflictOrchestrator,
    private val unresolvedConflictLog: DurableUnresolvedConflictLog,
    private val clock: DataLoomClock,
    private val resolvedConflictDecisionLog: DurableResolvedConflictDecisionLog? = null,
) {
    /**
     * `true` only when resolved decisions have a durable commit-once log.
     *
     * This is intentionally module-internal. The inbound pipeline uses it to
     * distinguish the historical observational mode from decision-application
     * mode without adding another public configuration contract.
     */
    internal val hasResolvedDecisionLog: Boolean
        get() = resolvedConflictDecisionLog != null

    /**
     * Detects and optionally resolves one conflict, then records whichever
     * durable evidence applies.
     *
     * Cancellation from the orchestrator or either durable store propagates.
     * A durable-record failure never replaces the real orchestration result;
     * both are returned so the caller can fail closed when application depends
     * on durable evidence.
     */
    public suspend fun detectAndResolve(
        request: ConflictOrchestrationRequest,
    ): DurableConflictDetectionResult {
        val result = orchestrator.detectAndResolve(request)
        var unresolvedOutcome: DurableUnresolvedConflictRecordOutcome? = null
        var resolvedOutcome: DurableResolvedConflictDecisionRecordOutcome? = null

        when (result) {
            is ConflictOrchestrationResult.ResolverNotConfigured ->
                if (conflictMatchesDetection(request, result.conflict)) {
                    unresolvedOutcome = recordUnresolved(
                        conflict = result.conflict,
                        reason = UnresolvedConflictReason.RESOLVER_NOT_CONFIGURED,
                    )
                }

            is ConflictOrchestrationResult.ResolverNotFound ->
                if (conflictMatchesDetection(request, result.conflict)) {
                    unresolvedOutcome = recordUnresolved(
                        conflict = result.conflict,
                        reason = UnresolvedConflictReason.RESOLVER_NOT_FOUND,
                    )
                }

            is ConflictOrchestrationResult.Resolved ->
                if (isRecordableResolvedResult(request, result)) {
                    resolvedOutcome = recordResolved(
                        conflict = result.conflict,
                        decision = result.decision,
                        resolverId = result.resolverId,
                    )
                }

            is ConflictOrchestrationResult.DetectorNotFound,
            is ConflictOrchestrationResult.NoConflict,
            -> Unit
        }

        return DurableConflictDetectionResult(
            orchestration = result,
            unresolvedRecordOutcome = unresolvedOutcome,
            resolvedDecisionRecordOutcome = resolvedOutcome,
        )
    }

    /**
     * Rejects detector/resolver contract violations before they can poison the
     * commit-once resolved-decision log. The exact invalid orchestration result
     * still returns to the caller, which can fail with a specific contract
     * diagnostic; only durable recording is suppressed.
     */
    private fun isRecordableResolvedResult(
        request: ConflictOrchestrationRequest,
        result: ConflictOrchestrationResult.Resolved,
    ): Boolean {
        val conflict = result.conflict
        if (!conflictMatchesDetection(request, conflict)) {
            return false
        }

        val merge = result.decision as? ConflictResolutionDecision.Merge
            ?: return true
        return merge.expectedEntity.type == conflict.entity.type &&
            merge.expectedEntity.id == conflict.entity.id
    }


    private fun conflictMatchesDetection(
        request: ConflictOrchestrationRequest,
        conflict: SynchronizationConflict,
    ): Boolean {
        val detection = request.detectionRequest
        return conflict.localChange == detection.localChange &&
            conflict.remoteChange == detection.remoteChange
    }

    private suspend fun recordUnresolved(
        conflict: SynchronizationConflict,
        reason: UnresolvedConflictReason,
    ): DurableUnresolvedConflictRecordOutcome {
        val record = UnresolvedConflictRecord(
            conflictType = conflict.type,
            entity = conflict.entity,
            localChange = conflict.localChange.toSummary(),
            remoteChange = conflict.remoteChange.toSummary(),
            conflictMetadata = conflict.metadata,
            reason = reason,
            committedAt = clock.now(),
        )
        return unresolvedConflictLog.record(conflict.id, record)
    }

    private suspend fun recordResolved(
        conflict: SynchronizationConflict,
        decision: ConflictResolutionDecision,
        resolverId: ConflictResolverId,
    ): DurableResolvedConflictDecisionRecordOutcome? {
        val log = resolvedConflictDecisionLog ?: return null
        val record = ResolvedConflictDecisionRecord(
            conflictType = conflict.type,
            entity = conflict.entity,
            localChange = conflict.localChange.toSummary(),
            remoteChange = conflict.remoteChange.toSummary(),
            conflictMetadata = conflict.metadata,
            resolverId = resolverId,
            decisionKind = decision.toKind(),
            decisionMetadata = decision.metadataValue(),
            mergedChange = (decision as? ConflictResolutionDecision.Merge)
                ?.resolvedChange
                ?.toSummary(),
            failureErrorCode = (decision as? ConflictResolutionDecision.Fail)
                ?.error
                ?.code
                ?.value,
            committedAt = clock.now(),
        )
        return log.record(conflict.id, record)
    }

    private fun ChangeEvent.toSummary(): UnresolvedConflictChangeSummary =
        UnresolvedConflictChangeSummary(
            changeEventId = id,
            operation = operation,
            metadata = metadata,
        )

    private fun ConflictResolutionDecision.toKind(): ResolvedConflictDecisionKind =
        when (this) {
            is ConflictResolutionDecision.UseLocal ->
                ResolvedConflictDecisionKind.USE_LOCAL
            is ConflictResolutionDecision.UseRemote ->
                ResolvedConflictDecisionKind.USE_REMOTE
            is ConflictResolutionDecision.Merge ->
                ResolvedConflictDecisionKind.MERGE
            is ConflictResolutionDecision.Defer ->
                ResolvedConflictDecisionKind.DEFER
            is ConflictResolutionDecision.Fail ->
                ResolvedConflictDecisionKind.FAIL
        }

    private fun ConflictResolutionDecision.metadataValue() =
        when (this) {
            is ConflictResolutionDecision.UseLocal -> metadata
            is ConflictResolutionDecision.UseRemote -> metadata
            is ConflictResolutionDecision.Merge -> metadata
            is ConflictResolutionDecision.Defer -> metadata
            is ConflictResolutionDecision.Fail -> metadata
        }

    override fun toString(): String =
        "DurableConflictDetectionCoordinator(orchestrator=$orchestrator)"
}

/**
 * Exact conflict orchestration result plus optional durable-record evidence.
 */
public data class DurableConflictDetectionResult(
    public val orchestration: ConflictOrchestrationResult,
    public val unresolvedRecordOutcome: DurableUnresolvedConflictRecordOutcome?,
    public val resolvedDecisionRecordOutcome: DurableResolvedConflictDecisionRecordOutcome? = null,
)
