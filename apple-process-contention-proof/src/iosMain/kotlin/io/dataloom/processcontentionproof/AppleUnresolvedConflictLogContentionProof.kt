@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.processcontentionproof

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
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.state.AppleFileDurableStateStore
import kotlinx.coroutines.runBlocking

/**
 * Real production Apple counterpart to
 * `AndroidUnresolvedConflictLogContentionInstrumentedTest`
 * (`dataloom-queue-room/src/androidTest/kotlin/io/dataloom/queue/room/`),
 * driving the exact same real [DurableUnresolvedConflictLog] against the
 * same production [AppleFileDurableStateStore] that two genuinely separate
 * iOS Simulator *app processes* (not two threads in one process) race
 * against -- extending [AppleCircuitBreakerProbeContentionProof]'s own
 * proven two-process racing mechanism (see that object's KDoc for the full
 * "how two genuinely separate Simulator app processes race on one file"
 * explanation, not repeated here) from `#94`'s circuit-breaker domain to
 * `#95`'s conflict-engine unresolved-conflict domain -- the specific
 * follow-up `docs/status/market-readiness.md`'s `#95` row names as still
 * open: "Apple process-kill/contention evidence for either conflict-log
 * domain".
 *
 * ## Symmetric racers, unlike the circuit-breaker domain
 *
 * [AppleCircuitBreakerProbeContentionProof] has an asymmetric opener/racer
 * shape: one process seeds `HALF_OPEN` state before either process races.
 * This proof's two racing processes are symmetric instead, mirroring
 * `UnresolvedConflictContentionContentProviderBase`'s own Android precedent
 * exactly: both processes independently [warmUpAndSignalReady] (a harmless
 * [io.dataloom.api.state.DurableStateStore.load], warming up the process and
 * this store's lazy directory/lock-file creation before either one races) and
 * then both [waitForGoSignalThenAttemptRecord] the identical, fixed [RECORD]
 * for the identical [CONFLICT_ID]. Mutual exclusion is enforced by
 * [AppleFileDurableStateStore.compareAndSet]'s real `flock`-based
 * compare-and-set -- never a test-only mutex -- exactly the mechanism
 * [DurableUnresolvedConflictLog.record]'s own bounded load-evaluate-compare-and-set
 * retry loop depends on: the losing process's compare-and-set observes
 * [io.dataloom.api.state.DurableStateCompareAndSetResult.Conflict], reloads,
 * sees the winner's already-committed row, confirms the facts agree (both
 * racers attempt to record the identical [RECORD]), and returns
 * [DurableUnresolvedConflictRecordOutcome.AlreadyRecorded] -- never a second
 * `Recorded`, never `Conflict`/`PersistenceFailure`/`ContentionLimitReached`.
 */
public object AppleUnresolvedConflictLogContentionProof {

    /**
     * Opens a fresh [AppleFileDurableStateStore] rooted at [directoryPath]
     * and performs one harmless [io.dataloom.api.state.DurableStateStore.load]
     * (warming up this process and the store's lazy directory/lock-file
     * setup before the race), then touches [readyMarkerPath] so the host CI
     * script can confirm this process is alive and about to enter its wait
     * loop. Called by both racing app processes -- unlike
     * [AppleCircuitBreakerProbeContentionProof.openCircuitAndSignalReady],
     * this performs no domain-state mutation, matching the Android
     * precedent's own symmetric `METHOD_WARM_UP` step.
     */
    public fun warmUpAndSignalReady(directoryPath: String, readyMarkerPath: String): Unit = runBlocking {
        store(directoryPath).load(CONFLICT_ID)
        touchContentionMarkerFile(readyMarkerPath)
    }

    /**
     * Busy-polls (bounded by [maxPollAttempts]) for [goSignalPath] to
     * appear, then immediately calls the real
     * [DurableUnresolvedConflictLog.record] against a fresh
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

        val log = DurableUnresolvedConflictLog(store(directoryPath))
        val outcome = when (log.record(CONFLICT_ID, RECORD)) {
            is DurableUnresolvedConflictRecordOutcome.Recorded -> "RECORDED"
            is DurableUnresolvedConflictRecordOutcome.AlreadyRecorded -> "ALREADY_RECORDED"
            is DurableUnresolvedConflictRecordOutcome.Conflict -> "CONFLICT"
            is DurableUnresolvedConflictRecordOutcome.PersistenceFailure -> "PERSISTENCE_FAILURE"
            DurableUnresolvedConflictRecordOutcome.ContentionLimitReached -> "CONTENTION_LIMIT"
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

    private fun store(directoryPath: String): AppleFileDurableStateStore<ConflictId, UnresolvedConflictRecord> =
        AppleFileDurableStateStore(
            directoryPath = directoryPath,
            fileName = STATE_FILE_NAME,
            scopeKeyEncoder = DurableUnresolvedConflictLog.KeyEncoder,
            codec = UnresolvedConflictRecordCodec(),
        )

    /**
     * The on-disk snapshot file name this proof's store writes within its
     * shared directory -- distinct from
     * [AppleResolvedConflictDecisionLogContentionProof]'s own file name so
     * the two domains never collide if ever pointed at the same directory,
     * and distinct from [AppleFileDurableStateStore.DEFAULT_FILE_NAME] for
     * the same reason `AppleFileCircuitBreakerStateStore`'s own file name is
     * explicit rather than left to a shared default.
     */
    public const val STATE_FILE_NAME: String = "dataloom-unresolved-conflict-contention-state-v1.tsv"

    private val CONFLICT_ID = ConflictId("apple-contention-concurrent-unresolved-record")

    // Fixed, hardcoded record both racing processes attempt to record --
    // identical facts on both sides, matching
    // UnresolvedConflictContentionContentProviderBase's own Android
    // precedent's fixed RECORD constant, so a successful race's
    // AlreadyRecorded outcome is never ambiguous about whether the "facts
    // agree" check inside DurableUnresolvedConflictLog.record passed only by
    // coincidence.
    private val RECORD = UnresolvedConflictRecord(
        conflictType = ConflictType.CONCURRENT_CHANGE,
        entity = EntityReference(EntityType("note"), EntityId("note-apple-contention-1")),
        localChange = UnresolvedConflictChangeSummary(
            ChangeEventId("local-apple-contention-1"),
            ChangeOperation.UPDATE,
            DataLoomMetadata.Empty,
        ),
        remoteChange = UnresolvedConflictChangeSummary(
            ChangeEventId("remote-apple-contention-1"),
            ChangeOperation.UPDATE,
            DataLoomMetadata.Empty,
        ),
        conflictMetadata = DataLoomMetadata.Empty,
        reason = UnresolvedConflictReason.RESOLVER_NOT_CONFIGURED,
        committedAt = DataLoomInstant(10_000L),
    )
}
