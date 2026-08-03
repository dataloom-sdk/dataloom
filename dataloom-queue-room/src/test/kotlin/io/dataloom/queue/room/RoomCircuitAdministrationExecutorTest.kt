package io.dataloom.queue.room

import io.dataloom.api.circuit.AuthorizedCircuitAdministrationCommand
import io.dataloom.api.circuit.CircuitAdministrationAction
import io.dataloom.api.circuit.CircuitAdministrationAuthorizationId
import io.dataloom.api.circuit.CircuitAdministrationCommandId
import io.dataloom.api.circuit.CircuitAdministrationExecutionResult
import io.dataloom.api.circuit.CircuitAdministrationPrincipalId
import io.dataloom.api.circuit.CircuitAdministrationReason
import io.dataloom.api.circuit.CircuitAdministrationRequest
import io.dataloom.api.circuit.CircuitBreakerPhase
import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.circuit.CircuitBreakerState
import io.dataloom.api.circuit.CircuitBreakerStateRecord
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.Recoverability
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.queue.room.internal.CircuitAdministrationExecutionDao
import io.dataloom.queue.room.internal.CircuitAdministrationExecutionEntityResult
import io.dataloom.queue.room.internal.CircuitAdministrationExecutionIntegrityException
import io.dataloom.queue.room.internal.CircuitAdministrationExecutionVersionExhaustedException
import io.dataloom.queue.room.internal.DataLoomRoomDatabase
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class RoomCircuitAdministrationExecutorTest {
    private lateinit var database: DataLoomRoomDatabase
    private lateinit var dao: CircuitAdministrationExecutionDao
    private lateinit var clock: DataLoomClock
    private lateinit var executor: RoomCircuitAdministrationExecutor

    @Before
    fun setUp() {
        database = mock()
        dao = mock()
        clock = mock()
        whenever(database.circuitAdministrationExecutionDao()).thenReturn(dao)
        whenever(clock.now()).thenReturn(DataLoomInstant(3_000L))
        executor = RoomCircuitAdministrationExecutor(database, clock)
    }

    @Test
    fun `applied transaction preserves exact resulting record`() {
        runBlocking {
            val record = resultingRecord()
            whenever(dao.execute(eq(command()), eq(3_000L))).thenReturn(
                CircuitAdministrationExecutionEntityResult.Applied(record),
            )

            val result = assertIs<CircuitAdministrationExecutionResult.Applied>(
                executor.execute(command()),
            )

            assertEquals(record, result.record)
        }
    }

    @Test
    fun `semantic rejection preserves stable reason code`() {
        runBlocking {
            whenever(dao.execute(any(), eq(3_000L))).thenReturn(
                CircuitAdministrationExecutionEntityResult.Rejected(
                    "CIRCUIT_ADMIN_COMMAND_MISSING",
                ),
            )

            val result = assertIs<CircuitAdministrationExecutionResult.Rejected>(
                executor.execute(command()),
            )

            assertEquals("CIRCUIT_ADMIN_COMMAND_MISSING", result.reasonCode)
        }
    }

    @Test
    fun `clock regression is a canonical non recoverable state failure`() {
        runBlocking {
            whenever(dao.execute(any(), eq(3_000L))).thenReturn(
                CircuitAdministrationExecutionEntityResult.ClockRegression,
            )

            val result = assertIs<CircuitAdministrationExecutionResult.Failed>(
                executor.execute(command()),
            )

            assertEquals("CIRCUIT_ADMIN_EXECUTION_CLOCK_REGRESSION", result.failure.code.value)
            assertEquals(ErrorCategory.STATE, result.failure.category)
            assertEquals(Recoverability.NON_RECOVERABLE, result.failure.recoverability)
        }
    }

    @Test
    fun `integrity failure is canonical and redacted`() {
        runBlocking {
            whenever(dao.execute(any(), eq(3_000L))).thenAnswer {
                throw CircuitAdministrationExecutionIntegrityException(
                    IllegalStateException("secret"),
                )
            }

            val result = assertIs<CircuitAdministrationExecutionResult.Failed>(
                executor.execute(command()),
            )

            assertEquals("CIRCUIT_ADMIN_ROOM_EXECUTOR_STATE_CORRUPT", result.failure.code.value)
        }
    }

    @Test
    fun `version exhaustion is non recoverable`() {
        runBlocking {
            whenever(dao.execute(any(), eq(3_000L))).thenAnswer {
                throw CircuitAdministrationExecutionVersionExhaustedException()
            }

            val result = assertIs<CircuitAdministrationExecutionResult.Failed>(
                executor.execute(command()),
            )

            assertEquals("CIRCUIT_ADMIN_STATE_VERSION_EXHAUSTED", result.failure.code.value)
            assertEquals(Recoverability.NON_RECOVERABLE, result.failure.recoverability)
        }
    }

    @Test
    fun `database failure is canonical and recoverable`() {
        runBlocking {
            whenever(dao.execute(any(), eq(3_000L))).thenThrow(
                mock<android.database.sqlite.SQLiteException>(),
            )

            val result = assertIs<CircuitAdministrationExecutionResult.Failed>(
                executor.execute(command()),
            )

            assertEquals("CIRCUIT_ADMIN_ROOM_EXECUTOR_DATABASE_FAILURE", result.failure.code.value)
            assertEquals(ErrorCategory.STORAGE, result.failure.category)
            assertEquals(Recoverability.RECOVERABLE, result.failure.recoverability)
        }
    }

    @Test
    fun `database cancellation propagates unchanged`() {
        val expected = CancellationException("cancelled")
        runBlocking {
            whenever(dao.execute(any(), eq(3_000L))).thenThrow(expected)
        }

        val actual = assertFailsWith<CancellationException> {
            runBlocking { executor.execute(command()) }
        }

        assertEquals("cancelled", actual.message)
    }

    private fun command(): AuthorizedCircuitAdministrationCommand =
        AuthorizedCircuitAdministrationCommand(
            request = CircuitAdministrationRequest(
                commandId = CircuitAdministrationCommandId("command-1"),
                scope = CircuitBreakerScope.global(),
                principalId = CircuitAdministrationPrincipalId("operator-1"),
                requestedAt = DataLoomInstant(1_000L),
                action = CircuitAdministrationAction.CLOSE,
                reason = CircuitAdministrationReason("Operator closed the global circuit"),
            ),
            authorizationId = CircuitAdministrationAuthorizationId("authorization-1"),
        )

    private fun resultingRecord(): CircuitBreakerStateRecord = CircuitBreakerStateRecord(
        state = CircuitBreakerState(
            scope = CircuitBreakerScope.global(),
            phase = CircuitBreakerPhase.CLOSED,
            consecutiveFailures = 0,
            failureWindowStartedAt = null,
            openUntil = null,
            probeGeneration = 2L,
            probeInFlight = false,
            updatedAt = DataLoomInstant(3_000L),
        ),
        version = 4L,
    )
}
