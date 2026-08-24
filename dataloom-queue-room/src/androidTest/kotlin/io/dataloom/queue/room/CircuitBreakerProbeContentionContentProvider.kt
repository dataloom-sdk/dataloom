package io.dataloom.queue.room

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import androidx.room.Room
import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.queue.room.internal.DataLoomRoomDatabase
import io.dataloom.runtime.retry.CircuitBreakerConfiguration
import io.dataloom.runtime.retry.CircuitBreakerCoordinator
import io.dataloom.runtime.retry.CircuitBreakerExecutionGate
import io.dataloom.runtime.retry.CircuitBreakerPermission
import io.dataloom.runtime.retry.CircuitProtectedOperationResult
import kotlinx.coroutines.runBlocking

/**
 * Test-only [ContentProvider] declared *twice* in
 * `src/androidTest/AndroidManifest.xml` -- once under
 * [CircuitBreakerProbeContentionContract.AUTHORITY_A] /
 * [CircuitBreakerProbeContentionContract.PROCESS_SUFFIX_A], once under
 * [CircuitBreakerProbeContentionContract.AUTHORITY_B] /
 * [CircuitBreakerProbeContentionContract.PROCESS_SUFFIX_B] -- so Android
 * hosts two independent instances of this exact same class in two genuinely
 * separate OS processes. [AndroidCircuitBreakerProbeContentionInstrumentedTest]
 * uses that to prove that when both processes race to acquire the single
 * half-open probe permit for the same circuit scope against the same on-disk
 * database at (as close to) the same real wall-clock moment as two real OS
 * processes allow, exactly one wins and the other is genuinely rejected --
 * enforced by [RoomCircuitBreakerStateStore]'s real atomic
 * compare-and-set (a single `UPDATE ... WHERE record_version = :expectedVersion`
 * SQL statement Android's SQLite driver serializes across processes via its
 * own file locking), not a test-only mutex.
 *
 * Every entry point opens a fresh [DataLoomRoomDatabase] connection to the
 * on-disk database named by the call argument and drives the real
 * [CircuitBreakerCoordinator]/[CircuitBreakerExecutionGate] production
 * pipeline against a real [RoomCircuitBreakerStateStore] -- never touching
 * internal state directly. Every call uses a wall-clock-backed
 * [DataLoomClock] rather than a fixed test clock, because the two racing
 * processes have no shared in-memory clock to synchronize on; only real
 * system time is common between them.
 */
public class CircuitBreakerProbeContentionContentProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val databaseName = requireNotNull(arg) {
            "CircuitBreakerProbeContentionContentProvider requires a database name argument."
        }
        val appContext = requireNotNull(context) {
            "CircuitBreakerProbeContentionContentProvider has no attached Context."
        }
        return when (method) {
            CircuitBreakerProbeContentionContract.METHOD_WARM_UP -> {
                runBlocking { warmUp(appContext, databaseName) }
            }
            CircuitBreakerProbeContentionContract.METHOD_OPEN_CIRCUIT -> {
                runBlocking { openCircuit(appContext, databaseName) }
            }
            CircuitBreakerProbeContentionContract.METHOD_ATTEMPT_PROBE -> {
                runBlocking { attemptProbe(appContext, databaseName) }
            }
            CircuitBreakerProbeContentionContract.METHOD_READ_CIRCUIT_STATE -> {
                runBlocking { readCircuitState(appContext, databaseName) }
            }
            else -> error("Unknown CircuitBreakerProbeContentionContentProvider method: $method")
        }
    }

    private suspend fun warmUp(context: Context, databaseName: String): Bundle {
        val database = openDatabase(context, databaseName)
        try {
            RoomCircuitBreakerStateStore(database).load(SCOPE)
        } finally {
            database.close()
        }
        return Bundle().apply {
            putInt(CircuitBreakerProbeContentionContract.KEY_PID, android.os.Process.myPid())
        }
    }

    private suspend fun openCircuit(context: Context, databaseName: String): Bundle {
        val database = openDatabase(context, databaseName)
        try {
            val gate = gate(RealTimeClock(), RoomCircuitBreakerStateStore(database))
            val failure = InjectedTransportFailure()

            gate.execute<Unit>(SCOPE) { CircuitProtectedOperationResult.Failure(failure) }
            gate.execute<Unit>(SCOPE) { CircuitProtectedOperationResult.Failure(failure) }

            return Bundle().apply {
                putInt(CircuitBreakerProbeContentionContract.KEY_PID, android.os.Process.myPid())
            }
        } finally {
            database.close()
        }
    }

    private suspend fun attemptProbe(context: Context, databaseName: String): Bundle {
        val database = openDatabase(context, databaseName)
        try {
            val coordinator = CircuitBreakerCoordinator(
                configuration = configuration(),
                clock = RealTimeClock(),
                stateStore = RoomCircuitBreakerStateStore(database),
            )
            val bundle = Bundle().apply {
                putInt(CircuitBreakerProbeContentionContract.KEY_PID, android.os.Process.myPid())
            }
            when (val permission = coordinator.acquire(SCOPE)) {
                CircuitBreakerPermission.Allowed -> {
                    bundle.putString(CircuitBreakerProbeContentionContract.KEY_OUTCOME, "ALLOWED_NO_CIRCUIT")
                    bundle.putLong(CircuitBreakerProbeContentionContract.KEY_GENERATION, -1L)
                    bundle.putString(CircuitBreakerProbeContentionContract.KEY_REJECTION_REASON, "")
                }
                is CircuitBreakerPermission.ProbeAllowed -> {
                    bundle.putString(CircuitBreakerProbeContentionContract.KEY_OUTCOME, "ALLOWED")
                    bundle.putLong(CircuitBreakerProbeContentionContract.KEY_GENERATION, permission.permit.generation)
                    bundle.putString(CircuitBreakerProbeContentionContract.KEY_REJECTION_REASON, "")
                }
                is CircuitBreakerPermission.Rejected -> {
                    bundle.putString(CircuitBreakerProbeContentionContract.KEY_OUTCOME, "REJECTED")
                    bundle.putLong(CircuitBreakerProbeContentionContract.KEY_GENERATION, -1L)
                    bundle.putString(CircuitBreakerProbeContentionContract.KEY_REJECTION_REASON, permission.reason.name)
                }
                is CircuitBreakerPermission.PersistenceFailure -> {
                    bundle.putString(CircuitBreakerProbeContentionContract.KEY_OUTCOME, "PERSISTENCE_FAILURE")
                    bundle.putLong(CircuitBreakerProbeContentionContract.KEY_GENERATION, -1L)
                    bundle.putString(CircuitBreakerProbeContentionContract.KEY_REJECTION_REASON, "")
                }
                CircuitBreakerPermission.ContentionLimitReached -> {
                    bundle.putString(CircuitBreakerProbeContentionContract.KEY_OUTCOME, "CONTENTION_LIMIT")
                    bundle.putLong(CircuitBreakerProbeContentionContract.KEY_GENERATION, -1L)
                    bundle.putString(CircuitBreakerProbeContentionContract.KEY_REJECTION_REASON, "")
                }
            }
            return bundle
        } finally {
            database.close()
        }
    }

    private suspend fun readCircuitState(context: Context, databaseName: String): Bundle {
        val database = openDatabase(context, databaseName)
        try {
            val loaded = RoomCircuitBreakerStateStore(database).load(SCOPE)
            val result = loaded as io.dataloom.api.provider.ProviderOperationResult.Success<*>
            return when (val value = result.value as io.dataloom.api.circuit.CircuitBreakerLoadResult) {
                is io.dataloom.api.circuit.CircuitBreakerLoadResult.Found -> Bundle().apply {
                    putInt(CircuitBreakerProbeContentionContract.KEY_PID, android.os.Process.myPid())
                    putString(CircuitBreakerProbeContentionContract.KEY_PHASE, value.record.state.phase.name)
                    putLong(
                        CircuitBreakerProbeContentionContract.KEY_PROBE_GENERATION,
                        value.record.state.probeGeneration,
                    )
                    putBoolean(
                        CircuitBreakerProbeContentionContract.KEY_PROBE_IN_FLIGHT,
                        value.record.state.probeInFlight,
                    )
                }
                io.dataloom.api.circuit.CircuitBreakerLoadResult.Missing -> Bundle().apply {
                    putInt(CircuitBreakerProbeContentionContract.KEY_PID, android.os.Process.myPid())
                    putString(CircuitBreakerProbeContentionContract.KEY_PHASE, "MISSING")
                    putLong(CircuitBreakerProbeContentionContract.KEY_PROBE_GENERATION, -1L)
                    putBoolean(CircuitBreakerProbeContentionContract.KEY_PROBE_IN_FLIGHT, false)
                }
            }
        } finally {
            database.close()
        }
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
    ): CircuitBreakerExecutionGate = CircuitBreakerExecutionGate(
        CircuitBreakerCoordinator(
            configuration = configuration(),
            clock = clock,
            stateStore = store,
        ),
    )

    /**
     * A short, generous-margin configuration shared by every entry point:
     * [failureThreshold] = 2 real failures opens the circuit almost
     * instantly; [openDuration] = 400ms is long enough that
     * [android.content.ContentResolver.call] round-trips comfortably finish
     * before it elapses, but short enough that the test only needs a brief
     * real sleep to guarantee it has elapsed before racing; the wide
     * [failureWindow] and [halfOpenProbeLeaseDuration] exist purely to avoid
     * any timing coincidence unrelated to the actual race under test.
     */
    private fun configuration(): CircuitBreakerConfiguration = CircuitBreakerConfiguration(
        failureThreshold = 2,
        failureWindow = SchedulingDelay(30_000L),
        openDuration = SchedulingDelay(400L),
        halfOpenProbeLeaseDuration = SchedulingDelay(5_000L),
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

    /**
     * Real wall-clock [DataLoomClock]. The two racing processes share no
     * in-memory state, so a fixed/manually-advanced test clock (as used by
     * [CircuitBreakerProcessTerminationContentProvider], which only ever
     * runs one process at a time) cannot represent "now" consistently
     * between them -- only genuine system time can.
     */
    private class RealTimeClock : DataLoomClock {
        override fun now(): DataLoomInstant = DataLoomInstant(System.currentTimeMillis())
    }

    private data class InjectedTransportFailure(
        override val code: ErrorCode = ErrorCode("CIRCUIT_PROBE_CONTENTION_INJECTED_TRANSPORT_FAILURE"),
        override val category: ErrorCategory = ErrorCategory.NETWORK,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Sanitized injected failure for probe-contention proof.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private companion object {
        val SCOPE = CircuitBreakerScope.provider(ProviderId("circuit-probe-contention"))
    }
}
