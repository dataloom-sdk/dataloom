package io.dataloom.runtime.retry

import io.dataloom.api.identifier.ScheduleId
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.retry.RetryBudgetState
import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.synchronization.SynchronizationResult

/**
 * Immutable request for one scheduler-backed retry orchestration cycle.
 *
 * The original five-argument constructor is preserved. The six-argument
 * constructor accepts optional durable [RetryBudgetState] returned by a prior
 * successful scheduling cycle. Construction performs no policy evaluation,
 * clock read, provider call, or scheduling.
 */
public class SynchronizationRetryRequest(
    public val synchronizationRequest: SynchronizationRequest,
    public val synchronizationResult: SynchronizationResult,
    public val retryOperation: RetryOperation,
    public val retryAttempt: RetryAttempt,
    public val scheduleId: ScheduleId,
) {
    private var storedRetryBudgetState: RetryBudgetState? = null

    /** Durable state from the previously accepted retry, or null. */
    public val retryBudgetState: RetryBudgetState?
        get() = storedRetryBudgetState

    /** Creates a request carrying the exact previously accepted budget state. */
    public constructor(
        synchronizationRequest: SynchronizationRequest,
        synchronizationResult: SynchronizationResult,
        retryOperation: RetryOperation,
        retryAttempt: RetryAttempt,
        scheduleId: ScheduleId,
        retryBudgetState: RetryBudgetState?,
    ) : this(
        synchronizationRequest = synchronizationRequest,
        synchronizationResult = synchronizationResult,
        retryOperation = retryOperation,
        retryAttempt = retryAttempt,
        scheduleId = scheduleId,
    ) {
        storedRetryBudgetState = retryBudgetState
    }

    /** Safe diagnostic representation without payloads, credentials, or state details. */
    override fun toString(): String {
        val resultVariant = synchronizationResult::class.simpleName ?: "Unknown"
        return "SynchronizationRetryRequest(" +
            "sessionId=${synchronizationRequest.sessionId.value}, " +
            "resultVariant=$resultVariant, " +
            "retryOperation=$retryOperation, " +
            "retryAttempt=${retryAttempt.number}, " +
            "scheduleId=${scheduleId.value}, " +
            "budgeted=${storedRetryBudgetState != null}" +
            ")"
    }
}
