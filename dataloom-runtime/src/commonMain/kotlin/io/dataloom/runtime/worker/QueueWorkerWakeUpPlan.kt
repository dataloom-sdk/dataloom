package io.dataloom.runtime.worker

import io.dataloom.api.identifier.ScheduleId
import io.dataloom.api.scheduling.ExistingSchedulePolicy
import io.dataloom.api.scheduling.ScheduleConstraints
import io.dataloom.api.scheduling.SchedulingDelay

/**
 * Structured wake-up plan produced by [QueueWorkerCoordinator] after
 * inspecting a bounded queue processing cycle result.
 *
 * ## Variants
 *
 * - [NoWakeUp] — no continuation condition was detected. No scheduler call is
 *   required.
 * - [Schedule] — at least one continuation condition was detected. The plan
 *   carries the selected delay, wake-up reason, worker schedule identifier,
 *   constraints, and existing-schedule policy. A single
 *   [io.dataloom.api.scheduling.SchedulerProvider.schedule] call will cover
 *   the plan.
 *
 * ## Plan selection rules
 *
 * 1. Neither acquisition limit reached nor a rescheduled entry available:
 *    [NoWakeUp].
 * 2. Only acquisition limit reached: [Schedule] with the configured
 *    [QueueWorkerConfiguration.continuationDelay] and reason
 *    [QueueWorkerWakeUpReason.ACQUISITION_LIMIT_REACHED].
 * 3. Only a rescheduled entry available: [Schedule] with a delay calculated
 *    from the injected [io.dataloom.api.time.DataLoomClock] to
 *    [io.dataloom.runtime.queue.QueueProcessingResult.Processed.earliestRescheduledAt]
 *    and reason [QueueWorkerWakeUpReason.RESCHEDULED_ENTRY_AVAILABLE].
 * 4. Both conditions exist: [Schedule] with the earlier of the two candidate
 *    delays and reason [QueueWorkerWakeUpReason.BOTH].
 *
 * ## Construction behaviour
 *
 * Construction does not perform scheduling, clock reads, or provider calls.
 * All values are preserved exactly.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 */
public sealed interface QueueWorkerWakeUpPlan {

    /**
     * No wake-up is required.
     *
     * Neither the acquisition limit was reached nor were any entries
     * successfully rescheduled in this processing cycle.
     */
    public data object NoWakeUp : QueueWorkerWakeUpPlan

    /**
     * A scheduler wake-up is required.
     *
     * This plan carries the selected delay, wake-up reason, and all
     * scheduling parameters needed to build a single
     * [io.dataloom.api.scheduling.ScheduleRequest].
     *
     * @param reason the [QueueWorkerWakeUpReason] that triggered this plan.
     * @param delay the selected minimum scheduling delay.
     * @param scheduleId the worker schedule identifier forwarded verbatim from
     *   [QueueWorkerConfiguration.scheduleId].
     * @param constraints execution constraints forwarded verbatim from
     *   [QueueWorkerConfiguration.constraints].
     * @param existingSchedulePolicy policy applied when a schedule with the
     *   same [scheduleId] already exists. Forwarded verbatim from
     *   [QueueWorkerConfiguration.existingSchedulePolicy].
     */
    public data class Schedule(
        /** Wake-up reason that produced this plan. */
        public val reason: QueueWorkerWakeUpReason,

        /** Selected minimum scheduling delay. */
        public val delay: SchedulingDelay,

        /**
         * Worker schedule identifier forwarded verbatim from
         * [QueueWorkerConfiguration.scheduleId].
         */
        public val scheduleId: ScheduleId,

        /**
         * Execution constraints forwarded verbatim from
         * [QueueWorkerConfiguration.constraints].
         */
        public val constraints: ScheduleConstraints,

        /**
         * Policy applied when a schedule with the same [scheduleId] already
         * exists. Forwarded verbatim from
         * [QueueWorkerConfiguration.existingSchedulePolicy].
         */
        public val existingSchedulePolicy: ExistingSchedulePolicy,
    ) : QueueWorkerWakeUpPlan
}
