package io.dataloom.runtime.retry

import io.dataloom.api.identifier.ScheduleId
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.synchronization.SynchronizationResult

/**
 * Immutable request carrying all context needed by
 * [SynchronizationRetryOrchestrator] to evaluate retry policy and schedule a
 * future synchronization attempt.
 *
 * ## Purpose
 *
 * [SynchronizationRetryOrchestrator.evaluateAndSchedule] receives a
 * [SynchronizationRetryRequest] containing the original synchronization
 * request, the terminal result that produced the failure, the logical retry
 * operation, the current attempt number, and the stable schedule identifier.
 *
 * ## Construction restrictions
 *
 * Construction performs no retry evaluation, no provider call, no scheduling,
 * no clock read, and generates no identifier.
 *
 * ## RetryAttempt semantics
 *
 * The supplied [retryAttempt] is passed to [io.dataloom.api.retry.RetryPolicy]
 * unchanged. [SynchronizationRetryOrchestrator] does not silently increment
 * the attempt number. Attempt advancement is the responsibility of the future
 * runtime or queue processor that creates the next
 * [SynchronizationRetryRequest].
 *
 * ## Sensitive-data restrictions
 *
 * The [toString] representation does not expose:
 * - payload bytes
 * - checkpoint tokens
 * - credentials
 * - authorization headers
 * - encryption keys
 * - personal data
 * - stack traces
 * - complete [SynchronizationResult] `toString()` output
 *
 * Safe diagnostics are limited to the synchronization request ID, retry
 * operation, retry attempt number, schedule ID, and result variant name.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 *
 * @param synchronizationRequest the original synchronization request that
 *   produced the terminal result. Required. Preserved unchanged.
 * @param synchronizationResult the terminal [SynchronizationResult] that
 *   triggered retry evaluation. Required. Preserved unchanged.
 * @param retryOperation the logical operation identifier passed to
 *   [io.dataloom.api.retry.RetryPolicy.evaluate]. Required.
 * @param retryAttempt the current retry attempt counter passed unchanged to
 *   [io.dataloom.api.retry.RetryPolicy.evaluate]. Required.
 * @param scheduleId the stable identifier supplied to
 *   [io.dataloom.api.scheduling.ScheduleRequest] when a retry is scheduled.
 *   Required.
 */
public class SynchronizationRetryRequest(
    /** The original synchronization request that produced the terminal result. */
    public val synchronizationRequest: SynchronizationRequest,

    /** The terminal [SynchronizationResult] that triggered retry evaluation. */
    public val synchronizationResult: SynchronizationResult,

    /**
     * The logical operation identifier passed to
     * [io.dataloom.api.retry.RetryPolicy.evaluate].
     */
    public val retryOperation: RetryOperation,

    /**
     * The current retry attempt counter passed unchanged to
     * [io.dataloom.api.retry.RetryPolicy.evaluate].
     *
     * Not incremented by the orchestrator.
     */
    public val retryAttempt: RetryAttempt,

    /**
     * The stable schedule identifier supplied to
     * [io.dataloom.api.scheduling.ScheduleRequest] when scheduling a retry.
     */
    public val scheduleId: ScheduleId,
) {
    /**
     * Returns a safe diagnostic representation that does not expose payload
     * bytes, checkpoint tokens, credentials, authorization headers, encryption
     * keys, personal data, stack traces, or the complete
     * [SynchronizationResult] `toString()` output.
     */
    override fun toString(): String {
        val requestId = synchronizationRequest.sessionId.value
        val resultVariant = synchronizationResult::class.simpleName ?: "Unknown"
        return "SynchronizationRetryRequest(" +
            "sessionId=$requestId, " +
            "resultVariant=$resultVariant, " +
            "retryOperation=$retryOperation, " +
            "retryAttempt=${retryAttempt.number}, " +
            "scheduleId=${scheduleId.value}" +
            ")"
    }
}
