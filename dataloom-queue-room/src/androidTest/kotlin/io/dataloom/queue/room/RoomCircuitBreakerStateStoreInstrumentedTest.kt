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
    fun compareAndSetPersistsVersionedProbeLeaseAcrossReopen() = kotlinx.coroutines.test.runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "dataloom-circuit-store-test"
        context.deleteDatabase(name)
        val scope = CircuitBreakerScope.provider(ProviderId("transport"))
        val state = CircuitBreakerState(
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

        val firstDatabase = Room.databaseBuilder(context, DataLoomRoomDatabase::class.java, name)
            .addMigrations(*DataLoomRoomMigrations.ALL)
            .build()
        val firstStore = RoomCircuitBreakerStateStore(firstDatabase)
        val inserted = assertIs<ProviderOperationResult.Success<CircuitBreakerCompareAndSetResult>>(
            firstStore.compareAndSet(CircuitBreakerCompareAndSetRequest(scope, null, state)),
        )
        assertEquals(0L, assertIs<CircuitBreakerCompareAndSetResult.Updated>(inserted.value).record.version)
        firstDatabase.close()

        val reopened = Room.databaseBuilder(context, DataLoomRoomDatabase::class.java, name)
            .addMigrations(*DataLoomRoomMigrations.ALL)
            .build()
        try {
            val loaded = assertIs<ProviderOperationResult.Success<CircuitBreakerLoadResult>>(
                RoomCircuitBreakerStateStore(reopened).load(scope),
            )
            val record = assertIs<CircuitBreakerLoadResult.Found>(loaded.value).record
            assertEquals(0L, record.version)
            assertEquals(DataLoomInstant(2_000L), record.state.probeLeaseUntil)
        } finally {
            reopened.close()
            context.deleteDatabase(name)
        }
    }
}