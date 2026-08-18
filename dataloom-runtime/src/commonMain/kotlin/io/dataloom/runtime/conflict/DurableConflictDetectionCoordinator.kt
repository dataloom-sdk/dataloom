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
import io.dataloom.api.conflict.UnresolvedConflictRecord
import io.dataloom.api.conflict.UnresolvedConflictReason
import io.dataloom.api.identifier.ConflictResolverId
import io.dataloom.api.time.DataLoomClock

/**
 * Composes [SynchronizationConflictOrchestrator] with
 * [DurableUnresolvedConflictLog] and, optionally,
 * [DurableResolvedConflictDecisionLog]: the first real caller of the durable
 * unresolved-conflict log, closing the "available primitive, not adopted end
 * to end" gap both types previously documented, and — once
 * [resolvedConflictDecisionLog] is supplied — the first real caller of the
 * durable resolved-conflict-decision log too.
 *
 * ## Why a separate coordinator, not a change to the orchestrator
 *
 * [SynchronizationConflictOrchestrator] is explicitly documented as never
 * applying anything "to storage, queues, or any synchronization pipeline."
 * This coordinator does not change that boundary or reach inside the
 * orchestrator — it wraps [detectAndResolve] from the outside, the same way
 * a caller composes any other side-effect-free component with a durable
 * store.
 *
 * ## What gets durably recorded
 *
 * The two *unresolved* outcomes —
 * [ConflictOrchestrationResult.ResolverNotConfigured] and
 * [ConflictOrchestrationResult.ResolverNotFound] — are passed to
 * [unresolvedConflictLog]. [ConflictOrchestrationResult.DetectorNotFound] and
 * [ConflictOrchestrationResult.NoConflict] are never durably recorded by
 * either log.
 *
 * [ConflictOrchestrationResult.Resolved] is passed to
 * [resolvedConflictDecisionLog] when one is supplied (`null` by default,
 * preserving byte-for-byte prior behavior for existing callers). Recording a
 * resolved decision never persists
 * [io.dataloom.api.conflict.ConflictResolutionDecision.Merge]'s payload — see
 * [ResolvedConflictDecisionRecord]'s "Avoiding the Merge payload landmine"
 * documentation.
 *
 * ## Durable-recording failures do not hide the orchestration result
 *
 * A [DurableUnresolvedConflictRecordOutcome.PersistenceFailure],
 * [DurableUnresolvedConflictRecordOutcome.ContentionLimitReached],
 * [DurableResolvedConflictDecisionRecordOutcome.PersistenceFailure], or
 * [DurableResolvedConflictDecisionRecordOutcome.ContentionLimitReached] does
 * not change or suppress the returned [ConflictOrchestrationResult] — the
 * caller still receives the real detection/resolution outcome and can decide
 * how to react to a durable-recording failure separately, via
 * [DurableConflictDetectionResult.unresolvedRecordOutcome] /
 * [DurableConflictDetectionResult.resolvedDecisionRecordOutcome]. This
 * mirrors [SynchronizationConflictOrchestrator]'s own posture for its
 * optional event emitter: "ordinary observer failures do not stop resolver
 * selection or resolution."
 *
 * ## Cancellation
 *
 * A thrown [kotlin.coroutines.cancellation.CancellationException] from
 * [orchestrator], [unresolvedConflictLog], or [resolvedConflictDecisionLog]
 * propagates normally.
 *
 * @param orchestrator the conflict detection/resolution orchestrator to wrap.
 * @param unresolvedConflictLog durable persistence for conflicts that could
 *   not be automatically resolved.
 * @param clock supplies the commit timestamp for each durably recorded
 *   conflict.
 * @param resolvedConflictDecisionLog optional durable persistence for
 *   conflicts a resolver genuinely resolved. `null` (the default) disables
 *   resolved-decision recording entirely — no lookup or record attempt is
 *   made — preserving prior behavior for callers constructed before this
 *   parameter existed.
 */
public class DurableConflictDetectionCoordinator(
    private val orchestrator: SynchronizationConflictOrchestrator,
    private val unresolvedConflictLog: DurableUnresolvedConflictLog,
    private val clock: DataLoomClock,
    private val resolvedConflictDecisionLog: DurableResolvedConflictDecisionLog? = null,
) {

    /**
     * Performs [SynchronizationConflictOrchestrator.detectAndResolve] and
     * durably records the outcome when applicable: an unresolved outcome is
     * always recorded via [unresolvedConflictLog]; a resolved outcome is
     * recorded via [resolvedConflictDecisionLog] only when one was supplied.
     *
     * @param request the [ConflictOrchestrationRequest] to evaluate.
     * @return the exact [ConflictOrchestrationResult] from [orchestrator],
     *   paired with whichever durable record outcome applies, or `null` when
     *   the outcome did not require one.
     */
    public suspend fun detectAndResolve(
        request: ConflictOrchestrationRequest,
    ): DurableConflictDetectionResult {
        val result = orchestrator.detectAndResolve(request)
        var unresolvedOutcome: DurableUnresolvedConflictRecordOutcome? = null
        var resolvedOutcome: DurableResolvedConflictDecisionRecordOutcome? = null
        when (result) {
            is ConflictOrchestrationResult.ResolverNotConfigured ->
                unresolvedOutcome = recordUnresolved(result.conflict, UnresolvedConflictReason.RESOLVER_NOT_CONFIGURED)
            is ConflictOrchestrationResult.ResolverNotFound ->
                unresolvedOutcome = recordUnresolved(result.conflict, UnresolvedConflictReason.RESOLVER_NOT_FOUND)
            is ConflictOrchestrationResult.Resolved ->
                resolvedOutcome = recordResolved(result.conflict, result.decision, result.resolverId)
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
            mergedChange = (decision as? ConflictResolutionDecision.Merge)?.resolvedChange?.toSummary(),
            failureErrorCode = (decision as? ConflictResolutionDecision.Fail)?.error?.code?.value,
            committedAt = clock.now(),
        )
        return log.record(conflict.id, record)
    }

    private fun ChangeEvent.toSummary(): UnresolvedConflictChangeSummary =
        UnresolvedConflictChangeSummary(changeEventId = id, operation = operation, metadata = metadata)

    private fun ConflictResolutionDecision.toKind(): ResolvedConflictDecisionKind = when (this) {
        is ConflictResolutionDecision.UseLocal -> ResolvedConflictDecisionKind.USE_LOCAL
        is ConflictResolutionDecision.UseRemote -> ResolvedConflictDecisionKind.USE_REMOTE
        is ConflictResolutionDecision.Merge -> ResolvedConflictDecisionKind.MERGE
        is ConflictResolutionDecision.Defer -> ResolvedConflictDecisionKind.DEFER
        is ConflictResolutionDecision.Fail -> ResolvedConflictDecisionKind.FAIL
    }

    private fun ConflictResolutionDecision.metadataValue() = when (this) {
        is ConflictResolutionDecision.UseLocal -> metadata
        is ConflictResolutionDecision.UseRemote -> metadata
        is ConflictResolutionDecision.Merge -> metadata
        is ConflictResolutionDecision.Defer -> metadata
        is ConflictResolutionDecision.Fail -> metadata
    }

    override fun toString(): String = "DurableConflictDetectionCoordinator(orchestrator=$orchestrator)"
}

/**
 * Result of one [DurableConflictDetectionCoordinator.detectAndResolve] call.
 *
 * @param orchestration the exact [ConflictOrchestrationResult] returned by
 *   [SynchronizationConflictOrchestrator.detectAndResolve]. Never reinterpreted.
 * @param unresolvedRecordOutcome the [DurableUnresolvedConflictRecordOutcome]
 *   when [orchestration] was an unresolved outcome and a record attempt was
 *   made, or `null` when [orchestration] did not require one.
 * @param resolvedDecisionRecordOutcome the
 *   [DurableResolvedConflictDecisionRecordOutcome] when [orchestration] was
 *   [ConflictOrchestrationResult.Resolved] and a
 *   [DurableConflictDetectionCoordinator.resolvedConflictDecisionLog] was
 *   configured, or `null` when no record attempt was made (either
 *   [orchestration] was not [ConflictOrchestrationResult.Resolved], or no
 *   resolved-decision log was configured).
 */
public data class DurableConflictDetectionResult(
    public val orchestration: ConflictOrchestrationResult,
    public val unresolvedRecordOutcome: DurableUnresolvedConflictRecordOutcome?,
    public val resolvedDecisionRecordOutcome: DurableResolvedConflictDecisionRecordOutcome? = null,
)
