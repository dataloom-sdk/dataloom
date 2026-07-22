package io.dataloom.api.scheduling

import io.dataloom.api.identifier.ScheduleId
import io.dataloom.api.model.SynchronizationRequest

/**
 * Immutable scheduling intent for a synchronization workflow.
 *
 * A [ScheduleRequest] is a pure data contract that describes which
 * synchronization to schedule, when it should be deferred, what constraints
 * must be satisfied, and how to handle an existing schedule with the same
 * identifier.
 *
 * ## Construction behaviour
 *
 * Construction does not schedule execution, enqueue synchronization, inspect
 * platform state, or mutate the [synchronizationRequest].
 *
 * ## Platform semantics
 *
 * Passing a [ScheduleRequest] to [SchedulerProvider.schedule] communicates
 * scheduling intent to the platform provider. A successful provider result
 * means the provider accepted the request; it does not guarantee that
 * synchronization has started, completed, or succeeded.
 *
 * ## Equality
 *
 * Equality compares all properties by value.
 *
 * @param id required stable identifier for this scheduled entry.
 * @param synchronizationRequest required immutable synchronization intent.
 * @param delay minimum scheduling delay. Defaults to [SchedulingDelay.ZERO].
 * @param constraints execution constraints for this schedule. Defaults to
 *   [ScheduleConstraints] with all defaults applied.
 * @param existingPolicy behaviour when a schedule with the same [id] already
 *   exists. Defaults to [ExistingSchedulePolicy.KEEP].
 */
public data class ScheduleRequest(
    /** Required stable identifier for this scheduled entry. */
    public val id: ScheduleId,

    /** Required immutable synchronization intent to be scheduled. */
    public val synchronizationRequest: SynchronizationRequest,

    /**
     * Minimum scheduling delay.
     *
     * Defaults to [SchedulingDelay.ZERO], meaning no minimum delay is
     * requested.
     */
    public val delay: SchedulingDelay = SchedulingDelay.ZERO,

    /**
     * Execution constraints for this schedule.
     *
     * Defaults to [ScheduleConstraints] with all defaults applied: no
     * connectivity requirement, charging not required, and empty metadata.
     */
    public val constraints: ScheduleConstraints = ScheduleConstraints(),

    /**
     * Policy to apply when a schedule with the same [id] already exists in the
     * platform scheduler.
     *
     * Defaults to [ExistingSchedulePolicy.KEEP].
     */
    public val existingPolicy: ExistingSchedulePolicy = ExistingSchedulePolicy.KEEP,
)
