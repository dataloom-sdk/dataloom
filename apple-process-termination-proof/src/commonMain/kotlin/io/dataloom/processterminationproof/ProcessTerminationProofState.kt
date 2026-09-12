package io.dataloom.processterminationproof

/**
 * Primitive-typed snapshot of one persisted circuit-breaker record, returned
 * by [AppleCircuitBreakerProcessTerminationProof].
 *
 * Every field is a String/Int/Long so this type crosses the Kotlin/Native
 * Objective-C/Swift interop boundary in the simplest shape available: no
 * nested sealed classes, no fields typed as other exported DataLoom types.
 * The real, richer production type this is derived from is
 * `io.dataloom.api.circuit.CircuitBreakerStateRecord`
 * (`dataloom-api`) -- this class exists only so the Apple Simulator proof
 * app (a real launchable app target, not a Kotlin/Native test binary) can
 * read the outcome back through plain Swift-visible fields, without needing
 * to construct or pattern-match DataLoom's sealed circuit-breaker result
 * types from Swift.
 *
 * @property phase [io.dataloom.api.circuit.CircuitBreakerPhase.name] of the
 *   persisted state (`"CLOSED"`, `"OPEN"`, or `"HALF_OPEN"`).
 * @property consecutiveFailures the persisted consecutive-failure count.
 * @property openUntilEpochMillis the persisted open-until deadline in epoch
 *   milliseconds, or `-1` when the persisted state has no open-until deadline
 *   (i.e. the phase is not `OPEN`).
 * @property probeGeneration the persisted probe generation.
 * @property version the persisted compare-and-set version.
 */
public class ProcessTerminationProofState(
    public val phase: String,
    public val consecutiveFailures: Int,
    public val openUntilEpochMillis: Long,
    public val probeGeneration: Long,
    public val version: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProcessTerminationProofState) return false
        return phase == other.phase &&
            consecutiveFailures == other.consecutiveFailures &&
            openUntilEpochMillis == other.openUntilEpochMillis &&
            probeGeneration == other.probeGeneration &&
            version == other.version
    }

    override fun hashCode(): Int {
        var result = phase.hashCode()
        result = 31 * result + consecutiveFailures
        result = 31 * result + openUntilEpochMillis.hashCode()
        result = 31 * result + probeGeneration.hashCode()
        result = 31 * result + version.hashCode()
        return result
    }

    override fun toString(): String = "ProcessTerminationProofState(" +
        "phase=$phase, " +
        "consecutiveFailures=$consecutiveFailures, " +
        "openUntilEpochMillis=$openUntilEpochMillis, " +
        "probeGeneration=$probeGeneration, " +
        "version=$version)"
}
