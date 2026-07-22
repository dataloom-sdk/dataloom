package io.dataloom.api.scheduling

/**
 * Closed set of policies for handling a [ScheduleRequest] when a schedule
 * with the same [io.dataloom.api.identifier.ScheduleId] already exists in the
 * platform scheduler.
 *
 * The concrete [SchedulerProvider] implementation is responsible for mapping
 * these semantics to the underlying platform scheduler. This type does not
 * implement replacement or retention behavior itself.
 *
 * Enum ordinals are not a compatibility contract and must not be persisted.
 */
public enum class ExistingSchedulePolicy {
    /**
     * Retain an existing schedule with the same
     * [io.dataloom.api.identifier.ScheduleId].
     *
     * When the platform scheduler already holds a schedule with the same
     * identifier, the new request is ignored and the existing schedule
     * remains unchanged.
     */
    KEEP,

    /**
     * Replace an existing schedule with the same
     * [io.dataloom.api.identifier.ScheduleId].
     *
     * When the platform scheduler already holds a schedule with the same
     * identifier, it is removed and the new schedule request is registered
     * in its place.
     */
    REPLACE,
}
