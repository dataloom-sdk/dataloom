package io.dataloom.runtime.submission

import io.dataloom.api.queue.QueueProvider
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomClock
import io.dataloom.runtime.retry.CoroutineRetryTimeoutExecutor
import io.dataloom.runtime.retry.RetryTimeoutConfiguration
import io.dataloom.runtime.retry.RetryTimeoutCoordinator
import io.dataloom.runtime.retry.TimeoutEnforcingQueueProvider

/**
 * Additive assembly for queue submission with a bounded provider timeout.
 *
 * The returned [DataLoomQueueSubmission] performs ordinary encoding and
 * structural validation first. Only its single `QueueProvider.enqueue` call is
 * routed through [TimeoutEnforcingQueueProvider].
 *
 * A timed-out enqueue is durably ambiguous: the provider may have committed
 * before cooperative cancellation was observed. The timeout therefore remains
 * `Recoverability.UNKNOWN`, no automatic replay occurs, and the exact stable
 * queue-entry identifier remains available in
 * [QueueSubmissionResult.QueueProviderFailure].
 *
 * Construction performs no encoder invocation, provider call, clock read,
 * timeout execution, identifier generation, or coroutine launch.
 */
public object QueueSubmissionProviderTimeoutRuntime {

    /**
     * Creates one timeout-protected queue-submission capability.
     *
     * [queueProviderTimeout] applies only to `QueueProvider.enqueue`; it is not
     * reused for queue-worker recovery, acquisition, transitions, scheduling,
     * retry policy, transport, storage, or workflow execution.
     */
    public fun create(
        queueProvider: QueueProvider,
        encoder: QueuedSynchronizationWorkEncoder,
        clock: DataLoomClock,
        queueProviderTimeout: SchedulingDelay,
    ): DataLoomQueueSubmission {
        val protectedQueueProvider = TimeoutEnforcingQueueProvider(
            delegate = queueProvider,
            timeoutCoordinator = RetryTimeoutCoordinator(
                configuration = RetryTimeoutConfiguration(
                    providerTimeout = queueProviderTimeout,
                ),
                clock = clock,
                executor = CoroutineRetryTimeoutExecutor(),
            ),
        )
        return DefaultDataLoomQueueSubmission(
            queueProvider = protectedQueueProvider,
            encoder = encoder,
        )
    }
}
