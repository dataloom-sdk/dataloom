package io.dataloom.queue.room

/**
 * Shared method names, argument keys, and result-bundle keys used by
 * [CircuitBreakerProcessTerminationContentProvider] and
 * [AndroidProcessTerminationCircuitBreakerInstrumentedTest] to exchange
 * a real circuit-breaker proof across a genuine Android process boundary.
 *
 * Kept as plain string/const constants (not a shared interface) because the
 * two sides communicate only through [android.content.ContentResolver.call],
 * which is itself untyped -- there is no compiled contract between separate
 * OS processes.
 */
internal object CircuitBreakerProcessTerminationContract {
    /** Authority of [CircuitBreakerProcessTerminationContentProvider]. Test-APK only. */
    const val AUTHORITY: String = "io.dataloom.queue.room.test.circuitproof"

    /** Process suffix declared for the provider in src/androidTest/AndroidManifest.xml. */
    const val PROCESS_SUFFIX: String = ":circuitproof"

    /**
     * Drives two eligible failures through a real [CircuitBreakerExecutionGate]
     * so the circuit opens, then returns the persisted state. `arg` is the
     * on-disk database name to open.
     */
    const val METHOD_OPEN_CIRCUIT: String = "openCircuit"

    /**
     * Opens a brand-new connection to the same on-disk database and returns
     * whatever circuit state is currently persisted there. `arg` is the
     * on-disk database name to open.
     */
    const val METHOD_READ_CIRCUIT_STATE: String = "readCircuitState"

    /**
     * Re-drives the real [io.dataloom.runtime.retry.CircuitBreakerCoordinator.acquire]
     * gate -- the actual production decision entry point, never
     * [RoomCircuitBreakerStateStore.load] directly -- exactly one millisecond
     * *before* the circuit's persisted open-deadline, against a fresh
     * connection to the same on-disk database. Proves the relaunched
     * process's real gate genuinely still rejects access while the circuit is
     * open, not merely that the persisted phase reads back as `"OPEN"`.
     * `arg` is the on-disk database name to open. The deadline itself is
     * deterministic (see [CircuitBreakerProcessTerminationContentProvider]'s
     * fixed-clock `openCircuit` sequence), so no extra argument is needed.
     */
    const val METHOD_ATTEMPT_ACCESS_BEFORE_DEADLINE: String = "attemptAccessBeforeDeadline"

    /**
     * Re-drives the real [io.dataloom.runtime.retry.CircuitBreakerCoordinator.acquire]
     * gate exactly *at* the circuit's persisted open-deadline, against a
     * fresh connection to the same on-disk database. Proves the relaunched
     * process's real gate grants exactly one half-open probe permission --
     * the same single-probe-permit semantics
     * [CircuitBreakerProbeContentionContract] already proves for the
     * non-relaunch case -- rather than merely reading a persisted phase.
     * `arg` is the on-disk database name to open.
     */
    const val METHOD_ATTEMPT_PROBE_AT_DEADLINE: String = "attemptProbeAtDeadline"

    /**
     * Records a real success, through the real
     * [io.dataloom.runtime.retry.CircuitBreakerCoordinator.recordSuccess],
     * for the probe permission granted by [METHOD_ATTEMPT_PROBE_AT_DEADLINE],
     * then re-drives [io.dataloom.runtime.retry.CircuitBreakerCoordinator.acquire]
     * once more. Proves the relaunched process's real gate genuinely returns
     * to normal ALLOWED state after a successful half-open probe -- recovery
     * through the gate, not just a persisted CLOSED phase. `arg` is the
     * on-disk database name to open.
     */
    const val METHOD_RECORD_PROBE_SUCCESS_AND_REVERIFY_RECOVERY: String =
        "recordProbeSuccessAndReverifyRecovery"

    /** This process's pid ([Int]), from [android.os.Process.myPid]. */
    const val KEY_PID: String = "pid"

    /** [io.dataloom.api.circuit.CircuitBreakerPhase] name, or "MISSING" if no record exists. */
    const val KEY_PHASE: String = "phase"

    /** Epoch-millisecond open deadline, or -1 if absent. */
    const val KEY_OPEN_UNTIL_MILLIS: String = "openUntilMillis"

    const val KEY_CONSECUTIVE_FAILURES: String = "consecutiveFailures"

    const val KEY_PROBE_GENERATION: String = "probeGeneration"

    /**
     * One of `"ALLOWED"`, `"PROBE_ALLOWED"`, `"REJECTED"`, `"PERSISTENCE_FAILURE"`,
     * or `"CONTENTION_LIMIT"` -- the [io.dataloom.runtime.retry.CircuitBreakerPermission]
     * variant the real gate returned for a
     * [METHOD_ATTEMPT_ACCESS_BEFORE_DEADLINE]/[METHOD_ATTEMPT_PROBE_AT_DEADLINE]/
     * [METHOD_RECORD_PROBE_SUCCESS_AND_REVERIFY_RECOVERY] call.
     */
    const val KEY_OUTCOME: String = "outcome"

    /**
     * [io.dataloom.runtime.retry.CircuitBreakerRejectionReason] name when
     * [KEY_OUTCOME] is `"REJECTED"`, or `""` otherwise.
     */
    const val KEY_REJECTION_REASON: String = "rejectionReason"

    /**
     * The granted [io.dataloom.runtime.retry.CircuitBreakerProbePermit.generation]
     * when [KEY_OUTCOME] is `"PROBE_ALLOWED"`, or `-1` otherwise.
     */
    const val KEY_GENERATION: String = "generation"
}
