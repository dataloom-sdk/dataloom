package io.dataloom.runtime.conflict

import io.dataloom.api.conflict.AuthorizedConflictAdministrationCommand
import io.dataloom.api.conflict.ConflictAdministrationAuthorizationDecision
import io.dataloom.api.conflict.ConflictAdministrationAuthorizer
import io.dataloom.api.conflict.ConflictAdministrationCommandState
import io.dataloom.api.conflict.ConflictAdministrationCommandStatus
import io.dataloom.api.conflict.ConflictAdministrationCompareAndSetRequest
import io.dataloom.api.conflict.ConflictAdministrationCompareAndSetResult
import io.dataloom.api.conflict.ConflictAdministrationExecutionResult
import io.dataloom.api.conflict.ConflictAdministrationExecutor
import io.dataloom.api.conflict.ConflictAdministrationFailureSnapshot
import io.dataloom.api.conflict.ConflictAdministrationLoadResult
import io.dataloom.api.conflict.ConflictAdministrationPrincipalId
import io.dataloom.api.conflict.ConflictAdministrationRequest
import io.dataloom.api.conflict.ConflictAdministrationStateRecord
import io.dataloom.api.conflict.ConflictAdministrationStateStore
import io.dataloom.api.conflict.ConflictResolutionDecision
import io.dataloom.api.conflict.DurableResolvedConflictDecisionLog
import io.dataloom.api.conflict.DurableResolvedConflictDecisionRecordOutcome
import io.dataloom.api.conflict.DurableUnresolvedConflictLog
import io.dataloom.api.conflict.ResolvedConflictDecisionKind
import io.dataloom.api.conflict.ResolvedConflictDecisionRecord
import io.dataloom.api.conflict.UnresolvedConflictChangeSummary
import io.dataloom.api.conflict.UnresolvedConflictRecord
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.ConflictResolverId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant

/**
 * Fail-closed coordinator for authorized, idempotent, and audited manual
 * conflict resolution -- the "authorized manual operations" capability for
 * conflicts [DurableConflictDetectionCoordinator] durably recorded as
 * unresolved.
 *
 * ## Relationship to the live inbound pipeline
 *
 * This coordinator is a deliberately separate application path from
 * [io.dataloom.runtime.execution.inbound.InboundConflictDecisionPreparer].
 * That preparer only ever applies a decision to the exact live
 * [io.dataloom.api.change.ChangeSet] batch that produced it, inside one
 * inbound pull -- by the time an operator gets around to deciding an
 * already-durably-recorded unresolved conflict, that batch is no longer
 * live: the pull that detected it already returned
 * `SynchronizationResult.Failed` without advancing its checkpoint. This
 * coordinator never touches that pipeline, a [io.dataloom.api.change.ChangeSet],
 * or a `StorageProvider` directly. It authorizes, checks eligibility, durably
 * records the outcome, and delegates the actual application of the decision
 * to a host-owned [ConflictAdministrationExecutor] -- the same separation
 * [io.dataloom.api.retry.RetryAdministrationExecutor] already establishes for
 * administrative retry: DataLoom owns authorization, idempotency, and audit;
 * the host owns retrieving the real payload (never durably retained by
 * [DurableUnresolvedConflictLog] or [DurableResolvedConflictDecisionLog], by
 * design) and deciding whether the target entity is still eligible.
 * Freshness/staleness checking against the live entity is the executor's
 * responsibility, not this coordinator's.
 *
 * ## Eligibility
 *
 * A command is eligible only when [unresolvedConflictLog] currently holds an
 * [UnresolvedConflictRecord] for [ConflictAdministrationRequest.conflictId]
 * (a nonexistent conflict ID is rejected) and [resolvedConflictDecisionLog]
 * does not already hold a decision for it (an already-resolved conflict ID
 * is rejected) -- both surface as
 * [ConflictAdministrationCommandStatus.POLICY_REJECTED]. A
 * [ConflictResolutionDecision.Merge] whose `expectedEntity` does not match
 * the recorded conflict's entity is rejected the same way, mirroring
 * [io.dataloom.runtime.execution.inbound.InboundConflictDecisionPreparer]'s
 * own merge-contract check. Eligibility is re-checked against durable state
 * on every attempt; it is never cached in the command record.
 *
 * ## Durable recording
 *
 * A successful [ConflictAdministrationExecutor.execute] is recorded into
 * [resolvedConflictDecisionLog] by reusing [ResolvedConflictDecisionRecord]
 * -- with [ConflictAdministrationRequest.principalId] encoded as a sentinel
 * [ConflictResolverId] (`"manual:<principalId>"`) rather than a real
 * [io.dataloom.api.conflict.ConflictResolver.id] -- instead of inventing a
 * new durable-record type for manual decisions. A durable-recording race
 * (two different commands resolving the same conflict) surfaces as
 * [ConflictAdministrationCommandStatus.EXECUTION_FAILED] rather than a false
 * success.
 */
public class ConflictAdministrationCoordinator(
    private val clock: DataLoomClock,
    private val authorizer: ConflictAdministrationAuthorizer,
    private val stateStore: ConflictAdministrationStateStore,
    private val executor: ConflictAdministrationExecutor,
    private val unresolvedConflictLog: DurableUnresolvedConflictLog,
    private val resolvedConflictDecisionLog: DurableResolvedConflictDecisionLog,
    private val maximumStateUpdateAttempts: Int = DEFAULT_MAX_STATE_UPDATE_ATTEMPTS,
) {
    init {
        require(maximumStateUpdateAttempts >= 1) {
            "ConflictAdministrationCoordinator maximumStateUpdateAttempts must be at least one."
        }
    }

    /** Executes or resumes one administrative manual-conflict command. */
    public suspend fun execute(
        request: ConflictAdministrationRequest,
    ): ConflictAdministrationResult {
        repeat(maximumStateUpdateAttempts) {
            val current = when (val loaded = load(request)) {
                is ConflictAdministrationLoadOutcome.Failure ->
                    return ConflictAdministrationResult.PersistenceFailure(loaded.error)
                ConflictAdministrationLoadOutcome.Missing -> when (val admitted = admit(request)) {
                    is ConflictAdministrationAdmissionOutcome.Completed -> return admitted.result
                    ConflictAdministrationAdmissionOutcome.Conflict -> return@repeat
                    is ConflictAdministrationAdmissionOutcome.Failure ->
                        return ConflictAdministrationResult.PersistenceFailure(admitted.error)
                    is ConflictAdministrationAdmissionOutcome.Authorized -> admitted.record
                }
                is ConflictAdministrationLoadOutcome.Found -> loaded.record
                is ConflictAdministrationLoadOutcome.FoundConflict ->
                    return ConflictAdministrationResult.CommandConflict(loaded.record)
            }

            terminalResult(current)?.let { result -> return result }

            check(current.state.status == ConflictAdministrationCommandStatus.AUTHORIZED) {
                "ConflictAdministrationStateStore returned a non-terminal unsupported status."
            }

            val observedAt = clock.now()
            if (observedAt.epochMilliseconds < current.state.updatedAt.epochMilliseconds) {
                return ConflictAdministrationResult.ClockRegression(
                    observedAt = observedAt,
                    persistedAt = current.state.updatedAt,
                )
            }

            when (val eligibility = checkEligibility(request)) {
                is Eligibility.Failure ->
                    return ConflictAdministrationResult.PersistenceFailure(eligibility.error)

                is Eligibility.Ineligible -> {
                    when (
                        val update = update(
                            current = current,
                            nextState = current.state.copy(
                                status = ConflictAdministrationCommandStatus.POLICY_REJECTED,
                                updatedAt = observedAt,
                                rejectionReasonCode = eligibility.reasonCode,
                            ),
                        )
                    ) {
                        ConflictAdministrationUpdateOutcome.Conflict -> return@repeat
                        is ConflictAdministrationUpdateOutcome.Failure ->
                            return ConflictAdministrationResult.PersistenceFailure(update.error)
                        is ConflictAdministrationUpdateOutcome.Updated ->
                            return ConflictAdministrationResult.PolicyRejected(update.record)
                    }
                }

                is Eligibility.Eligible -> {
                    val command = AuthorizedConflictAdministrationCommand(
                        request = request,
                        authorizationId = checkNotNull(current.state.authorizationId),
                        unresolvedRecord = eligibility.record,
                    )
                    val executionResult = executor.execute(command)
                    val finalState = finalState(
                        current = current.state,
                        observedAt = observedAt,
                        executionResult = executionResult,
                        unresolvedRecord = eligibility.record,
                    )

                    when (val update = update(current, finalState)) {
                        ConflictAdministrationUpdateOutcome.Conflict -> return@repeat
                        is ConflictAdministrationUpdateOutcome.Failure -> {
                            return ConflictAdministrationResult.ExecutionRecordingUnconfirmed(
                                command = command,
                                executionResult = executionResult,
                                persistenceError = update.error,
                            )
                        }
                        is ConflictAdministrationUpdateOutcome.Updated -> return terminalResult(update.record)
                            ?: error("Conflict administration execution must produce a terminal state.")
                    }
                }
            }
        }
        return ConflictAdministrationResult.ContentionLimitReached
    }

    private suspend fun admit(
        request: ConflictAdministrationRequest,
    ): ConflictAdministrationAdmissionOutcome {
        val observedAt = clock.now()
        return when (val decision = authorizer.authorize(request)) {
            is ConflictAdministrationAuthorizationDecision.Denied -> {
                when (
                    val update = create(
                        ConflictAdministrationCommandState(
                            request = request,
                            status = ConflictAdministrationCommandStatus.AUTHORIZATION_DENIED,
                            authorizationId = null,
                            updatedAt = observedAt,
                            rejectionReasonCode = decision.reasonCode,
                        ),
                    )
                ) {
                    ConflictAdministrationUpdateOutcome.Conflict -> ConflictAdministrationAdmissionOutcome.Conflict
                    is ConflictAdministrationUpdateOutcome.Failure ->
                        ConflictAdministrationAdmissionOutcome.Failure(update.error)
                    is ConflictAdministrationUpdateOutcome.Updated ->
                        ConflictAdministrationAdmissionOutcome.Completed(
                            ConflictAdministrationResult.AuthorizationDenied(update.record),
                        )
                }
            }
            is ConflictAdministrationAuthorizationDecision.Authorized -> {
                when (
                    val update = create(
                        ConflictAdministrationCommandState(
                            request = request,
                            status = ConflictAdministrationCommandStatus.AUTHORIZED,
                            authorizationId = decision.authorizationId,
                            updatedAt = observedAt,
                        ),
                    )
                ) {
                    ConflictAdministrationUpdateOutcome.Conflict -> ConflictAdministrationAdmissionOutcome.Conflict
                    is ConflictAdministrationUpdateOutcome.Failure ->
                        ConflictAdministrationAdmissionOutcome.Failure(update.error)
                    is ConflictAdministrationUpdateOutcome.Updated ->
                        ConflictAdministrationAdmissionOutcome.Authorized(update.record)
                }
            }
        }
    }

    /**
     * Checks whether [request] currently targets a real, still-unresolved,
     * not-yet-resolved conflict -- see this class's own "Eligibility" class
     * doc.
     */
    private suspend fun checkEligibility(request: ConflictAdministrationRequest): Eligibility {
        val unresolved = when (val result = unresolvedConflictLog.current(request.conflictId)) {
            is ProviderOperationResult.Failure -> return Eligibility.Failure(result.error)
            is ProviderOperationResult.Success ->
                result.value ?: return Eligibility.Ineligible(CONFLICT_NOT_UNRESOLVED)
        }

        val alreadyResolved = when (val result = resolvedConflictDecisionLog.current(request.conflictId)) {
            is ProviderOperationResult.Failure -> return Eligibility.Failure(result.error)
            is ProviderOperationResult.Success -> result.value != null
        }
        if (alreadyResolved) {
            return Eligibility.Ineligible(CONFLICT_ALREADY_RESOLVED)
        }

        val merge = request.decision as? ConflictResolutionDecision.Merge
        if (merge != null &&
            (merge.expectedEntity.type != unresolved.entity.type || merge.expectedEntity.id != unresolved.entity.id)
        ) {
            return Eligibility.Ineligible(MERGE_ENTITY_MISMATCH)
        }

        return Eligibility.Eligible(unresolved)
    }

    private suspend fun finalState(
        current: ConflictAdministrationCommandState,
        observedAt: DataLoomInstant,
        executionResult: ConflictAdministrationExecutionResult,
        unresolvedRecord: UnresolvedConflictRecord,
    ): ConflictAdministrationCommandState = when (executionResult) {
        ConflictAdministrationExecutionResult.Applied ->
            when (val outcome = recordResolvedDecision(current.request, unresolvedRecord)) {
                is DurableResolvedConflictDecisionRecordOutcome.Recorded,
                is DurableResolvedConflictDecisionRecordOutcome.AlreadyRecorded,
                -> current.copy(
                    status = ConflictAdministrationCommandStatus.SUCCEEDED,
                    updatedAt = observedAt,
                )

                is DurableResolvedConflictDecisionRecordOutcome.Conflict -> current.copy(
                    status = ConflictAdministrationCommandStatus.EXECUTION_FAILED,
                    updatedAt = observedAt,
                    executionFailure = ConflictAdministrationFailureSnapshot(
                        code = ErrorCode("DL-CONFLICT-ADMINISTRATION-NON-CONVERGENT"),
                        category = ErrorCategory.CONFLICT,
                        severity = ErrorSeverity.ERROR,
                        recoverability = Recoverability.NON_RECOVERABLE,
                    ),
                )

                is DurableResolvedConflictDecisionRecordOutcome.PersistenceFailure -> current.copy(
                    status = ConflictAdministrationCommandStatus.EXECUTION_FAILED,
                    updatedAt = observedAt,
                    executionFailure = outcome.error.snapshot(),
                )

                DurableResolvedConflictDecisionRecordOutcome.ContentionLimitReached -> current.copy(
                    status = ConflictAdministrationCommandStatus.EXECUTION_FAILED,
                    updatedAt = observedAt,
                    executionFailure = ConflictAdministrationFailureSnapshot(
                        code = ErrorCode("DL-CONFLICT-ADMINISTRATION-CONTENTION"),
                        category = ErrorCategory.STATE,
                        severity = ErrorSeverity.ERROR,
                        recoverability = Recoverability.RECOVERABLE,
                    ),
                )
            }

        is ConflictAdministrationExecutionResult.Rejected -> current.copy(
            status = ConflictAdministrationCommandStatus.EXECUTION_REJECTED,
            updatedAt = observedAt,
            rejectionReasonCode = executionResult.reasonCode,
        )

        is ConflictAdministrationExecutionResult.Failed -> current.copy(
            status = ConflictAdministrationCommandStatus.EXECUTION_FAILED,
            updatedAt = observedAt,
            executionFailure = executionResult.error.snapshot(),
        )
    }

    /**
     * Records [request]'s decision into [resolvedConflictDecisionLog], reusing
     * [ResolvedConflictDecisionRecord] with a sentinel manual
     * [ConflictResolverId] -- see this class's own "Durable recording" class
     * doc.
     */
    private suspend fun recordResolvedDecision(
        request: ConflictAdministrationRequest,
        unresolvedRecord: UnresolvedConflictRecord,
    ): DurableResolvedConflictDecisionRecordOutcome {
        val decision = request.decision
        val record = ResolvedConflictDecisionRecord(
            conflictType = unresolvedRecord.conflictType,
            entity = unresolvedRecord.entity,
            localChange = unresolvedRecord.localChange,
            remoteChange = unresolvedRecord.remoteChange,
            conflictMetadata = unresolvedRecord.conflictMetadata,
            resolverId = manualResolverId(request.principalId),
            decisionKind = decision.toKind(),
            decisionMetadata = decision.metadataValue(),
            mergedChange = (decision as? ConflictResolutionDecision.Merge)?.resolvedChange?.let {
                UnresolvedConflictChangeSummary(
                    changeEventId = it.id,
                    operation = it.operation,
                    metadata = it.metadata,
                )
            },
            failureErrorCode = (decision as? ConflictResolutionDecision.Fail)?.error?.code?.value,
            committedAt = clock.now(),
        )
        return resolvedConflictDecisionLog.record(request.conflictId, record)
    }

    private suspend fun load(request: ConflictAdministrationRequest): ConflictAdministrationLoadOutcome =
        when (val result = stateStore.load(request.commandId)) {
            is ProviderOperationResult.Failure -> ConflictAdministrationLoadOutcome.Failure(result.error)
            is ProviderOperationResult.Success -> when (val value = result.value) {
                ConflictAdministrationLoadResult.Missing -> ConflictAdministrationLoadOutcome.Missing
                is ConflictAdministrationLoadResult.Found -> {
                    check(value.record.state.request.commandId == request.commandId) {
                        "ConflictAdministrationStateStore returned a record for another command id."
                    }
                    if (value.record.state.request != request) {
                        ConflictAdministrationLoadOutcome.FoundConflict(value.record)
                    } else {
                        ConflictAdministrationLoadOutcome.Found(value.record)
                    }
                }
            }
        }

    private suspend fun create(
        state: ConflictAdministrationCommandState,
    ): ConflictAdministrationUpdateOutcome = compareAndSet(
        ConflictAdministrationCompareAndSetRequest(
            commandId = state.request.commandId,
            expectedVersion = null,
            nextState = state,
        ),
    )

    private suspend fun update(
        current: ConflictAdministrationStateRecord,
        nextState: ConflictAdministrationCommandState,
    ): ConflictAdministrationUpdateOutcome = compareAndSet(
        ConflictAdministrationCompareAndSetRequest(
            commandId = current.state.request.commandId,
            expectedVersion = current.version,
            nextState = nextState,
        ),
    )

    private suspend fun compareAndSet(
        request: ConflictAdministrationCompareAndSetRequest,
    ): ConflictAdministrationUpdateOutcome = when (val result = stateStore.compareAndSet(request)) {
        is ProviderOperationResult.Failure -> ConflictAdministrationUpdateOutcome.Failure(result.error)
        is ProviderOperationResult.Success -> when (val value = result.value) {
            is ConflictAdministrationCompareAndSetResult.Conflict -> ConflictAdministrationUpdateOutcome.Conflict
            is ConflictAdministrationCompareAndSetResult.Updated ->
                ConflictAdministrationUpdateOutcome.Updated(value.record)
        }
    }

    private fun terminalResult(
        record: ConflictAdministrationStateRecord,
    ): ConflictAdministrationResult? = when (record.state.status) {
        ConflictAdministrationCommandStatus.AUTHORIZED -> null
        ConflictAdministrationCommandStatus.SUCCEEDED -> ConflictAdministrationResult.Succeeded(record)
        ConflictAdministrationCommandStatus.AUTHORIZATION_DENIED ->
            ConflictAdministrationResult.AuthorizationDenied(record)
        ConflictAdministrationCommandStatus.POLICY_REJECTED ->
            ConflictAdministrationResult.PolicyRejected(record)
        ConflictAdministrationCommandStatus.EXECUTION_REJECTED ->
            ConflictAdministrationResult.ExecutionRejected(record)
        ConflictAdministrationCommandStatus.EXECUTION_FAILED ->
            ConflictAdministrationResult.ExecutionFailed(record)
    }

    private fun manualResolverId(principalId: ConflictAdministrationPrincipalId): ConflictResolverId =
        ConflictResolverId("manual:${principalId.value}")

    private fun ConflictResolutionDecision.toKind(): ResolvedConflictDecisionKind = when (this) {
        is ConflictResolutionDecision.UseLocal -> ResolvedConflictDecisionKind.USE_LOCAL
        is ConflictResolutionDecision.UseRemote -> ResolvedConflictDecisionKind.USE_REMOTE
        is ConflictResolutionDecision.Merge -> ResolvedConflictDecisionKind.MERGE
        is ConflictResolutionDecision.Fail -> ResolvedConflictDecisionKind.FAIL
        is ConflictResolutionDecision.Defer ->
            error("ConflictAdministrationRequest never carries Defer -- rejected at construction.")
    }

    private fun ConflictResolutionDecision.metadataValue() = when (this) {
        is ConflictResolutionDecision.UseLocal -> metadata
        is ConflictResolutionDecision.UseRemote -> metadata
        is ConflictResolutionDecision.Merge -> metadata
        is ConflictResolutionDecision.Fail -> metadata
        is ConflictResolutionDecision.Defer -> metadata
    }

    private fun DataLoomError.snapshot(): ConflictAdministrationFailureSnapshot = ConflictAdministrationFailureSnapshot(
        code = code,
        category = category,
        severity = severity,
        recoverability = recoverability,
    )

    public companion object {
        private const val DEFAULT_MAX_STATE_UPDATE_ATTEMPTS: Int = 8
        private const val CONFLICT_NOT_UNRESOLVED: String = "CONFLICT_NOT_UNRESOLVED"
        private const val CONFLICT_ALREADY_RESOLVED: String = "CONFLICT_ALREADY_RESOLVED"
        private const val MERGE_ENTITY_MISMATCH: String = "MERGE_ENTITY_MISMATCH"
    }
}

/** Exact outcome of one administrative manual-conflict coordination attempt. */
public sealed interface ConflictAdministrationResult {
    public data class Succeeded(
        public val record: ConflictAdministrationStateRecord,
    ) : ConflictAdministrationResult

    public data class AuthorizationDenied(
        public val record: ConflictAdministrationStateRecord,
    ) : ConflictAdministrationResult

    public data class PolicyRejected(
        public val record: ConflictAdministrationStateRecord,
    ) : ConflictAdministrationResult

    public data class ExecutionRejected(
        public val record: ConflictAdministrationStateRecord,
    ) : ConflictAdministrationResult

    public data class ExecutionFailed(
        public val record: ConflictAdministrationStateRecord,
    ) : ConflictAdministrationResult

    public data class CommandConflict(
        public val existing: ConflictAdministrationStateRecord,
    ) : ConflictAdministrationResult

    public data class PersistenceFailure(
        public val error: DataLoomError,
    ) : ConflictAdministrationResult

    public data class ExecutionRecordingUnconfirmed(
        public val command: AuthorizedConflictAdministrationCommand,
        public val executionResult: ConflictAdministrationExecutionResult,
        public val persistenceError: DataLoomError,
    ) : ConflictAdministrationResult

    public data class ClockRegression(
        public val observedAt: DataLoomInstant,
        public val persistedAt: DataLoomInstant,
    ) : ConflictAdministrationResult

    public data object ContentionLimitReached : ConflictAdministrationResult
}

private sealed interface Eligibility {
    data class Eligible(val record: UnresolvedConflictRecord) : Eligibility
    data class Ineligible(val reasonCode: String) : Eligibility
    data class Failure(val error: DataLoomError) : Eligibility
}

private sealed interface ConflictAdministrationLoadOutcome {
    data object Missing : ConflictAdministrationLoadOutcome
    data class Found(val record: ConflictAdministrationStateRecord) : ConflictAdministrationLoadOutcome
    data class FoundConflict(val record: ConflictAdministrationStateRecord) : ConflictAdministrationLoadOutcome
    data class Failure(val error: DataLoomError) : ConflictAdministrationLoadOutcome
}

private sealed interface ConflictAdministrationAdmissionOutcome {
    data object Conflict : ConflictAdministrationAdmissionOutcome
    data class Authorized(val record: ConflictAdministrationStateRecord) : ConflictAdministrationAdmissionOutcome
    data class Completed(val result: ConflictAdministrationResult) : ConflictAdministrationAdmissionOutcome
    data class Failure(val error: DataLoomError) : ConflictAdministrationAdmissionOutcome
}

private sealed interface ConflictAdministrationUpdateOutcome {
    data object Conflict : ConflictAdministrationUpdateOutcome
    data class Updated(val record: ConflictAdministrationStateRecord) : ConflictAdministrationUpdateOutcome
    data class Failure(val error: DataLoomError) : ConflictAdministrationUpdateOutcome
}
