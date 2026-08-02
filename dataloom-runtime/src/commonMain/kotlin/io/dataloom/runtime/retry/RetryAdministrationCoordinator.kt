package io.dataloom.runtime.retry

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.Recoverability
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.retry.AuthorizedRetryAdministrationCommand
import io.dataloom.api.retry.RetryAdministrationAction
import io.dataloom.api.retry.RetryAdministrationAuthorizationDecision
import io.dataloom.api.retry.RetryAdministrationAuthorizer
import io.dataloom.api.retry.RetryAdministrationCommandState
import io.dataloom.api.retry.RetryAdministrationCommandStatus
import io.dataloom.api.retry.RetryAdministrationCompareAndSetRequest
import io.dataloom.api.retry.RetryAdministrationCompareAndSetResult
import io.dataloom.api.retry.RetryAdministrationExecutionResult
import io.dataloom.api.retry.RetryAdministrationExecutor
import io.dataloom.api.retry.RetryAdministrationLoadResult
import io.dataloom.api.retry.RetryAdministrationRequest
import io.dataloom.api.retry.RetryAdministrationStateRecord
import io.dataloom.api.retry.RetryAdministrationStateStore
import io.dataloom.api.retry.RetryFailureSnapshot
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant

/**
 * Fail-closed coordinator for authorized, idempotent, and audited manual retry.
 *
 * The original failure snapshot is immutable. Reclassification changes only
 * the effective recoverability supplied to the executor. The executor remains
 * responsible for an idempotent queue mutation keyed by the command id.
 */
public class RetryAdministrationCoordinator(
    private val clock: DataLoomClock,
    private val authorizer: RetryAdministrationAuthorizer,
    private val stateStore: RetryAdministrationStateStore,
    private val executor: RetryAdministrationExecutor,
    private val maximumStateUpdateAttempts: Int = 8,
) {
    init {
        require(maximumStateUpdateAttempts >= 1) {
            "RetryAdministrationCoordinator maximumStateUpdateAttempts must be at least one."
        }
    }

    /** Executes or resumes one administrative retry command. */
    public suspend fun execute(
        request: RetryAdministrationRequest,
    ): RetryAdministrationResult {
        repeat(maximumStateUpdateAttempts) {
            val current = when (val loaded = load(request)) {
                is RetryAdministrationLoadOutcome.Failure -> return RetryAdministrationResult.PersistenceFailure(
                    loaded.error,
                )
                RetryAdministrationLoadOutcome.Missing -> when (val admitted = admit(request)) {
                    is RetryAdministrationAdmissionOutcome.Completed -> return admitted.result
                    RetryAdministrationAdmissionOutcome.Conflict -> return@repeat
                    is RetryAdministrationAdmissionOutcome.Failure -> return RetryAdministrationResult.PersistenceFailure(
                        admitted.error,
                    )
                    is RetryAdministrationAdmissionOutcome.Authorized -> admitted.record
                }
                is RetryAdministrationLoadOutcome.Found -> loaded.record
                is RetryAdministrationLoadOutcome.FoundConflict -> {
                    return RetryAdministrationResult.CommandConflict(loaded.record)
                }
            }

            terminalResult(current)?.let { result -> return result }

            check(current.state.status == RetryAdministrationCommandStatus.AUTHORIZED) {
                "RetryAdministrationStateStore returned a non-terminal unsupported status."
            }

            val observedAt = clock.now()
            if (observedAt.epochMilliseconds < current.state.updatedAt.epochMilliseconds) {
                return RetryAdministrationResult.ClockRegression(
                    observedAt = observedAt,
                    persistedAt = current.state.updatedAt,
                )
            }

            val effectiveRecoverability = effectiveRecoverability(request)
            if (effectiveRecoverability == null) {
                when (val update = update(
                    current = current,
                    nextState = current.state.copy(
                        status = RetryAdministrationCommandStatus.POLICY_REJECTED,
                        updatedAt = observedAt,
                        rejectionReasonCode = RECLASSIFICATION_REQUIRED,
                    ),
                )) {
                    RetryAdministrationUpdateOutcome.Conflict -> return@repeat
                    is RetryAdministrationUpdateOutcome.Failure -> {
                        return RetryAdministrationResult.PersistenceFailure(update.error)
                    }
                    is RetryAdministrationUpdateOutcome.Updated -> {
                        return RetryAdministrationResult.PolicyRejected(update.record)
                    }
                }
            }

            val command = AuthorizedRetryAdministrationCommand(
                request = request,
                authorizationId = checkNotNull(current.state.authorizationId),
                effectiveRecoverability = checkNotNull(effectiveRecoverability),
            )
            val executionResult = executor.execute(command)
            val finalState = finalState(
                current = current.state,
                observedAt = observedAt,
                executionResult = executionResult,
            )

            when (val update = update(current, finalState)) {
                RetryAdministrationUpdateOutcome.Conflict -> return@repeat
                is RetryAdministrationUpdateOutcome.Failure -> {
                    return RetryAdministrationResult.ExecutionRecordingUnconfirmed(
                        command = command,
                        executionResult = executionResult,
                        persistenceError = update.error,
                    )
                }
                is RetryAdministrationUpdateOutcome.Updated -> return terminalResult(update.record)
                    ?: error("Retry administration execution must produce a terminal state.")
            }
        }
        return RetryAdministrationResult.ContentionLimitReached
    }

    private suspend fun admit(
        request: RetryAdministrationRequest,
    ): RetryAdministrationAdmissionOutcome {
        val observedAt = clock.now()
        return when (val decision = authorizer.authorize(request)) {
            is RetryAdministrationAuthorizationDecision.Denied -> {
                when (val update = create(
                    RetryAdministrationCommandState(
                        request = request,
                        status = RetryAdministrationCommandStatus.AUTHORIZATION_DENIED,
                        authorizationId = null,
                        effectiveRecoverability = null,
                        updatedAt = observedAt,
                        rejectionReasonCode = decision.reasonCode,
                    ),
                )) {
                    RetryAdministrationUpdateOutcome.Conflict -> RetryAdministrationAdmissionOutcome.Conflict
                    is RetryAdministrationUpdateOutcome.Failure -> RetryAdministrationAdmissionOutcome.Failure(update.error)
                    is RetryAdministrationUpdateOutcome.Updated -> RetryAdministrationAdmissionOutcome.Completed(
                        RetryAdministrationResult.AuthorizationDenied(update.record),
                    )
                }
            }
            is RetryAdministrationAuthorizationDecision.Authorized -> {
                val effective = when (request.action) {
                    RetryAdministrationAction.REQUEUE -> request.originalFailure.recoverability
                    RetryAdministrationAction.RECLASSIFY_AND_REQUEUE -> Recoverability.RECOVERABLE
                }
                when (val update = create(
                    RetryAdministrationCommandState(
                        request = request,
                        status = RetryAdministrationCommandStatus.AUTHORIZED,
                        authorizationId = decision.authorizationId,
                        effectiveRecoverability = effective,
                        updatedAt = observedAt,
                    ),
                )) {
                    RetryAdministrationUpdateOutcome.Conflict -> RetryAdministrationAdmissionOutcome.Conflict
                    is RetryAdministrationUpdateOutcome.Failure -> RetryAdministrationAdmissionOutcome.Failure(update.error)
                    is RetryAdministrationUpdateOutcome.Updated -> RetryAdministrationAdmissionOutcome.Authorized(update.record)
                }
            }
        }
    }

    private suspend fun load(request: RetryAdministrationRequest): RetryAdministrationLoadOutcome =
        when (val result = stateStore.load(request.commandId)) {
            is ProviderOperationResult.Failure -> RetryAdministrationLoadOutcome.Failure(result.error)
            is ProviderOperationResult.Success -> when (val value = result.value) {
                RetryAdministrationLoadResult.Missing -> RetryAdministrationLoadOutcome.Missing
                is RetryAdministrationLoadResult.Found -> {
                    check(value.record.state.request.commandId == request.commandId) {
                        "RetryAdministrationStateStore returned a record for another command id."
                    }
                    if (value.record.state.request != request) {
                        RetryAdministrationLoadOutcome.FoundConflict(value.record)
                    } else {
                        RetryAdministrationLoadOutcome.Found(value.record)
                    }
                }
            }
        }

    private suspend fun create(
        state: RetryAdministrationCommandState,
    ): RetryAdministrationUpdateOutcome = compareAndSet(
        RetryAdministrationCompareAndSetRequest(
            commandId = state.request.commandId,
            expectedVersion = null,
            nextState = state,
        ),
    )

    private suspend fun update(
        current: RetryAdministrationStateRecord,
        nextState: RetryAdministrationCommandState,
    ): RetryAdministrationUpdateOutcome = compareAndSet(
        RetryAdministrationCompareAndSetRequest(
            commandId = current.state.request.commandId,
            expectedVersion = current.version,
            nextState = nextState,
        ),
    )

    private suspend fun compareAndSet(
        request: RetryAdministrationCompareAndSetRequest,
    ): RetryAdministrationUpdateOutcome = when (val result = stateStore.compareAndSet(request)) {
        is ProviderOperationResult.Failure -> RetryAdministrationUpdateOutcome.Failure(result.error)
        is ProviderOperationResult.Success -> when (val value = result.value) {
            is RetryAdministrationCompareAndSetResult.Conflict -> RetryAdministrationUpdateOutcome.Conflict
            is RetryAdministrationCompareAndSetResult.Updated -> RetryAdministrationUpdateOutcome.Updated(value.record)
        }
    }

    private fun effectiveRecoverability(
        request: RetryAdministrationRequest,
    ): Recoverability? = when (request.action) {
        RetryAdministrationAction.RECLASSIFY_AND_REQUEUE -> Recoverability.RECOVERABLE
        RetryAdministrationAction.REQUEUE -> if (request.originalFailure.isAutomaticRetrySafe()) {
            Recoverability.RECOVERABLE
        } else {
            null
        }
    }

    private fun finalState(
        current: RetryAdministrationCommandState,
        observedAt: DataLoomInstant,
        executionResult: RetryAdministrationExecutionResult,
    ): RetryAdministrationCommandState = when (executionResult) {
        RetryAdministrationExecutionResult.Applied -> current.copy(
            status = RetryAdministrationCommandStatus.SUCCEEDED,
            updatedAt = observedAt,
        )
        is RetryAdministrationExecutionResult.Rejected -> current.copy(
            status = RetryAdministrationCommandStatus.EXECUTION_REJECTED,
            updatedAt = observedAt,
            rejectionReasonCode = executionResult.reasonCode,
        )
        is RetryAdministrationExecutionResult.Failed -> current.copy(
            status = RetryAdministrationCommandStatus.EXECUTION_FAILED,
            updatedAt = observedAt,
            executionFailure = executionResult.error.snapshot(),
        )
    }

    private fun terminalResult(
        record: RetryAdministrationStateRecord,
    ): RetryAdministrationResult? = when (record.state.status) {
        RetryAdministrationCommandStatus.AUTHORIZED -> null
        RetryAdministrationCommandStatus.SUCCEEDED -> RetryAdministrationResult.Succeeded(record)
        RetryAdministrationCommandStatus.AUTHORIZATION_DENIED -> {
            RetryAdministrationResult.AuthorizationDenied(record)
        }
        RetryAdministrationCommandStatus.POLICY_REJECTED -> {
            RetryAdministrationResult.PolicyRejected(record)
        }
        RetryAdministrationCommandStatus.EXECUTION_REJECTED -> {
            RetryAdministrationResult.ExecutionRejected(record)
        }
        RetryAdministrationCommandStatus.EXECUTION_FAILED -> {
            RetryAdministrationResult.ExecutionFailed(record)
        }
    }
}

/** Exact outcome of one administrative retry coordination attempt. */
public sealed interface RetryAdministrationResult {
    public data class Succeeded(
        public val record: RetryAdministrationStateRecord,
    ) : RetryAdministrationResult

    public data class AuthorizationDenied(
        public val record: RetryAdministrationStateRecord,
    ) : RetryAdministrationResult

    public data class PolicyRejected(
        public val record: RetryAdministrationStateRecord,
    ) : RetryAdministrationResult

    public data class ExecutionRejected(
        public val record: RetryAdministrationStateRecord,
    ) : RetryAdministrationResult

    public data class ExecutionFailed(
        public val record: RetryAdministrationStateRecord,
    ) : RetryAdministrationResult

    public data class CommandConflict(
        public val existing: RetryAdministrationStateRecord,
    ) : RetryAdministrationResult

    public data class PersistenceFailure(
        public val error: DataLoomError,
    ) : RetryAdministrationResult

    public data class ExecutionRecordingUnconfirmed(
        public val command: AuthorizedRetryAdministrationCommand,
        public val executionResult: RetryAdministrationExecutionResult,
        public val persistenceError: DataLoomError,
    ) : RetryAdministrationResult

    public data class ClockRegression(
        public val observedAt: DataLoomInstant,
        public val persistedAt: DataLoomInstant,
    ) : RetryAdministrationResult

    public data object ContentionLimitReached : RetryAdministrationResult
}

private sealed interface RetryAdministrationLoadOutcome {
    data object Missing : RetryAdministrationLoadOutcome
    data class Found(val record: RetryAdministrationStateRecord) : RetryAdministrationLoadOutcome
    data class FoundConflict(val record: RetryAdministrationStateRecord) : RetryAdministrationLoadOutcome
    data class Failure(val error: DataLoomError) : RetryAdministrationLoadOutcome
}

private sealed interface RetryAdministrationAdmissionOutcome {
    data object Conflict : RetryAdministrationAdmissionOutcome
    data class Authorized(val record: RetryAdministrationStateRecord) : RetryAdministrationAdmissionOutcome
    data class Completed(val result: RetryAdministrationResult) : RetryAdministrationAdmissionOutcome
    data class Failure(val error: DataLoomError) : RetryAdministrationAdmissionOutcome
}

private sealed interface RetryAdministrationUpdateOutcome {
    data object Conflict : RetryAdministrationUpdateOutcome
    data class Updated(val record: RetryAdministrationStateRecord) : RetryAdministrationUpdateOutcome
    data class Failure(val error: DataLoomError) : RetryAdministrationUpdateOutcome
}

private fun RetryFailureSnapshot.isAutomaticRetrySafe(): Boolean =
    recoverability == Recoverability.RECOVERABLE && !category.isProtectedFromAutomaticRetry()

private fun ErrorCategory.isProtectedFromAutomaticRetry(): Boolean = when (this) {
    ErrorCategory.AUTHENTICATION,
    ErrorCategory.AUTHORIZATION,
    ErrorCategory.SERIALIZATION,
    ErrorCategory.VALIDATION,
    ErrorCategory.CONFIGURATION,
    ErrorCategory.POLICY,
    ErrorCategory.CONFLICT,
    ErrorCategory.SECURITY,
    -> true

    ErrorCategory.NETWORK,
    ErrorCategory.STORAGE,
    ErrorCategory.QUEUE,
    ErrorCategory.SCHEDULER,
    ErrorCategory.STATE,
    ErrorCategory.PROVIDER,
    ErrorCategory.PLUGIN,
    ErrorCategory.INTERNAL,
    -> false
}

private fun DataLoomError.snapshot(): RetryFailureSnapshot = RetryFailureSnapshot(
    code = code,
    category = category,
    severity = severity,
    recoverability = recoverability,
)

private const val RECLASSIFICATION_REQUIRED: String = "RETRY_RECLASSIFICATION_REQUIRED"
