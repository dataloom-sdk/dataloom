package io.dataloom.runtime.retry

import io.dataloom.api.scheduling.ExistingSchedulePolicy
import io.dataloom.api.scheduling.ScheduleConstraints

/**
 * Immutable configuration governing how [SynchronizationRetryOrchestrator]
 * builds a [io.dataloom.api.scheduling.ScheduleRequest] when retry is
 * required.
 *
 * ## Purpose
 *
 * [RetrySchedulingConfiguration] bundles the execution constraints and the
 * existing-schedule policy that are forwarded verbatim to every
 * [io.dataloom.api.scheduling.ScheduleRequest] produced by
 * [SynchronizationRetryOrchestrator].
 *
 * ## Construction restrictions
 *
 * Construction does not schedule execution, read the clock, or generate an
 * identifier.
 *
 * ## Equality
 *
 * Equality compares [constraints] and [existingSchedulePolicy] by value.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 *
 * @param constraints execution constraints forwarded to every
 *   [io.dataloom.api.scheduling.ScheduleRequest]. Required.
 * @param existingSchedulePolicy policy applied when a schedule with the same
 *   [io.dataloom.api.identifier.ScheduleId] already exists. Required.
 */
public data class RetrySchedulingConfiguration(
    /**
     * Execution constraints forwarded to every
     * [io.dataloom.api.scheduling.ScheduleRequest].
     */
    public val constraints: ScheduleConstraints,

    /**
     * Policy applied when a schedule with the same
     * [io.dataloom.api.identifier.ScheduleId] already exists in the platform
     * scheduler.
     */
    public val existingSchedulePolicy: ExistingSchedulePolicy,
)
