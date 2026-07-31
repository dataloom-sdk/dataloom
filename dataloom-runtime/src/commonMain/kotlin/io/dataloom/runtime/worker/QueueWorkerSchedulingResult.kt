package io.dataloom.runtime.worker

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.scheduling.ScheduleReceipt
import io.dataloom.runtime.retry.CircuitBreakerExecutionResult

/**
 * Structured result of the scheduling step inside
 * [QueueWorkerCoordinator.run].
 *
 * ## Variants
 *
 * - [NotRequired] — no wake-up plan was required. No scheduler call was made.
 * - [Scheduled] — [io.dataloom.api.scheduling.SchedulerProvider.schedule] was
 *   called once and the provider accepted the request on the direct path.
 * - [CircuitProtected] — scheduling used an explicit circuit and preserves the
 *   complete pre-execution, provider, and post-execution recording evidence.
 * - [SchedulerNotConfigured] — a wake-up was required but no
 *   [io.dataloom.api.scheduling.SchedulerProvider] was supplied to the
 *   coordinator. The exact plan is preserved. Another host trigger may be
 *   required to wake the queue worker.
 * - [SchedulerFailed] — the provider returned a canonical
 *   [io.dataloom.api.error.DataLoomError]. Durable queue transitions have
 *   already been persisted and must not be rolled back. Another host trigger
 *   may be required to wake the queue worker.
 *
 * ## Scheduler failure contract
 *
 * A scheduler failure after successful queue processing must not be reported
 * as a queue-processing failure. Durable queue transitions that already
 * succeeded must not be rolled back. Scheduling is not retried within the
 * same coordinator run.
 *
 * ## Cancellation
 *
 * [kotlin.coroutines.cancellation.CancellationException] from
 * [io.dataloom.api.scheduling.SchedulerProvider.schedule] is not classified
 * as [SchedulerFailed]. It propagates normally and is never converted into
 * this result type.
 *
 * ## Sensitive-data restrictions
 *
 * No raw [Throwable], no stack trace, and no scheduler provider instance
 * are exposed. [SchedulerFailed.error] is a canonical [DataLoomError] whose
 * [DataLoomError.message] must already be sanitized by the provider.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 */
public sealed interface QueueWorkerSchedulingResult {

    /**
     * No wake-up plan was required after the processing cycle.
     *
     * No [io.dataloom.api.scheduling.SchedulerProvider] call was made.
     */
    public data object NotRequired : QueueWorkerSchedulingResult

    /**
     * [io.dataloom.api.scheduling.SchedulerProvider.schedule] was called once
     * and the provider accepted the request.
     *
     * @param receipt the exact [ScheduleReceipt] returned by the provider.
     * @param plan the exact [QueueWorkerWakeUpPlan.Schedule] that was executed.
     */
    public data class Scheduled(
        /** Exact [ScheduleReceipt] returned by the provider. */
        public val receipt: ScheduleReceipt,

        /** Exact [QueueWorkerWakeUpPlan.Schedule] that was executed. */
        public val plan: QueueWorkerWakeUpPlan.Schedule,
    ) : QueueWorkerSchedulingResult

    /**
     * Scheduling was protected by an explicitly configured scheduler circuit.
     *
     * [executionResult] preserves whether the provider was rejected before
     * invocation, returned a canonical failure, accepted the schedule, or
     * accepted it before the later circuit-state recording became unconfirmed.
     * An accepted schedule is never collapsed into a generic failure merely
     * because post-execution circuit persistence failed.
     */
    public data class CircuitProtected(
        /** Complete circuit permission, provider, and recording evidence. */
        public val executionResult: CircuitBreakerExecutionResult<ScheduleReceipt>,

        /** Exact wake-up plan submitted or rejected by the scheduler boundary. */
        public val plan: QueueWorkerWakeUpPlan.Schedule,
    ) : QueueWorkerSchedulingResult

    /**
     * A wake-up was required but no
     * [io.dataloom.api.scheduling.SchedulerProvider] was supplied to the
     * coordinator.
     *
     * The exact [plan] is preserved. Another host trigger may be required to
     * wake the queue worker.
     *
     * @param plan the exact [QueueWorkerWakeUpPlan.Schedule] that could not be
     *   executed.
     */
    public data class SchedulerNotConfigured(
        /** Exact [QueueWorkerWakeUpPlan.Schedule] that could not be executed. */
        public val plan: QueueWorkerWakeUpPlan.Schedule,
    ) : QueueWorkerSchedulingResult

    /**
     * A wake-up was required but
     * [io.dataloom.api.scheduling.SchedulerProvider] returned a canonical
     * failure.
     *
     * Durable queue transitions have already been persisted and must not be
     * rolled back. Another host trigger may be required to wake the queue
     * worker. Scheduling is not retried within the same coordinator run.
     *
     * @param error the exact canonical [DataLoomError] returned by the
     *   provider.
     * @param plan the exact [QueueWorkerWakeUpPlan.Schedule] whose execution
     *   failed.
     */
    public data class SchedulerFailed(
        /** Exact canonical [DataLoomError] returned by the provider. */
        public val error: DataLoomError,

        /** Exact [QueueWorkerWakeUpPlan.Schedule] whose execution failed. */
        public val plan: QueueWorkerWakeUpPlan.Schedule,
    ) : QueueWorkerSchedulingResult
}
