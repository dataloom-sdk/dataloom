package io.dataloom.runtime.execution.bidirectional

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.synchronization.SynchronizationSkipReason
import io.dataloom.api.synchronization.SynchronizationSummary
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.execution.SynchronizationExecutionContext
import io.dataloom.runtime.execution.SynchronizationPipeline

/**
 * [SynchronizationPipeline] implementation for the bidirectional
 * synchronization direction.
 *
 * ## Purpose
 *
 * [BidirectionalSynchronizationPipeline] composes an existing outbound push
 * pipeline and an existing inbound pull pipeline into a single, sequential
 * bidirectional execution. It does not re-implement provider operations. All
 * synchronization work is delegated to the two child pipelines.
 *
 * ## Direction
 *
 * [direction] is [SynchronizationDirection.BIDIRECTIONAL]. This pipeline is
 * selected when a [io.dataloom.api.model.SynchronizationRequest] specifies
 * the bidirectional direction.
 *
 * ## Delegate validation
 *
 * The [outboundPipeline] must declare [SynchronizationDirection.PUSH].
 * The [inboundPipeline] must declare [SynchronizationDirection.PULL].
 * An incorrect direction is rejected with [IllegalArgumentException] at
 * construction time. Construction does not invoke either child pipeline.
 *
 * ## Execution order
 *
 * [BidirectionalPipelineConfiguration.executionOrder] determines which child
 * pipeline runs first. The default is
 * [BidirectionalExecutionOrder.OUTBOUND_THEN_INBOUND], which pushes local
 * changes before pulling remote changes. The exact same
 * [SynchronizationExecutionContext] is passed to both child pipelines
 * unchanged.
 *
 * ## Sequential execution
 *
 * The two child pipelines execute strictly sequentially. Parallel execution
 * is not performed.
 *
 * ## Continuation rules
 *
 * The second pipeline is executed when the first pipeline returns:
 * - [SynchronizationResult.Succeeded]
 * - [SynchronizationResult.PartiallySucceeded]
 * - [SynchronizationResult.Skipped] with [SynchronizationSkipReason.NO_CHANGES]
 *
 * The second pipeline is **not** executed when the first pipeline returns:
 * - [SynchronizationResult.Failed]
 * - [SynchronizationResult.Cancelled]
 * - [SynchronizationResult.Skipped] with any reason other than
 *   [SynchronizationSkipReason.NO_CHANGES]
 *
 * ## Result combination
 *
 * The results of both child pipelines are combined according to the result
 * combination matrix defined in DL-023. The composed terminal result uses
 * [io.dataloom.core.runtime.RuntimeDependencies.clock] for its final
 * completion timestamp.
 *
 * ## Summary combination
 *
 * Counters from both child summaries are summed. Overflow-safe addition is
 * enforced; if any counter overflows, a canonical
 * [SynchronizationResult.Failed] is returned instead.
 *
 * ## Cancellation boundary
 *
 * A [kotlin.coroutines.cancellation.CancellationException] thrown by either
 * child pipeline propagates normally. It is never converted into a
 * [SynchronizationResult]. A child pipeline that returns the explicit
 * [SynchronizationResult.Cancelled] value is distinct from a thrown
 * [kotlin.coroutines.cancellation.CancellationException].
 *
 * ## Direct provider-call restriction
 *
 * This pipeline does not directly call any [io.dataloom.api.storage.StorageProvider],
 * [io.dataloom.api.transport.TransportProvider],
 * [io.dataloom.api.scheduler.SchedulerProvider],
 * [io.dataloom.api.connectivity.ConnectivityProvider],
 * [io.dataloom.api.queue.QueueProvider],
 * [io.dataloom.api.retry.RetryPolicy],
 * [io.dataloom.api.conflict.ConflictDetector],
 * [io.dataloom.api.conflict.ConflictResolver], or any provider lifecycle,
 * registry, or observer operation. All provider calls are delegated to the
 * child pipelines.
 *
 * ## Retry, queue, and scheduler boundary
 *
 * This pipeline does not invoke any retry policy, enqueue work, reschedule
 * queue entries, or call any scheduler. Child results are combined only.
 *
 * ## Event boundary
 *
 * This pipeline does not dispatch events, register observers, or use any
 * [kotlinx.coroutines.flow.Flow], [kotlinx.coroutines.channels.Channel], or
 * [kotlinx.coroutines.flow.StateFlow].
 *
 * ## Security
 *
 * Diagnostic representations include only execution order, direction names,
 * result variant names, and summary counts. Payload bytes, checkpoint token
 * values, credentials, authorization headers, encryption keys, personal data,
 * and stack traces are never exposed.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API, core, and runtime types
 * only. Safe for use in Kotlin Multiplatform common code.
 *
 * @param outboundPipeline the [SynchronizationPipeline] responsible for
 *   outbound push execution. Must declare
 *   [SynchronizationDirection.PUSH].
 * @param inboundPipeline the [SynchronizationPipeline] responsible for
 *   inbound pull execution. Must declare
 *   [SynchronizationDirection.PULL].
 * @param configuration the [BidirectionalPipelineConfiguration] controlling
 *   the sequential execution order.
 * @throws IllegalArgumentException when [outboundPipeline] does not declare
 *   [SynchronizationDirection.PUSH] or [inboundPipeline] does not declare
 *   [SynchronizationDirection.PULL].
 */
public class BidirectionalSynchronizationPipeline(
    private val outboundPipeline: SynchronizationPipeline,
    private val inboundPipeline: SynchronizationPipeline,
    private val configuration: BidirectionalPipelineConfiguration,
) : SynchronizationPipeline {

    init {
        require(outboundPipeline.direction == SynchronizationDirection.PUSH) {
            "BidirectionalSynchronizationPipeline outboundPipeline must declare direction PUSH, " +
                "but was ${outboundPipeline.direction}."
        }
        require(inboundPipeline.direction == SynchronizationDirection.PULL) {
            "BidirectionalSynchronizationPipeline inboundPipeline must declare direction PULL, " +
                "but was ${inboundPipeline.direction}."
        }
    }

    /**
     * The bidirectional synchronization direction.
     *
     * This pipeline is selected when the incoming
     * [io.dataloom.api.model.SynchronizationRequest] specifies
     * [SynchronizationDirection.BIDIRECTIONAL].
     */
    override val direction: SynchronizationDirection = SynchronizationDirection.BIDIRECTIONAL

    /**
     * Executes the bidirectional synchronization pipeline.
     *
     * Delegates to the two child pipelines in the order configured by
     * [BidirectionalPipelineConfiguration.executionOrder]. The exact same
     * [context] is passed to both child pipelines unchanged.
     *
     * @param context the immutable execution context for this synchronization
     *   run.
     * @return the combined [SynchronizationResult] representing the terminal
     *   outcome of the bidirectional execution.
     */
    override suspend fun execute(context: SynchronizationExecutionContext): SynchronizationResult {
        return when (configuration.executionOrder) {
            BidirectionalExecutionOrder.OUTBOUND_THEN_INBOUND ->
                executeOutboundThenInbound(context)
            BidirectionalExecutionOrder.INBOUND_THEN_OUTBOUND ->
                executeInboundThenOutbound(context)
        }
    }

    // -------------------------------------------------------------------------
    // Ordered execution
    // -------------------------------------------------------------------------

    private suspend fun executeOutboundThenInbound(
        context: SynchronizationExecutionContext,
    ): SynchronizationResult {
        val firstResult = outboundPipeline.execute(context)
        if (!permitsSecondPipeline(firstResult)) {
            return terminalFromFirstOnly(firstResult, context)
        }
        val secondResult = inboundPipeline.execute(context)
        return combineResults(firstResult, secondResult, context)
    }

    private suspend fun executeInboundThenOutbound(
        context: SynchronizationExecutionContext,
    ): SynchronizationResult {
        val firstResult = inboundPipeline.execute(context)
        if (!permitsSecondPipeline(firstResult)) {
            return terminalFromFirstOnly(firstResult, context)
        }
        val secondResult = outboundPipeline.execute(context)
        return combineResults(firstResult, secondResult, context)
    }

    // -------------------------------------------------------------------------
    // Continuation decision
    // -------------------------------------------------------------------------

    /**
     * Returns `true` when [result] permits the second child pipeline to be
     * executed.
     *
     * Continuation is allowed for [SynchronizationResult.Succeeded],
     * [SynchronizationResult.PartiallySucceeded], and
     * [SynchronizationResult.Skipped] with [SynchronizationSkipReason.NO_CHANGES].
     *
     * Continuation is denied for [SynchronizationResult.Failed],
     * [SynchronizationResult.Cancelled], and
     * [SynchronizationResult.Skipped] with any reason other than
     * [SynchronizationSkipReason.NO_CHANGES].
     */
    private fun permitsSecondPipeline(result: SynchronizationResult): Boolean =
        when (result) {
            is SynchronizationResult.Succeeded -> true
            is SynchronizationResult.PartiallySucceeded -> true
            is SynchronizationResult.Skipped ->
                result.reason == SynchronizationSkipReason.NO_CHANGES
            is SynchronizationResult.Failed -> false
            is SynchronizationResult.Cancelled -> false
        }

    // -------------------------------------------------------------------------
    // Single-result terminal (second pipeline not executed)
    // -------------------------------------------------------------------------

    /**
     * Produces the composed terminal result when the second pipeline was not
     * executed.
     *
     * Reads the composed completion timestamp from the injected clock. Does
     * not reuse the child completion timestamp.
     */
    private fun terminalFromFirstOnly(
        firstResult: SynchronizationResult,
        context: SynchronizationExecutionContext,
    ): SynchronizationResult {
        val completedAt: DataLoomInstant = context.runtimeDependencies.clock.now()
        return when (firstResult) {
            is SynchronizationResult.Failed -> SynchronizationResult.Failed(
                request = firstResult.request,
                completedAt = completedAt,
                summary = firstResult.summary,
                error = firstResult.error,
            )
            is SynchronizationResult.Cancelled -> SynchronizationResult.Cancelled(
                request = firstResult.request,
                completedAt = completedAt,
                summary = firstResult.summary,
            )
            is SynchronizationResult.Skipped -> SynchronizationResult.Skipped(
                request = firstResult.request,
                completedAt = completedAt,
                summary = firstResult.summary,
                reason = firstResult.reason,
            )
            // Succeeded and PartiallySucceeded never reach here (they permit the second pipeline).
            else -> error(
                "BidirectionalSynchronizationPipeline: unexpected first-only terminal result " +
                    "variant ${firstResult::class.simpleName}.",
            )
        }
    }

    // -------------------------------------------------------------------------
    // Result combination (both pipelines executed)
    // -------------------------------------------------------------------------

    /**
     * Combines the results of two child pipelines into a single terminal
     * [SynchronizationResult].
     *
     * The composed completion timestamp is always read from the injected clock.
     * Summary counters are summed with overflow-safe addition; if any counter
     * overflows a canonical [SynchronizationResult.Failed] is returned.
     */
    private fun combineResults(
        firstResult: SynchronizationResult,
        secondResult: SynchronizationResult,
        context: SynchronizationExecutionContext,
    ): SynchronizationResult {
        val request = context.request
        val completedAt: DataLoomInstant = context.runtimeDependencies.clock.now()

        // Combine summaries with overflow protection.
        val combinedSummary: SynchronizationSummary = try {
            combineSummaries(firstResult.summary, secondResult.summary)
        } catch (e: ArithmeticException) {
            return SynchronizationResult.Failed(
                request = request,
                completedAt = completedAt,
                summary = firstResult.summary,
                error = summaryOverflowError(),
            )
        }

        // Handle terminal second-pipeline results first.
        when (secondResult) {
            is SynchronizationResult.Failed -> return SynchronizationResult.Failed(
                request = request,
                completedAt = completedAt,
                summary = combinedSummary,
                error = secondResult.error,
            )
            is SynchronizationResult.Cancelled -> return SynchronizationResult.Cancelled(
                request = request,
                completedAt = completedAt,
                summary = combinedSummary,
            )
            else -> { /* continue to matrix below */ }
        }

        // Result combination matrix.
        return when {
            // Both no-changes.
            firstIsNoChanges(firstResult) && secondIsNoChanges(secondResult) ->
                SynchronizationResult.Skipped(
                    request = request,
                    completedAt = completedAt,
                    summary = combinedSummary,
                    reason = SynchronizationSkipReason.NO_CHANGES,
                )

            // One no-changes, one Succeeded → Succeeded.
            firstIsNoChanges(firstResult) && secondResult is SynchronizationResult.Succeeded ->
                SynchronizationResult.Succeeded(
                    request = request,
                    completedAt = completedAt,
                    summary = combinedSummary,
                )

            firstResult is SynchronizationResult.Succeeded && secondIsNoChanges(secondResult) ->
                SynchronizationResult.Succeeded(
                    request = request,
                    completedAt = completedAt,
                    summary = combinedSummary,
                )

            // Both Succeeded.
            firstResult is SynchronizationResult.Succeeded &&
                secondResult is SynchronizationResult.Succeeded ->
                SynchronizationResult.Succeeded(
                    request = request,
                    completedAt = completedAt,
                    summary = combinedSummary,
                )

            // Any partial result → PartiallySucceeded (errors in execution order).
            else -> {
                val errors: List<DataLoomError> = buildCombinedErrors(firstResult, secondResult)
                SynchronizationResult.PartiallySucceeded(
                    request = request,
                    completedAt = completedAt,
                    summary = combinedSummary,
                    errors = errors,
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Summary combination helpers
    // -------------------------------------------------------------------------

    /**
     * Combines two [SynchronizationSummary] instances by summing every
     * corresponding counter using overflow-safe addition.
     *
     * @throws ArithmeticException if any counter addition overflows.
     */
    private fun combineSummaries(
        a: SynchronizationSummary,
        b: SynchronizationSummary,
    ): SynchronizationSummary {
        val outboundEventsRead = safeAddLong(a.outboundEventsRead, b.outboundEventsRead)
        val outboundEventsAccepted = safeAddLong(a.outboundEventsAccepted, b.outboundEventsAccepted)
        val outboundEventsMarkedForRetry = safeAddLong(
            a.outboundEventsMarkedForRetry,
            b.outboundEventsMarkedForRetry,
        )
        val outboundEventsRejected = safeAddLong(a.outboundEventsRejected, b.outboundEventsRejected)
        val inboundEventsReceived = safeAddLong(a.inboundEventsReceived, b.inboundEventsReceived)
        val inboundEventsApplied = safeAddLong(a.inboundEventsApplied, b.inboundEventsApplied)
        val conflictsDetected = safeAddLong(a.conflictsDetected, b.conflictsDetected)
        val retryAttempts = safeAddInt(a.retryAttempts, b.retryAttempts)

        return SynchronizationSummary(
            outboundEventsRead = outboundEventsRead,
            outboundEventsAccepted = outboundEventsAccepted,
            outboundEventsMarkedForRetry = outboundEventsMarkedForRetry,
            outboundEventsRejected = outboundEventsRejected,
            inboundEventsReceived = inboundEventsReceived,
            inboundEventsApplied = inboundEventsApplied,
            conflictsDetected = conflictsDetected,
            retryAttempts = retryAttempts,
        )
    }

    /**
     * Overflow-safe Long addition for non-negative summary counters.
     *
     * @throws ArithmeticException if addition would overflow [Long.MAX_VALUE].
     */
    private fun safeAddLong(a: Long, b: Long): Long {
        if (b > 0L && a > Long.MAX_VALUE - b) {
            throw ArithmeticException(
                "BidirectionalSynchronizationPipeline: Long summary counter overflow: $a + $b",
            )
        }
        return a + b
    }

    /**
     * Overflow-safe Int addition for non-negative [SynchronizationSummary.retryAttempts].
     *
     * @throws ArithmeticException if addition would overflow [Int.MAX_VALUE].
     */
    private fun safeAddInt(a: Int, b: Int): Int {
        val sum: Long = a.toLong() + b.toLong()
        if (sum > Int.MAX_VALUE.toLong()) {
            throw ArithmeticException(
                "BidirectionalSynchronizationPipeline: Int retryAttempts overflow: $a + $b",
            )
        }
        return sum.toInt()
    }

    // -------------------------------------------------------------------------
    // Error collection helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a combined, defensively copied error list from up to two partial
     * results.
     *
     * Errors from the first pipeline precede errors from the second pipeline.
     * Error order is preserved. Duplicate errors are not removed.
     */
    private fun buildCombinedErrors(
        firstResult: SynchronizationResult,
        secondResult: SynchronizationResult,
    ): List<DataLoomError> {
        val errors = mutableListOf<DataLoomError>()
        if (firstResult is SynchronizationResult.PartiallySucceeded) {
            errors.addAll(firstResult.errors)
        }
        if (secondResult is SynchronizationResult.PartiallySucceeded) {
            errors.addAll(secondResult.errors)
        }
        // When no errors were found but we still ended up here, produce a
        // programming-error guard. This should not occur in practice because
        // combineResults only reaches this branch when at least one side is
        // PartiallySucceeded.
        check(errors.isNotEmpty()) {
            "BidirectionalSynchronizationPipeline: PartiallySucceeded branch reached with " +
                "empty error collection. firstResult=${firstResult::class.simpleName}, " +
                "secondResult=${secondResult::class.simpleName}."
        }
        return errors.toList()
    }

    // -------------------------------------------------------------------------
    // Predicate helpers
    // -------------------------------------------------------------------------

    private fun firstIsNoChanges(result: SynchronizationResult): Boolean =
        result is SynchronizationResult.Skipped &&
            result.reason == SynchronizationSkipReason.NO_CHANGES

    private fun secondIsNoChanges(result: SynchronizationResult): Boolean =
        result is SynchronizationResult.Skipped &&
            result.reason == SynchronizationSkipReason.NO_CHANGES

    // -------------------------------------------------------------------------
    // Error factories
    // -------------------------------------------------------------------------

    /**
     * Canonical safe error returned when summary counter addition overflows
     * during result combination.
     */
    private fun summaryOverflowError(): DataLoomError =
        BidirectionalPipelineError(
            code = ErrorCode("DL-BIDIRECTIONAL-SUMMARY-OVERFLOW"),
            category = ErrorCategory.INTERNAL,
            severity = ErrorSeverity.ERROR,
            recoverability = Recoverability.NON_RECOVERABLE,
            message = "BidirectionalSynchronizationPipeline: summary counter overflow during " +
                "result combination.",
        )

    // -------------------------------------------------------------------------
    // Internal error type
    // -------------------------------------------------------------------------

    /**
     * Safe, internally-created [DataLoomError] used for pipeline-level
     * contract violations.
     *
     * Never exposes payload content, credentials, checkpoint tokens, personal
     * data, or raw [Throwable] instances.
     */
    private data class BidirectionalPipelineError(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val severity: ErrorSeverity,
        override val recoverability: Recoverability,
        override val message: String,
        override val cause: Throwable? = null,
    ) : DataLoomError

    // -------------------------------------------------------------------------
    // Diagnostic
    // -------------------------------------------------------------------------

    override fun toString(): String =
        "BidirectionalSynchronizationPipeline(" +
            "direction=$direction, " +
            "executionOrder=${configuration.executionOrder}" +
            ")"
}
