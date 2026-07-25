package io.dataloom.runtime.worker

import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.QueueProvider
import io.dataloom.api.scheduling.ScheduleRequest
import io.dataloom.api.scheduling.SchedulerProvider
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomClock
import io.dataloom.runtime.queue.DurableQueueExecutionProcessor
import io.dataloom.runtime.queue.QueueProcessingResult

/**
 * Platform-independent coordinator that orchestrates one complete queue-worker
 * execution cycle.
 *
 * One [run] call follows this deterministic sequence:
 *
 * 1. Validate the [QueueWorkerRunRequest] against [configuration].
 * 2. Optionally invoke [io.dataloom.api.provider.QueueProvider.recoverExpiredLeases]
 *    exactly once when [QueueWorkerConfiguration.recoverExpiredLeasesBeforeProcessing]
 *    is `true`.
 * 3. On recovery provider failure: return [QueueWorkerRunResult.RecoveryFailed].
 *    No queue acquisition or scheduling is performed.
 * 4. Invoke [io.dataloom.runtime.queue.DurableQueueExecutionProcessor.process]
 *    exactly once.
 * 5. Inspect the [QueueProcessingResult] variant.
 * 6. For [QueueProcessingResult.NoWork] or [QueueProcessingResult.Processed]:
 *    build a [QueueWorkerWakeUpPlan] from actual processing evidence.
 * 7. For [QueueProcessingResult.QueueProviderFailure] or
 *    [QueueProcessingResult.QueueContractViolation]: return
 *    [QueueWorkerRunResult.ProcessingFailed] without scheduling.
 * 8. When [QueueWorkerWakeUpPlan.NoWakeUp]: return
 *    [QueueWorkerRunResult.ProcessingCompleted] with
 *    [QueueWorkerSchedulingResult.NotRequired].
 * 9. When [QueueWorkerWakeUpPlan.Schedule] and no [schedulerProvider]:
 *    return [QueueWorkerSchedulingResult.SchedulerNotConfigured].
 * 10. Build one [ScheduleRequest] and call
 *     [io.dataloom.api.scheduling.SchedulerProvider.schedule] exactly once.
 * 11. On provider success: return [QueueWorkerSchedulingResult.Scheduled].
 * 12. On provider failure: return [QueueWorkerSchedulingResult.SchedulerFailed]
 *     inside [QueueWorkerRunResult.ProcessingCompleted]. Queue state is not
 *     rolled back.
 *
 * ## Single bounded cycle
 *
 * [run] performs at most one recovery call, one queue-processing call, and
 * one scheduler call. It does not loop over the queue or perform a second
 * acquisition.
 *
 * ## Continuation evidence
 *
 * Wake-up decisions are based only on
 * [QueueProcessingResult.Processed.acquisitionLimitReached] and
 * [QueueProcessingResult.Processed.earliestRescheduledAt]. Failed transitions
 * do not contribute continuation evidence.
 *
 * ## Scheduler failure isolation
 *
 * A scheduler failure after successful queue processing is reported in
 * [QueueWorkerSchedulingResult.SchedulerFailed] inside
 * [QueueWorkerRunResult.ProcessingCompleted]. It does not cause a
 * [QueueWorkerRunResult.ProcessingFailed] result. Durable queue state is not
 * rolled back.
 *
 * ## Cancellation
 *
 * [kotlin.coroutines.cancellation.CancellationException] from any provider,
 * the queue processor, or the clock propagates normally and is never converted
 * into a structured result variant.
 *
 * ## Boundaries
 *
 * This coordinator must not:
 * - Execute a second queue-processing cycle.
 * - Call synchronization coordinators or pipelines directly.
 * - Invoke [io.dataloom.api.retry.RetryPolicy] directly.
 * - Decode queue entry payloads.
 * - Create or mutate queue entries outside [QueueProvider] contracts.
 * - Observe connectivity continuously.
 * - Dispatch new synchronization event variants.
 * - Own a [kotlinx.coroutines.CoroutineScope] or select a dispatcher.
 * - Use [System.currentTimeMillis], [java.time], or any platform clock.
 * - Use reflection, [java.util.ServiceLoader], or a DI framework.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 *
 * @param queueProvider the [QueueProvider] used for expired-lease recovery.
 *   The [DurableQueueExecutionProcessor] uses its own reference for
 *   acquisition and transitions. Required.
 * @param queueProcessor the [DurableQueueExecutionProcessor] that drives one
 *   bounded queue-processing cycle. Required.
 * @param schedulerProvider the optional platform scheduler. When `null`,
 *   [QueueWorkerSchedulingResult.SchedulerNotConfigured] is returned when a
 *   wake-up is required.
 * @param clock the injected [DataLoomClock] used to calculate the delay to
 *   the earliest rescheduled-entry availability. Required. Not read when no
 *   rescheduled entry timestamp is present.
 * @param configuration immutable configuration carrying scheduling parameters
 *   and the recovery flag. Required.
 */
public class QueueWorkerCoordinator(
    private val queueProvider: QueueProvider,
    private val queueProcessor: DurableQueueExecutionProcessor,
    private val schedulerProvider: SchedulerProvider?,
    private val clock: DataLoomClock,
    private val configuration: QueueWorkerConfiguration,
) {

    /**
     * Executes one complete queue-worker coordination cycle.
     *
     * The cycle performs at most one recovery call, one queue-processing call,
     * and one scheduler call. [kotlin.coroutines.cancellation.CancellationException]
     * from any step propagates normally.
     *
     * @param request the immutable run request carrying the processing request
     *   and optional recovery request.
     * @return a [QueueWorkerRunResult] describing the terminal outcome.
     * @throws IllegalArgumentException when
     *   [QueueWorkerConfiguration.recoverExpiredLeasesBeforeProcessing] is
     *   `true` but [QueueWorkerRunRequest.recoveryRequest] is `null`.
     */
    public suspend fun run(request: QueueWorkerRunRequest): QueueWorkerRunResult {
        // Step 1: Validate recovery-request presence when recovery is enabled.
        if (configuration.recoverExpiredLeasesBeforeProcessing) {
            requireNotNull(request.recoveryRequest) {
                "QueueWorkerRunRequest.recoveryRequest must not be null when " +
                    "QueueWorkerConfiguration.recoverExpiredLeasesBeforeProcessing is true."
            }
        }

        // Step 2: Optional expired-lease recovery — exactly once.
        var recoveryResult: io.dataloom.api.queue.ExpiredLeaseRecoveryResult? = null
        if (configuration.recoverExpiredLeasesBeforeProcessing) {
            val recoveryRequest = request.recoveryRequest!!
            when (val result = queueProvider.recoverExpiredLeases(recoveryRequest)) {
                is ProviderOperationResult.Failure -> {
                    // Step 3: Recovery failure stops the cycle.
                    return QueueWorkerRunResult.RecoveryFailed(error = result.error)
                }
                is ProviderOperationResult.Success -> {
                    // Step 4: Recovery success — preserve exact result.
                    recoveryResult = result.value
                }
            }
        }

        // Step 5: Invoke queue processor exactly once.
        val processingResult = queueProcessor.process(request.processingRequest)

        // Step 6–7: Inspect the processing result.
        return when (processingResult) {
            is QueueProcessingResult.QueueProviderFailure,
            is QueueProcessingResult.QueueContractViolation,
            -> {
                // Step 9: Processing failure — no scheduling.
                QueueWorkerRunResult.ProcessingFailed(
                    recoveryResult = recoveryResult,
                    processingResult = processingResult,
                )
            }

            is QueueProcessingResult.NoWork -> {
                // No entries — no continuation evidence.
                val plan = QueueWorkerWakeUpPlan.NoWakeUp
                QueueWorkerRunResult.ProcessingCompleted(
                    recoveryResult = recoveryResult,
                    processingResult = processingResult,
                    schedulingResult = executeScheduling(plan),
                )
            }

            is QueueProcessingResult.Processed -> {
                // Step 8: Build wake-up plan from actual processing evidence.
                val plan = buildWakeUpPlan(processingResult)
                QueueWorkerRunResult.ProcessingCompleted(
                    recoveryResult = recoveryResult,
                    processingResult = processingResult,
                    schedulingResult = executeScheduling(plan),
                )
            }
        }
    }

    // =========================================================================
    // Wake-up plan construction
    // =========================================================================

    /**
     * Builds a [QueueWorkerWakeUpPlan] from the continuation evidence in
     * [processed].
     *
     * Reads the [clock] at most once — only when [processed.earliestRescheduledAt]
     * is non-null.
     */
    private fun buildWakeUpPlan(processed: QueueProcessingResult.Processed): QueueWorkerWakeUpPlan {
        val limitReached = processed.acquisitionLimitReached
        val reschedAt = processed.earliestRescheduledAt

        return when {
            !limitReached && reschedAt == null -> {
                // Neither condition: no wake-up required.
                QueueWorkerWakeUpPlan.NoWakeUp
            }

            limitReached && reschedAt == null -> {
                // Only acquisition limit reached: use the configured continuation delay.
                QueueWorkerWakeUpPlan.Schedule(
                    reason = QueueWorkerWakeUpReason.ACQUISITION_LIMIT_REACHED,
                    delay = configuration.continuationDelay,
                    scheduleId = configuration.scheduleId,
                    constraints = configuration.constraints,
                    existingSchedulePolicy = configuration.existingSchedulePolicy,
                )
            }

            !limitReached && reschedAt != null -> {
                // Only rescheduled entry: calculate delay from clock.
                val delay = calculateRescheduleDelay(reschedAt)
                QueueWorkerWakeUpPlan.Schedule(
                    reason = QueueWorkerWakeUpReason.RESCHEDULED_ENTRY_AVAILABLE,
                    delay = delay,
                    scheduleId = configuration.scheduleId,
                    constraints = configuration.constraints,
                    existingSchedulePolicy = configuration.existingSchedulePolicy,
                )
            }

            else -> {
                // Both conditions: pick the earlier candidate delay.
                val reschedDelay = calculateRescheduleDelay(reschedAt!!)
                val continuationMs = configuration.continuationDelay.milliseconds
                val reschedMs = reschedDelay.milliseconds
                val selectedDelay = if (continuationMs <= reschedMs) {
                    configuration.continuationDelay
                } else {
                    reschedDelay
                }
                QueueWorkerWakeUpPlan.Schedule(
                    reason = QueueWorkerWakeUpReason.BOTH,
                    delay = selectedDelay,
                    scheduleId = configuration.scheduleId,
                    constraints = configuration.constraints,
                    existingSchedulePolicy = configuration.existingSchedulePolicy,
                )
            }
        }
    }

    /**
     * Calculates `max(0, earliestRescheduledAt - now)` using the injected
     * [clock].
     *
     * Uses overflow-safe arithmetic. Returns [SchedulingDelay.ZERO] when the
     * timestamp is already due or when overflow would occur.
     */
    private fun calculateRescheduleDelay(
        earliestRescheduledAt: io.dataloom.api.time.DataLoomInstant,
    ): SchedulingDelay {
        val nowMs = clock.now().epochMilliseconds
        val targetMs = earliestRescheduledAt.epochMilliseconds
        val delayMs = targetMs - nowMs
        return if (delayMs <= 0L) {
            SchedulingDelay.ZERO
        } else {
            SchedulingDelay(delayMs)
        }
    }

    // =========================================================================
    // Scheduling
    // =========================================================================

    /**
     * Executes the scheduling step for the given [plan].
     *
     * Returns [QueueWorkerSchedulingResult.NotRequired] when [plan] is
     * [QueueWorkerWakeUpPlan.NoWakeUp].
     *
     * Calls [schedulerProvider] at most once. [CancellationException] from the
     * provider propagates normally.
     */
    private suspend fun executeScheduling(
        plan: QueueWorkerWakeUpPlan,
    ): QueueWorkerSchedulingResult {
        if (plan is QueueWorkerWakeUpPlan.NoWakeUp) {
            return QueueWorkerSchedulingResult.NotRequired
        }

        val schedulePlan = plan as QueueWorkerWakeUpPlan.Schedule

        if (schedulerProvider == null) {
            return QueueWorkerSchedulingResult.SchedulerNotConfigured(plan = schedulePlan)
        }

        val scheduleRequest = ScheduleRequest(
            id = schedulePlan.scheduleId,
            synchronizationRequest = null,
            delay = schedulePlan.delay,
            constraints = schedulePlan.constraints,
            existingPolicy = schedulePlan.existingSchedulePolicy,
        )

        return when (val result = schedulerProvider.schedule(scheduleRequest)) {
            is ProviderOperationResult.Success -> QueueWorkerSchedulingResult.Scheduled(
                receipt = result.value,
                plan = schedulePlan,
            )
            is ProviderOperationResult.Failure -> QueueWorkerSchedulingResult.SchedulerFailed(
                error = result.error,
                plan = schedulePlan,
            )
        }
    }
}
