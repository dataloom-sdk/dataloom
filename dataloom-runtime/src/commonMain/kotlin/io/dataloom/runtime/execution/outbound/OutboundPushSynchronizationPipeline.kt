package io.dataloom.runtime.execution.outbound

import io.dataloom.api.change.ChangeSet
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.error.safeDiagnosticString
import io.dataloom.api.identifier.ChangeSetId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.storage.OutboundChangeReadRequest
import io.dataloom.api.storage.OutboundChangeReadResult
import io.dataloom.api.synchronization.ChangeAcknowledgementStatus
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.synchronization.OutboundChangeAcknowledgementRequest
import io.dataloom.api.synchronization.SynchronizationPhase
import io.dataloom.api.synchronization.SynchronizationProgress
import io.dataloom.api.synchronization.SynchronizationProgressUnit
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.synchronization.SynchronizationSkipReason
import io.dataloom.api.synchronization.SynchronizationSummary
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.runtime.execution.SynchronizationExecutionContext
import io.dataloom.runtime.execution.SynchronizationPipeline
import io.dataloom.runtime.execution.lifecycle.SynchronizationRuntimeEventEmitter

/**
 * Canonical [SynchronizationPipeline] implementation for the outbound push
 * synchronization direction.
 *
 * ## Purpose
 *
 * [OutboundPushSynchronizationPipeline] reads pending outbound changes from
 * the configured [io.dataloom.api.storage.StorageProvider], pushes each batch
 * through the configured [io.dataloom.api.transport.TransportProvider],
 * validates the returned [ChangeSetAcknowledgement] against the pushed
 * [ChangeSet], and persists the acknowledgement back through the storage
 * provider.
 *
 * ## Required flow
 *
 * ```text
 * StorageProvider.readOutboundChanges()
 *     -> TransportProvider.pushChanges()
 *     -> validate ChangeSetAcknowledgement
 *     -> StorageProvider.acknowledgeOutboundChanges()
 *     -> continue while more outbound changes are available
 *     -> construct SynchronizationResult
 * ```
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
 * [direction] is [SynchronizationDirection.PUSH]. This pipeline implements
 * outbound execution only; it is not registered for
 * [SynchronizationDirection.BIDIRECTIONAL] synchronization.
 *
 * ## Sequential batch processing
 *
 * Batches are read and pushed strictly sequentially, one at a time. This
 * pipeline never fans out parallel pushes. Completed batch payload
 * references are released before the next batch is read; no pipeline-owned
 * collection accumulates processed [ChangeSet] instances across the
 * execution.
 *
 * ## Duplicate batch protection
 *
 * [ChangeSetId] values processed during one `execute` call are tracked
 * locally. If storage returns the same [ChangeSetId] again during that call,
 * the pipeline stops and returns [SynchronizationResult.Failed] with a
 * canonical, safe provider-contract-violation error. This protection is
 * local to a single `execute` call; no persistent deduplication storage is
 * introduced.
 *
 * ## Acknowledgement validation
 *
 * Before an acknowledgement is persisted, the pipeline validates that:
 * - [ChangeSetAcknowledgement.changeSetId] matches the pushed [ChangeSet.id].
 * - Every pushed change-event ID appears exactly once in the acknowledgement.
 * - No acknowledgement references an unknown change-event ID.
 *
 * Validation failures stop processing and return
 * [SynchronizationResult.Failed] with a safe canonical error that exposes no
 * payload content.
 *
 * ## At-least-once delivery
 *
 * Transport may accept a batch even though local acknowledgement persistence
 * later fails. This pipeline provides at-least-once delivery semantics only;
 * it does not claim exactly-once delivery. Transport implementations and
 * backend APIs should support idempotency using stable [ChangeSetId] and
 * change-event ID values.
 *
 * ## Result classification
 *
 * - [SynchronizationResult.Skipped] with [SynchronizationSkipReason.NO_CHANGES]
 *   when the first storage read reports no outbound changes.
 * - [SynchronizationResult.Succeeded] when every processed event was
 *   acknowledged as [ChangeAcknowledgementStatus.ACCEPTED].
 * - [SynchronizationResult.PartiallySucceeded] when at least one processed
 *   event was [ChangeAcknowledgementStatus.RETRY] or
 *   [ChangeAcknowledgementStatus.REJECTED], or when the configured
 *   per-execution batch limit is reached while more changes remain available.
 * - [SynchronizationResult.Failed] when a canonical provider failure or
 *   acknowledgement-validation failure occurs.
 *
 * ## Boundary restrictions
 *
 * This pipeline calls only
 * [io.dataloom.api.storage.StorageProvider.readOutboundChanges],
 * [io.dataloom.api.transport.TransportProvider.pushChanges], and
 * [io.dataloom.api.storage.StorageProvider.acknowledgeOutboundChanges]. It
 * does not read or write checkpoints, does not call provider lifecycle or
 * health operations, does not invoke
 * [io.dataloom.api.retry.RetryPolicy], does not schedule or enqueue work, and
 * does not dispatch lifecycle events or notify observers.
 *
 * ## Cancellation
 *
 * [kotlinx.coroutines.CancellationException] raised by any suspend provider
 * operation propagates normally. It is never converted into a
 * [SynchronizationResult]. Unexpected programming exceptions are not caught
 * or converted into canonical failures.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API, core, and runtime types
 * only. Safe for use in Kotlin Multiplatform common code.
 *
 * @param configuration the [OutboundPushPipelineConfiguration] bounding this
 *   pipeline's entity-type restriction and batch limits.
 */
public class OutboundPushSynchronizationPipeline(
    private val configuration: OutboundPushPipelineConfiguration,
) : SynchronizationPipeline {

    override val direction: SynchronizationDirection = SynchronizationDirection.PUSH

    override suspend fun execute(context: SynchronizationExecutionContext): SynchronizationResult {
        val request: SynchronizationRequest = context.request
        val storageProvider = context.providers.storageProvider
        val transportProvider = context.providers.transportProvider
        val runtimeEmitter = context.lifecycleEventEmitter as? SynchronizationRuntimeEventEmitter

        var outboundEventsRead = 0L
        var outboundEventsAccepted = 0L
        var outboundEventsMarkedForRetry = 0L
        var outboundEventsRejected = 0L
        var batchesProcessed = 0
        val processedChangeSetIds = mutableSetOf<ChangeSetId>()
        val collectedErrors = mutableListOf<DataLoomError>()

        fun currentSummary(): SynchronizationSummary =
            SynchronizationSummary(
                outboundEventsRead = outboundEventsRead,
                outboundEventsAccepted = outboundEventsAccepted,
                outboundEventsMarkedForRetry = outboundEventsMarkedForRetry,
                outboundEventsRejected = outboundEventsRejected,
            )

        fun terminalTimestamp(): DataLoomInstant = context.runtimeDependencies.clock.now()

        while (true) {
            val readRequest = OutboundChangeReadRequest(
                request = request,
                entityTypes = configuration.entityTypes,
                maxEvents = configuration.maxEventsPerBatch,
            )

            // Emit READING_OUTBOUND phase immediately before storage read.
            // CancellationException propagates; ordinary observer failures are
            // ignored and do not prevent the storage read.
            context.lifecycleEventEmitter?.emitPhaseChanged(context, SynchronizationPhase.READING_OUTBOUND)

            val readResult = when (val outcome = storageProvider.readOutboundChanges(readRequest)) {
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

            when (readResult) {
                is OutboundChangeReadResult.NoChanges -> {
                    return if (batchesProcessed == 0) {
                        SynchronizationResult.Skipped(
                            request = request,
                            completedAt = terminalTimestamp(),
                            summary = currentSummary(),
                            reason = SynchronizationSkipReason.NO_CHANGES,
                        )
                    } else {
                        buildCompletionResult(request, terminalTimestamp(), currentSummary(), collectedErrors)
                    }
                }

                is OutboundChangeReadResult.Changes -> {
                    val changeSet = readResult.changeSet

                    if (!processedChangeSetIds.add(changeSet.id)) {
                        return SynchronizationResult.Failed(
                            request = request,
                            completedAt = terminalTimestamp(),
                            summary = currentSummary(),
                            error = duplicateBatchError(changeSet.id),
                        )
                    }

                    // Emit PUSHING phase immediately before transport push.
                    // CancellationException propagates; ordinary observer failures do
                    // not prevent the push operation.
                    context.lifecycleEventEmitter?.emitPhaseChanged(context, SynchronizationPhase.PUSHING)

                    val pushOutcome = transportProvider.pushChanges(
                        PushChangesRequest(request = request, changeSet = changeSet),
                    )
                    val acknowledgement = when (pushOutcome) {
                        is ProviderOperationResult.Success -> pushOutcome.value
                        is ProviderOperationResult.Failure -> {
                            return SynchronizationResult.Failed(
                                request = request,
                                completedAt = terminalTimestamp(),
                                summary = currentSummary(),
                                error = pushOutcome.error,
                            )
                        }
                    }

                    val validationError = validateAcknowledgement(changeSet, acknowledgement)
                    if (validationError != null) {
                        return SynchronizationResult.Failed(
                            request = request,
                            completedAt = terminalTimestamp(),
                            summary = currentSummary(),
                            error = validationError,
                        )
                    }

                    // Emit ACKNOWLEDGING_OUTBOUND phase immediately before
                    // acknowledgement persistence. CancellationException propagates;
                    // ordinary observer failures do not prevent the acknowledge call.
                    context.lifecycleEventEmitter?.emitPhaseChanged(context, SynchronizationPhase.ACKNOWLEDGING_OUTBOUND)

                    val acknowledgeOutcome = storageProvider.acknowledgeOutboundChanges(
                        OutboundChangeAcknowledgementRequest(request = request, acknowledgement = acknowledgement),
                    )
                    when (acknowledgeOutcome) {
                        is ProviderOperationResult.Failure -> {
                            return SynchronizationResult.Failed(
                                request = request,
                                completedAt = terminalTimestamp(),
                                summary = currentSummary(),
                                error = acknowledgeOutcome.error,
                            )
                        }

                        is ProviderOperationResult.Success -> Unit
                    }

                    outboundEventsRead += changeSet.events.size.toLong()
                    batchesProcessed += 1

                    for (eventAcknowledgement in acknowledgement.events) {
                        when (eventAcknowledgement.status) {
                            ChangeAcknowledgementStatus.ACCEPTED -> {
                                outboundEventsAccepted += 1L
                            }

                            ChangeAcknowledgementStatus.RETRY -> {
                                outboundEventsMarkedForRetry += 1L
                                collectedErrors.add(
                                    eventAcknowledgement.error
                                        ?: missingEventStatusError(eventAcknowledgement.status),
                                )
                            }

                            ChangeAcknowledgementStatus.REJECTED -> {
                                outboundEventsRejected += 1L
                                collectedErrors.add(
                                    eventAcknowledgement.error
                                        ?: missingEventStatusError(eventAcknowledgement.status),
                                )
                            }
                        }
                    }

                    // Emit ProgressUpdated after this batch is durably completed.
                    // The acknowledgement has been persisted; the batch is now
                    // durable. CancellationException propagates; ordinary observer
                    // failures are not propagated.
                    // Note: if cancellation occurs here, the batch has already been
                    // durably acknowledged; durable work is not rolled back.
                    if (runtimeEmitter != null) {
                        val progress = SynchronizationProgress(
                            phase = SynchronizationPhase.PUSHING,
                            completed = outboundEventsRead,
                            total = null,
                            unit = SynchronizationProgressUnit.EVENTS,
                        )
                        runtimeEmitter.emitProgressUpdated(request, progress)
                    }

                    if (!readResult.hasMore) {
                        return buildCompletionResult(request, terminalTimestamp(), currentSummary(), collectedErrors)
                    }

                    if (batchesProcessed >= configuration.maxBatchesPerExecution) {
                        val errorsWithBatchLimit = collectedErrors.toMutableList()
                        errorsWithBatchLimit.add(batchLimitReachedError())
                        return SynchronizationResult.PartiallySucceeded(
                            request = request,
                            completedAt = terminalTimestamp(),
                            summary = currentSummary(),
                            errors = errorsWithBatchLimit,
                        )
                    }

                    // Continue to the next storage read. The processed change
                    // set and acknowledgement are not retained beyond this
                    // iteration.
                }
            }
        }
    }

    /**
     * Builds the terminal [SynchronizationResult] for a completed execution
     * that processed at least one batch.
     *
     * Returns [SynchronizationResult.Succeeded] when [collectedErrors] is
     * empty, or [SynchronizationResult.PartiallySucceeded] with the collected
     * errors otherwise.
     */
    private fun buildCompletionResult(
        request: SynchronizationRequest,
        completedAt: DataLoomInstant,
        summary: SynchronizationSummary,
        collectedErrors: List<DataLoomError>,
    ): SynchronizationResult =
        if (collectedErrors.isEmpty()) {
            SynchronizationResult.Succeeded(
                request = request,
                completedAt = completedAt,
                summary = summary,
            )
        } else {
            SynchronizationResult.PartiallySucceeded(
                request = request,
                completedAt = completedAt,
                summary = summary,
                errors = collectedErrors,
            )
        }

    /**
     * Validates that [acknowledgement] fully and exclusively corresponds to
     * the pushed [changeSet].
     *
     * Returns a safe canonical [DataLoomError] describing the first detected
     * violation, or `null` when the acknowledgement is valid.
     */
    private fun validateAcknowledgement(
        changeSet: ChangeSet,
        acknowledgement: ChangeSetAcknowledgement,
    ): DataLoomError? {
        if (acknowledgement.changeSetId != changeSet.id) {
            return OutboundPushPipelineError(
                code = ErrorCode("DL-OUTBOUND-ACK-CHANGESET-MISMATCH"),
                category = ErrorCategory.VALIDATION,
                recoverability = Recoverability.NON_RECOVERABLE,
                message = "Acknowledgement changeSetId does not match the pushed ChangeSet.id.",
            )
        }

        val pushedEventIds = changeSet.events.map { it.id }
        val acknowledgedEventIds = acknowledgement.events.map { it.eventId }

        if (acknowledgedEventIds.size != acknowledgedEventIds.toSet().size) {
            return OutboundPushPipelineError(
                code = ErrorCode("DL-OUTBOUND-ACK-DUPLICATE-EVENT"),
                category = ErrorCategory.VALIDATION,
                recoverability = Recoverability.NON_RECOVERABLE,
                message = "Acknowledgement contains duplicate change-event identifiers.",
            )
        }

        val pushedIdSet = pushedEventIds.toSet()
        val acknowledgedIdSet = acknowledgedEventIds.toSet()

        if (acknowledgedIdSet.any { it !in pushedIdSet }) {
            return OutboundPushPipelineError(
                code = ErrorCode("DL-OUTBOUND-ACK-UNKNOWN-EVENT"),
                category = ErrorCategory.VALIDATION,
                recoverability = Recoverability.NON_RECOVERABLE,
                message = "Acknowledgement references an unknown change-event identifier.",
            )
        }

        if (pushedIdSet.any { it !in acknowledgedIdSet }) {
            return OutboundPushPipelineError(
                code = ErrorCode("DL-OUTBOUND-ACK-MISSING-EVENT"),
                category = ErrorCategory.VALIDATION,
                recoverability = Recoverability.NON_RECOVERABLE,
                message = "Acknowledgement is missing one or more pushed change-event identifiers.",
            )
        }

        return null
    }

    /**
     * Builds the safe canonical error returned when the same [ChangeSetId] is
     * read more than once during a single execution.
     */
    private fun duplicateBatchError(changeSetId: ChangeSetId): DataLoomError =
        OutboundPushPipelineError(
            code = ErrorCode("DL-OUTBOUND-DUPLICATE-BATCH"),
            category = ErrorCategory.PROVIDER,
            recoverability = Recoverability.NON_RECOVERABLE,
            message = "StorageProvider returned the same ChangeSetId (${changeSetId.value}) twice " +
                "during one execution.",
        )

    /**
     * Builds the safe canonical error returned when the configured
     * per-execution batch limit is reached while more outbound changes
     * remain available.
     */
    private fun batchLimitReachedError(): DataLoomError =
        OutboundPushPipelineError(
            code = ErrorCode("DL-OUTBOUND-BATCH-LIMIT-REACHED"),
            category = ErrorCategory.STATE,
            recoverability = Recoverability.RECOVERABLE,
            message = "OutboundPushSynchronizationPipeline reached the configured " +
                "maxBatchesPerExecution limit while more outbound changes remain available.",
        )

    /**
     * Builds the safe canonical error used when a non-accepted acknowledged
     * event status has no acknowledgement-provided error.
     */
    private fun missingEventStatusError(status: ChangeAcknowledgementStatus): DataLoomError =
        OutboundPushPipelineError(
            code = ErrorCode("DL-OUTBOUND-EVENT-STATUS-$status"),
            category = ErrorCategory.PROVIDER,
            recoverability = if (status == ChangeAcknowledgementStatus.RETRY) {
                Recoverability.RECOVERABLE
            } else {
                Recoverability.NON_RECOVERABLE
            },
            message = "Outbound change event was acknowledged with status $status and no " +
                "acknowledgement-provided error.",
        )

    /**
     * Safe, internally-created [DataLoomError] implementation used for
     * pipeline contract violations.
     *
     * Never exposes payload content, credentials, checkpoint tokens, personal
     * data, or raw [Throwable] instances.
     */
    private data class OutboundPushPipelineError(
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
