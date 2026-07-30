package io.dataloom.queue.room

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
import io.dataloom.queue.room.internal.CircuitBreakerCompareAndSetEntityResult
import io.dataloom.queue.room.internal.CircuitBreakerStateDao
import io.dataloom.queue.room.internal.CircuitBreakerStateEntity
import io.dataloom.queue.room.internal.DataLoomRoomDatabase
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class RoomCircuitBreakerStateStoreTest {
    private val scope = CircuitBreakerScope.provider(ProviderId("transport"))
    private lateinit var database: DataLoomRoomDatabase
    private lateinit var dao: CircuitBreakerStateDao
    private lateinit var store: RoomCircuitBreakerStateStore

    @Before
    fun setUp() {
        database = mock()
        dao = mock()
        whenever(database.circuitBreakerStateDao()).thenReturn(dao)
        store = RoomCircuitBreakerStateStore(database)
    }

    @Test
    fun `missing row is returned as an explicit missing result`() {
        runBlocking {
            whenever(dao.load(any())).thenReturn(null)

            val result = assertIs<ProviderOperationResult.Success<CircuitBreakerLoadResult>>(
                store.load(scope),
            )

            assertIs<CircuitBreakerLoadResult.Missing>(result.value)
        }
    }

    @Test
    fun `compare and set conflict preserves the current durable record`() {
        runBlocking {
            val current = validEntity(recordVersion = 2L)
            whenever(dao.compareAndSet(eq(1L), any())).thenReturn(
                CircuitBreakerCompareAndSetEntityResult.Conflict(current),
            )

            val result = assertIs<ProviderOperationResult.Success<CircuitBreakerCompareAndSetResult>>(
                store.compareAndSet(
                    CircuitBreakerCompareAndSetRequest(
                        scope = scope,
                        expectedVersion = 1L,
                        nextState = closedState(),
                    ),
                ),
            )
            val conflict = assertIs<CircuitBreakerCompareAndSetResult.Conflict>(result.value)

            assertEquals(2L, conflict.current?.version)
            assertEquals(scope, conflict.current?.state?.scope)
        }
    }

    @Test
    fun `malformed durable row fails closed as an integrity failure`() {
        runBlocking {
            whenever(dao.load(any())).thenReturn(validEntity(phase = "BROKEN"))

            val result = assertIs<ProviderOperationResult.Failure>(store.load(scope))

            assertEquals("CIRCUIT_ROOM_STATE_CORRUPT", result.error.code.value)
            assertEquals(Recoverability.NON_RECOVERABLE, result.error.recoverability)
            assertEquals("Persisted circuit state failed integrity validation.", result.error.message)
        }
    }

    @Test
    fun `record version exhaustion is non recoverable and does not access Room`() {
        runBlocking {
            val result = assertIs<ProviderOperationResult.Failure>(
                store.compareAndSet(
                    CircuitBreakerCompareAndSetRequest(
                        scope = scope,
                        expectedVersion = Long.MAX_VALUE,
                        nextState = closedState(),
                    ),
                ),
            )

            assertEquals("CIRCUIT_STATE_VERSION_EXHAUSTED", result.error.code.value)
            assertEquals(Recoverability.NON_RECOVERABLE, result.error.recoverability)
            verifyNoInteractions(dao)
        }
    }

    @Test
    fun `database failure is sanitized and recoverable`() {
        runBlocking {
            whenever(dao.load(any())).thenThrow(mock<android.database.sqlite.SQLiteException>())

            val result = assertIs<ProviderOperationResult.Failure>(store.load(scope))

            assertEquals("CIRCUIT_ROOM_DATABASE_FAILURE", result.error.code.value)
            assertEquals(Recoverability.RECOVERABLE, result.error.recoverability)
            assertEquals("A circuit-state database operation failed.", result.error.message)
        }
    }

    @Test
    fun `database cancellation propagates unchanged`() {
        val expected = CancellationException("cancelled")
        runBlocking {
            whenever(dao.load(any())).thenThrow(expected)
        }

        val actual = assertFailsWith<CancellationException> {
            runBlocking { store.load(scope) }
        }

        assertEquals("cancelled", actual.message)
    }

    private fun closedState(): CircuitBreakerState = CircuitBreakerState(
        scope = scope,
        phase = CircuitBreakerPhase.CLOSED,
        consecutiveFailures = 0,
        failureWindowStartedAt = null,
        openUntil = null,
        probeGeneration = 0L,
        probeInFlight = false,
        updatedAt = DataLoomInstant(1_000L),
    )

    private fun validEntity(
        recordVersion: Long = 0L,
        phase: String = CircuitBreakerPhase.CLOSED.name,
    ): CircuitBreakerStateEntity = CircuitBreakerStateEntity(
        scopeKey = "PROVIDER|9:transport|-|-|-",
        scopeKind = "PROVIDER",
        providerId = "transport",
        operation = null,
        tenantId = null,
        workflowId = null,
        phase = phase,
        consecutiveFailures = 0,
        failureWindowStartedAtMs = null,
        openUntilMs = null,
        probeGeneration = 0L,
        probeInFlight = false,
        probeLeaseUntilMs = null,
        updatedAtMs = 1_000L,
        recordVersion = recordVersion,
    )
}
