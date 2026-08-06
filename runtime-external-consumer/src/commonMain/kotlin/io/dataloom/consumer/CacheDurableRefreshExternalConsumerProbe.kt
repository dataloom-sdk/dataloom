package io.dataloom.consumer

import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.ScheduleId
import io.dataloom.api.strategy.StrategyOperationInput
import io.dataloom.runtime.strategy.StrategyCacheDurableRefreshResult
import io.dataloom.runtime.strategy.StrategyCacheServedWithDurableRefreshResult
import io.dataloom.runtime.strategy.StrategySynchronizationExecutionResult

/** Compile-only construction of caller-owned durable refresh identities. */
internal fun durableCacheRefreshInput(): StrategyOperationInput =
    StrategyOperationInput.CacheFirstDurableRefresh(
        queueEntryId = QueueEntryId("consumer-durable-refresh-entry"),
        scheduleId = ScheduleId("consumer-durable-refresh-schedule"),
    )

/** Compile-only inspection of every public durable refresh result branch. */
internal fun inspectDurableCacheRefresh(
    result: StrategySynchronizationExecutionResult,
): String = when (result) {
    is StrategyCacheServedWithDurableRefreshResult -> {
        result.evaluation.plan.id
        result.completedAt
        result.evaluatedCacheState
        result.freshness.cacheState
        result.dataOrigin
        when (val refresh = result.refresh) {
            is StrategyCacheDurableRefreshResult.Scheduled -> {
                refresh.queueAdmissionDisposition
                refresh.queueState
                refresh.receipt.id
            }
            is StrategyCacheDurableRefreshResult.AlreadyInProgress ->
                refresh.queueState
            is StrategyCacheDurableRefreshResult.AlreadyTerminal ->
                refresh.queueState
            is StrategyCacheDurableRefreshResult.IdentityConflict ->
                refresh.currentState
            is StrategyCacheDurableRefreshResult.QueueFailed ->
                refresh.error.code
            is StrategyCacheDurableRefreshResult.ScheduleFailed -> {
                refresh.queueAdmissionDisposition
                refresh.queueState
                refresh.error.code
            }
        }
        result.refresh.disposition.name
    }
    else -> result::class.simpleName ?: "UNKNOWN"
}
