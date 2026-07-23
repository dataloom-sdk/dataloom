package io.dataloom.runtime.retry

/**
 * Canonical status values produced by [SynchronizationRetryOrchestrator].
 *
 * Each variant represents a distinct terminal outcome of a single
 * [SynchronizationRetryOrchestrator.evaluateAndSchedule] invocation.
 *
 * ## Ordinal stability
 *
 * Do not rely on enum ordinals for serialization or persistence. Ordinals may
 * change as variants are added or reordered. Use the variant names for any
 * durable representation.
 *
 * ## Coroutine cancellation
 *
 * [CancellationException][kotlin.coroutines.cancellation.CancellationException]
 * is never classified as a [RetryOrchestrationStatus]. Thrown cancellation
 * propagates normally out of the orchestrator.
 *
 * ## Unexpected exceptions
 *
 * Programming errors from [io.dataloom.api.retry.RetryPolicy] or
 * [io.dataloom.api.scheduling.SchedulerProvider] are not classified as a
 * status. They propagate normally.
 */
public enum class RetryOrchestrationStatus {

    /**
     * The [io.dataloom.api.synchronization.SynchronizationResult] contained
     * no retry-evaluable failure.
     *
     * Returned for [io.dataloom.api.synchronization.SynchronizationResult.Succeeded],
     * [io.dataloom.api.synchronization.SynchronizationResult.Skipped], and
     * [io.dataloom.api.synchronization.SynchronizationResult.Cancelled].
     *
     * No [io.dataloom.api.retry.RetryPolicy] invocation occurs.
     * No [io.dataloom.api.scheduling.SchedulerProvider] invocation occurs.
     */
    NOT_REQUIRED,

    /**
     * Retry evaluation completed but no decision requested another attempt.
     *
     * All [io.dataloom.api.retry.RetryDecision] values returned by
     * [io.dataloom.api.retry.RetryPolicy] were
     * [io.dataloom.api.retry.RetryDecision.Stop].
     *
     * No [io.dataloom.api.scheduling.SchedulerProvider] invocation occurs.
     */
    STOPPED,

    /**
     * Retry evaluation requested another attempt and
     * [io.dataloom.api.scheduling.SchedulerProvider] accepted the schedule.
     *
     * [io.dataloom.api.scheduling.SchedulerProvider.schedule] returned
     * [io.dataloom.api.provider.ProviderOperationResult.Success].
     */
    SCHEDULED,

    /**
     * Retry was requested but no [io.dataloom.api.scheduling.SchedulerProvider]
     * was supplied to [SynchronizationRetryOrchestrator].
     *
     * No scheduling operation was attempted.
     */
    SCHEDULER_NOT_CONFIGURED,

    /**
     * Retry was requested but
     * [io.dataloom.api.scheduling.SchedulerProvider.schedule] returned
     * [io.dataloom.api.provider.ProviderOperationResult.Failure].
     *
     * The canonical [io.dataloom.api.error.DataLoomError] from the provider
     * is preserved in [RetryOrchestrationResult.schedulerError].
     */
    SCHEDULER_FAILED,
}
