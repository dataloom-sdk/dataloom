package io.dataloom.runtime.retry

import io.dataloom.api.circuit.AuthorizedCircuitAdministrationCommand
import io.dataloom.api.circuit.CircuitAdministrationAction
import io.dataloom.api.circuit.CircuitAdministrationAuthorizationDecision
import io.dataloom.api.circuit.CircuitAdministrationAuthorizer
import io.dataloom.api.circuit.CircuitAdministrationCommandState
import io.dataloom.api.circuit.CircuitAdministrationCommandStatus
import io.dataloom.api.circuit.CircuitAdministrationCompareAndSetRequest
import io.dataloom.api.circuit.CircuitAdministrationCompareAndSetResult
import io.dataloom.api.circuit.CircuitAdministrationExecutionResult
import io.dataloom.api.circuit.CircuitAdministrationExecutor
import io.dataloom.api.circuit.CircuitAdministrationLoadResult
import io.dataloom.api.circuit.CircuitAdministrationRequest
import io.dataloom.api.circuit.CircuitAdministrationStateRecord
import io.dataloom.api.circuit.CircuitAdministrationStateStore
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant

/**
 * Fail-closed coordinator for authorized, idempotent, and audited circuit
 * administration.
 *
 * The executor owns the atomic platform mutation and durable command receipt.
 * The coordinator owns authorization, immutable command admission, replay,
 * bounded contention, deadline policy, and terminal audit recording.
 */
public class CircuitAdministrationCoordinator(
    private val clock: DataLoomClock,
    private val authorizer: CircuitAdministrationAuthorizer,
    private val stateStore: CircuitAdministrationStateStore,
    private val executor: CircuitAdministrationExecutor,
    private val maximumStateUpdateAttempts: Int = 8,
) {
    init {
        require(maximumStateUpdateAttempts >= 1) {
            "CircuitAdministrationCoordinator maximumStateUpdateAttempts must be at least one."
        }
    }

    /** Executes or resumes one privileged circuit command. */
    public suspend fun execute(
        request: CircuitAdministrationRequest,
    ): CircuitAdministrationResult {
        repeat(maximumStateUpdateAttempts) {
            val current = when (val loaded = load(request)) {
                is CircuitAdministrationLoadOutcome.Failure -> {
                    return CircuitAdministrationResult.PersistenceFailure(loaded.error)
                }
                CircuitAdministrationLoadOutcome.Missing -> when (val admitted = admit(request)) {
                    is CircuitAdministrationAdmissionOutcome.Authorized -> admitted.record
                    is CircuitAdministrationAdmissionOutcome.Completed -> return admitted.result
                    CircuitAdministrationAdmissionOutcome.Conflict -> return@repeat
                    is CircuitAdministrationAdmissionOutcome.Failure -> {
                        return CircuitAdministrationResult.PersistenceFailure(admitted.error)
                    }
                }
                is CircuitAdministrationLoadOutcome.Found -> loaded.record
                is CircuitAdministrationLoadOutcome.FoundConflict -> {
                    return CircuitAdministrationResult.CommandConflict(loaded.record)
                }
            }

            terminalResult(current)?.let { result -> return result }

            check(current.state.status == CircuitAdministrationCommandStatus.AUTHORIZED) {
                "CircuitAdministrationStateStore returned a non-terminal unsupported status."
            }

            val observedAt = clock.now()
            if (observedAt.epochMilliseconds < current.state.updatedAt.epochMilliseconds) {
                return CircuitAdministrationResult.ClockRegression(
                    observedAt = observedAt,
                    persistedAt = current.state.updatedAt,
                )
            }

            if (request.action == CircuitAdministrationAction.OPEN &&
                observedAt.epochMilliseconds >= checkNotNull(request.openUntil).epochMilliseconds
            ) {
                when (val update = update(
                    current = current,
                    nextState = current.state.copy(
                        status = CircuitAdministrationCommandStatus.POLICY_REJECTED,
                        updatedAt = observedAt,
                        rejectionReasonCode = OPEN_DEADLINE_EXPIRED,
                    ),
                )) {
                    CircuitAdministrationUpdateOutcome.Conflict -> return@repeat
                    is CircuitAdministrationUpdateOutcome.Failure -> {
                        return CircuitAdministrationResult.PersistenceFailure(update.error)
                    }
                    is CircuitAdministrationUpdateOutcome.Updated -> {
                        return CircuitAdministrationResult.PolicyRejected(update.record)
                    }
                }
            }

            val command = AuthorizedCircuitAdministrationCommand(
                request = request,
                authorizationId = checkNotNull(current.state.authorizationId),
            )
            val executionResult = executor.execute(command)
            if (executionResult is CircuitAdministrationExecutionResult.Applied) {
                check(executionResult.record.state.scope == request.scope) {
                    "CircuitAdministrationExecutor returned a record for another scope."
                }
            }

            when (val update = update(
                current = current,
                nextState = finalState(current.state, observedAt, executionResult),
            )) {
                CircuitAdministrationUpdateOutcome.Conflict -> return@repeat
                is CircuitAdministrationUpdateOutcome.Failure -> {
                    return CircuitAdministrationResult.ExecutionRecordingUnconfirmed(
                        command = command,
                        executionResult = executionResult,
                        persistenceError = update.error,
                    )
                }
                is CircuitAdministrationUpdateOutcome.Updated -> {
                    return terminalResult(update.record)
                        ?: error("Circuit administration execution must produce a terminal state.")
                }
            }
        }
        return CircuitAdministrationResult.ContentionLimitReached
    }

    private suspend fun admit(
        request: CircuitAdministrationRequest,
    ): CircuitAdministrationAdmissionOutcome {
        val observedAt = clock.now()
        return when (val decision = authorizer.authorize(request)) {
            is CircuitAdministrationAuthorizationDecision.Denied -> when (val update = create(
                CircuitAdministrationCommandState(
                    request = request,
                    status = CircuitAdministrationCommandStatus.AUTHORIZATION_DENIED,
                    authorizationId = null,
                    updatedAt = observedAt,
                    rejectionReasonCode = decision.reasonCode,
                ),
            )) {
                CircuitAdministrationUpdateOutcome.Conflict -> {
                    CircuitAdministrationAdmissionOutcome.Conflict
                }
                is CircuitAdministrationUpdateOutcome.Failure -> {
                    CircuitAdministrationAdmissionOutcome.Failure(update.error)
                }
                is CircuitAdministrationUpdateOutcome.Updated -> {
                    CircuitAdministrationAdmissionOutcome.Completed(
                        CircuitAdministrationResult.AuthorizationDenied(update.record),
                    )
                }
            }
            is CircuitAdministrationAuthorizationDecision.Authorized -> when (val update = create(
                CircuitAdministrationCommandState(
                    request = request,
                    status = CircuitAdministrationCommandStatus.AUTHORIZED,
                    authorizationId = decision.authorizationId,
                    updatedAt = observedAt,
                ),
            )) {
                CircuitAdministrationUpdateOutcome.Conflict -> {
                    CircuitAdministrationAdmissionOutcome.Conflict
                }
                is CircuitAdministrationUpdateOutcome.Failure -> {
                    CircuitAdministrationAdmissionOutcome.Failure(update.error)
                }
                is CircuitAdministrationUpdateOutcome.Updated -> {
                    CircuitAdministrationAdmissionOutcome.Authorized(update.record)
                }
            }
        }
    }

    private suspend fun load(
        request: CircuitAdministrationRequest,
    ): CircuitAdministrationLoadOutcome = when (val result = stateStore.load(request.commandId)) {
        is ProviderOperationResult.Failure -> CircuitAdministrationLoadOutcome.Failure(result.error)
        is ProviderOperationResult.Success -> when (val value = result.value) {
            CircuitAdministrationLoadResult.Missing -> CircuitAdministrationLoadOutcome.Missing
            is CircuitAdministrationLoadResult.Found -> {
                check(value.record.state.request.commandId == request.commandId) {
                    "CircuitAdministrationStateStore returned a record for another command id."
                }
                if (value.record.state.request == request) {
                    CircuitAdministrationLoadOutcome.Found(value.record)
                } else {
                    CircuitAdministrationLoadOutcome.FoundConflict(value.record)
                }
            }
        }
    }

    private suspend fun create(
        state: CircuitAdministrationCommandState,
    ): CircuitAdministrationUpdateOutcome = compareAndSet(
        CircuitAdministrationCompareAndSetRequest(
            commandId = state.request.commandId,
            expectedVersion = null,
            nextState = state,
        ),
    )

    private suspend fun update(
        current: CircuitAdministrationStateRecord,
        nextState: CircuitAdministrationCommandState,
    ): CircuitAdministrationUpdateOutcome = compareAndSet(
        CircuitAdministrationCompareAndSetRequest(
            commandId = current.state.request.commandId,
            expectedVersion = current.version,
            nextState = nextState,
        ),
    )

    private suspend fun compareAndSet(
        request: CircuitAdministrationCompareAndSetRequest,
    ): CircuitAdministrationUpdateOutcome = when (val result = stateStore.compareAndSet(request)) {
        is ProviderOperationResult.Failure -> CircuitAdministrationUpdateOutcome.Failure(result.error)
        is ProviderOperationResult.Success -> when (val value = result.value) {
            is CircuitAdministrationCompareAndSetResult.Conflict -> {
                value.current?.let { current ->
                    check(current.state.request.commandId == request.commandId) {
                        "CircuitAdministrationStateStore returned conflict for another command id."
                    }
                }
                CircuitAdministrationUpdateOutcome.Conflict
            }
            is CircuitAdministrationCompareAndSetResult.Updated -> {
                val expectedVersion = request.expectedVersion
                check(value.record.state == request.nextState) {
                    "CircuitAdministrationStateStore returned different command state after update."
                }
                check(expectedVersion == null || value.record.version > expectedVersion) {
                    "CircuitAdministrationStateStore did not advance the command-state version."
                }
                CircuitAdministrationUpdateOutcome.Updated(value.record)
            }
        }
    }

    private fun finalState(
        current: CircuitAdministrationCommandState,
        observedAt: DataLoomInstant,
        executionResult: CircuitAdministrationExecutionResult,
    ): CircuitAdministrationCommandState = when (executionResult) {
        is CircuitAdministrationExecutionResult.Applied -> current.copy(
            status = CircuitAdministrationCommandStatus.SUCCEEDED,
            updatedAt = observedAt,
            resultingRecord = executionResult.record,
        )
        is CircuitAdministrationExecutionResult.Rejected -> current.copy(
            status = CircuitAdministrationCommandStatus.EXECUTION_REJECTED,
            updatedAt = observedAt,
            rejectionReasonCode = executionResult.reasonCode,
        )
        is CircuitAdministrationExecutionResult.Failed -> current.copy(
            status = CircuitAdministrationCommandStatus.EXECUTION_FAILED,
            updatedAt = observedAt,
            executionFailure = executionResult.failure,
        )
    }

    private fun terminalResult(
        record: CircuitAdministrationStateRecord,
    ): CircuitAdministrationResult? = when (record.state.status) {
        CircuitAdministrationCommandStatus.AUTHORIZED -> null
        CircuitAdministrationCommandStatus.SUCCEEDED -> {
            CircuitAdministrationResult.Succeeded(record)
        }
        CircuitAdministrationCommandStatus.AUTHORIZATION_DENIED -> {
            CircuitAdministrationResult.AuthorizationDenied(record)
        }
        CircuitAdministrationCommandStatus.POLICY_REJECTED -> {
            CircuitAdministrationResult.PolicyRejected(record)
        }
        CircuitAdministrationCommandStatus.EXECUTION_REJECTED -> {
            CircuitAdministrationResult.ExecutionRejected(record)
        }
        CircuitAdministrationCommandStatus.EXECUTION_FAILED -> {
            CircuitAdministrationResult.ExecutionFailed(record)
        }
    }
}

/** Exact outcome of one circuit-administration coordination attempt. */
public sealed interface CircuitAdministrationResult {
    public data class Succeeded(
        public val record: CircuitAdministrationStateRecord,
    ) : CircuitAdministrationResult

    public data class AuthorizationDenied(
        public val record: CircuitAdministrationStateRecord,
    ) : CircuitAdministrationResult

    public data class PolicyRejected(
        public val record: CircuitAdministrationStateRecord,
    ) : CircuitAdministrationResult

    public data class ExecutionRejected(
        public val record: CircuitAdministrationStateRecord,
    ) : CircuitAdministrationResult

    public data class ExecutionFailed(
        public val record: CircuitAdministrationStateRecord,
    ) : CircuitAdministrationResult

    public data class CommandConflict(
        public val existing: CircuitAdministrationStateRecord,
    ) : CircuitAdministrationResult

    public data class PersistenceFailure(
        public val error: DataLoomError,
    ) : CircuitAdministrationResult

    public data class ExecutionRecordingUnconfirmed(
        public val command: AuthorizedCircuitAdministrationCommand,
        public val executionResult: CircuitAdministrationExecutionResult,
        public val persistenceError: DataLoomError,
    ) : CircuitAdministrationResult

    public data class ClockRegression(
        public val observedAt: DataLoomInstant,
        public val persistedAt: DataLoomInstant,
    ) : CircuitAdministrationResult

    public data object ContentionLimitReached : CircuitAdministrationResult
}

private sealed interface CircuitAdministrationLoadOutcome {
    data object Missing : CircuitAdministrationLoadOutcome
    data class Found(val record: CircuitAdministrationStateRecord) : CircuitAdministrationLoadOutcome
    data class FoundConflict(val record: CircuitAdministrationStateRecord) : CircuitAdministrationLoadOutcome
    data class Failure(val error: DataLoomError) : CircuitAdministrationLoadOutcome
}

private sealed interface CircuitAdministrationAdmissionOutcome {
    data object Conflict : CircuitAdministrationAdmissionOutcome
    data class Authorized(val record: CircuitAdministrationStateRecord) : CircuitAdministrationAdmissionOutcome
    data class Completed(val result: CircuitAdministrationResult) : CircuitAdministrationAdmissionOutcome
    data class Failure(val error: DataLoomError) : CircuitAdministrationAdmissionOutcome
}

private sealed interface CircuitAdministrationUpdateOutcome {
    data object Conflict : CircuitAdministrationUpdateOutcome
    data class Updated(val record: CircuitAdministrationStateRecord) : CircuitAdministrationUpdateOutcome
    data class Failure(val error: DataLoomError) : CircuitAdministrationUpdateOutcome
}

private const val OPEN_DEADLINE_EXPIRED: String = "CIRCUIT_ADMINISTRATION_OPEN_DEADLINE_EXPIRED"
