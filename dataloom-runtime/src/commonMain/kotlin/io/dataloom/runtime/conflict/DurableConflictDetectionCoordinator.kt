package io.dataloom.runtime.conflict

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.conflict.DurableUnresolvedConflictLog
import io.dataloom.api.conflict.DurableUnresolvedConflictRecordOutcome
import io.dataloom.api.conflict.SynchronizationConflict
import io.dataloom.api.conflict.UnresolvedConflictChangeSummary
import io.dataloom.api.conflict.UnresolvedConflictRecord
import io.dataloom.api.conflict.UnresolvedConflictReason
import io.dataloom.api.time.DataLoomClock

/**
 * Composes [SynchronizationConflictOrchestrator] with
 * [DurableUnresolvedConflictLog]: the first real caller of the durable
 * unresolved-conflict log, closing the "available primitive, not adopted end
 * to end" gap both types previously documented.
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
 * Only the two *unresolved* outcomes —
 * [ConflictOrchestrationResult.ResolverNotConfigured] and
 * [ConflictOrchestrationResult.ResolverNotFound] — are passed to
 * [unresolvedConflictLog]. [ConflictOrchestrationResult.DetectorNotFound],
 * [ConflictOrchestrationResult.NoConflict], and
 * [ConflictOrchestrationResult.Resolved] are returned unchanged with a `null`
 * record outcome; a resolved decision's durability (including
 * [io.dataloom.api.conflict.ConflictResolutionDecision.Merge]'s payload) is
 * explicitly out of scope for [DurableUnresolvedConflictLog] — see its own
 * documentation.
 *
 * ## Durable-recording failures do not hide the orchestration result
 *
 * A [DurableUnresolvedConflictRecordOutcome.PersistenceFailure] or
 * [DurableUnresolvedConflictRecordOutcome.ContentionLimitReached] does not
 * change or suppress the returned [ConflictOrchestrationResult] — the caller
 * still receives the real detection/resolution outcome and can decide how to
 * react to a durable-recording failure separately, via
 * [DurableConflictDetectionResult.unresolvedRecordOutcome]. This mirrors
 * [SynchronizationConflictOrchestrator]'s own posture for its optional event
 * emitter: "ordinary observer failures do not stop resolver selection or
 * resolution."
 *
 * ## Cancellation
 *
 * A thrown [kotlin.coroutines.cancellation.CancellationException] from either
 * [orchestrator] or [unresolvedConflictLog] propagates normally.
 *
 * @param orchestrator the conflict detection/resolution orchestrator to wrap.
 * @param unresolvedConflictLog durable persistence for conflicts that could
 *   not be automatically resolved.
 * @param clock supplies the commit timestamp for each durably recorded
 *   conflict.
 */
public class DurableConflictDetectionCoordinator(
    private val orchestrator: SynchronizationConflictOrchestrator,
    private val unresolvedConflictLog: DurableUnresolvedConflictLog,
    private val clock: DataLoomClock,
) {

    /**
     * Performs [SynchronizationConflictOrchestrator.detectAndResolve] and, if
     * the outcome is unresolved (no resolver configured or found), durably
     * records it via [unresolvedConflictLog].
     *
     * @param request the [ConflictOrchestrationRequest] to evaluate.
     * @return the exact [ConflictOrchestrationResult] from [orchestrator],
     *   paired with the [DurableUnresolvedConflictRecordOutcome] when a
     *   record attempt was made, or `null` when the outcome did not require
     *   one.
     */
    public suspend fun detectAndResolve(
        request: ConflictOrchestrationRequest,
    ): DurableConflictDetectionResult {
        val result = orchestrator.detectAndResolve(request)
        val recordOutcome = when (result) {
            is ConflictOrchestrationResult.ResolverNotConfigured ->
                recordUnresolved(result.conflict, UnresolvedConflictReason.RESOLVER_NOT_CONFIGURED)
            is ConflictOrchestrationResult.ResolverNotFound ->
                recordUnresolved(result.conflict, UnresolvedConflictReason.RESOLVER_NOT_FOUND)
            is ConflictOrchestrationResult.DetectorNotFound,
            is ConflictOrchestrationResult.NoConflict,
            is ConflictOrchestrationResult.Resolved,
            -> null
        }
        return DurableConflictDetectionResult(orchestration = result, unresolvedRecordOutcome = recordOutcome)
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

    private fun ChangeEvent.toSummary(): UnresolvedConflictChangeSummary =
        UnresolvedConflictChangeSummary(changeEventId = id, operation = operation, metadata = metadata)

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
 */
public data class DurableConflictDetectionResult(
    public val orchestration: ConflictOrchestrationResult,
    public val unresolvedRecordOutcome: DurableUnresolvedConflictRecordOutcome?,
)
