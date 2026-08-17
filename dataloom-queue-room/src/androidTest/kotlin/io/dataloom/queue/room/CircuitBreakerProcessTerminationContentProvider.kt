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
            else -> error("Unknown CircuitBreakerProcessTerminationContentProvider method: $method")
        }
    }

    private suspend fun openCircuit(context: Context, databaseName: String): Bundle {
        val database = openDatabase(context, databaseName)
        try {
            val clock = MutableClock(1_000L)
            val gate = gate(clock, RoomCircuitBreakerStateStore(database))
            val failure = InjectedTransportFailure()

            gate.execute<Unit>(SCOPE) { CircuitProtectedOperationResult.Failure(failure) }

            clock.nowMillis = 1_040L
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
    ): CircuitBreakerExecutionGate = CircuitBreakerExecutionGate(
        CircuitBreakerCoordinator(
            configuration = CircuitBreakerConfiguration(
                failureThreshold = 2,
                failureWindow = SchedulingDelay(5_000L),
                openDuration = SchedulingDelay(1_000L),
                halfOpenProbeLeaseDuration = SchedulingDelay(500L),
            ),
            clock = clock,
            stateStore = store,
        ),
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
    }
}
