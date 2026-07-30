package io.dataloom.consumer

import io.dataloom.api.identifier.ScheduleId
import io.dataloom.api.scheduling.ExistingSchedulePolicy
import io.dataloom.api.scheduling.ScheduleConstraints
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.runtime.worker.QueueWorkerConfiguration

/** Compile-only use of queue-worker scheduler timeout configuration. */
internal fun compileQueueWorkerTimeoutConfiguration(): QueueWorkerConfiguration =
    QueueWorkerConfiguration(
        scheduleId = ScheduleId("external-worker"),
        constraints = ScheduleConstraints(),
        existingSchedulePolicy = ExistingSchedulePolicy.REPLACE,
        continuationDelay = SchedulingDelay(1_000L),
        recoverExpiredLeasesBeforeProcessing = true,
        schedulerProviderTimeout = SchedulingDelay(5_000L),
    )
