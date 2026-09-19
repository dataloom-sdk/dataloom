@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.processterminationproof

import io.dataloom.api.change.EntityReference
import io.dataloom.api.conflict.ConflictType
import io.dataloom.api.conflict.DurableResolvedConflictDecisionLog
import io.dataloom.api.conflict.DurableResolvedConflictDecisionRecordOutcome
import io.dataloom.api.conflict.ResolvedConflictDecisionKind
import io.dataloom.api.conflict.ResolvedConflictDecisionRecord
import io.dataloom.api.conflict.ResolvedConflictDecisionRecordCodec
import io.dataloom.api.conflict.UnresolvedConflictChangeSummary
import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.ConflictResolverId
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.model.ChangeOperation
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateRecord
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.state.AppleFileDurableStateStore
import kotlinx.coroutines.runBlocking

/**
 * Real production write/read path exercised by the Apple Simulator
 * process-termination CI proof app, for `#95`'s "Apple process-kill/relaunch
 * evidence for either conflict-log domain" gap -- the resolved-decision-log
 * twin of [AppleUnresolvedConflictLogProcessTerminationProof], sharing that
 * object's identical [AppleFileDurableStateStore] compare-and-set persistence
 * path and identical write/read-back shape, differing only in the record type
 * ([ResolvedConflictDecisionRecord] vs. [io.dataloom.api.conflict.UnresolvedConflictRecord])
 * and the [io.dataloom.api.state.DurableStateCodec] plugged in
 * ([ResolvedConflictDecisionRecordCodec] vs.
 * [io.dataloom.api.conflict.UnresolvedConflictRecordCodec]) -- see that
 * object's own KDoc for the full "what this drives"/"no scope reduction
 * needed" reasoning, not repeated here.
 *
 * This is a Kotlin `object`, exported to Objective-C/Swift as a class with a
 * `shared` singleton accessor -- from Swift:
 * `AppleResolvedConflictDecisionLogProcessTerminationProof.shared.recordAndPersist(directoryPath:)`.
 *
 * Closes the same `#95` gap
 * [AppleUnresolvedConflictLogProcessTerminationProof] closes, for this gate's
 * other durable conflict-engine domain -- the same pairing
 * [AppleResolvedConflictDecisionLogContentionProof][io.dataloom.processcontentionproof.AppleResolvedConflictDecisionLogContentionProof]
 * already established for the sibling *contention* proof shape.
 */
public object AppleResolvedConflictDecisionLogProcessTerminationProof {

    /**
     * Records the fixed [RECORD] for [CONFLICT_ID] through a new
     * [AppleFileDurableStateStore] rooted at [directoryPath] and returns the
     * persisted record read back afterward. Throws (via Kotlin `error`) if
     * the record call reports anything other than
     * [DurableResolvedConflictDecisionRecordOutcome.Recorded] -- see
     * [AppleUnresolvedConflictLogProcessTerminationProof.recordAndPersist]'s
     * own KDoc for the full "fail loudly, must be called at most once, guard
     * with [readPersistedState]" reasoning, identical here.
     */
    public fun recordAndPersist(
        directoryPath: String,
    ): ResolvedConflictDecisionLogProcessTerminationProofState = runBlocking {
        val log = DurableResolvedConflictDecisionLog(store(directoryPath))
        when (val outcome = log.record(CONFLICT_ID, RECORD)) {
            is DurableResolvedConflictDecisionRecordOutcome.Recorded -> Unit
            is DurableResolvedConflictDecisionRecordOutcome.AlreadyRecorded ->
                error(
                    "Unexpected AlreadyRecorded during recordAndPersist -- callers must guard repeat " +
                        "invocations with readPersistedState first: $outcome",
                )
            is DurableResolvedConflictDecisionRecordOutcome.Conflict ->
                error("Unexpected resolved-conflict-decision compare-and-set conflict during recordAndPersist: $outcome")
            is DurableResolvedConflictDecisionRecordOutcome.PersistenceFailure ->
                error("Resolved-conflict-decision record persistence failed during recordAndPersist: ${outcome.error}")
            DurableResolvedConflictDecisionRecordOutcome.ContentionLimitReached ->
                error("Resolved-conflict-decision record hit its contention limit during recordAndPersist.")
        }
        readPersistedStateInternal(directoryPath)
            ?: error(
                "Expected a persisted resolved-conflict-decision record immediately after recordAndPersist " +
                    "but found none.",
            )
    }

    /**
     * Reads the persisted record back through a brand-new
     * [AppleFileDurableStateStore] instance rooted at [directoryPath], via
     * [io.dataloom.api.state.DurableStateStore.load] -- a non-mutating
     * operation, safe to call both as this proof's own outside verification
     * and as the proof app's idempotency guard. Returns `null` when no record
     * has been persisted yet. Throws (via Kotlin `error`) on a store failure.
     */
    public fun readPersistedState(
        directoryPath: String,
    ): ResolvedConflictDecisionLogProcessTerminationProofState? = runBlocking {
        readPersistedStateInternal(directoryPath)
    }

    private suspend fun readPersistedStateInternal(
        directoryPath: String,
    ): ResolvedConflictDecisionLogProcessTerminationProofState? =
        when (val result = store(directoryPath).load(CONFLICT_ID)) {
            is ProviderOperationResult.Success -> when (val loaded = result.value) {
                is DurableStateLoadResult.Found -> loaded.record.toProofState()
                DurableStateLoadResult.Missing -> null
            }
            is ProviderOperationResult.Failure ->
                error(
                    "AppleFileDurableStateStore.load failed while reading resolved-conflict-decision " +
                        "state: ${result.error}",
                )
        }

    private fun store(directoryPath: String): AppleFileDurableStateStore<ConflictId, ResolvedConflictDecisionRecord> =
        AppleFileDurableStateStore(
            directoryPath = directoryPath,
            fileName = STATE_FILE_NAME,
            scopeKeyEncoder = DurableResolvedConflictDecisionLog.KeyEncoder,
            codec = ResolvedConflictDecisionRecordCodec(),
        )

    private fun DurableStateRecord<ResolvedConflictDecisionRecord>.toProofState(): ResolvedConflictDecisionLogProcessTerminationProofState {
        val record = state
        return ResolvedConflictDecisionLogProcessTerminationProofState(
            conflictType = record.conflictType.name,
            entityType = record.entity.type.value,
            entityId = record.entity.id.value,
            resolverId = record.resolverId.value,
            decisionKind = record.decisionKind.name,
            committedAtEpochMillis = record.committedAt.epochMilliseconds,
            version = version,
        )
    }

    /**
     * The on-disk snapshot file name this proof's store writes within its
     * caller-supplied directory -- see
     * [AppleUnresolvedConflictLogProcessTerminationProof.STATE_FILE_NAME]'s
     * own doc for why this is explicit rather than a shared default, and
     * distinct from every sibling proof's own file name.
     */
    public const val STATE_FILE_NAME: String = "dataloom-resolved-conflict-decision-termination-state-v1.tsv"

    private val CONFLICT_ID = ConflictId("apple-termination-proof-resolved-record")

    // Fixed, hardcoded record this proof always attempts to record -- see
    // AppleUnresolvedConflictLogProcessTerminationProof's own RECORD doc
    // comment for why this must stay distinct from the contention proof's
    // own fixed record.
    private val RECORD = ResolvedConflictDecisionRecord(
        conflictType = ConflictType.CONCURRENT_CHANGE,
        entity = EntityReference(EntityType("note"), EntityId("note-apple-termination-proof-resolved-1")),
        localChange = UnresolvedConflictChangeSummary(
            ChangeEventId("local-apple-termination-proof-resolved-1"),
            ChangeOperation.UPDATE,
            DataLoomMetadata.Empty,
        ),
        remoteChange = UnresolvedConflictChangeSummary(
            ChangeEventId("remote-apple-termination-proof-resolved-1"),
            ChangeOperation.UPDATE,
            DataLoomMetadata.Empty,
        ),
        conflictMetadata = DataLoomMetadata.Empty,
        resolverId = ConflictResolverId("dataloom.builtin.server-wins"),
        decisionKind = ResolvedConflictDecisionKind.USE_REMOTE,
        decisionMetadata = DataLoomMetadata.Empty,
        committedAt = DataLoomInstant(20_000L),
    )
}
