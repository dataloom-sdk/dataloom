package io.dataloom.runtime.execution.inbound

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.error.safeDiagnosticString
import io.dataloom.api.identifier.ChangeSetId
import io.dataloom.api.identifier.CheckpointKey
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.storage.InboundChangeApplyRequest
import io.dataloom.api.synchronization.CheckpointReadRequest
import io.dataloom.api.synchronization.CheckpointWriteRequest
import io.dataloom.api.synchronization.SynchronizationCheckpoint
import io.dataloom.api.synchronization.SynchronizationPhase
import io.dataloom.api.synchronization.SynchronizationProgress
import io.dataloom.api.synchronization.SynchronizationProgressUnit
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.synchronization.SynchronizationSkipReason
import io.dataloom.api.synchronization.SynchronizationSummary
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.runtime.execution.SynchronizationExecutionContext
import io.dataloom.runtime.execution.SynchronizationPipeline
import io.dataloom.runtime.execution.lifecycle.SynchronizationRuntimeEventEmitter

/**
 * Canonical inbound pull pipeline.
 *
 * The ordinary flow remains:
 *
 * `read checkpoint -> pull -> apply -> write checkpoint`.
 *
 * Conflict behavior has two compatible modes:
 *
 * - When [conflictDetection] is absent, no conflict work occurs.
 * - When it is present but its coordinator has no resolved-decision log,
 *   conflict detection remains observational, preserving the historical
 *   behavior.
 * - When the coordinator has a resolved-decision log, the log becomes a
 *   fail-closed application barrier. `UseRemote`, `UseLocal`, and `Merge` are
 *   applied deterministically before checkpoint advancement. Unresolved,
 *   deferred, failed, contradictory, or unrecorded decisions stop the batch
 *   before application and checkpoint write.
 *
 * In application mode, decision evidence is committed before storage
 * application. If application succeeds and checkpoint persistence later fails,
 * a replay must reproduce the same decision; the commit-once decision log
 * returns `AlreadyRecorded`. A different decision for the same conflict is
 * rejected before storage access, preventing silent non-convergence.
 */
public class InboundPullSynchronizationPipeline(
    private val configuration: InboundPullPipelineConfiguration,
    private val conflictDetection: InboundPullConflictDetectionConfiguration? = null,
) : SynchronizationPipeline {

    override val direction: SynchronizationDirection = SynchronizationDirection.PULL

    private val conflictPreparer = InboundConflictDecisionPreparer(conflictDetection)

    override suspend fun execute(
        context: SynchronizationExecutionContext,
    ): SynchronizationResult {
        val request = context.request
        val storageProvider = context.providers.storageProvider
        val transportProvider = context.providers.transportProvider
        val checkpointKey = CheckpointKey(request.workflowId.value)
        val runtimeEmitter =
            context.lifecycleEventEmitter as? SynchronizationRuntimeEventEmitter

        var inboundEventsReceived = 0L
        var inboundEventsApplied = 0L
        var conflictsDetected = 0L
        var batchesProcessed = 0
        val processedChangeSetIds = mutableSetOf<ChangeSetId>()

        fun currentSummary(): SynchronizationSummary =
            SynchronizationSummary(
                inboundEventsReceived = inboundEventsReceived,
                inboundEventsApplied = inboundEventsApplied,
                conflictsDetected = conflictsDetected,
            )

        fun terminalTimestamp(): DataLoomInstant =
            context.runtimeDependencies.clock.now()

        val initialCheckpointResult = storageProvider.readCheckpoint(
            CheckpointReadRequest(
                request = request,
                key = checkpointKey,
            ),
        )
        var currentCheckpoint: SynchronizationCheckpoint? =
            when (initialCheckpointResult) {
                is ProviderOperationResult.Success ->
                    initialCheckpointResult.value

                is ProviderOperationResult.Failure ->
                    return SynchronizationResult.Failed(
                        request = request,
                        completedAt = terminalTimestamp(),
                        summary = currentSummary(),
                        error = initialCheckpointResult.error,
                    )
            }

        while (true) {
            val pullRequest = PullChangesRequest(
                request = request,
                entityTypes = configuration.entityTypes,
                maxEvents = configuration.maxEventsPerBatch,
                checkpoint = currentCheckpoint,
            )

            context.lifecycleEventEmitter?.emitPhaseChanged(
                context,
                SynchronizationPhase.PULLING,
            )

            val pullResult =
                when (val outcome = transportProvider.pullChanges(pullRequest)) {
                    is ProviderOperationResult.Success -> outcome.value
                    is ProviderOperationResult.Failure ->
                        return SynchronizationResult.Failed(
                            request = request,
                            completedAt = terminalTimestamp(),
                            summary = currentSummary(),
                            error = outcome.error,
                        )
                }

            when (pullResult) {
                is PullChangesResult.NoChanges -> {
                    val noChangeCheckpoint = pullResult.nextCheckpoint
                    if (noChangeCheckpoint != null) {
                        context.lifecycleEventEmitter?.emitPhaseChanged(
                            context,
                            SynchronizationPhase.WRITING_CHECKPOINT,
                        )
                        val writeOutcome = storageProvider.writeCheckpoint(
                            CheckpointWriteRequest(
                                request = request,
                                checkpoint = noChangeCheckpoint,
                            ),
                        )
                        if (writeOutcome is ProviderOperationResult.Failure) {
                            return SynchronizationResult.Failed(
                                request = request,
                                completedAt = terminalTimestamp(),
                                summary = currentSummary(),
                                error = writeOutcome.error,
                            )
                        }
                    }

                    return if (batchesProcessed == 0) {
                        SynchronizationResult.Skipped(
                            request = request,
                            completedAt = terminalTimestamp(),
                            summary = currentSummary(),
                            reason = SynchronizationSkipReason.NO_CHANGES,
                        )
                    } else {
                        SynchronizationResult.Succeeded(
                            request = request,
                            completedAt = terminalTimestamp(),
                            summary = currentSummary(),
                        )
                    }
                }

                is PullChangesResult.Changes -> {
                    val originalChangeSet = pullResult.changeSet

                    if (!processedChangeSetIds.add(originalChangeSet.id)) {
                        return SynchronizationResult.Failed(
                            request = request,
                            completedAt = terminalTimestamp(),
                            summary = currentSummary(),
                            error = duplicateBatchError(originalChangeSet.id),
                        )
                    }

                    inboundEventsReceived += originalChangeSet.events.size.toLong()

                    val preparation = conflictPreparer.prepare(
                        request = request,
                        changeSet = originalChangeSet,
                        context = context,
                    )
                    conflictsDetected += preparation.conflictsDetected

                    val preparedChangeSet =
                        when (preparation) {
                            is InboundConflictPreparation.Ready ->
                                preparation.changeSet

                            is InboundConflictPreparation.Blocked ->
                                return SynchronizationResult.Failed(
                                    request = request,
                                    completedAt = terminalTimestamp(),
                                    summary = currentSummary(),
                                    error = preparation.error,
                                )
                        }

                    if (preparedChangeSet != null) {
                        context.lifecycleEventEmitter?.emitPhaseChanged(
                            context,
                            SynchronizationPhase.APPLYING_INBOUND,
                        )

                        val applyOutcome = storageProvider.applyInboundChanges(
                            InboundChangeApplyRequest(
                                request = request,
                                changeSet = preparedChangeSet,
                            ),
                        )
                        if (applyOutcome is ProviderOperationResult.Failure) {
                            return SynchronizationResult.Failed(
                                request = request,
                                completedAt = terminalTimestamp(),
                                summary = currentSummary(),
                                error = applyOutcome.error,
                            )
                        }

                        inboundEventsApplied +=
                            preparedChangeSet.events.size.toLong()
                    }

                    val nextCheckpoint = pullResult.nextCheckpoint
                    if (nextCheckpoint != null) {
                        context.lifecycleEventEmitter?.emitPhaseChanged(
                            context,
                            SynchronizationPhase.WRITING_CHECKPOINT,
                        )
                        val writeOutcome = storageProvider.writeCheckpoint(
                            CheckpointWriteRequest(
                                request = request,
                                checkpoint = nextCheckpoint,
                            ),
                        )
                        if (writeOutcome is ProviderOperationResult.Failure) {
                            return SynchronizationResult.Failed(
                                request = request,
                                completedAt = terminalTimestamp(),
                                summary = currentSummary(),
                                error = writeOutcome.error,
                            )
                        }
                        currentCheckpoint = nextCheckpoint
                    }

                    batchesProcessed++

                    if (runtimeEmitter != null) {
                        runtimeEmitter.emitProgressUpdated(
                            request,
                            SynchronizationProgress(
                                phase = SynchronizationPhase.APPLYING_INBOUND,
                                completed = inboundEventsApplied,
                                total = null,
                                unit = SynchronizationProgressUnit.EVENTS,
                            ),
                        )
                    }

                    if (!pullResult.hasMore) {
                        return SynchronizationResult.Succeeded(
                            request = request,
                            completedAt = terminalTimestamp(),
                            summary = currentSummary(),
                        )
                    }

                    if (nextCheckpoint == null) {
                        return SynchronizationResult.Failed(
                            request = request,
                            completedAt = terminalTimestamp(),
                            summary = currentSummary(),
                            error = pagingContractError(),
                        )
                    }

                    if (
                        batchesProcessed >=
                        configuration.maxBatchesPerExecution
                    ) {
                        return SynchronizationResult.PartiallySucceeded(
                            request = request,
                            completedAt = terminalTimestamp(),
                            summary = currentSummary(),
                            errors = listOf(batchLimitReachedError()),
                        )
                    }
                }
            }
        }
    }

    private fun duplicateBatchError(
        changeSetId: ChangeSetId,
    ): DataLoomError =
        pipelineError(
            code = "DL-INBOUND-DUPLICATE-BATCH",
            category = ErrorCategory.PROVIDER,
            recoverability = Recoverability.NON_RECOVERABLE,
            message =
                "TransportProvider returned the same ChangeSetId (${changeSetId.value}) twice during one execution.",
        )

    private fun pagingContractError(): DataLoomError =
        pipelineError(
            code = "DL-INBOUND-PAGING-CONTRACT-VIOLATION",
            category = ErrorCategory.PROVIDER,
            recoverability = Recoverability.NON_RECOVERABLE,
            message =
                "TransportProvider returned hasMore=true without a nextCheckpoint.",
        )

    private fun batchLimitReachedError(): DataLoomError =
        pipelineError(
            code = "DL-INBOUND-BATCH-LIMIT-REACHED",
            category = ErrorCategory.STATE,
            recoverability = Recoverability.RECOVERABLE,
            message =
                "Inbound pull reached maxBatchesPerExecution while more changes remain.",
        )

    private fun pipelineError(
        code: String,
        category: ErrorCategory,
        recoverability: Recoverability,
        message: String,
    ): DataLoomError =
        InboundPullPipelineError(
            code = ErrorCode(code),
            category = category,
            recoverability = recoverability,
            message = message,
        )

    private data class InboundPullPipelineError(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val recoverability: Recoverability,
        override val message: String,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val cause: Throwable? = null,
    ) : DataLoomError {
        override fun toString(): String = safeDiagnosticString()
    }
}
