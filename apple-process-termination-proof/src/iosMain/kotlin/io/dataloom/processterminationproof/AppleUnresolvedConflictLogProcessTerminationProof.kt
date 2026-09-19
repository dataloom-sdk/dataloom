@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.processterminationproof

import io.dataloom.api.change.EntityReference
import io.dataloom.api.conflict.ConflictType
import io.dataloom.api.conflict.DurableUnresolvedConflictLog
import io.dataloom.api.conflict.DurableUnresolvedConflictRecordOutcome
import io.dataloom.api.conflict.UnresolvedConflictChangeSummary
import io.dataloom.api.conflict.UnresolvedConflictRecord
import io.dataloom.api.conflict.UnresolvedConflictRecordCodec
import io.dataloom.api.conflict.UnresolvedConflictReason
import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ConflictId
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
 * evidence for either conflict-log domain" gap -- a mechanical extension of
 * [AppleCircuitBreakerProcessTerminationProof]'s/[AppleRetryBudgetProcessTerminationProof]'s
 * own proven single-process kill/relaunch shape (`#94`) from circuit-breaker
 * and retry-budget state to [DurableUnresolvedConflictLog]'s own durable
 * unresolved-conflict state, the same real production type
 * [AppleUnresolvedConflictLogContentionProof][io.dataloom.processcontentionproof.AppleUnresolvedConflictLogContentionProof]
 * already drives against [AppleFileDurableStateStore] for the genuinely
 * different *contention* (two racing processes) proof shape in the sibling
 * `apple-process-contention-proof` module. This object is new, unrelated
 * infrastructure in a different module for a different acceptance
 * item -- proving one process's state survives a genuine OS-level kill and
 * relaunch, not proving two processes race safely.
 *
 * This is a Kotlin `object`, exported to Objective-C/Swift as a class with a
 * `shared` singleton accessor -- from Swift:
 * `AppleUnresolvedConflictLogProcessTerminationProof.shared.recordAndPersist(directoryPath:)`.
 *
 * ## What this drives
 *
 * [recordAndPersist] performs one real production
 * [DurableUnresolvedConflictLog.record] call against a fresh
 * [AppleFileDurableStateStore] rooted at [directoryPath] -- the exact store
 * [AppleUnresolvedConflictLogContentionProof][io.dataloom.processcontentionproof.AppleUnresolvedConflictLogContentionProof]
 * already proved real on macOS CI for the contention proof, reused here for a
 * different proof shape -- recording the fixed [RECORD] for [CONFLICT_ID].
 * [readPersistedState] opens a brand-new [AppleFileDurableStateStore] instance
 * against the same directory and reads the record back via
 * [io.dataloom.api.state.DurableStateStore.load], a confirmed non-mutating
 * operation (see [DurableUnresolvedConflictLog]'s own `current` method and
 * [AppleFileDurableStateStore.load]'s implementation, which performs no
 * compare-and-set) -- unlike the retry-budget proof's own
 * `hasPersistedRetryBudgetState`, which had to fall back to a bare file-existence
 * check because [io.dataloom.runtime.queue.AppleFileQueueProvider] has no
 * non-mutating read path of its own. This mirrors
 * [AppleCircuitBreakerProcessTerminationProof.readPersistedState]'s own shape
 * exactly: a real read used both by the CI proof's own verification and by
 * the proof app itself as an idempotency guard across the kill/relaunch.
 *
 * The CI proof this module exists for calls [recordAndPersist] from one real,
 * launched Simulator app process, kills that process with
 * `xcrun simctl terminate` (a genuine OS-level kill, not an in-process
 * simulation), relaunches it, and calls [readPersistedState] from the
 * *relaunched* process -- see `docs/apple/process-termination-proof.md` for
 * the full CI shape and what remains unverified from a Windows host.
 *
 * ## No scope reduction needed
 *
 * Unlike [AppleCircuitBreakerProcessTerminationProof], which drives
 * `AppleFileCircuitBreakerStateStore` directly rather than through the full
 * `CircuitBreakerExecutionGate`/`CircuitBreakerCoordinator` pair,
 * [DurableUnresolvedConflictLog] has no coordinator/execution-gate layer of
 * its own to bypass -- `record` already is the real production entry point a
 * caller like `DurableConflictDetectionCoordinator` uses. This object drives
 * it directly, with no further scope reduction, matching
 * [AppleRetryBudgetProcessTerminationProof]'s own "no further scope reduction
 * to make" precedent for [io.dataloom.runtime.queue.AppleFileQueueProvider].
 */
public object AppleUnresolvedConflictLogProcessTerminationProof {

    /**
     * Records the fixed [RECORD] for [CONFLICT_ID] through a new
     * [AppleFileDurableStateStore] rooted at [directoryPath] and returns the
     * persisted record read back afterward. Throws (via Kotlin `error`) if
     * the record call reports anything other than
     * [DurableUnresolvedConflictRecordOutcome.Recorded] -- this proof harness
     * intentionally does not swallow or retry failures, matching this
     * repository's "fail loudly rather than assume success" testing
     * discipline.
     *
     * Must be called at most once against a given [directoryPath] while it
     * has never been recorded: a second call against the identical facts
     * would itself report [DurableUnresolvedConflictRecordOutcome.AlreadyRecorded]
     * rather than [DurableUnresolvedConflictRecordOutcome.Recorded] and is
     * therefore treated as unexpected here -- callers must guard repeat
     * invocations with [readPersistedState] first, the same idempotency-guard
     * shape [AppleCircuitBreakerProcessTerminationProof.openCircuitAndPersist]'s
     * own callers already follow.
     */
    public fun recordAndPersist(directoryPath: String): UnresolvedConflictLogProcessTerminationProofState = runBlocking {
        val log = DurableUnresolvedConflictLog(store(directoryPath))
        when (val outcome = log.record(CONFLICT_ID, RECORD)) {
            is DurableUnresolvedConflictRecordOutcome.Recorded -> Unit
            is DurableUnresolvedConflictRecordOutcome.AlreadyRecorded ->
                error(
                    "Unexpected AlreadyRecorded during recordAndPersist -- callers must guard repeat " +
                        "invocations with readPersistedState first: $outcome",
                )
            is DurableUnresolvedConflictRecordOutcome.Conflict ->
                error("Unexpected unresolved-conflict compare-and-set conflict during recordAndPersist: $outcome")
            is DurableUnresolvedConflictRecordOutcome.PersistenceFailure ->
                error("Unresolved-conflict record persistence failed during recordAndPersist: ${outcome.error}")
            DurableUnresolvedConflictRecordOutcome.ContentionLimitReached ->
                error("Unresolved-conflict record hit its contention limit during recordAndPersist.")
        }
        readPersistedStateInternal(directoryPath)
            ?: error("Expected a persisted unresolved-conflict record immediately after recordAndPersist but found none.")
    }

    /**
     * Reads the persisted record back through a brand-new
     * [AppleFileDurableStateStore] instance rooted at [directoryPath], via
     * [io.dataloom.api.state.DurableStateStore.load] -- a non-mutating
     * operation, safe to call both as this proof's own outside verification
     * and as the proof app's idempotency guard. Returns `null` when no record
     * has been persisted yet. Throws (via Kotlin `error`) on a store failure.
     */
    public fun readPersistedState(directoryPath: String): UnresolvedConflictLogProcessTerminationProofState? = runBlocking {
        readPersistedStateInternal(directoryPath)
    }

    private suspend fun readPersistedStateInternal(
        directoryPath: String,
    ): UnresolvedConflictLogProcessTerminationProofState? =
        when (val result = store(directoryPath).load(CONFLICT_ID)) {
            is ProviderOperationResult.Success -> when (val loaded = result.value) {
                is DurableStateLoadResult.Found -> loaded.record.toProofState()
                DurableStateLoadResult.Missing -> null
            }
            is ProviderOperationResult.Failure ->
                error("AppleFileDurableStateStore.load failed while reading unresolved-conflict state: ${result.error}")
        }

    private fun store(directoryPath: String): AppleFileDurableStateStore<ConflictId, UnresolvedConflictRecord> =
        AppleFileDurableStateStore(
            directoryPath = directoryPath,
            fileName = STATE_FILE_NAME,
            scopeKeyEncoder = DurableUnresolvedConflictLog.KeyEncoder,
            codec = UnresolvedConflictRecordCodec(),
        )

    private fun DurableStateRecord<UnresolvedConflictRecord>.toProofState(): UnresolvedConflictLogProcessTerminationProofState {
        val record = state
        return UnresolvedConflictLogProcessTerminationProofState(
            conflictType = record.conflictType.name,
            entityType = record.entity.type.value,
            entityId = record.entity.id.value,
            reason = record.reason.name,
            committedAtEpochMillis = record.committedAt.epochMilliseconds,
            version = version,
        )
    }

    /**
     * The on-disk snapshot file name this proof's store writes within its
     * caller-supplied directory -- distinct from
     * [AppleResolvedConflictDecisionLogProcessTerminationProof]'s own file
     * name, and distinct from
     * [io.dataloom.processcontentionproof.AppleUnresolvedConflictLogContentionProof.STATE_FILE_NAME]
     * (a genuinely different proof, in a different module, that must never
     * share a snapshot file with this one), and distinct from
     * [AppleFileDurableStateStore.DEFAULT_FILE_NAME] for the same reason
     * every other domain-specific Apple file-backed store in this repository
     * names its file explicitly rather than relying on a shared default.
     */
    public const val STATE_FILE_NAME: String = "dataloom-unresolved-conflict-termination-state-v1.tsv"

    private val CONFLICT_ID = ConflictId("apple-termination-proof-unresolved-record")

    // Fixed, hardcoded record this proof always attempts to record --
    // deliberately distinct field values from
    // AppleUnresolvedConflictLogContentionProof's own RECORD constant (a
    // different ConflictId besides) so the two proofs' persisted state can
    // never be confused if ever inspected side by side.
    private val RECORD = UnresolvedConflictRecord(
        conflictType = ConflictType.CONCURRENT_CHANGE,
        entity = EntityReference(EntityType("note"), EntityId("note-apple-termination-proof-1")),
        localChange = UnresolvedConflictChangeSummary(
            ChangeEventId("local-apple-termination-proof-1"),
            ChangeOperation.UPDATE,
            DataLoomMetadata.Empty,
        ),
        remoteChange = UnresolvedConflictChangeSummary(
            ChangeEventId("remote-apple-termination-proof-1"),
            ChangeOperation.UPDATE,
            DataLoomMetadata.Empty,
        ),
        conflictMetadata = DataLoomMetadata.Empty,
        reason = UnresolvedConflictReason.RESOLVER_NOT_CONFIGURED,
        committedAt = DataLoomInstant(20_000L),
    )
}
