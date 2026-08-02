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
                is LoadOutcome.Failure -> return RetryAdministrationResult.PersistenceFailure(
                    loaded.error,
                )
                LoadOutcome.Missing -> when (val admitted = admit(request)) {
                    is AdmissionOutcome.Completed -> return admitted.result
                    AdmissionOutcome.Conflict -> return@repeat
                    is AdmissionOutcome.Failure -> return RetryAdministrationResult.PersistenceFailure(
                        admitted.error,
                    )
                    is AdmissionOutcome.Authorized -> admitted.record
                }
                is LoadOutcome.Found -> loaded.record
                is LoadOutcome.FoundConflict -> {
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
                    UpdateOutcome.Conflict -> return@repeat
                    is UpdateOutcome.Failure -> {
                        return RetryAdministrationResult.PersistenceFailure(update.error)
                    }
                    is UpdateOutcome.Updated -> {
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
                UpdateOutcome.Conflict -> return@repeat
                is UpdateOutcome.Failure -> {
                    return RetryAdministrationResult.ExecutionRecordingUnconfirmed(
                        command = command,
                        executionResult = executionResult,
                        persistenceError = update.error,
                    )
                }
                is UpdateOutcome.Updated -> return terminalResult(update.record)
                    ?: error("Retry administration execution must produce a terminal state.")
            }
        }
        return RetryAdministrationResult.ContentionLimitReached
    }

    private suspend fun admit(
        request: RetryAdministrationRequest,
    ): AdmissionOutcome {
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
                    UpdateOutcome.Conflict -> AdmissionOutcome.Conflict
                    is UpdateOutcome.Failure -> AdmissionOutcome.Failure(update.error)
                    is UpdateOutcome.Updated -> AdmissionOutcome.Completed(
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
                    UpdateOutcome.Conflict -> AdmissionOutcome.Conflict
                    is UpdateOutcome.Failure -> AdmissionOutcome.Failure(update.error)
                    is UpdateOutcome.Updated -> AdmissionOutcome.Authorized(update.record)
                }
            }
        }
    }

    private suspend fun load(request: RetryAdministrationRequest): LoadOutcome =
        when (val result = stateStore.load(request.commandId)) {
            is ProviderOperationResult.Failure -> LoadOutcome.Failure(result.error)
            is ProviderOperationResult.Success -> when (val value = result.value) {
                RetryAdministrationLoadResult.Missing -> LoadOutcome.Missing
                is RetryAdministrationLoadResult.Found -> {
                    check(value.record.state.request.commandId == request.commandId) {
                        "RetryAdministrationStateStore returned a record for another command id."
                    }
                    if (value.record.state.request != request) {
                        LoadOutcome.FoundConflict(value.record)
                    } else {
                        LoadOutcome.Found(value.record)
                    }
                }
            }
        }

    private suspend fun create(
        state: RetryAdministrationCommandState,
    ): UpdateOutcome = compareAndSet(
        RetryAdministrationCompareAndSetRequest(
            commandId = state.request.commandId,
            expectedVersion = null,
            nextState = state,
        ),
    )

    private suspend fun update(
        current: RetryAdministrationStateRecord,
        nextState: RetryAdministrationCommandState,
    ): UpdateOutcome = compareAndSet(
        RetryAdministrationCompareAndSetRequest(
            commandId = current.state.request.commandId,
            expectedVersion = current.version,
            nextState = nextState,
        ),
    )

    private suspend fun compareAndSet(
        request: RetryAdministrationCompareAndSetRequest,
    ): UpdateOutcome = when (val result = stateStore.compareAndSet(request)) {
        is ProviderOperationResult.Failure -> UpdateOutcome.Failure(result.error)
        is ProviderOperationResult.Success -> when (val value = result.value) {
            is RetryAdministrationCompareAndSetResult.Conflict -> UpdateOutcome.Conflict
            is RetryAdministrationCompareAndSetResult.Updated -> UpdateOutcome.Updated(value.record)
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

private sealed interface LoadOutcome {
    data object Missing : LoadOutcome
    data class Found(val record: RetryAdministrationStateRecord) : LoadOutcome
    data class FoundConflict(val record: RetryAdministrationStateRecord) : LoadOutcome
    data class Failure(val error: DataLoomError) : LoadOutcome
}

private sealed interface AdmissionOutcome {
    data object Conflict : AdmissionOutcome
    data class Authorized(val record: RetryAdministrationStateRecord) : AdmissionOutcome
    data class Completed(val result: RetryAdministrationResult) : AdmissionOutcome
    data class Failure(val error: DataLoomError) : AdmissionOutcome
}

private sealed interface UpdateOutcome {
    data object Conflict : UpdateOutcome
    data class Updated(val record: RetryAdministrationStateRecord) : UpdateOutcome
    data class Failure(val error: DataLoomError) : UpdateOutcome
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
