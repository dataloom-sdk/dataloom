package io.dataloom.queue.room

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import androidx.room.Room
import io.dataloom.api.circuit.CircuitBreakerLoadResult
import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.circuit.CircuitBreakerState
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.queue.room.internal.DataLoomRoomDatabase
import io.dataloom.runtime.retry.CircuitBreakerConfiguration
import io.dataloom.runtime.retry.CircuitBreakerCoordinator
import io.dataloom.runtime.retry.CircuitBreakerExecutionGate
import io.dataloom.runtime.retry.CircuitBreakerExecutionResult
import io.dataloom.runtime.retry.CircuitBreakerPermission
import io.dataloom.runtime.retry.CircuitBreakerProbePermit
import io.dataloom.runtime.retry.CircuitBreakerRecordResult
import io.dataloom.runtime.retry.CircuitProtectedOperationResult
import io.dataloom.api.scheduling.SchedulingDelay
import kotlinx.coroutines.runBlocking

/**
 * Test-only [ContentProvider] hosted in its own `:circuitproof` process (see
 * `src/androidTest/AndroidManifest.xml`), used exclusively by
 * [AndroidProcessTerminationCircuitBreakerInstrumentedTest] to prove that
 * persisted circuit-breaker state survives a genuine Android OS process
 * kill and relaunch -- not a same-process database close/reopen simulation
 * like [RoomCircuitBreakerStateStoreInstrumentedTest] and
 * [RoomRetryCircuitFunctionalQualificationInstrumentedTest] already provide.
 *
 * Both entry points open a fresh [DataLoomRoomDatabase] connection to the
 * on-disk database named by the call argument and run the real
 * [CircuitBreakerExecutionGate]/[CircuitBreakerCoordinator] production
 * pipeline against a real [RoomCircuitBreakerStateStore] -- never touching
 * internal state directly. Each call also reports [android.os.Process.myPid]
 * so the caller can prove two calls were served by two different OS
 * processes, not a warm reused one.
 */
public class CircuitBreakerProcessTerminationContentProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val databaseName = requireNotNull(arg) {
            "CircuitBreakerProcessTerminationContentProvider requires a database name argument."
        }
        val appContext = requireNotNull(context) {
            "CircuitBreakerProcessTerminationContentProvider has no attached Context."
        }
        return when (method) {
            CircuitBreakerProcessTerminationContract.METHOD_OPEN_CIRCUIT -> {
                runBlocking { openCircuit(appContext, databaseName) }
            }
            CircuitBreakerProcessTerminationContract.METHOD_READ_CIRCUIT_STATE -> {
                runBlocking { readCircuitState(appContext, databaseName) }
            }
            CircuitBreakerProcessTerminationContract.METHOD_ATTEMPT_ACCESS_BEFORE_DEADLINE -> {
                runBlocking { attemptAccessBeforeDeadline(appContext, databaseName) }
            }
            CircuitBreakerProcessTerminationContract.METHOD_ATTEMPT_PROBE_AT_DEADLINE -> {
                runBlocking { attemptProbeAtDeadline(appContext, databaseName) }
            }
            CircuitBreakerProcessTerminationContract.METHOD_RECORD_PROBE_SUCCESS_AND_REVERIFY_RECOVERY -> {
                runBlocking { recordProbeSuccessAndReverifyRecovery(appContext, databaseName) }
            }
            else -> error("Unknown CircuitBreakerProcessTerminationContentProvider method: $method")
        }
    }

    private suspend fun openCircuit(context: Context, databaseName: String): Bundle {
        val database = openDatabase(context, databaseName)
        try {
            val clock = MutableClock(FIRST_FAILURE_AT_MS)
            val gate = gate(clock, RoomCircuitBreakerStateStore(database))
            val failure = InjectedTransportFailure()

            gate.execute<Unit>(SCOPE) { CircuitProtectedOperationResult.Failure(failure) }

            clock.nowMillis = SECOND_FAILURE_AT_MS
            val second = gate.execute<Unit>(SCOPE) { CircuitProtectedOperationResult.Failure(failure) }
            val executed = second as CircuitBreakerExecutionResult.Executed<*>
            val recorded = executed.recordResult as CircuitBreakerRecordResult.Recorded

            return stateBundle(recorded.record.state)
        } finally {
            database.close()
        }
    }

    private suspend fun readCircuitState(context: Context, databaseName: String): Bundle {
        val database = openDatabase(context, databaseName)
        try {
            val loaded = RoomCircuitBreakerStateStore(database).load(SCOPE)
            val result = loaded as ProviderOperationResult.Success<CircuitBreakerLoadResult>
            return when (val value = result.value) {
                is CircuitBreakerLoadResult.Found -> stateBundle(value.record.state)
                CircuitBreakerLoadResult.Missing -> Bundle().apply {
                    putInt(CircuitBreakerProcessTerminationContract.KEY_PID, android.os.Process.myPid())
                    putString(CircuitBreakerProcessTerminationContract.KEY_PHASE, "MISSING")
                    putLong(CircuitBreakerProcessTerminationContract.KEY_OPEN_UNTIL_MILLIS, -1L)
                    putInt(CircuitBreakerProcessTerminationContract.KEY_CONSECUTIVE_FAILURES, -1)
                    putLong(CircuitBreakerProcessTerminationContract.KEY_PROBE_GENERATION, -1L)
                }
            }
        } finally {
            database.close()
        }
    }

    /**
     * Re-drives the real gate exactly one millisecond *before* the circuit's
     * deterministic open-deadline ([OPEN_UNTIL_MS]), from a fresh connection
     * to the same on-disk database -- proof the relaunched process's real
     * [CircuitBreakerCoordinator.acquire] genuinely still rejects access
     * while open, not just that the persisted phase reads back as OPEN.
     */
    private suspend fun attemptAccessBeforeDeadline(context: Context, databaseName: String): Bundle {
        val database = openDatabase(context, databaseName)
        try {
            val coordinator = coordinator(MutableClock(OPEN_UNTIL_MS - 1L), RoomCircuitBreakerStateStore(database))
            return permissionBundle(coordinator.acquire(SCOPE))
        } finally {
            database.close()
        }
    }

    /**
     * Re-drives the real gate exactly *at* the circuit's deterministic
     * open-deadline ([OPEN_UNTIL_MS]), from a fresh connection to the same
     * on-disk database -- proof the relaunched process's real
     * [CircuitBreakerCoordinator.acquire] genuinely grants exactly one
     * half-open probe permission (generation [EXPECTED_PROBE_GENERATION]),
     * mirroring the single-probe-permit semantics
     * [AndroidCircuitBreakerProbeContentionInstrumentedTest] already proves
     * for the non-relaunch case.
     */
    private suspend fun attemptProbeAtDeadline(context: Context, databaseName: String): Bundle {
        val database = openDatabase(context, databaseName)
        try {
            val coordinator = coordinator(MutableClock(OPEN_UNTIL_MS), RoomCircuitBreakerStateStore(database))
            return permissionBundle(coordinator.acquire(SCOPE))
        } finally {
            database.close()
        }
    }

    /**
     * Records a real success for the probe permit granted by
     * [attemptProbeAtDeadline] through the real
     * [CircuitBreakerCoordinator.recordSuccess], then re-drives
     * [CircuitBreakerCoordinator.acquire] once more from a fresh connection
     * -- proof the relaunched process's real gate genuinely returns to normal
     * ALLOWED state after a successful half-open probe, not just that the
     * persisted phase reads back as CLOSED.
     */
    private suspend fun recordProbeSuccessAndReverifyRecovery(context: Context, databaseName: String): Bundle {
        val database = openDatabase(context, databaseName)
        try {
            val store = RoomCircuitBreakerStateStore(database)
            val recordingCoordinator = coordinator(MutableClock(PROBE_SUCCESS_AT_MS), store)
            val permit = CircuitBreakerProbePermit(SCOPE, EXPECTED_PROBE_GENERATION)
            val recordResult = recordingCoordinator.recordSuccess(SCOPE, permit)
            check(recordResult is CircuitBreakerRecordResult.Recorded) {
                "Expected the relaunched process's real recordSuccess to succeed for the " +
                    "probe granted at the deadline, but got: $recordResult"
            }

            val reverifyCoordinator = coordinator(MutableClock(RECOVERY_REVERIFY_AT_MS), store)
            return permissionBundle(reverifyCoordinator.acquire(SCOPE))
        } finally {
            database.close()
        }
    }

    private fun permissionBundle(permission: CircuitBreakerPermission): Bundle {
        val bundle = Bundle().apply {
            putInt(CircuitBreakerProcessTerminationContract.KEY_PID, android.os.Process.myPid())
        }
        when (permission) {
            CircuitBreakerPermission.Allowed -> {
                bundle.putString(CircuitBreakerProcessTerminationContract.KEY_OUTCOME, "ALLOWED")
                bundle.putLong(CircuitBreakerProcessTerminationContract.KEY_GENERATION, -1L)
                bundle.putString(CircuitBreakerProcessTerminationContract.KEY_REJECTION_REASON, "")
            }
            is CircuitBreakerPermission.ProbeAllowed -> {
                bundle.putString(CircuitBreakerProcessTerminationContract.KEY_OUTCOME, "PROBE_ALLOWED")
                bundle.putLong(CircuitBreakerProcessTerminationContract.KEY_GENERATION, permission.permit.generation)
                bundle.putString(CircuitBreakerProcessTerminationContract.KEY_REJECTION_REASON, "")
            }
            is CircuitBreakerPermission.Rejected -> {
                bundle.putString(CircuitBreakerProcessTerminationContract.KEY_OUTCOME, "REJECTED")
                bundle.putLong(CircuitBreakerProcessTerminationContract.KEY_GENERATION, -1L)
                bundle.putString(
                    CircuitBreakerProcessTerminationContract.KEY_REJECTION_REASON,
                    permission.reason.name,
                )
            }
            is CircuitBreakerPermission.PersistenceFailure -> {
                bundle.putString(CircuitBreakerProcessTerminationContract.KEY_OUTCOME, "PERSISTENCE_FAILURE")
                bundle.putLong(CircuitBreakerProcessTerminationContract.KEY_GENERATION, -1L)
                bundle.putString(CircuitBreakerProcessTerminationContract.KEY_REJECTION_REASON, "")
            }
            CircuitBreakerPermission.ContentionLimitReached -> {
                bundle.putString(CircuitBreakerProcessTerminationContract.KEY_OUTCOME, "CONTENTION_LIMIT")
                bundle.putLong(CircuitBreakerProcessTerminationContract.KEY_GENERATION, -1L)
                bundle.putString(CircuitBreakerProcessTerminationContract.KEY_REJECTION_REASON, "")
            }
        }
        return bundle
    }

    private fun stateBundle(state: CircuitBreakerState): Bundle = Bundle().apply {
        putInt(CircuitBreakerProcessTerminationContract.KEY_PID, android.os.Process.myPid())
        putString(CircuitBreakerProcessTerminationContract.KEY_PHASE, state.phase.name)
        putLong(
            CircuitBreakerProcessTerminationContract.KEY_OPEN_UNTIL_MILLIS,
            state.openUntil?.epochMilliseconds ?: -1L,
        )
        putInt(CircuitBreakerProcessTerminationContract.KEY_CONSECUTIVE_FAILURES, state.consecutiveFailures)
        putLong(CircuitBreakerProcessTerminationContract.KEY_PROBE_GENERATION, state.probeGeneration)
    }

    private fun openDatabase(context: Context, name: String): DataLoomRoomDatabase = Room.databaseBuilder(
        context,
        DataLoomRoomDatabase::class.java,
        name,
    ).addMigrations(*DataLoomRoomMigrations.ALL)
        .build()

    private fun gate(
        clock: DataLoomClock,
        store: RoomCircuitBreakerStateStore,
    ): CircuitBreakerExecutionGate = CircuitBreakerExecutionGate(coordinator(clock, store))

    private fun coordinator(
        clock: DataLoomClock,
        store: RoomCircuitBreakerStateStore,
    ): CircuitBreakerCoordinator = CircuitBreakerCoordinator(
        configuration = CircuitBreakerConfiguration(
            failureThreshold = 2,
            failureWindow = SchedulingDelay(5_000L),
            openDuration = SchedulingDelay(OPEN_DURATION_MS),
            halfOpenProbeLeaseDuration = SchedulingDelay(HALF_OPEN_PROBE_LEASE_MS),
        ),
        clock = clock,
        stateStore = store,
    )

    // ContentProvider query/insert/update/delete/getType are unused by this
    // test-only provider; call() is the sole entry point.
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private class MutableClock(var nowMillis: Long) : DataLoomClock {
        override fun now(): DataLoomInstant = DataLoomInstant(nowMillis)
    }

    private data class InjectedTransportFailure(
        override val code: ErrorCode = ErrorCode("CIRCUIT_PROOF_INJECTED_TRANSPORT_FAILURE"),
        override val category: ErrorCategory = ErrorCategory.NETWORK,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Sanitized injected failure for process-kill circuit proof.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private companion object {
        val SCOPE = CircuitBreakerScope.provider(ProviderId("circuit-proof-process-kill"))

        // Deterministic timeline for openCircuit's fixed-clock two-failure
        // sequence and configuration -- shared with the deadline/probe/
        // recovery re-drive methods below so their fixed clock readings line
        // up exactly with what openCircuit actually persists, without needing
        // to pass the deadline across the process boundary as an argument.
        const val FIRST_FAILURE_AT_MS: Long = 1_000L
        const val SECOND_FAILURE_AT_MS: Long = 1_040L
        const val OPEN_DURATION_MS: Long = 1_000L
        const val HALF_OPEN_PROBE_LEASE_MS: Long = 500L

        // openState() sets openUntil = observedAt(SECOND_FAILURE_AT_MS) + openDuration.
        const val OPEN_UNTIL_MS: Long = SECOND_FAILURE_AT_MS + OPEN_DURATION_MS

        // startProbe() grants current.probeGeneration(0, unchanged by opening
        // the circuit) + 1 for the first probe after the circuit opens.
        const val EXPECTED_PROBE_GENERATION: Long = 1L

        // Any instant after OPEN_UNTIL_MS and within the half-open probe
        // lease (OPEN_UNTIL_MS + HALF_OPEN_PROBE_LEASE_MS = 2_540L).
        const val PROBE_SUCCESS_AT_MS: Long = OPEN_UNTIL_MS + 10L

        // Any instant at or after PROBE_SUCCESS_AT_MS; CLOSED phase grants
        // access regardless of elapsed time, this just avoids a clock
        // regression against the just-recorded success's updatedAt.
        const val RECOVERY_REVERIFY_AT_MS: Long = PROBE_SUCCESS_AT_MS + 10L
    }
}
