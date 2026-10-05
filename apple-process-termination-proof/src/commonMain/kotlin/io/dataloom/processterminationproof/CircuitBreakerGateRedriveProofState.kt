package io.dataloom.processterminationproof

/**
 * Primitive-typed outcome of re-driving the real
 * `io.dataloom.runtime.retry.CircuitBreakerCoordinator` gate against an
 * already-persisted circuit-breaker record, returned by
 * [AppleCircuitBreakerProcessTerminationProof.redriveGateAfterRelaunch].
 *
 * Every field is a String/Long so this type crosses the Kotlin/Native
 * Objective-C/Swift interop boundary in the simplest shape available -- the
 * same design used by [ProcessTerminationProofState] for the raw persisted
 * record this type complements. This is **not** another raw-row read: every
 * field here reflects a real decision the production
 * `CircuitBreakerCoordinator.acquire`/`recordSuccess` gate made, mirroring
 * the three re-drive decisions
 * `CircuitBreakerProcessTerminationContentProvider`'s own
 * `attemptAccessBeforeDeadline`/`attemptProbeAtDeadline`/
 * `recordProbeSuccessAndReverifyRecovery` methods prove on Android after a
 * genuine process kill/relaunch (see that class's KDoc).
 *
 * @property beforeDeadlineOutcome the real gate's
 *   `CircuitBreakerPermission` one millisecond before the persisted
 *   open-deadline (expected `"REJECTED"`).
 * @property beforeDeadlineRejectionReason the real gate's
 *   `CircuitBreakerRejectionReason.name` for that rejection (expected
 *   `"OPEN"`), or an empty string when [beforeDeadlineOutcome] was not a
 *   rejection.
 * @property probeAtDeadlineOutcome the real gate's `CircuitBreakerPermission`
 *   exactly at the persisted open-deadline (expected `"PROBE_ALLOWED"`).
 * @property probeGeneration the half-open probe generation the real gate
 *   granted at the deadline, or `-1` when [probeAtDeadlineOutcome] was not
 *   `"PROBE_ALLOWED"`.
 * @property recoveryOutcome the real gate's `CircuitBreakerPermission` after
 *   that probe was recorded as a real success (expected `"ALLOWED"`).
 */
public class CircuitBreakerGateRedriveProofState(
    public val beforeDeadlineOutcome: String,
    public val beforeDeadlineRejectionReason: String,
    public val probeAtDeadlineOutcome: String,
    public val probeGeneration: Long,
    public val recoveryOutcome: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CircuitBreakerGateRedriveProofState) return false
        return beforeDeadlineOutcome == other.beforeDeadlineOutcome &&
            beforeDeadlineRejectionReason == other.beforeDeadlineRejectionReason &&
            probeAtDeadlineOutcome == other.probeAtDeadlineOutcome &&
            probeGeneration == other.probeGeneration &&
            recoveryOutcome == other.recoveryOutcome
    }

    override fun hashCode(): Int {
        var result = beforeDeadlineOutcome.hashCode()
        result = 31 * result + beforeDeadlineRejectionReason.hashCode()
        result = 31 * result + probeAtDeadlineOutcome.hashCode()
        result = 31 * result + probeGeneration.hashCode()
        result = 31 * result + recoveryOutcome.hashCode()
        return result
    }

    override fun toString(): String = "CircuitBreakerGateRedriveProofState(" +
        "beforeDeadlineOutcome=$beforeDeadlineOutcome, " +
        "beforeDeadlineRejectionReason=$beforeDeadlineRejectionReason, " +
        "probeAtDeadlineOutcome=$probeAtDeadlineOutcome, " +
        "probeGeneration=$probeGeneration, " +
        "recoveryOutcome=$recoveryOutcome)"
}
