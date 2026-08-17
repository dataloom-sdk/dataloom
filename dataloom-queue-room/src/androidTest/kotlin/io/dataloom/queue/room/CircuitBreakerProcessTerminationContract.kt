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

    /** This process's pid ([Int]), from [android.os.Process.myPid]. */
    const val KEY_PID: String = "pid"

    /** [io.dataloom.api.circuit.CircuitBreakerPhase] name, or "MISSING" if no record exists. */
    const val KEY_PHASE: String = "phase"

    /** Epoch-millisecond open deadline, or -1 if absent. */
    const val KEY_OPEN_UNTIL_MILLIS: String = "openUntilMillis"

    const val KEY_CONSECUTIVE_FAILURES: String = "consecutiveFailures"

    const val KEY_PROBE_GENERATION: String = "probeGeneration"
}
