package io.dataloom.runtime.execution.inbound

import io.dataloom.api.change.ChangeSet
import io.dataloom.api.conflict.ConflictDetectionRequest
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
import io.dataloom.api.storage.LocalConflictCandidateReadRequest
import io.dataloom.api.storage.LocalConflictCandidateReadResult
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
import io.dataloom.runtime.conflict.ConflictOrchestrationRequest
import io.dataloom.runtime.conflict.ConflictOrchestrationResult
import io.dataloom.runtime.execution.SynchronizationExecutionContext
import io.dataloom.runtime.execution.SynchronizationPipeline
import io.dataloom.runtime.execution.lifecycle.SynchronizationRuntimeEventEmitter

/**
 * Canonical [SynchronizationPipeline] implementation for the inbound pull
 * synchronization direction.
 *
 * ## Purpose
 *
 * [InboundPullSynchronizationPipeline] reads the stored synchronization
 * checkpoint once, then pulls inbound change batches through the configured
 * [io.dataloom.api.transport.TransportProvider], applies each batch through
 * the configured [io.dataloom.api.storage.StorageProvider], and advances the
 * checkpoint only after the corresponding inbound changes have been applied
 * successfully.
 *
 * ## Required flow
 *
 * ```text
 * StorageProvider.readCheckpoint()
 *     → TransportProvider.pullChanges(checkpoint)
 *     → StorageProvider.applyInboundChanges()
 *     → StorageProvider.writeCheckpoint(nextCheckpoint)
 *     → repeat when hasMore
 *     → SynchronizationResult
 * ```
 *
 * ## Apply-before-checkpoint invariant
 *
 * A next checkpoint associated with a [PullChangesResult.Changes] result is
 * never written before the corresponding [io.dataloom.api.change.ChangeSet]
 * has been applied successfully through
 * [io.dataloom.api.storage.StorageProvider.applyInboundChanges]. This
 * ordering is made explicit in the execution sequence and is verified by
 * tests.
 *
 * ## Providers
 *
 * Providers are obtained exclusively from
 * [SynchronizationExecutionContext.providers]. This pipeline does not receive
 * providers, a provider registry, a lifecycle coordinator, a
 * [kotlinx.coroutines.CoroutineScope], a dispatcher, or a DI framework
 * through its constructor.
 *
 * ## Direction
 *
 * [direction] is [SynchronizationDirection.PULL]. This pipeline implements
 * inbound execution only; it is not registered for
 * [SynchronizationDirection.BIDIRECTIONAL] synchronization.
 *
 * ## Checkpoint handling
 *
 * The stored checkpoint is read exactly once at the beginning of execution
 * using [io.dataloom.api.storage.StorageProvider.readCheckpoint] with a key
 * derived from [SynchronizationRequest.workflowId]. When no checkpoint is
 * stored the first pull uses a `null` checkpoint. After every successful
 * batch application, the next checkpoint (when present) is persisted via
 * [io.dataloom.api.storage.StorageProvider.writeCheckpoint] and used as the
 * current checkpoint for the next pull request.
 *
 * ## At-least-once inbound application
 *
 * Application storage adapters and backend change models must support
 * idempotent inbound application. If a batch is applied successfully but the
 * subsequent checkpoint write fails, the same batch may be delivered again on
 * the next execution. This pipeline does not claim exactly-once delivery.
 *
 * ## Duplicate batch protection
 *
 * [ChangeSetId] values processed during one `execute` call are tracked
 * locally. If the transport returns the same [ChangeSetId] again during that
 * call, the pipeline stops and returns [SynchronizationResult.Failed] with a
 * canonical, safe provider-contract-violation error. This protection is local
 * to a single `execute` call; no persistent deduplication storage is
 * introduced.
 *
 * ## Paging and forward-progress protection
 *
 * When [PullChangesResult.Changes.hasMore] is `true` but
 * [PullChangesResult.Changes.nextCheckpoint] is `null`, the pipeline stops
 * and returns [SynchronizationResult.Failed] with a safe provider-contract
 * error. This prevents a non-progressing or infinite pull loop.
 *
 * ## Batch limit
 *
 * When [InboundPullPipelineConfiguration.maxBatchesPerExecution] is reached
 * and the last result still had `hasMore = true`, the pipeline stops and
 * returns [SynchronizationResult.PartiallySucceeded] with a safe, recoverable
 * batch-limit error. No follow-up work is scheduled automatically.
 *
 * ## Result classification
 *
 * - [SynchronizationResult.Skipped] with [SynchronizationSkipReason.NO_CHANGES]
 *   when the first pull reports no inbound changes and any optional
 *   no-change checkpoint write succeeds.
 * - [SynchronizationResult.Succeeded] when at least one batch was applied,
 *   every required checkpoint was persisted, and execution completed without
 *   a partial condition.
 * - [SynchronizationResult.PartiallySucceeded] when at least one batch was
 *   applied and execution stopped because the per-execution batch limit was
 *   reached while more changes remained available.
 * - [SynchronizationResult.Failed] when any provider call fails or a
 *   runtime-detected contract violation occurs.
 *
 * ## Boundary restrictions
 *
 * This pipeline calls only
 * [io.dataloom.api.storage.StorageProvider.readCheckpoint],
 * [io.dataloom.api.transport.TransportProvider.pullChanges],
 * [io.dataloom.api.storage.StorageProvider.applyInboundChanges],
 * [io.dataloom.api.storage.StorageProvider.writeCheckpoint], and — only when
 * [conflictDetection] is supplied —
 * [io.dataloom.api.storage.StorageProvider.readLocalConflictCandidate] and
 * [io.dataloom.runtime.conflict.DurableConflictDetectionCoordinator.detectAndResolve].
 * It does not call outbound storage or transport operations, provider
 * lifecycle or health operations, [io.dataloom.api.retry.RetryPolicy],
 * scheduler, queue, connectivity, or observer operations.
 *
 * ## Conflict detection
 *
 * Supplying [conflictDetection] turns on best-effort conflict detection for
 * inbound changes; without it, this pipeline behaves exactly as it did
 * before this capability existed. For each event in an accepted
 * [io.dataloom.api.change.ChangeSet], **before** that batch is applied, the
 * pipeline reads the local conflict candidate for that event's entity via
 * [io.dataloom.api.storage.StorageProvider.readLocalConflictCandidate].
 * When one is found, the pipeline calls
 * [io.dataloom.runtime.conflict.DurableConflictDetectionCoordinator.detectAndResolve]
 * with the local and remote [io.dataloom.api.change.ChangeEvent]s, which
 * durably records genuinely unresolved outcomes (see
 * [io.dataloom.api.conflict.DurableUnresolvedConflictLog]).
 *
 * Detection is strictly observational in this slice: its outcome never
 * blocks, alters, or delays [io.dataloom.api.storage.StorageProvider.applyInboundChanges]
 * for the same batch — matching
 * [io.dataloom.runtime.conflict.SynchronizationConflictOrchestrator]'s own
 * "does not apply resolution decisions to storage" boundary. A
 * [io.dataloom.api.provider.ProviderOperationResult.Failure] from
 * `readLocalConflictCandidate` is treated the same as
 * [io.dataloom.api.storage.LocalConflictCandidateReadResult.NotFound] —
 * detection is skipped for that one event, the batch application proceeds
 * unaffected, and no [SynchronizationResult] is ever failed because of a
 * conflict-detection-only failure. [SynchronizationSummary.conflictsDetected]
 * counts every event whose orchestration outcome was not
 * [io.dataloom.runtime.conflict.ConflictOrchestrationResult.NoConflict].
 *
 * Detection reads one entity at a time — there is no batch-local-read
 * capability — so a batch of `n` events issues up to `n` additional
 * [io.dataloom.api.storage.StorageProvider.readLocalConflictCandidate] calls
 * when [conflictDetection] is supplied. This is a known, documented
 * characteristic of this first slice, not an oversight.
 *
 * ## Cancellation
 *
 * [kotlinx.coroutines.CancellationException] raised by any suspend provider
 * operation propagates normally. It is never converted into a
 * [SynchronizationResult]. Unexpected programming exceptions are not caught
 * or converted into canonical failures.
 *
 * ## Security
 *
 * Checkpoint token values are never included in errors, logs, or diagnostic
 * output. Structural identifiers ([ChangeSetId], [CheckpointKey],
 * [io.dataloom.api.provider.ProviderId], [ErrorCode]) may be used safely in
 * error messages.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API, core, and runtime types
 * only. Safe for use in Kotlin Multiplatform common code.
 *
 * @param configuration the [InboundPullPipelineConfiguration] bounding this
 *   pipeline's entity-type restriction and batch limits.
 * @param conflictDetection optional [InboundPullConflictDetectionConfiguration].
 *   `null` (the default) means conflict detection is off, and this pipeline
 *   behaves exactly as it did before this capability existed.
 */
public class InboundPullSynchronizationPipeline(
    private val configuration: InboundPullPipelineConfiguration,
    private val conflictDetection: InboundPullConflictDetectionConfiguration? = null,
) : SynchronizationPipeline {

    override val direction: SynchronizationDirection = SynchronizationDirection.PULL

    override suspend fun execute(context: SynchronizationExecutionContext): SynchronizationResult {
        val request: SynchronizationRequest = context.request
        val storageProvider = context.providers.storageProvider
        val transportProvider = context.providers.transportProvider
        val checkpointKey = CheckpointKey(request.workflowId.value)
        val runtimeEmitter = context.lifecycleEventEmitter as? SynchronizationRuntimeEventEmitter

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

        suspend fun detectConflictsBeforeApplying(changeSet: ChangeSet) {
            val detection = conflictDetection ?: return
            for (event in changeSet.events) {
                val candidateOutcome = storageProvider.readLocalConflictCandidate(
                    LocalConflictCandidateReadRequest(request = request, entity = event.entity),
                )
                val localChange = when (candidateOutcome) {
                    is ProviderOperationResult.Success -> when (val value = candidateOutcome.value) {
                        is LocalConflictCandidateReadResult.Found -> value.localChange
                        is LocalConflictCandidateReadResult.NotFound -> null
                    }
                    // A conflict-detection-only read failure never blocks batch
                    // application; detection is simply skipped for this event.
                    is ProviderOperationResult.Failure -> null
                } ?: continue

                val detectionResult = detection.coordinator.detectAndResolve(
                    ConflictOrchestrationRequest(
                        detectionRequest = ConflictDetectionRequest(
                            synchronizationRequest = request,
                            localChange = localChange,
                            remoteChange = event,
                        ),
                        bindings = detection.bindings,
                    ),
                )
                // Only count a genuine detected conflict: the detector actually ran and
                // reported ConflictDetected. DetectorNotFound means detection never ran
                // at all (a configuration problem, not a conflict), and NoConflict means
                // it ran and found nothing.
                when (detectionResult.orchestration) {
                    is ConflictOrchestrationResult.ResolverNotConfigured,
                    is ConflictOrchestrationResult.ResolverNotFound,
                    is ConflictOrchestrationResult.Resolved,
                    -> conflictsDetected++
                    is ConflictOrchestrationResult.DetectorNotFound,
                    is ConflictOrchestrationResult.NoConflict,
                    -> Unit
                }
            }
        }

        fun terminalTimestamp(): DataLoomInstant = context.runtimeDependencies.clock.now()

        // Step 1: Read the stored checkpoint exactly once.
        val initialCheckpointResult = storageProvider.readCheckpoint(
            CheckpointReadRequest(request = request, key = checkpointKey),
        )
        var currentCheckpoint: SynchronizationCheckpoint? = when (initialCheckpointResult) {
            is ProviderOperationResult.Success -> initialCheckpointResult.value
            is ProviderOperationResult.Failure -> {
                return SynchronizationResult.Failed(
                    request = request,
                    completedAt = terminalTimestamp(),
                    summary = currentSummary(),
                    error = initialCheckpointResult.error,
                )
            }
        }

        while (true) {
            // Step 2: Build pull request using exact synchronization request,
            // configured entity types, configured maxEventsPerBatch, and the
            // current checkpoint (null when no checkpoint has been persisted).
            val pullRequest = PullChangesRequest(
                request = request,
                entityTypes = configuration.entityTypes,
                maxEvents = configuration.maxEventsPerBatch,
                checkpoint = currentCheckpoint,
            )

            // Step 3: Pull inbound changes.
            // Emit PULLING phase immediately before the transport pull call.
            // CancellationException propagates; ordinary observer failures do
            // not prevent the pull operation.
            context.lifecycleEventEmitter?.emitPhaseChanged(context, SynchronizationPhase.PULLING)

            val pullResult = when (val outcome = transportProvider.pullChanges(pullRequest)) {
                is ProviderOperationResult.Success -> outcome.value
                is ProviderOperationResult.Failure -> {
                    return SynchronizationResult.Failed(
                        request = request,
                        completedAt = terminalTimestamp(),
                        summary = currentSummary(),
                        error = outcome.error,
                    )
                }
            }

            when (pullResult) {
                is PullChangesResult.NoChanges -> {
                    // Persist any optional no-change next checkpoint.
                    val noChangeCheckpoint = pullResult.nextCheckpoint
                    if (noChangeCheckpoint != null) {
                        // Emit WRITING_CHECKPOINT phase immediately before the
                        // no-change checkpoint write. CancellationException propagates;
                        // ordinary observer failures do not prevent the write.
                        context.lifecycleEventEmitter?.emitPhaseChanged(context, SynchronizationPhase.WRITING_CHECKPOINT)

                        val writeOutcome = storageProvider.writeCheckpoint(
                            CheckpointWriteRequest(request = request, checkpoint = noChangeCheckpoint),
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
                    val changeSet = pullResult.changeSet

                    // Step 4: Duplicate batch protection.
                    if (!processedChangeSetIds.add(changeSet.id)) {
                        return SynchronizationResult.Failed(
                            request = request,
                            completedAt = terminalTimestamp(),
                            summary = currentSummary(),
                            error = duplicateBatchError(changeSet.id),
                        )
                    }

                    // Count received events when a valid Changes result is
                    // accepted for processing.
                    inboundEventsReceived += changeSet.events.size.toLong()

                    // Step 4.5 (opt-in): detect conflicts against local state before
                    // applying. Strictly observational -- never blocks or alters the
                    // apply step below. No-op when conflictDetection was not supplied.
                    detectConflictsBeforeApplying(changeSet)

                    // Step 5: Apply the inbound change set.
                    // Emit APPLYING_INBOUND phase immediately before the apply call.
                    // CancellationException propagates; ordinary observer failures do
                    // not prevent the apply operation.
                    context.lifecycleEventEmitter?.emitPhaseChanged(context, SynchronizationPhase.APPLYING_INBOUND)

                    val applyOutcome = storageProvider.applyInboundChanges(
                        InboundChangeApplyRequest(request = request, changeSet = changeSet),
                    )
                    if (applyOutcome is ProviderOperationResult.Failure) {
                        // Do not write nextCheckpoint after apply failure.
                        return SynchronizationResult.Failed(
                            request = request,
                            completedAt = terminalTimestamp(),
                            summary = currentSummary(),
                            error = applyOutcome.error,
                        )
                    }

                    // Count applied events only after applyInboundChanges
                    // succeeds.
                    inboundEventsApplied += changeSet.events.size.toLong()

                    // Step 6: Persist next checkpoint only after successful
                    // apply. Never write checkpoint before apply succeeds.
                    val nextCheckpoint = pullResult.nextCheckpoint
                    if (nextCheckpoint != null) {
                        // Emit WRITING_CHECKPOINT phase immediately before the
                        // post-apply checkpoint write. CancellationException propagates;
                        // ordinary observer failures do not prevent the write.
                        context.lifecycleEventEmitter?.emitPhaseChanged(context, SynchronizationPhase.WRITING_CHECKPOINT)

                        val writeOutcome = storageProvider.writeCheckpoint(
                            CheckpointWriteRequest(request = request, checkpoint = nextCheckpoint),
                        )
                        if (writeOutcome is ProviderOperationResult.Failure) {
                            // Apply succeeded but checkpoint write failed.
                            // Preserve summary evidence showing the batch
                            // was received and applied. The same inbound
                            // changes may be delivered again because the
                            // application succeeded but checkpoint
                            // advancement failed.
                            return SynchronizationResult.Failed(
                                request = request,
                                completedAt = terminalTimestamp(),
                                summary = currentSummary(),
                                error = writeOutcome.error,
                            )
                        }
                        // Use the successfully persisted checkpoint for the
                        // next pull request.
                        currentCheckpoint = nextCheckpoint
                    }

                    batchesProcessed++

                    // Emit ProgressUpdated after this batch is durably completed.
                    // Apply has succeeded, and any required next checkpoint has been
                    // persisted. CancellationException propagates; ordinary observer
                    // failures are not propagated.
                    // Note: if cancellation occurs here, inbound application and any
                    // required checkpoint advancement have already completed; durable
                    // work is not rolled back.
                    if (runtimeEmitter != null) {
                        val progress = SynchronizationProgress(
                            phase = SynchronizationPhase.APPLYING_INBOUND,
                            completed = inboundEventsApplied,
                            total = null,
                            unit = SynchronizationProgressUnit.EVENTS,
                        )
                        runtimeEmitter.emitProgressUpdated(request, progress)
                    }

                    if (!pullResult.hasMore) {
                        // hasMore false: finish after successful apply and
                        // any required checkpoint write.
                        return SynchronizationResult.Succeeded(
                            request = request,
                            completedAt = terminalTimestamp(),
                            summary = currentSummary(),
                        )
                    }

                    // hasMore is true: validate that forward progress is
                    // possible before continuing.
                    if (nextCheckpoint == null) {
                        // The transport indicated more changes are available
                        // but provided no next checkpoint. Another pull
                        // would use the unchanged checkpoint and risk an
                        // infinite loop. Stop execution.
                        return SynchronizationResult.Failed(
                            request = request,
                            completedAt = terminalTimestamp(),
                            summary = currentSummary(),
                            error = pagingContractError(),
                        )
                    }

                    // Check per-execution batch limit.
                    if (batchesProcessed >= configuration.maxBatchesPerExecution) {
                        return SynchronizationResult.PartiallySucceeded(
                            request = request,
                            completedAt = terminalTimestamp(),
                            summary = currentSummary(),
                            errors = listOf(batchLimitReachedError()),
                        )
                    }

                    // Continue to the next pull. The processed change set is
                    // not retained beyond this iteration.
                }
            }
        }
    }

    /**
     * Builds the safe canonical error returned when the same [ChangeSetId] is
     * returned more than once during a single execution.
     */
    private fun duplicateBatchError(changeSetId: ChangeSetId): DataLoomError =
        InboundPullPipelineError(
            code = ErrorCode("DL-INBOUND-DUPLICATE-BATCH"),
            category = ErrorCategory.PROVIDER,
            recoverability = Recoverability.NON_RECOVERABLE,
            message = "TransportProvider returned the same ChangeSetId (${changeSetId.value}) twice " +
                "during one execution.",
        )

    /**
     * Builds the safe canonical error returned when [PullChangesResult.Changes.hasMore]
     * is `true` but [PullChangesResult.Changes.nextCheckpoint] is `null`.
     *
     * Performing another pull with the unchanged checkpoint would risk an
     * infinite non-progressing loop.
     */
    private fun pagingContractError(): DataLoomError =
        InboundPullPipelineError(
            code = ErrorCode("DL-INBOUND-PAGING-CONTRACT-VIOLATION"),
            category = ErrorCategory.PROVIDER,
            recoverability = Recoverability.NON_RECOVERABLE,
            message = "TransportProvider returned hasMore=true without a nextCheckpoint. " +
                "Cannot continue without a new checkpoint to prevent an infinite loop.",
        )

    /**
     * Builds the safe canonical error returned when the configured
     * per-execution batch limit is reached while more inbound changes
     * remain available.
     */
    private fun batchLimitReachedError(): DataLoomError =
        InboundPullPipelineError(
            code = ErrorCode("DL-INBOUND-BATCH-LIMIT-REACHED"),
            category = ErrorCategory.STATE,
            recoverability = Recoverability.RECOVERABLE,
            message = "InboundPullSynchronizationPipeline reached the configured " +
                "maxBatchesPerExecution limit while more inbound changes remain available.",
        )

    /**
     * Safe, internally-created [DataLoomError] implementation used for
     * pipeline contract violations.
     *
     * Never exposes payload content, credentials, checkpoint tokens, personal
     * data, or raw [Throwable] instances.
     */
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
