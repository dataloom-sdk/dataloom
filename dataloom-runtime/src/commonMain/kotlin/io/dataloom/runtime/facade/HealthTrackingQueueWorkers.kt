package io.dataloom.runtime.facade

import io.dataloom.api.error.DataLoomError
import io.dataloom.runtime.observation.health.QueueWorkerHealthTracker
import io.dataloom.runtime.observation.health.QueueWorkerRunOutcome
import io.dataloom.runtime.queue.CircuitBreakerQueueProcessingResult
import io.dataloom.runtime.queue.QueueProcessingResult
import io.dataloom.runtime.worker.CircuitBreakerQueueWorkerRecoveryResult
import io.dataloom.runtime.worker.CircuitBreakerQueueWorkerRunResult
import io.dataloom.runtime.worker.QueueWorkerRunRequest
import io.dataloom.runtime.worker.QueueWorkerRunResult
import kotlin.coroutines.cancellation.CancellationException

/**
 * Returns a [DataLoomQueueWorker] that behaves exactly like this one and
 * additionally reports every run's start and end to [tracker].
 *
 * The result is returned to the caller unchanged, an exception is rethrown
 * unchanged, and nothing the tracker does can affect either. See
 * [QueueWorkerHealthTracker] for what this does and does not make visible.
 */
public fun DataLoomQueueWorker.withHealthTracking(tracker: QueueWorkerHealthTracker): DataLoomQueueWorker =
    HealthTrackingQueueWorker(this, tracker)

/** Circuit-aware counterpart of [withHealthTracking]. */
public fun DataLoomCircuitQueueWorker.withHealthTracking(tracker: QueueWorkerHealthTracker): DataLoomCircuitQueueWorker =
    HealthTrackingCircuitQueueWorker(this, tracker)

private class HealthTrackingQueueWorker(
    private val delegate: DataLoomQueueWorker,
    private val tracker: QueueWorkerHealthTracker,
) : DataLoomQueueWorker {
    override suspend fun run(request: QueueWorkerRunRequest): QueueWorkerRunResult =
        trackRun(tracker, { delegate.run(request) }) { result ->
            when (result) {
                is QueueWorkerRunResult.RecoveryFailed -> QueueWorkerRunOutcome.RECOVERY_FAILED to result.error
                is QueueWorkerRunResult.ProcessingFailed ->
                    QueueWorkerRunOutcome.PROCESSING_FAILED to result.processingResult.errorOrNull()
                is QueueWorkerRunResult.ProcessingCompleted ->
                    (if (result.processingResult is QueueProcessingResult.NoWork) {
                        QueueWorkerRunOutcome.COMPLETED_NO_WORK
                    } else {
                        QueueWorkerRunOutcome.COMPLETED_PROCESSED
                    }) to null
            }
        }
}

private class HealthTrackingCircuitQueueWorker(
    private val delegate: DataLoomCircuitQueueWorker,
    private val tracker: QueueWorkerHealthTracker,
) : DataLoomCircuitQueueWorker {
    override suspend fun run(request: QueueWorkerRunRequest): CircuitBreakerQueueWorkerRunResult =
        trackRun(tracker, { delegate.run(request) }) { result ->
            when (result) {
                is CircuitBreakerQueueWorkerRunResult.RecoveryStopped ->
                    QueueWorkerRunOutcome.RECOVERY_FAILED to result.recoveryResult.errorOrNull()
                is CircuitBreakerQueueWorkerRunResult.ProcessingStopped ->
                    QueueWorkerRunOutcome.PROCESSING_FAILED to result.processingResult.errorOrNull()
                is CircuitBreakerQueueWorkerRunResult.ProcessingCompleted ->
                    (if (result.processingResult is CircuitBreakerQueueProcessingResult.NoWork) {
                        QueueWorkerRunOutcome.COMPLETED_NO_WORK
                    } else {
                        QueueWorkerRunOutcome.COMPLETED_PROCESSED
                    }) to null
            }
        }
}

private suspend fun <R> trackRun(
    tracker: QueueWorkerHealthTracker,
    run: suspend () -> R,
    classify: (R) -> Pair<QueueWorkerRunOutcome, DataLoomError?>,
): R {
    tracker.runStarted()
    val result = try {
        run()
    } catch (cancelled: CancellationException) {
        tracker.runCancelled()
        throw cancelled
    } catch (unexpected: Exception) {
        tracker.runFinished(QueueWorkerRunOutcome.UNEXPECTED_EXCEPTION, failure = null)
        throw unexpected
    }
    val (outcome, error) = classify(result)
    tracker.runFinished(outcome, error)
    return result
}

private fun QueueProcessingResult.errorOrNull(): DataLoomError? = when (this) {
    is QueueProcessingResult.QueueProviderFailure -> error
    is QueueProcessingResult.QueueContractViolation -> error
    is QueueProcessingResult.NoWork, is QueueProcessingResult.Processed -> null
}

private fun CircuitBreakerQueueProcessingResult.errorOrNull(): DataLoomError? = when (this) {
    is CircuitBreakerQueueProcessingResult.ProviderFailure -> error
    is CircuitBreakerQueueProcessingResult.QueueContractViolation -> error
    is CircuitBreakerQueueProcessingResult.NoWork,
    is CircuitBreakerQueueProcessingResult.Processed,
    is CircuitBreakerQueueProcessingResult.PreExecutionStopped,
    is CircuitBreakerQueueProcessingResult.CircuitRecordingUnconfirmed,
    -> null
}

private fun CircuitBreakerQueueWorkerRecoveryResult.errorOrNull(): DataLoomError? = when (this) {
    is CircuitBreakerQueueWorkerRecoveryResult.ProviderFailure -> error
    is CircuitBreakerQueueWorkerRecoveryResult.NotRequested,
    is CircuitBreakerQueueWorkerRecoveryResult.Completed,
    is CircuitBreakerQueueWorkerRecoveryResult.PreExecutionStopped,
    is CircuitBreakerQueueWorkerRecoveryResult.CircuitRecordingUnconfirmed,
    -> null
}
