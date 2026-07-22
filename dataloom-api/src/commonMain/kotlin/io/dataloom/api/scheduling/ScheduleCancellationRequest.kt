package io.dataloom.api.scheduling

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.ScheduleId

/**
 * Immutable request to cancel a previously scheduled synchronization.
 *
 * A [ScheduleCancellationRequest] identifies which scheduled entry should be
 * removed from the platform scheduler and provides the execution context for
 * the cancellation operation.
 *
 * ## Construction behaviour
 *
 * Construction does not cancel scheduled or running work. Cancellation is
 * performed by passing this request to [SchedulerProvider.cancel].
 *
 * ## Runtime cancellation semantics
 *
 * Cancellation of a scheduled entry does not automatically cancel a
 * synchronization workflow that has already started. Runtime workflow
 * cancellation semantics are deferred to a future issue.
 *
 * ## Equality
 *
 * Equality compares [id] and [context] by value.
 *
 * @param id required identifier of the schedule entry to cancel.
 * @param context required execution context for this cancellation request.
 */
public data class ScheduleCancellationRequest(
    /** Required identifier of the schedule entry to cancel. */
    public val id: ScheduleId,

    /** Required execution context for this cancellation request. */
    public val context: ExecutionContext,
)
