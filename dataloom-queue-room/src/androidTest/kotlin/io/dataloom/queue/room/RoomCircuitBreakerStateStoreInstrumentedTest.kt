package io.dataloom.queue.room

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.dataloom.api.circuit.CircuitBreakerCompareAndSetRequest
import io.dataloom.api.circuit.CircuitBreakerCompareAndSetResult
import io.dataloom.api.circuit.CircuitBreakerLoadResult
import io.dataloom.api.circuit.CircuitBreakerPhase
import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.circuit.CircuitBreakerState
import io.dataloom.api.error.Recoverability
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.queue.room.internal.DataLoomRoomDatabase
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomCircuitBreakerStateStoreInstrumentedTest {
    @Test
    fun compareAndSetPersistsUpdatesConflictsAndReopens() = kotlinx.coroutines.test.runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "dataloom-circuit-store-cas-test"
        context.deleteDatabase(name)
        val scope = CircuitBreakerScope.provider(ProviderId("transport"))
        val halfOpen = CircuitBreakerState(
            scope = scope,
            phase = CircuitBreakerPhase.HALF_OPEN,
            consecutiveFailures = 0,
            failureWindowStartedAt = null,
            openUntil = null,
            probeGeneration = 2L,
            probeInFlight = true,
            updatedAt = DataLoomInstant(1_000L),
            probeLeaseUntil = DataLoomInstant(2_000L),
        )
        val reopenedState = CircuitBreakerState(
            scope = scope,
            phase = CircuitBreakerPhase.OPEN,
            consecutiveFailures = 0,
            failureWindowStartedAt = null,
            openUntil = DataLoomInstant(3_500L),
            probeGeneration = 2L,
            probeInFlight = false,
            updatedAt = DataLoomInstant(2_500L),
        )

        val firstDatabase = database(context, name)
        try {
            val store = RoomCircuitBreakerStateStore(firstDatabase)
            val inserted = assertIs<ProviderOperationResult.Success<CircuitBreakerCompareAndSetResult>>(
                store.compareAndSet(CircuitBreakerCompareAndSetRequest(scope, null, halfOpen)),
            )
            assertEquals(
                0L,
                assertIs<CircuitBreakerCompareAndSetResult.Updated>(inserted.value).record.version,
            )

            val updated = assertIs<ProviderOperationResult.Success<CircuitBreakerCompareAndSetResult>>(
                store.compareAndSet(CircuitBreakerCompareAndSetRequest(scope, 0L, reopenedState)),
            )
            assertEquals(
                1L,
                assertIs<CircuitBreakerCompareAndSetResult.Updated>(updated.value).record.version,
            )

            val conflict = assertIs<ProviderOperationResult.Success<CircuitBreakerCompareAndSetResult>>(
                store.compareAndSet(CircuitBreakerCompareAndSetRequest(scope, 0L, halfOpen)),
            )
            val current = assertIs<CircuitBreakerCompareAndSetResult.Conflict>(conflict.value).current
            assertEquals(1L, current?.version)
            assertEquals(CircuitBreakerPhase.OPEN, current?.state?.phase)
        } finally {
            firstDatabase.close()
        }

        val reopened = database(context, name)
        try {
            val loaded = assertIs<ProviderOperationResult.Success<CircuitBreakerLoadResult>>(
                RoomCircuitBreakerStateStore(reopened).load(scope),
            )
            val record = assertIs<CircuitBreakerLoadResult.Found>(loaded.value).record
            assertEquals(1L, record.version)
            assertEquals(CircuitBreakerPhase.OPEN, record.state.phase)
            assertEquals(DataLoomInstant(3_500L), record.state.openUntil)
            assertEquals(null, record.state.probeLeaseUntil)
        } finally {
            reopened.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun malformedPersistedRowFailsClosedWithSanitizedError() = kotlinx.coroutines.test.runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "dataloom-circuit-store-corrupt-test"
        context.deleteDatabase(name)
        val database = database(context, name)
        val scope = CircuitBreakerScope.provider(ProviderId("transport"))
        try {
            database.openHelper.writableDatabase.execSQL(
                """
                INSERT INTO circuit_breaker_states (
                    scope_key, scope_kind, provider_id, operation, tenant_id, workflow_id,
                    phase, consecutive_failures, failure_window_started_at_ms, open_until_ms,
                    probe_generation, probe_in_flight, probe_lease_until_ms, updated_at_ms,
                    record_version
                ) VALUES (
                    'PROVIDER|9:transport|-|-|-', 'PROVIDER', 'transport', NULL, NULL, NULL,
                    'BROKEN_PHASE', 0, NULL, NULL, 0, 0, NULL, 1000, 0
                )
                """.trimIndent(),
            )

            val result = assertIs<ProviderOperationResult.Failure>(
                RoomCircuitBreakerStateStore(database).load(scope),
            )
            assertEquals("CIRCUIT_ROOM_STATE_CORRUPT", result.error.code.value)
            assertEquals(Recoverability.NON_RECOVERABLE, result.error.recoverability)
            assertEquals("Persisted circuit state failed integrity validation.", result.error.message)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private fun database(
        context: android.content.Context,
        name: String,
    ): DataLoomRoomDatabase = Room.databaseBuilder(
        context,
        DataLoomRoomDatabase::class.java,
        name,
    ).addMigrations(*DataLoomRoomMigrations.ALL)
        .build()
}
