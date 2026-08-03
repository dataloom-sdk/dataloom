package io.dataloom.queue.room

import io.dataloom.api.circuit.CircuitAdministrationAction
import io.dataloom.api.circuit.CircuitAdministrationAuthorizationId
import io.dataloom.api.circuit.CircuitAdministrationCommandId
import io.dataloom.api.circuit.CircuitAdministrationCommandState
import io.dataloom.api.circuit.CircuitAdministrationCommandStatus
import io.dataloom.api.circuit.CircuitAdministrationCompareAndSetRequest
import io.dataloom.api.circuit.CircuitAdministrationCompareAndSetResult
import io.dataloom.api.circuit.CircuitAdministrationLoadResult
import io.dataloom.api.circuit.CircuitAdministrationPrincipalId
import io.dataloom.api.circuit.CircuitAdministrationReason
import io.dataloom.api.circuit.CircuitAdministrationRequest
import io.dataloom.api.circuit.CircuitBreakerPhase
import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.queue.room.internal.CircuitAdministrationCompareAndSetEntityResult
import io.dataloom.queue.room.internal.CircuitAdministrationStateDao
import io.dataloom.queue.room.internal.CircuitAdministrationStateEntity
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

class RoomCircuitAdministrationStateStoreTest {
    private lateinit var database: DataLoomRoomDatabase
    private lateinit var dao: CircuitAdministrationStateDao
    private lateinit var store: RoomCircuitAdministrationStateStore

    @Before
    fun setUp() {
        database = mock()
        dao = mock()
        whenever(database.circuitAdministrationStateDao()).thenReturn(dao)
        store = RoomCircuitAdministrationStateStore(database)
    }

    @Test
    fun `missing command is returned explicitly`() {
        runBlocking {
            whenever(dao.load(any())).thenReturn(null)

            val result = assertIs<ProviderOperationResult.Success<CircuitAdministrationLoadResult>>(
                store.load(CircuitAdministrationCommandId("command-1")),
            )

            assertIs<CircuitAdministrationLoadResult.Missing>(result.value)
        }
    }

    @Test
    fun `compare and set conflict preserves immutable scope and version`() {
        runBlocking {
            val current = validEntity(recordVersion = 3L)
            whenever(dao.compareAndSet(eq(2L), any())).thenReturn(
                CircuitAdministrationCompareAndSetEntityResult.Conflict(current),
            )

            val result = assertIs<ProviderOperationResult.Success<CircuitAdministrationCompareAndSetResult>>(
                store.compareAndSet(
                    CircuitAdministrationCompareAndSetRequest(
                        commandId = CircuitAdministrationCommandId("command-1"),
                        expectedVersion = 2L,
                        nextState = authorizedState(),
                    ),
                ),
            )
            val conflict = assertIs<CircuitAdministrationCompareAndSetResult.Conflict>(result.value)

            assertEquals(3L, conflict.current?.version)
            assertEquals(CircuitBreakerScope.global(), conflict.current?.state?.request?.scope)
            assertEquals("operator-1", conflict.current?.state?.request?.principalId?.value)
        }
    }

    @Test
    fun `partial resulting state fails closed`() {
        runBlocking {
            whenever(dao.load(any())).thenReturn(
                validEntity(
                    status = CircuitAdministrationCommandStatus.SUCCEEDED.name,
                    resultPhase = CircuitBreakerPhase.CLOSED.name,
                ),
            )

            val result = assertIs<ProviderOperationResult.Failure>(
                store.load(CircuitAdministrationCommandId("command-1")),
            )

            assertEquals("CIRCUIT_ADMIN_ROOM_STATE_CORRUPT", result.error.code.value)
        }
    }

    @Test
    fun `version exhaustion is non recoverable and does not access Room`() {
        runBlocking {
            val result = assertIs<ProviderOperationResult.Failure>(
                store.compareAndSet(
                    CircuitAdministrationCompareAndSetRequest(
                        commandId = CircuitAdministrationCommandId("command-1"),
                        expectedVersion = Long.MAX_VALUE,
                        nextState = authorizedState(),
                    ),
                ),
            )

            assertEquals("CIRCUIT_ADMIN_STATE_VERSION_EXHAUSTED", result.error.code.value)
            verifyNoInteractions(dao)
        }
    }

    @Test
    fun `database failure is sanitized and recoverable`() {
        runBlocking {
            whenever(dao.load(any())).thenThrow(mock<android.database.sqlite.SQLiteException>())

            val result = assertIs<ProviderOperationResult.Failure>(
                store.load(CircuitAdministrationCommandId("command-1")),
            )

            assertEquals("CIRCUIT_ADMIN_ROOM_DATABASE_FAILURE", result.error.code.value)
            assertEquals("A circuit-administration database operation failed.", result.error.message)
        }
    }

    @Test
    fun `database cancellation propagates unchanged`() {
        val expected = CancellationException("cancelled")
        runBlocking {
            whenever(dao.load(any())).thenThrow(expected)
        }

        val actual = assertFailsWith<CancellationException> {
            runBlocking { store.load(CircuitAdministrationCommandId("command-1")) }
        }

        assertEquals("cancelled", actual.message)
    }

    private fun authorizedState(): CircuitAdministrationCommandState =
        CircuitAdministrationCommandState(
            request = request(),
            status = CircuitAdministrationCommandStatus.AUTHORIZED,
            authorizationId = CircuitAdministrationAuthorizationId("authorization-1"),
            updatedAt = DataLoomInstant(2_000L),
        )

    private fun request(): CircuitAdministrationRequest = CircuitAdministrationRequest(
        commandId = CircuitAdministrationCommandId("command-1"),
        scope = CircuitBreakerScope.global(),
        principalId = CircuitAdministrationPrincipalId("operator-1"),
        requestedAt = DataLoomInstant(1_000L),
        action = CircuitAdministrationAction.CLOSE,
        reason = CircuitAdministrationReason("Operator closed the global circuit"),
    )

    private fun validEntity(
        recordVersion: Long = 0L,
        status: String = CircuitAdministrationCommandStatus.AUTHORIZED.name,
        resultPhase: String? = null,
    ): CircuitAdministrationStateEntity = CircuitAdministrationStateEntity(
        commandId = "command-1",
        scopeKey = "GLOBAL|-|-|-|-",
        scopeKind = "GLOBAL",
        providerId = null,
        operation = null,
        tenantId = null,
        workflowId = null,
        principalId = "operator-1",
        requestedAtMs = 1_000L,
        action = CircuitAdministrationAction.CLOSE.name,
        reason = "Operator closed the global circuit",
        requestedOpenUntilMs = null,
        status = status,
        authorizationId = "authorization-1",
        updatedAtMs = 2_000L,
        rejectionReasonCode = null,
        resultPhase = resultPhase,
        resultConsecutiveFailures = null,
        resultFailureWindowStartedAtMs = null,
        resultOpenUntilMs = null,
        resultProbeGeneration = null,
        resultProbeInFlight = null,
        resultProbeLeaseUntilMs = null,
        resultUpdatedAtMs = null,
        resultRecordVersion = null,
        executionErrorCode = null,
        executionErrorCategory = null,
        executionErrorSeverity = null,
        executionErrorRecoverability = null,
        recordVersion = recordVersion,
    )
}
