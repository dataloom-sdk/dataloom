package io.dataloom.consumer

import io.dataloom.api.scheduling.ScheduleRequest
import io.dataloom.api.scheduling.SchedulerProvider
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomClock
import io.dataloom.runtime.retry.CoroutineRetryTimeoutExecutor
import io.dataloom.runtime.retry.RetryTimeoutConfiguration
import io.dataloom.runtime.retry.RetryTimeoutCoordinator
import io.dataloom.runtime.retry.RetryTimeoutExecutionRequest
import io.dataloom.runtime.retry.RetryTimeoutExecutionResult
import io.dataloom.runtime.retry.RetryTimeoutKind
import io.dataloom.runtime.retry.TimeoutEnforcingSchedulerProvider

/** Compile-only use of production coroutine timeout enforcement from an external module. */
internal suspend fun compileCoroutineTimeoutConsumer(
    schedulerProvider: SchedulerProvider,
    clock: DataLoomClock,
    scheduleRequest: ScheduleRequest,
): RetryTimeoutExecutionResult<String> {
    val executor = CoroutineRetryTimeoutExecutor()
    val coordinator = RetryTimeoutCoordinator(
        configuration = RetryTimeoutConfiguration(
            providerTimeout = SchedulingDelay(5_000L),
        ),
        clock = clock,
        executor = executor,
    )
    val protectedScheduler = TimeoutEnforcingSchedulerProvider(
        delegate = schedulerProvider,
        timeoutCoordinator = coordinator,
    )

    protectedScheduler.descriptor
    protectedScheduler.schedule(scheduleRequest)

    return executor.execute(
        RetryTimeoutExecutionRequest(
            kind = RetryTimeoutKind.PROVIDER,
            timeout = SchedulingDelay(1_000L),
        ),
    ) {
        "completed"
    }
}
