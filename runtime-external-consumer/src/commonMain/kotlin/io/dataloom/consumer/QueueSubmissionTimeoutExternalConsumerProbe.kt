package io.dataloom.consumer

import io.dataloom.api.queue.QueueProvider
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomClock
import io.dataloom.runtime.facade.DataLoomBuilder
import io.dataloom.runtime.facade.DataLoomQueueSubmissionSpec
import io.dataloom.runtime.submission.DataLoomQueueSubmission
import io.dataloom.runtime.submission.QueueSubmissionProviderTimeoutRuntime
import io.dataloom.runtime.submission.QueuedSynchronizationWorkEncoder

/** Proves the historical direct queue-submission specification remains available. */
public fun directQueueSubmissionSpec(
    encoder: QueuedSynchronizationWorkEncoder,
): DataLoomQueueSubmissionSpec = DataLoomQueueSubmissionSpec(encoder)

/** Proves the timeout-enabled queue-submission specification is consumable. */
public fun timeoutQueueSubmissionSpec(
    encoder: QueuedSynchronizationWorkEncoder,
): DataLoomQueueSubmissionSpec = DataLoomQueueSubmissionSpec(
    encoder = encoder,
    queueProviderTimeout = SchedulingDelay(5_000L),
)

/** Proves builder configuration remains platform-neutral. */
public fun configureQueueSubmissionTimeout(
    builder: DataLoomBuilder,
    encoder: QueuedSynchronizationWorkEncoder,
): DataLoomBuilder = builder.queueSubmissionConfiguration(
    DataLoomQueueSubmissionSpec(
        encoder = encoder,
        queueProviderTimeout = SchedulingDelay(5_000L),
    ),
)

/** Proves standalone protected submission assembly is public and KMP-safe. */
public fun protectedQueueSubmission(
    queueProvider: QueueProvider,
    encoder: QueuedSynchronizationWorkEncoder,
    clock: DataLoomClock,
): DataLoomQueueSubmission = QueueSubmissionProviderTimeoutRuntime.create(
    queueProvider = queueProvider,
    encoder = encoder,
    clock = clock,
    queueProviderTimeout = SchedulingDelay(5_000L),
)
