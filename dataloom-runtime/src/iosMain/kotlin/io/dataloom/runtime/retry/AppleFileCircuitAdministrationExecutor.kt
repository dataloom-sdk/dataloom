package io.dataloom.runtime.retry

import io.dataloom.api.circuit.AuthorizedCircuitAdministrationCommand
import io.dataloom.api.circuit.CircuitAdministrationAction
import io.dataloom.api.circuit.CircuitAdministrationCommandStatus
import io.dataloom.api.circuit.CircuitAdministrationExecutionResult
import io.dataloom.api.circuit.CircuitAdministrationExecutor
import io.dataloom.api.circuit.CircuitAdministrationFailureSnapshot
import io.dataloom.api.circuit.CircuitBreakerPhase
import io.dataloom.api.circuit.CircuitBreakerState
import io.dataloom.api.circuit.CircuitBreakerStateRecord
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Apple executor for authorized circuit-administration commands.
 *
 * The durable command and circuit state share one bounded snapshot. Under one
 * process-shared file lock, execution validates the exact immutable request and
 * authorization, derives the next circuit version, records `SUCCEEDED`, and
 * atomically replaces the snapshot. Redelivery returns the exact durable result
 * without applying a second mutation.
 */
public class AppleFileCircuitAdministrationExecutor(
    directoryPath: String,
    private val clock: DataLoomClock,
    fileName: String = DEFAULT_FILE_NAME,
) : CircuitAdministrationExecutor {
    private val boundary = AppleCircuitStateFileBoundary(directoryPath, fileName)

    override suspend fun execute(
        command: AuthorizedCircuitAdministrationCommand,
    ): CircuitAdministrationExecutionResult {
        val observedAt = clock.now()
        return try {
            currentCoroutineContext().ensureActive()
            boundary.withExclusiveLock {
                executeLocked(command, observedAt)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: AppleCircuitAdministrationVersionExhaustedException) {
            CircuitAdministrationExecutionResult.Failed(
                AppleCircuitAdministrationExecutorError.versionExhausted(),
            )
        } catch (_: AppleCircuitStateLimitException) {
            CircuitAdministrationExecutionResult.Failed(
                AppleCircuitAdministrationExecutorError.stateLimitExceeded(),
            )
        } catch (_: AppleCircuitAdministrationRecordLimitException) {
            CircuitAdministrationExecutionResult.Failed(
                AppleCircuitAdministrationExecutorError.stateLimitExceeded(),
            )
        } catch (_: MalformedAppleCircuitStateException) {
            CircuitAdministrationExecutionResult.Failed(
                AppleCircuitAdministrationExecutorError.integrityFailure(),
            )
        } catch (_: AppleCircuitFileException) {
            CircuitAdministrationExecutionResult.Failed(
                AppleCircuitAdministrationExecutorError.fileFailure(),
            )
        } catch (_: Exception) {
            CircuitAdministrationExecutionResult.Failed(
                AppleCircuitAdministrationExecutorError.fileFailure(),
            )
        }
    }

    private suspend fun executeLocked(
        command: AuthorizedCircuitAdministrationCommand,
        observedAt: DataLoomInstant,
    ): CircuitAdministrationExecutionResult {
        val snapshot = boundary.readSnapshot()
        val durableRecord = snapshot.administrationRecords[command.request.commandId.value]
            ?: return rejected(CIRCUIT_COMMAND_MISSING)
        val durableState = durableRecord.state
        if (durableState.request != command.request) {
            return rejected(CIRCUIT_COMMAND_CONFLICT)
        }
        if (durableState.status == CircuitAdministrationCommandStatus.SUCCEEDED) {
            return if (durableState.authorizationId == command.authorizationId) {
                CircuitAdministrationExecutionResult.Applied(
                    checkNotNull(durableState.resultingRecord),
                )
            } else {
                rejected(CIRCUIT_AUTHORIZATION_MISMATCH)
            }
        }
        if (durableState.status != CircuitAdministrationCommandStatus.AUTHORIZED) {
            return rejected(CIRCUIT_COMMAND_NOT_AUTHORIZED)
        }
        if (durableState.authorizationId != command.authorizationId) {
            return rejected(CIRCUIT_AUTHORIZATION_MISMATCH)
        }
        if (
            observedAt.epochMilliseconds < durableState.updatedAt.epochMilliseconds ||
            observedAt.epochMilliseconds < command.request.requestedAt.epochMilliseconds
        ) {
            return failed(AppleCircuitAdministrationExecutorError.clockRegression())
        }
        if (
            command.request.action == CircuitAdministrationAction.OPEN &&
            observedAt.epochMilliseconds >=
            checkNotNull(command.request.openUntil).epochMilliseconds
        ) {
            return rejected(CIRCUIT_OPEN_DEADLINE_EXPIRED)
        }
        if (durableRecord.version == Long.MAX_VALUE) {
            throw AppleCircuitAdministrationVersionExhaustedException()
        }

        val scope = command.request.scope
        val scopeKey = scopeStorageKey(scope)
        val current = snapshot.circuitRecords[scopeKey]
        if (current != null && current.state.scope != scope) {
            throw MalformedAppleCircuitStateException(
                IllegalStateException("Persisted circuit scope does not match its key."),
            )
        }
        if (
            current != null &&
            observedAt.epochMilliseconds < current.state.updatedAt.epochMilliseconds
        ) {
            return failed(AppleCircuitAdministrationExecutorError.clockRegression())
        }
        if (current?.version == Long.MAX_VALUE) {
            throw AppleCircuitAdministrationVersionExhaustedException()
        }

        val nextState = nextState(command, current?.state, observedAt)
        val resultingRecord = CircuitBreakerStateRecord(
            state = nextState,
            version = current?.version?.plus(1L) ?: 0L,
        )
        val succeededRecord = durableRecord.copy(
            state = durableState.copy(
                status = CircuitAdministrationCommandStatus.SUCCEEDED,
                updatedAt = observedAt,
                rejectionReasonCode = null,
                resultingRecord = resultingRecord,
                executionFailure = null,
            ),
            version = durableRecord.version + 1L,
        )
        snapshot.circuitRecords[scopeKey] = resultingRecord
        snapshot.administrationRecords[command.request.commandId.value] = succeededRecord
        currentCoroutineContext().ensureActive()
        boundary.writeSnapshot(snapshot)
        return CircuitAdministrationExecutionResult.Applied(resultingRecord)
    }

    private fun nextState(
        command: AuthorizedCircuitAdministrationCommand,
        current: CircuitBreakerState?,
        observedAt: DataLoomInstant,
    ): CircuitBreakerState {
        val probeGeneration = current?.probeGeneration ?: 0L
        val preserveClosedFailures =
            command.request.action == CircuitAdministrationAction.CLOSE &&
                current?.phase == CircuitBreakerPhase.CLOSED
        return when (command.request.action) {
            CircuitAdministrationAction.OPEN -> CircuitBreakerState(
                scope = command.request.scope,
                phase = CircuitBreakerPhase.OPEN,
                consecutiveFailures = 0,
                failureWindowStartedAt = null,
                openUntil = checkNotNull(command.request.openUntil),
                probeGeneration = probeGeneration,
                probeInFlight = false,
                updatedAt = observedAt,
            )
            CircuitAdministrationAction.CLOSE,
            CircuitAdministrationAction.RESET,
            -> CircuitBreakerState(
                scope = command.request.scope,
                phase = CircuitBreakerPhase.CLOSED,
                consecutiveFailures = if (preserveClosedFailures) {
                    checkNotNull(current).consecutiveFailures
                } else {
                    0
                },
                failureWindowStartedAt = if (preserveClosedFailures) {
                    checkNotNull(current).failureWindowStartedAt
                } else {
                    null
                },
                openUntil = null,
                probeGeneration = probeGeneration,
                probeInFlight = false,
                updatedAt = observedAt,
            )
        }
    }

    private fun rejected(reasonCode: String): CircuitAdministrationExecutionResult =
        CircuitAdministrationExecutionResult.Rejected(reasonCode)

    private fun failed(
        failure: CircuitAdministrationFailureSnapshot,
    ): CircuitAdministrationExecutionResult = CircuitAdministrationExecutionResult.Failed(failure)

    public companion object {
        public const val DEFAULT_FILE_NAME: String =
            AppleFileCircuitBreakerStateStore.DEFAULT_FILE_NAME
    }
}

private object AppleCircuitAdministrationExecutorError {
    fun fileFailure(): CircuitAdministrationFailureSnapshot = failure(
        code = "CIRCUIT_ADMIN_APPLE_EXECUTOR_FILE_IO_FAILURE",
        category = ErrorCategory.STORAGE,
        recoverability = Recoverability.RECOVERABLE,
    )

    fun integrityFailure(): CircuitAdministrationFailureSnapshot = failure(
        code = "CIRCUIT_ADMIN_APPLE_EXECUTOR_STATE_CORRUPT",
        category = ErrorCategory.STATE,
        recoverability = Recoverability.NON_RECOVERABLE,
    )

    fun stateLimitExceeded(): CircuitAdministrationFailureSnapshot = failure(
        code = "CIRCUIT_ADMIN_APPLE_STATE_LIMIT_EXCEEDED",
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

private class AppleCircuitAdministrationVersionExhaustedException : Exception()

private const val CIRCUIT_COMMAND_MISSING: String = "CIRCUIT_ADMIN_COMMAND_MISSING"
private const val CIRCUIT_COMMAND_CONFLICT: String = "CIRCUIT_ADMIN_COMMAND_CONFLICT"
private const val CIRCUIT_COMMAND_NOT_AUTHORIZED: String =
    "CIRCUIT_ADMIN_COMMAND_NOT_AUTHORIZED"
private const val CIRCUIT_AUTHORIZATION_MISMATCH: String =
    "CIRCUIT_ADMIN_AUTHORIZATION_MISMATCH"
private const val CIRCUIT_OPEN_DEADLINE_EXPIRED: String =
    "CIRCUIT_ADMINISTRATION_OPEN_DEADLINE_EXPIRED"
