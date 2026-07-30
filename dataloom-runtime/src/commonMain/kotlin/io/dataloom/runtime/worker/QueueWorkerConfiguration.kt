package io.dataloom.runtime.worker

import io.dataloom.api.identifier.ScheduleId
import io.dataloom.api.scheduling.ExistingSchedulePolicy
import io.dataloom.api.scheduling.ScheduleConstraints
import io.dataloom.api.scheduling.SchedulingDelay

/**
 * Immutable configuration for a [QueueWorkerCoordinator] run.
 *
 * [QueueWorkerConfiguration] carries the scheduling parameters that
 * [QueueWorkerCoordinator] uses when deciding whether to request another
 * queue-worker wake-up and how to construct the corresponding
 * [io.dataloom.api.scheduling.ScheduleRequest].
 *
 * ## Construction behaviour
 *
 * Construction does not perform any queue operation, scheduling operation,
 * clock read, timeout execution, or identifier generation. All values supplied
 * by the caller are preserved exactly.
 *
 * ## continuationDelay
 *
 * [continuationDelay] is used exclusively when the bounded queue acquisition
 * returned the maximum requested number of entries and more immediately
 * available work may remain. It must not be used as:
 *
 * - a retry-policy delay
 * - an offline-deferral delay
 * - an entry availability timestamp
 * - a substitute for an explicitly rescheduled entry availability time
 *
 * ## schedulerProviderTimeout
 *
 * [schedulerProviderTimeout] is an optional upper bound for the queue worker's
 * follow-up [io.dataloom.api.scheduling.SchedulerProvider.schedule] call. When
 * configured, [QueueWorkerCoordinator] applies the production cooperative
 * coroutine provider-timeout boundary before invoking the scheduler.
 *
 * A null value preserves the historical unbounded scheduler invocation. A zero
 * value rejects before the scheduler is called. This timeout is not reused as a
 * connection, request, idle, policy, queue-processing, or workflow timeout.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 *
 * @param scheduleId stable identifier forwarded verbatim to every
 *   [io.dataloom.api.scheduling.ScheduleRequest] built by this coordinator.
 * @param constraints execution constraints forwarded verbatim to every
 *   [io.dataloom.api.scheduling.ScheduleRequest].
 * @param existingSchedulePolicy policy applied when a schedule with the same
 *   [scheduleId] already exists in the platform scheduler.
 * @param continuationDelay minimum scheduling delay used when the acquisition
 *   limit was reached and another bounded processing cycle may be useful.
 * @param recoverExpiredLeasesBeforeProcessing when `true`, the coordinator
 *   calls [io.dataloom.api.queue.QueueProvider.recoverExpiredLeases] exactly
 *   once before invoking the queue processor.
 * @param schedulerProviderTimeout optional timeout applied only to the queue
 *   worker's scheduler-provider call.
 */
public data class QueueWorkerConfiguration(
    /** Stable identifier forwarded to every [io.dataloom.api.scheduling.ScheduleRequest]. */
    public val scheduleId: ScheduleId,

    /** Execution constraints forwarded to every [io.dataloom.api.scheduling.ScheduleRequest]. */
    public val constraints: ScheduleConstraints,

    /**
     * Policy applied when a schedule with the same [scheduleId] already
     * exists in the platform scheduler.
     */
    public val existingSchedulePolicy: ExistingSchedulePolicy,

    /**
     * Minimum scheduling delay used when the acquisition limit was reached
     * and another bounded processing cycle may be useful.
     */
    public val continuationDelay: SchedulingDelay,

    /**
     * When `true`, the coordinator calls
     * [io.dataloom.api.queue.QueueProvider.recoverExpiredLeases] exactly once
     * before invoking the queue processor.
     */
    public val recoverExpiredLeasesBeforeProcessing: Boolean,

    /** Optional timeout applied only to the follow-up scheduler-provider call. */
    public val schedulerProviderTimeout: SchedulingDelay? = null,
)
