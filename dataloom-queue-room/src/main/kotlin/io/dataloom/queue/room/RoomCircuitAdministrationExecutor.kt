package io.dataloom.queue.room

import io.dataloom.api.circuit.AuthorizedCircuitAdministrationCommand
import io.dataloom.api.circuit.CircuitAdministrationExecutionResult
import io.dataloom.api.circuit.CircuitAdministrationExecutor
import io.dataloom.api.circuit.CircuitAdministrationFailureSnapshot
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.time.DataLoomClock
import io.dataloom.queue.room.internal.CircuitAdministrationExecutionEntityResult
import io.dataloom.queue.room.internal.CircuitAdministrationExecutionIntegrityException
import io.dataloom.queue.room.internal.CircuitAdministrationExecutionVersionExhaustedException
import io.dataloom.queue.room.internal.DataLoomRoomDatabase

/**
 * Android Room executor for authorized circuit-administration commands.
 *
 * The circuit state and command must live in the same [DataLoomRoomDatabase].
 * One Room transaction validates the exact immutable command and authorization,
 * applies the requested circuit transition, and records the resulting durable
 * circuit version. Redelivery replays that receipt without another mutation.
 */
public class RoomCircuitAdministrationExecutor(
    database: DataLoomRoomDatabase,
    private val clock: DataLoomClock,
) : CircuitAdministrationExecutor {
    private val dao = database.circuitAdministrationExecutionDao()

    override suspend fun execute(
        command: AuthorizedCircuitAdministrationCommand,
    ): CircuitAdministrationExecutionResult {
        val observedAt = clock.now()
        return try {
            when (val result = dao.execute(command, observedAt.epochMilliseconds)) {
                is CircuitAdministrationExecutionEntityResult.Applied ->
                    CircuitAdministrationExecutionResult.Applied(result.record)
                CircuitAdministrationExecutionEntityResult.ClockRegression ->
                    CircuitAdministrationExecutionResult.Failed(
                        RoomCircuitAdministrationExecutorError.clockRegression(),
                    )
                is CircuitAdministrationExecutionEntityResult.Rejected ->
                    CircuitAdministrationExecutionResult.Rejected(result.reasonCode)
            }
        } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
            throw cancelled
        } catch (_: CircuitAdministrationExecutionVersionExhaustedException) {
            CircuitAdministrationExecutionResult.Failed(
                RoomCircuitAdministrationExecutorError.versionExhausted(),
            )
        } catch (_: CircuitAdministrationExecutionIntegrityException) {
            CircuitAdministrationExecutionResult.Failed(
                RoomCircuitAdministrationExecutorError.integrityFailure(),
            )
        } catch (_: Exception) {
            CircuitAdministrationExecutionResult.Failed(
                RoomCircuitAdministrationExecutorError.databaseFailure(),
            )
        }
    }
}

private object RoomCircuitAdministrationExecutorError {
    fun databaseFailure(): CircuitAdministrationFailureSnapshot = failure(
        code = "CIRCUIT_ADMIN_ROOM_EXECUTOR_DATABASE_FAILURE",
        category = ErrorCategory.STORAGE,
        recoverability = Recoverability.RECOVERABLE,
    )

    fun integrityFailure(): CircuitAdministrationFailureSnapshot = failure(
        code = "CIRCUIT_ADMIN_ROOM_EXECUTOR_STATE_CORRUPT",
        category = ErrorCategory.STATE,
        recoverability = Recoverability.NON_RECOVERABLE,
    )

    fun versionExhausted(): CircuitAdministrationFailureSnapshot = failure(
        code = "CIRCUIT_ADMIN_STATE_VERSION_EXHAUSTED",
        category = ErrorCategory.STATE,
        recoverability = Recoverability.NON_RECOVERABLE,
    )

    fun clockRegression(): CircuitAdministrationFailureSnapshot = failure(
        code = "CIRCUIT_ADMIN_EXECUTION_CLOCK_REGRESSION",
        category = ErrorCategory.STATE,
        recoverability = Recoverability.NON_RECOVERABLE,
    )

    private fun failure(
        code: String,
        category: ErrorCategory,
        recoverability: Recoverability,
    ): CircuitAdministrationFailureSnapshot = CircuitAdministrationFailureSnapshot(
        code = ErrorCode(code),
        category = category,
        severity = ErrorSeverity.ERROR,
        recoverability = recoverability,
    )
}
