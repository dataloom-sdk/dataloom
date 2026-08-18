package io.dataloom.queue.room

/**
 * Shared method names, argument keys, and result-bundle keys used by
 * [RetryBudgetProcessTerminationContentProvider] and
 * [AndroidProcessTerminationRetryBudgetInstrumentedTest] to exchange a real
 * durable retry-budget proof across a genuine Android process boundary.
 *
 * Kept as plain string/const constants (not a shared interface) because the
 * two sides communicate only through [android.content.ContentResolver.call],
 * which is itself untyped -- there is no compiled contract between separate
 * OS processes. Mirrors [CircuitBreakerProcessTerminationContract], which
 * serves the same role for the separate circuit-breaker durable structure.
 */
internal object RetryBudgetProcessTerminationContract {
    /** Authority of [RetryBudgetProcessTerminationContentProvider]. Test-APK only. */
    const val AUTHORITY: String = "io.dataloom.queue.room.test.retrybudgetproof"

    /** Process suffix declared for the provider in src/androidTest/AndroidManifest.xml. */
    const val PROCESS_SUFFIX: String = ":retrybudgetproof"

    /**
     * Enqueues a real queue entry and drives it through the real
     * [io.dataloom.queue.room.RoomQueueProvider] production coordinator's
     * `acquire` -> `reschedule` -> `acquire` -> `defer` sequence so a genuine
     * retry attempt number and retry-budget window/cumulative-delay are
     * persisted, then returns that persisted state read back from an
     * independent acquisition. `arg` is the on-disk database name to open.
     */
    const val METHOD_WRITE_RETRY_BUDGET: String = "writeRetryBudget"

    /**
     * Opens a brand-new connection to the same on-disk database and acquires
     * the same entry again to read back whatever retry-budget state is
     * currently persisted there. `arg` is the on-disk database name to open.
     */
    const val METHOD_READ_RETRY_BUDGET: String = "readRetryBudget"

    /** This process's pid ([Int]), from [android.os.Process.myPid]. */
    const val KEY_PID: String = "pid"

    /** [io.dataloom.api.retry.RetryAttempt.number], or -1 if absent. */
    const val KEY_RETRY_ATTEMPT_NUMBER: String = "retryAttemptNumber"

    /** Epoch-millisecond retry-budget window start. */
    const val KEY_RETRY_WINDOW_STARTED_AT_MILLIS: String = "retryWindowStartedAtMillis"

    /** Epoch-millisecond most recent accepted retry-budget evaluation instant. */
    const val KEY_RETRY_LAST_EVALUATED_AT_MILLIS: String = "retryLastEvaluatedAtMillis"

    /** Sum of delays accepted for durable retry transitions, in milliseconds. */
    const val KEY_RETRY_CUMULATIVE_DELAY_MILLIS: String = "retryCumulativeDelayMillis"
}
