package io.dataloom.runtime.retry

import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.scheduling.ScheduleReceipt
import io.dataloom.api.scheduling.ScheduleRequest
import io.dataloom.api.scheduling.SchedulerProvider

/**
 * Applies circuit permission and outcome recording to one retry scheduling call.
 *
 * The selected [scope] must either be global/workflow scoped or identify the
 * exact scheduler provider. An operation-bearing scope must use
 * [SchedulerCircuitOperation.SCHEDULE]. No implicit scope derivation or fallback
 * is applied.
 */
public class CircuitBreakerRetrySchedulingAdapter(
    private val schedulerProvider: SchedulerProvider,
    private val providerOperationAdapter: CircuitBreakerProviderOperationAdapter,
    public val scope: CircuitBreakerScope,
) {
    init {
        require(scope.providerId == null || scope.providerId == schedulerProvider.descriptor.id) {
            "CircuitBreakerRetrySchedulingAdapter scope provider must match scheduler provider."
        }
        require(
            scope.operation == null ||
                scope.operation == SchedulerCircuitOperation.SCHEDULE.retryOperation,
        ) {
            "CircuitBreakerRetrySchedulingAdapter scope operation must be scheduler.schedule."
        }
    }

    /** Acquires circuit permission and invokes [SchedulerProvider.schedule] at most once. */
    public suspend fun schedule(
        request: ScheduleRequest,
    ): CircuitBreakerExecutionResult<ScheduleReceipt> = providerOperationAdapter.execute(scope) {
        schedulerProvider.schedule(request)
    }
}
