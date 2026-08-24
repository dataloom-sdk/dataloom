package io.dataloom.queue.room

/**
 * Shared method names, argument keys, and result-bundle keys used by
 * [CircuitBreakerProbeContentionContentProviderA],
 * [CircuitBreakerProbeContentionContentProviderB], and
 * [AndroidCircuitBreakerProbeContentionInstrumentedTest] to exchange a real
 * genuine cross-process half-open probe contention proof across two separate
 * Android OS process boundaries.
 *
 * Unlike [CircuitBreakerProcessTerminationContract] (one second process,
 * called sequentially before/after a kill), this contract is served by two
 * genuinely different provider classes -- [CircuitBreakerProbeContentionContentProviderA]
 * and [CircuitBreakerProbeContentionContentProviderB], both extending the
 * shared [CircuitBreakerProbeContentionContentProviderBase] -- declared once
 * each in `src/androidTest/AndroidManifest.xml` under two different
 * authorities and two different `android:process` values --
 * [AUTHORITY_A]/[PROCESS_SUFFIX_A] and [AUTHORITY_B]/[PROCESS_SUFFIX_B] --
 * so the test can drive two genuinely separate, concurrently-racing OS
 * processes against the same on-disk circuit-breaker database at once. Two
 * distinct classes are required, not one class declared twice: Android's
 * `PackageManagerService` addresses every component by `ComponentName`
 * (package + class name) and only supports one live registration per
 * `ComponentName` at runtime, even though a class declared twice in the
 * manifest compiles and packages without error -- see
 * [CircuitBreakerProbeContentionContentProviderBase]'s class doc for the
 * real-device failure this was found by.
 *
 * Kept as plain string/const constants (not a shared interface) because the
 * two sides communicate only through [android.content.ContentResolver.call],
 * which is itself untyped -- there is no compiled contract between separate
 * OS processes.
 */
internal object CircuitBreakerProbeContentionContract {
    /** Authority of the provider instance hosted in the [PROCESS_SUFFIX_A] process. */
    const val AUTHORITY_A: String = "io.dataloom.queue.room.test.circuitprobea"

    /** Process suffix declared for the [AUTHORITY_A] provider instance. */
    const val PROCESS_SUFFIX_A: String = ":circuitprobea"

    /** Authority of the provider instance hosted in the [PROCESS_SUFFIX_B] process. */
    const val AUTHORITY_B: String = "io.dataloom.queue.room.test.circuitprobeb"

    /** Process suffix declared for the [AUTHORITY_B] provider instance. */
    const val PROCESS_SUFFIX_B: String = ":circuitprobeb"

    /**
     * Opens (or no-ops against) the on-disk database named by `arg`, forcing
     * Room/SQLite to create the schema and this provider's host process to
     * start, without mutating any circuit-breaker state. Used purely to warm
     * up a process before a race so neither racer wins solely because the
     * other was still cold-starting.
     */
    const val METHOD_WARM_UP: String = "warmUp"

    /**
     * Drives two real eligible failures through a real
     * [io.dataloom.runtime.retry.CircuitBreakerExecutionGate] using a
     * wall-clock-backed [io.dataloom.api.time.DataLoomClock] (not a fixed
     * test clock -- both racing processes must observe the same real time),
     * so the circuit opens. `arg` is the on-disk database name to open.
     */
    const val METHOD_OPEN_CIRCUIT: String = "openCircuit"

    /**
     * Attempts to acquire circuit-breaker permission for the shared scope
     * against the on-disk database named by `arg`, using the real
     * [io.dataloom.runtime.retry.CircuitBreakerCoordinator.acquire]
     * production entry point -- the same call the production
     * [io.dataloom.runtime.retry.CircuitBreakerExecutionGate] makes before
     * running an operation. Returns the raw permission outcome without
     * executing or recording any operation, so this proves permit
     * acquisition contention in isolation.
     */
    const val METHOD_ATTEMPT_PROBE: String = "attemptProbe"

    /**
     * Opens a brand-new connection to the same on-disk database and returns
     * whatever circuit state is currently persisted there. `arg` is the
     * on-disk database name to open.
     */
    const val METHOD_READ_CIRCUIT_STATE: String = "readCircuitState"

    /** This process's pid ([Int]), from [android.os.Process.myPid]. */
    const val KEY_PID: String = "pid"

    /**
     * One of "ALLOWED" ([io.dataloom.runtime.retry.CircuitBreakerPermission.ProbeAllowed]),
     * "REJECTED" ([io.dataloom.runtime.retry.CircuitBreakerPermission.Rejected]),
     * "ALLOWED_NO_CIRCUIT" ([io.dataloom.runtime.retry.CircuitBreakerPermission.Allowed] --
     * would mean the circuit was not actually open, a setup failure), or
     * "PERSISTENCE_FAILURE"/"CONTENTION_LIMIT" for the remaining
     * [io.dataloom.runtime.retry.CircuitBreakerPermission] cases. Set only by
     * [METHOD_ATTEMPT_PROBE].
     */
    const val KEY_OUTCOME: String = "outcome"

    /** Granted probe generation ([Long]), or -1 if not granted. Set only by [METHOD_ATTEMPT_PROBE]. */
    const val KEY_GENERATION: String = "generation"

    /**
     * [io.dataloom.runtime.retry.CircuitBreakerRejectionReason] name, or "" if
     * the outcome was not a rejection. Set only by [METHOD_ATTEMPT_PROBE].
     */
    const val KEY_REJECTION_REASON: String = "rejectionReason"

    /** [io.dataloom.api.circuit.CircuitBreakerPhase] name, or "MISSING" if no record exists. */
    const val KEY_PHASE: String = "phase"

    /** Persisted probe generation, or -1 if absent. */
    const val KEY_PROBE_GENERATION: String = "probeGeneration"

    /** Persisted probe-in-flight flag. */
    const val KEY_PROBE_IN_FLIGHT: String = "probeInFlight"
}
