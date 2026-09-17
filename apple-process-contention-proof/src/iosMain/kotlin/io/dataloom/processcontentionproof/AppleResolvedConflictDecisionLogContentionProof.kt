@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.processcontentionproof

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
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.state.AppleFileDurableStateStore
import kotlinx.coroutines.runBlocking

/**
 * Real production Apple counterpart to
 * `AndroidResolvedConflictDecisionLogContentionInstrumentedTest`
 * (`dataloom-queue-room/src/androidTest/kotlin/io/dataloom/queue/room/`) --
 * the resolved-decision-log twin of
 * [AppleUnresolvedConflictLogContentionProof], sharing that object's
 * identical [AppleFileDurableStateStore] compare-and-set persistence path
 * and symmetric warm-up/race shape, differing only in the record type
 * ([ResolvedConflictDecisionRecord] vs. [io.dataloom.api.conflict.UnresolvedConflictRecord])
 * and the [io.dataloom.api.state.DurableStateCodec] plugged in
 * ([ResolvedConflictDecisionRecordCodec] vs.
 * [io.dataloom.api.conflict.UnresolvedConflictRecordCodec]) -- see that
 * object's own KDoc for the full "symmetric racers, unlike the
 * circuit-breaker domain" and cross-process mutual-exclusion explanation,
 * not repeated here.
 *
 * Closes the same `#95` gap
 * [AppleUnresolvedConflictLogContentionProof] closes, for this gate's other
 * durable conflict-engine domain: `docs/status/market-readiness.md`'s `#95`
 * row names "Apple process-kill/contention evidence for either conflict-log
 * domain" as still open, matching how
 * `AndroidResolvedConflictDecisionLogContentionInstrumentedTest` itself
 * closed the identical follow-up
 * `AndroidUnresolvedConflictLogContentionInstrumentedTest`'s own class doc
 * named as still open on Android.
 */
public object AppleResolvedConflictDecisionLogContentionProof {

    /**
     * Opens a fresh [AppleFileDurableStateStore] rooted at [directoryPath]
     * and performs one harmless [io.dataloom.api.state.DurableStateStore.load],
     * then touches [readyMarkerPath]. Called by both racing app processes --
     * see [AppleUnresolvedConflictLogContentionProof.warmUpAndSignalReady]
     * for the full reasoning.
     */
    public fun warmUpAndSignalReady(directoryPath: String, readyMarkerPath: String): Unit = runBlocking {
        store(directoryPath).load(CONFLICT_ID)
        touchContentionMarkerFile(readyMarkerPath)
    }

    /**
     * Busy-polls (bounded by [maxPollAttempts]) for [goSignalPath] to
     * appear, then immediately calls the real
     * [DurableResolvedConflictDecisionLog.record] against a fresh
     * [AppleFileDurableStateStore] rooted at [directoryPath] with the fixed
     * [RECORD]/[CONFLICT_ID], re-reads the persisted version through a
     * second freshly constructed store instance, and returns the classified
     * outcome. Called by both racing app processes.
     */
    public fun waitForGoSignalThenAttemptRecord(
        directoryPath: String,
        goSignalPath: String,
        maxPollAttempts: Int = DEFAULT_CONFLICT_CONTENTION_MAX_POLL_ATTEMPTS,
    ): ConflictRecordContentionProofResult = runBlocking {
        if (!awaitContentionGoSignal(goSignalPath, maxPollAttempts)) {
            return@runBlocking ConflictRecordContentionProofResult(
                outcome = "TIMED_OUT_WAITING_FOR_GO_SIGNAL",
                persistedVersion = -1L,
            )
        }

        val log = DurableResolvedConflictDecisionLog(store(directoryPath))
        val outcome = when (log.record(CONFLICT_ID, RECORD)) {
            is DurableResolvedConflictDecisionRecordOutcome.Recorded -> "RECORDED"
            is DurableResolvedConflictDecisionRecordOutcome.AlreadyRecorded -> "ALREADY_RECORDED"
            is DurableResolvedConflictDecisionRecordOutcome.Conflict -> "CONFLICT"
            is DurableResolvedConflictDecisionRecordOutcome.PersistenceFailure -> "PERSISTENCE_FAILURE"
            DurableResolvedConflictDecisionRecordOutcome.ContentionLimitReached -> "CONTENTION_LIMIT"
        }
        ConflictRecordContentionProofResult(
            outcome = outcome,
            persistedVersion = readPersistedVersion(directoryPath),
        )
    }

    private suspend fun readPersistedVersion(directoryPath: String): Long =
        when (val loaded = store(directoryPath).load(CONFLICT_ID)) {
            is ProviderOperationResult.Failure -> -1L
            is ProviderOperationResult.Success -> when (val value = loaded.value) {
                is DurableStateLoadResult.Found -> value.record.version
                DurableStateLoadResult.Missing -> -1L
            }
        }

    private fun store(directoryPath: String): AppleFileDurableStateStore<ConflictId, ResolvedConflictDecisionRecord> =
        AppleFileDurableStateStore(
            directoryPath = directoryPath,
            fileName = STATE_FILE_NAME,
            scopeKeyEncoder = DurableResolvedConflictDecisionLog.KeyEncoder,
            codec = ResolvedConflictDecisionRecordCodec(),
        )

    /**
     * The on-disk snapshot file name this proof's store writes within its
     * shared directory -- see
     * [AppleUnresolvedConflictLogContentionProof.STATE_FILE_NAME]'s own doc
     * for why this is explicit rather than a shared default.
     */
    public const val STATE_FILE_NAME: String = "dataloom-resolved-conflict-decision-contention-state-v1.tsv"

    private val CONFLICT_ID = ConflictId("apple-contention-concurrent-resolved-record")

    // Fixed, hardcoded record both racing processes attempt to record -- see
    // AppleUnresolvedConflictLogContentionProof's own RECORD doc comment for
    // why this must stay identical on both sides.
    private val RECORD = ResolvedConflictDecisionRecord(
        conflictType = ConflictType.CONCURRENT_CHANGE,
        entity = EntityReference(EntityType("note"), EntityId("note-apple-contention-resolved-1")),
        localChange = UnresolvedConflictChangeSummary(
            ChangeEventId("local-apple-contention-resolved-1"),
            ChangeOperation.UPDATE,
            DataLoomMetadata.Empty,
        ),
        remoteChange = UnresolvedConflictChangeSummary(
            ChangeEventId("remote-apple-contention-resolved-1"),
            ChangeOperation.UPDATE,
            DataLoomMetadata.Empty,
        ),
        conflictMetadata = DataLoomMetadata.Empty,
        resolverId = ConflictResolverId("dataloom.builtin.server-wins"),
        decisionKind = ResolvedConflictDecisionKind.USE_REMOTE,
        decisionMetadata = DataLoomMetadata.Empty,
        committedAt = DataLoomInstant(10_000L),
    )
}
