package io.dataloom.processcontentionproof

/**
 * Plain, primitive-typed outcome of one call to
 * [AppleUnresolvedConflictLogContentionProof.waitForGoSignalThenAttemptRecord]
 * or
 * [AppleResolvedConflictDecisionLogContentionProof.waitForGoSignalThenAttemptRecord],
 * mirroring [ProcessContentionProofResult]'s own "every field a primitive
 * Kotlin type" Objective-C/Swift interop discipline.
 *
 * Both `#95` conflict-log domains share this one result shape. Unlike the
 * circuit-breaker domain's own [ProcessContentionProofResult], nothing here
 * needs a rejection-reason or generation field: a lost `record()` race
 * reports `AlreadyRecorded` directly as its own [outcome] value, not a
 * separate rejection-reason enum the way the circuit breaker's
 * `CircuitBreakerRejectionReason` does. [persistedVersion] instead plays the
 * bonus-corroboration role
 * [AndroidUnresolvedConflictLogContentionInstrumentedTest]/
 * [AndroidResolvedConflictDecisionLogContentionInstrumentedTest]'s own
 * "read the row back from a brand-new connection and assert `record_version
 * == 0`" step plays on Android: rather than have the CI host script decode
 * this store's own hex-encoded on-disk snapshot format directly (unlike the
 * circuit-breaker domain's plain-text TSV, [io.dataloom.runtime.state.AppleFileDurableStateStore]'s
 * on-disk format is not casually greppable), each call re-reads the
 * persisted version through a freshly constructed store instance after
 * attempting its own `record()` and reports it directly.
 *
 * @property outcome one of `"RECORDED"`, `"ALREADY_RECORDED"`, `"CONFLICT"`,
 *   `"PERSISTENCE_FAILURE"`, `"CONTENTION_LIMIT"`, or
 *   `"TIMED_OUT_WAITING_FOR_GO_SIGNAL"` (the shared "go" marker file never
 *   appeared within the bounded poll budget -- a harness failure, not a
 *   `record()` outcome) -- the exact vocabulary
 *   `UnresolvedConflictContentionContentProviderBase`/
 *   `ResolvedConflictDecisionContentionContentProviderBase`'s own Android
 *   precedent already established for `KEY_OUTCOME`.
 * @property persistedVersion the [io.dataloom.api.state.DurableStateRecord.version]
 *   read back for the fixed scope this proof recorded against, through a
 *   freshly constructed store instance, immediately after the `record()`
 *   attempt above -- `-1` if [outcome] is `"TIMED_OUT_WAITING_FOR_GO_SIGNAL"`
 *   or if that follow-up read itself found nothing/failed. A successful race
 *   (one `RECORDED`, one `ALREADY_RECORDED`, never a compare-and-set
 *   overwrite) means both processes observe the identical value `0` here.
 */
public class ConflictRecordContentionProofResult(
    public val outcome: String,
    public val persistedVersion: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ConflictRecordContentionProofResult) return false
        return outcome == other.outcome && persistedVersion == other.persistedVersion
    }

    override fun hashCode(): Int = 31 * outcome.hashCode() + persistedVersion.hashCode()

    override fun toString(): String =
        "ConflictRecordContentionProofResult(outcome=$outcome, persistedVersion=$persistedVersion)"
}
