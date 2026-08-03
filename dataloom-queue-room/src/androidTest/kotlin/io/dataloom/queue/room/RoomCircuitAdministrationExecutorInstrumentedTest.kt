package io.dataloom.queue.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.dataloom.api.circuit.AuthorizedCircuitAdministrationCommand
import io.dataloom.api.circuit.CircuitAdministrationAction
import io.dataloom.api.circuit.CircuitAdministrationAuthorizationId
import io.dataloom.api.circuit.CircuitAdministrationCommandId
import io.dataloom.api.circuit.CircuitAdministrationCommandState
import io.dataloom.api.circuit.CircuitAdministrationCommandStatus
import io.dataloom.api.circuit.CircuitAdministrationCompareAndSetRequest
import io.dataloom.api.circuit.CircuitAdministrationCompareAndSetResult
import io.dataloom.api.circuit.CircuitAdministrationExecutionResult
import io.dataloom.api.circuit.CircuitAdministrationLoadResult
import io.dataloom.api.circuit.CircuitAdministrationPrincipalId
import io.dataloom.api.circuit.CircuitAdministrationReason
import io.dataloom.api.circuit.CircuitAdministrationRequest
import io.dataloom.api.circuit.CircuitBreakerCompareAndSetRequest
import io.dataloom.api.circuit.CircuitBreakerCompareAndSetResult
import io.dataloom.api.circuit.CircuitBreakerLoadResult
import io.dataloom.api.circuit.CircuitBreakerPhase
import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.circuit.CircuitBreakerState
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.queue.room.internal.DataLoomRoomDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertIs

@RunWith(AndroidJUnit4::class)
class RoomCircuitAdministrationExecutorInstrumentedTest {
    private lateinit var database: DataLoomRoomDatabase
    private lateinit var commandStore: RoomCircuitAdministrationStateStore
    private lateinit var circuitStore: RoomCircuitBreakerStateStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, DataLoomRoomDatabase::class.java).build()
        commandStore = RoomCircuitAdministrationStateStore(database)
        circuitStore = RoomCircuitBreakerStateStore(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun resetCommitsCircuitMutationAndSucceededReceiptExactlyOnce() = runBlocking<Unit> {
        val scope = CircuitBreakerScope.global()
        val halfOpen = CircuitBreakerState(
            scope = scope,
            phase = CircuitBreakerPhase.HALF_OPEN,
            consecutiveFailures = 0,
            failureWindowStartedAt = null,
            openUntil = null,
            probeGeneration = 7L,
            probeInFlight = true,
            updatedAt = DataLoomInstant(2_000L),
            probeLeaseUntil = DataLoomInstant(4_000L),
        )
        assertIs<CircuitBreakerCompareAndSetResult.Updated>(
            assertIs<ProviderOperationResult.Success<CircuitBreakerCompareAndSetResult>>(
                circuitStore.compareAndSet(CircuitBreakerCompareAndSetRequest(scope, null, halfOpen)),
            ).value,
        )

        val request = request(CircuitAdministrationAction.RESET)
        val authorizationId = CircuitAdministrationAuthorizationId("authorization-1")
        createAuthorizedCommand(request, authorizationId)
        val command = AuthorizedCircuitAdministrationCommand(request, authorizationId)
        val executor = RoomCircuitAdministrationExecutor(database, FixedClock(3_000L))

        val first = assertIs<CircuitAdministrationExecutionResult.Applied>(
            executor.execute(command),
        )
        val replay = assertIs<CircuitAdministrationExecutionResult.Applied>(
            executor.execute(command),
        )

        assertEquals(first.record, replay.record)
        assertEquals(1L, first.record.version)
        assertEquals(CircuitBreakerPhase.CLOSED, first.record.state.phase)
        assertEquals(7L, first.record.state.probeGeneration)
        assertEquals(0, first.record.state.consecutiveFailures)

        val durableCommand = assertIs<CircuitAdministrationLoadResult.Found>(
            assertIs<ProviderOperationResult.Success<CircuitAdministrationLoadResult>>(
                commandStore.load(request.commandId),
            ).value,
        ).record
        assertEquals(CircuitAdministrationCommandStatus.SUCCEEDED, durableCommand.state.status)
        assertEquals(1L, durableCommand.version)
        assertEquals(first.record, durableCommand.state.resultingRecord)

        val durableCircuit = assertIs<CircuitBreakerLoadResult.Found>(
            assertIs<ProviderOperationResult.Success<CircuitBreakerLoadResult>>(
                circuitStore.load(scope),
            ).value,
        ).record
        assertEquals(first.record, durableCircuit)
    }

    @Test
    fun authorizationMismatchRejectsWithoutChangingCircuitOrCommand() = runBlocking<Unit> {
        val request = request(CircuitAdministrationAction.OPEN, DataLoomInstant(5_000L))
        createAuthorizedCommand(
            request,
            CircuitAdministrationAuthorizationId("authorization-expected"),
        )

        val result = assertIs<CircuitAdministrationExecutionResult.Rejected>(
            RoomCircuitAdministrationExecutor(database, FixedClock(3_000L)).execute(
                AuthorizedCircuitAdministrationCommand(
                    request,
                    CircuitAdministrationAuthorizationId("authorization-other"),
                ),
            ),
        )

        assertEquals("CIRCUIT_ADMIN_AUTHORIZATION_MISMATCH", result.reasonCode)
        assertIs<CircuitBreakerLoadResult.Missing>(
            assertIs<ProviderOperationResult.Success<CircuitBreakerLoadResult>>(
                circuitStore.load(request.scope),
            ).value,
        )
        val durableCommand = assertIs<CircuitAdministrationLoadResult.Found>(
            assertIs<ProviderOperationResult.Success<CircuitAdministrationLoadResult>>(
                commandStore.load(request.commandId),
            ).value,
        ).record
        assertEquals(CircuitAdministrationCommandStatus.AUTHORIZED, durableCommand.state.status)
        assertEquals(0L, durableCommand.version)
    }

    private suspend fun createAuthorizedCommand(
        request: CircuitAdministrationRequest,
        authorizationId: CircuitAdministrationAuthorizationId,
    ) {
        assertIs<CircuitAdministrationCompareAndSetResult.Updated>(
            assertIs<ProviderOperationResult.Success<CircuitAdministrationCompareAndSetResult>>(
                commandStore.compareAndSet(
                    CircuitAdministrationCompareAndSetRequest(
                        commandId = request.commandId,
                        expectedVersion = null,
                        nextState = CircuitAdministrationCommandState(
                            request = request,
                            status = CircuitAdministrationCommandStatus.AUTHORIZED,
                            authorizationId = authorizationId,
                            updatedAt = DataLoomInstant(2_500L),
                        ),
                    ),
                ),
            ).value,
        )
    }

    private fun request(
        action: CircuitAdministrationAction,
        openUntil: DataLoomInstant? = null,
    ): CircuitAdministrationRequest = CircuitAdministrationRequest(
        commandId = CircuitAdministrationCommandId("command-${action.name.lowercase()}"),
        scope = CircuitBreakerScope.global(),
        principalId = CircuitAdministrationPrincipalId("operator-1"),
        requestedAt = DataLoomInstant(1_000L),
        action = action,
        reason = CircuitAdministrationReason("Operator requested ${action.name.lowercase()}"),
        openUntil = openUntil,
    )

    private class FixedClock(private val value: Long) : DataLoomClock {
        override fun now(): DataLoomInstant = DataLoomInstant(value)
    }
}
