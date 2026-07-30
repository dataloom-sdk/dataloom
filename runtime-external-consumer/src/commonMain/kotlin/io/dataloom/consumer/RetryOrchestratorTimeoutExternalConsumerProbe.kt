package io.dataloom.consumer

import io.dataloom.api.retry.RetryPolicy
import io.dataloom.api.scheduling.ExistingSchedulePolicy
import io.dataloom.api.scheduling.ScheduleConstraints
import io.dataloom.api.scheduling.SchedulerProvider
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomClock
import io.dataloom.runtime.retry.RetryBudgetConfiguration
import io.dataloom.runtime.retry.RetryHintConfiguration
import io.dataloom.runtime.retry.RetrySchedulingConfiguration
import io.dataloom.runtime.retry.SynchronizationRetryOrchestrator

/** Compile-only coverage for the public retry-orchestrator timeout factory. */
internal fun compileRetryOrchestratorTimeoutConsumer(
    retryPolicy: RetryPolicy,
    schedulerProvider: SchedulerProvider?,
    clock: DataLoomClock,
): SynchronizationRetryOrchestrator =
    SynchronizationRetryOrchestrator.withSchedulerProviderTimeout(
        retryPolicy = retryPolicy,
        schedulerProvider = schedulerProvider,
        configuration = RetrySchedulingConfiguration(
            constraints = ScheduleConstraints(),
            existingSchedulePolicy = ExistingSchedulePolicy.REPLACE,
        ),
        clock = clock,
        schedulerProviderTimeout = SchedulingDelay(5_000L),
        budgetConfiguration = RetryBudgetConfiguration(
            maximumElapsedTime = SchedulingDelay(120_000L),
        ),
        hintConfiguration = RetryHintConfiguration(
            maximumHintDelay = SchedulingDelay(60_000L),
        ),
    )
