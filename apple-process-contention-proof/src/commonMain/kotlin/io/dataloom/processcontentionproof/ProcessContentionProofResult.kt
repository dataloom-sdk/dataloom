package io.dataloom.processcontentionproof

/**
 * Plain, primitive-typed outcome of one call to
 * [AppleCircuitBreakerProbeContentionProof.waitForGoSignalThenAttemptProbe],
 * mirroring the `Bundle` keys `CircuitBreakerProbeContentionContract` defines
 * for the Android proof this module's Apple counterpart is modeled on
 * (`dataloom-queue-room/src/androidTest/kotlin/io/dataloom/queue/room/CircuitBreakerProbeContentionContract.kt`).
 *
 * Every field is a primitive Kotlin type so this class crosses the
 * Kotlin/Native Objective-C/Swift interop boundary without re-exporting any
 * of `dataloom-api`'s/`dataloom-runtime`'s own richer sealed types --
 * matching `apple-process-termination-proof`'s own
 * `ProcessTerminationProofState` precedent (a plain class with manual
 * `equals`/`hashCode`/`toString`, not a `data class`).
 *
 * @property outcome one of `"ALLOWED_NO_CIRCUIT"`, `"ALLOWED"`, `"REJECTED"`,
 *   `"PERSISTENCE_FAILURE"`, `"CONTENTION_LIMIT"`, or
 *   `"TIMED_OUT_WAITING_FOR_GO_SIGNAL"` (the shared "go" marker file never
 *   appeared within the bounded poll budget -- a harness failure, not a
 *   circuit-breaker outcome).
 * @property rejectionReason [io.dataloom.runtime.retry.CircuitBreakerRejectionReason.name]
 *   when [outcome] is `"REJECTED"`, else empty.
 * @property probeGeneration the granted probe generation when [outcome] is
 *   `"ALLOWED"`, else `-1`.
 */
public class ProcessContentionProofResult(
    public val outcome: String,
    public val rejectionReason: String,
    public val probeGeneration: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProcessContentionProofResult) return false
        return outcome == other.outcome &&
            rejectionReason == other.rejectionReason &&
            probeGeneration == other.probeGeneration
    }

    override fun hashCode(): Int {
        var result = outcome.hashCode()
        result = 31 * result + rejectionReason.hashCode()
        result = 31 * result + probeGeneration.hashCode()
        return result
    }

    override fun toString(): String = "ProcessContentionProofResult(" +
        "outcome=$outcome, " +
        "rejectionReason=$rejectionReason, " +
        "probeGeneration=$probeGeneration)"
}
