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
 * The five-argument constructor is retained explicitly for source and JVM
 * constructor compatibility. It delegates to a null scheduler timeout.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 */
public data class QueueWorkerConfiguration(
    /** Stable identifier forwarded to every [io.dataloom.api.scheduling.ScheduleRequest]. */
    public val scheduleId: ScheduleId,

    /** Execution constraints forwarded to every [io.dataloom.api.scheduling.ScheduleRequest]. */
    public val constraints: ScheduleConstraints,

    /** Policy applied when a schedule with the same [scheduleId] already exists. */
    public val existingSchedulePolicy: ExistingSchedulePolicy,

    /** Minimum scheduling delay used when the bounded acquisition is full. */
    public val continuationDelay: SchedulingDelay,

    /** Enables one expired-lease recovery operation before queue processing. */
    public val recoverExpiredLeasesBeforeProcessing: Boolean,

    /** Optional timeout applied only to the follow-up scheduler-provider call. */
    public val schedulerProviderTimeout: SchedulingDelay?,
) {
    /**
     * Preserves the original constructor and historical unbounded scheduler
     * behavior.
     */
    public constructor(
        scheduleId: ScheduleId,
        constraints: ScheduleConstraints,
        existingSchedulePolicy: ExistingSchedulePolicy,
        continuationDelay: SchedulingDelay,
        recoverExpiredLeasesBeforeProcessing: Boolean,
    ) : this(
        scheduleId = scheduleId,
        constraints = constraints,
        existingSchedulePolicy = existingSchedulePolicy,
        continuationDelay = continuationDelay,
        recoverExpiredLeasesBeforeProcessing = recoverExpiredLeasesBeforeProcessing,
        schedulerProviderTimeout = null,
    )
}
