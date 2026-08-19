package io.dataloom.runtime.execution.inbound

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.ChangeSet
import io.dataloom.api.conflict.ConflictDetectionRequest
import io.dataloom.api.conflict.ConflictResolutionDecision
import io.dataloom.api.conflict.DurableResolvedConflictDecisionRecordOutcome
import io.dataloom.api.conflict.DurableUnresolvedConflictRecordOutcome
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.error.safeDiagnosticString
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.storage.LocalConflictCandidateReadRequest
import io.dataloom.api.storage.LocalConflictCandidateReadResult
import io.dataloom.runtime.conflict.ConflictOrchestrationRequest
import io.dataloom.runtime.conflict.ConflictOrchestrationResult
import io.dataloom.runtime.execution.SynchronizationExecutionContext

/**
 * Prepares one inbound batch for application after optional conflict work.
 *
 * A missing resolved-decision log preserves the historical observational mode.
 * A configured log enables fail-closed decision application and is required to
 * commit the exact decision before any transformed batch reaches storage.
 */
internal class InboundConflictDecisionPreparer(
    private val conflictDetection: InboundPullConflictDetectionConfiguration?,
) {
    suspend fun prepare(
        request: SynchronizationRequest,
        changeSet: ChangeSet,
        context: SynchronizationExecutionContext,
    ): InboundConflictPreparation {
        val detection = conflictDetection
            ?: return InboundConflictPreparation.Ready(changeSet, 0L)
        return if (detection.coordinator.hasResolvedDecisionLog) {
            prepareForApplication(request, changeSet, context, detection)
        } else {
            observe(request, changeSet, context, detection)
        }
    }

    private suspend fun observe(
        request: SynchronizationRequest,
        changeSet: ChangeSet,
        context: SynchronizationExecutionContext,
        detection: InboundPullConflictDetectionConfiguration,
    ): InboundConflictPreparation {
        var conflictCount = 0L
        val storage = context.providers.storageProvider
        for (remoteEvent in changeSet.events) {
            val candidate = storage.readLocalConflictCandidate(
                LocalConflictCandidateReadRequest(request, remoteEvent.entity),
            )
            val localEvent = when (candidate) {
                is ProviderOperationResult.Success -> when (val value = candidate.value) {
                    is LocalConflictCandidateReadResult.Found -> value.localChange
                    is LocalConflictCandidateReadResult.NotFound -> null
                }
                is ProviderOperationResult.Failure -> null
            } ?: continue

            val result = detection.coordinator.detectAndResolve(
                request(request, localEvent, remoteEvent, detection),
            ).orchestration
            when (result) {
                is ConflictOrchestrationResult.ResolverNotConfigured,
                is ConflictOrchestrationResult.ResolverNotFound,
                is ConflictOrchestrationResult.Resolved,
                -> conflictCount++
                is ConflictOrchestrationResult.DetectorNotFound,
                is ConflictOrchestrationResult.NoConflict,
                -> Unit
            }
        }
        return InboundConflictPreparation.Ready(changeSet, conflictCount)
    }

    private suspend fun prepareForApplication(
        request: SynchronizationRequest,
        changeSet: ChangeSet,
        context: SynchronizationExecutionContext,
        detection: InboundPullConflictDetectionConfiguration,
    ): InboundConflictPreparation {
        val effectiveEvents = mutableListOf<ChangeEvent>()
        val storage = context.providers.storageProvider
        var conflictCount = 0L
        var changed = false

        for (remoteEvent in changeSet.events) {
            val candidate = storage.readLocalConflictCandidate(
                LocalConflictCandidateReadRequest(request, remoteEvent.entity),
            )
            val localEvent = when (candidate) {
                is ProviderOperationResult.Success -> when (val value = candidate.value) {
                    is LocalConflictCandidateReadResult.Found -> value.localChange
                    is LocalConflictCandidateReadResult.NotFound -> null
                }
                is ProviderOperationResult.Failure ->
                    return InboundConflictPreparation.Blocked(candidate.error, conflictCount)
            }
            if (localEvent == null) {
                effectiveEvents += remoteEvent
                continue
            }

            val durable = detection.coordinator.detectAndResolve(
                request(request, localEvent, remoteEvent, detection),
            )
            when (val orchestration = durable.orchestration) {
                is ConflictOrchestrationResult.DetectorNotFound ->
                    return blocked(
                        code = "DL-CONFLICT-DETECTOR-NOT-FOUND",
                        category = ErrorCategory.CONFIGURATION,
                        recoverability = Recoverability.NON_RECOVERABLE,
                        message = "Configured conflict detector was not found.",
                        conflictsDetected = conflictCount,
                    )

                is ConflictOrchestrationResult.NoConflict -> effectiveEvents += remoteEvent

                is ConflictOrchestrationResult.ResolverNotConfigured,
                is ConflictOrchestrationResult.ResolverNotFound,
                -> {
                    conflictCount++
                    return InboundConflictPreparation.Blocked(
                        unresolvedBarrierError(durable.unresolvedRecordOutcome),
                        conflictCount,
                    )
                }

                is ConflictOrchestrationResult.Resolved -> {
                    conflictCount++
                    validateContract(localEvent, remoteEvent, orchestration)?.let {
                        return InboundConflictPreparation.Blocked(it, conflictCount)
                    }
                    resolvedBarrierError(durable.resolvedDecisionRecordOutcome)?.let {
                        return InboundConflictPreparation.Blocked(it, conflictCount)
                    }
                    when (val decision = orchestration.decision) {
                        is ConflictResolutionDecision.UseLocal -> changed = true
                        is ConflictResolutionDecision.UseRemote -> effectiveEvents += remoteEvent
                        is ConflictResolutionDecision.Merge -> {
                            effectiveEvents += decision.resolvedChange
                            changed = changed || decision.resolvedChange != remoteEvent
                        }
                        is ConflictResolutionDecision.Defer ->
                            return blocked(
                                code = "DL-CONFLICT-DECISION-DEFERRED",
                                category = ErrorCategory.CONFLICT,
                                recoverability = Recoverability.NON_RECOVERABLE,
                                message = "Conflict resolution was deferred.",
                                conflictsDetected = conflictCount,
                            )
                        is ConflictResolutionDecision.Fail ->
                            return InboundConflictPreparation.Blocked(decision.error, conflictCount)
                    }
                }
            }
        }

        val prepared = when {
            effectiveEvents.isEmpty() -> null
            !changed && effectiveEvents.size == changeSet.events.size -> changeSet
            else -> ChangeSet(changeSet.id, effectiveEvents, changeSet.metadata)
        }
        return InboundConflictPreparation.Ready(prepared, conflictCount)
    }

    private fun request(
        request: SynchronizationRequest,
        localEvent: ChangeEvent,
        remoteEvent: ChangeEvent,
        detection: InboundPullConflictDetectionConfiguration,
    ): ConflictOrchestrationRequest =
        ConflictOrchestrationRequest(
            ConflictDetectionRequest(request, localEvent, remoteEvent),
            detection.bindings,
        )

    private fun validateContract(
        localEvent: ChangeEvent,
        remoteEvent: ChangeEvent,
        result: ConflictOrchestrationResult.Resolved,
    ): DataLoomError? {
        val conflict = result.conflict
        if (conflict.localChange != localEvent || conflict.remoteChange != remoteEvent) {
            return error(
                "DL-CONFLICT-DETECTOR-CONTRACT-VIOLATION",
                ErrorCategory.CONFLICT,
                Recoverability.NON_RECOVERABLE,
                "Conflict detector returned changes that did not match the evaluated inputs.",
            )
        }
        val merge = result.decision as? ConflictResolutionDecision.Merge ?: return null
        return if (
            merge.expectedEntity.type != conflict.entity.type ||
            merge.expectedEntity.id != conflict.entity.id
        ) {
            error(
                "DL-CONFLICT-MERGE-CONTRACT-VIOLATION",
                ErrorCategory.CONFLICT,
                Recoverability.NON_RECOVERABLE,
                "Merge decision referenced a different entity.",
            )
        } else {
            null
        }
    }

    private fun resolvedBarrierError(
        outcome: DurableResolvedConflictDecisionRecordOutcome?,
    ): DataLoomError? = when (outcome) {
        is DurableResolvedConflictDecisionRecordOutcome.Recorded,
        is DurableResolvedConflictDecisionRecordOutcome.AlreadyRecorded,
        -> null
        is DurableResolvedConflictDecisionRecordOutcome.PersistenceFailure -> outcome.error
        is DurableResolvedConflictDecisionRecordOutcome.Conflict -> error(
            "DL-CONFLICT-DECISION-NON-CONVERGENT",
            ErrorCategory.CONFLICT,
            Recoverability.NON_RECOVERABLE,
            "A different durable decision already exists for this conflict.",
        )
        is DurableResolvedConflictDecisionRecordOutcome.ContentionLimitReached -> error(
            "DL-CONFLICT-DECISION-CONTENTION",
            ErrorCategory.STATE,
            Recoverability.RECOVERABLE,
            "Resolved conflict decision exceeded the configured contention bound.",
        )
        null -> error(
            "DL-CONFLICT-DECISION-STORE-REQUIRED",
            ErrorCategory.CONFIGURATION,
            Recoverability.NON_RECOVERABLE,
            "Decision application requires a durable resolved-decision store.",
        )
    }

    private fun unresolvedBarrierError(
        outcome: DurableUnresolvedConflictRecordOutcome?,
    ): DataLoomError = when (outcome) {
        is DurableUnresolvedConflictRecordOutcome.PersistenceFailure -> outcome.error
        is DurableUnresolvedConflictRecordOutcome.Conflict -> error(
            "DL-CONFLICT-UNRESOLVED-RECORD-CONFLICT",
            ErrorCategory.CONFLICT,
            Recoverability.NON_RECOVERABLE,
            "A different unresolved record already exists for this conflict.",
        )
        is DurableUnresolvedConflictRecordOutcome.ContentionLimitReached -> error(
            "DL-CONFLICT-UNRESOLVED-CONTENTION",
            ErrorCategory.STATE,
            Recoverability.RECOVERABLE,
            "Unresolved conflict exceeded the configured contention bound.",
        )
        is DurableUnresolvedConflictRecordOutcome.Recorded,
        is DurableUnresolvedConflictRecordOutcome.AlreadyRecorded,
        -> error(
            "DL-CONFLICT-UNRESOLVED",
            ErrorCategory.CONFLICT,
            Recoverability.NON_RECOVERABLE,
            "Inbound application stopped because the conflict remains unresolved.",
        )
        null -> error(
            "DL-CONFLICT-UNRESOLVED-RECORD-MISSING",
            ErrorCategory.STATE,
            Recoverability.NON_RECOVERABLE,
            "Unresolved conflict outcome did not include durable evidence.",
        )
    }

    private fun blocked(
        code: String,
        category: ErrorCategory,
        recoverability: Recoverability,
        message: String,
        conflictsDetected: Long,
    ): InboundConflictPreparation.Blocked =
        InboundConflictPreparation.Blocked(
            error(code, category, recoverability, message),
            conflictsDetected,
        )

    private fun error(
        code: String,
        category: ErrorCategory,
        recoverability: Recoverability,
        message: String,
    ): DataLoomError = InboundConflictApplicationError(
        ErrorCode(code),
        category,
        recoverability,
        message,
    )
}

internal sealed interface InboundConflictPreparation {
    val conflictsDetected: Long

    data class Ready(
        val changeSet: ChangeSet?,
        override val conflictsDetected: Long,
    ) : InboundConflictPreparation

    data class Blocked(
        val error: DataLoomError,
        override val conflictsDetected: Long,
    ) : InboundConflictPreparation
}

private data class InboundConflictApplicationError(
    override val code: ErrorCode,
    override val category: ErrorCategory,
    override val recoverability: Recoverability,
    override val message: String,
    override val severity: ErrorSeverity = ErrorSeverity.ERROR,
    override val cause: Throwable? = null,
) : DataLoomError {
    override fun toString(): String = safeDiagnosticString()
}
