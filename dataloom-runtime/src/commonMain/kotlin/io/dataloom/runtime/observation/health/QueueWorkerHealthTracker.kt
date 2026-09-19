package io.dataloom.runtime.observation.health

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** How one queue-worker `run` ended, as a closed vocabulary. */
public enum class QueueWorkerRunOutcome {
    /** The run completed and found the queue empty. */
    COMPLETED_NO_WORK,

    /** The run completed after processing entries. */
    COMPLETED_PROCESSED,

    /** Expired-lease recovery failed or was stopped, so processing did not run. */
    RECOVERY_FAILED,

    /** Processing failed or was stopped (queue-provider failure, contract violation, circuit stop). */
    PROCESSING_FAILED,

    /** `run` threw something other than cancellation (for example an invalid request). */
    UNEXPECTED_EXCEPTION,
}

/**
 * The closed, non-sensitive part of a [DataLoomError] a worker failure
 * retained. [DataLoomError.message] and [DataLoomError.cause] are never read,
 * so nothing sensitive can enter the tracker in the first place.
 */
public data class QueueWorkerFailureDescriptor(
    public val code: ErrorCode,
    public val category: ErrorCategory,
    public val severity: ErrorSeverity,
    public val recoverability: Recoverability,
)

internal fun DataLoomError.toWorkerFailureDescriptor(): QueueWorkerFailureDescriptor =
    QueueWorkerFailureDescriptor(code, category, severity, recoverability)

/**
 * Everything [QueueWorkerHealthTracker] currently knows about its worker.
 *
 * @param runsInFlight runs started and not yet finished (`0` = idle).
 * @param lastRunStartedAt when the most recent run started, `null` if none has.
 * @param lastCompletedRunAt when the most recent run *completed normally*
 *   (recovery and processing did not fail), `null` if none has.
 * @param lastEmptyQueueAt when the most recent run found the queue empty --
 *   the only evidence of a "drained" queue this tracker has; a run that
 *   processed entries may have left more behind.
 * @param lastRunOutcome how the most recent finished run ended; not updated by
 *   a cancelled run.
 * @param consecutiveFailedRuns failed runs since the last normal completion
 *   ([QueueWorkerRunOutcome.RECOVERY_FAILED], [QueueWorkerRunOutcome.PROCESSING_FAILED]
 *   and [QueueWorkerRunOutcome.UNEXPECTED_EXCEPTION] count; cancellation does not).
 * @param lastFailure the closed error vocabulary of the most recent failed
 *   run, when that run carried a [DataLoomError]; cleared by a normal completion.
 * @param lastEventAt the time of the most recent recorded event of any kind.
 * @param trackedSince when the tracker was created -- the earliest instant it
 *   can speak for.
 */
public data class QueueWorkerObservedState(
    public val runsInFlight: Int,
    public val lastRunStartedAt: DataLoomInstant?,
    public val lastCompletedRunAt: DataLoomInstant?,
    public val lastEmptyQueueAt: DataLoomInstant?,
    public val lastRunOutcome: QueueWorkerRunOutcome?,
    public val consecutiveFailedRuns: Int,
    public val lastFailure: QueueWorkerFailureDescriptor?,
    public val lastEventAt: DataLoomInstant?,
    public val trackedSince: DataLoomInstant,
)

/**
 * The synchronous health read path for a queue worker.
 *
 * The workers' own state is not queryable -- a run is a suspending call that
 * returns a sealed result -- so this tracker is fed by wrapping the worker
 * (`DataLoomQueueWorker.withHealthTracking(tracker)` /
 * `DataLoomCircuitQueueWorker.withHealthTracking(tracker)`, or
 * `DataLoomBuilder.queueWorkerHealthTracker(tracker)`), which reports every
 * run's start and end here. [snapshot] then returns the accumulated state
 * without suspending, doing I/O or touching the worker.
 *
 * ## The guarantee -- and its limit
 *
 * Because the tracker sees every run made *through the wrapper*, its counters
 * are exact for that wrapper in this process: `runsInFlight` is not a cache of
 * something elsewhere, it *is* the wrapper's bookkeeping. What it cannot see:
 * runs made by another process sharing the queue, and runs made directly on an
 * unwrapped coordinator. A tracker that has never seen a run reports exactly
 * that (`lastRunStartedAt == null`), which is *not* evidence the queue is
 * empty.
 *
 * The tracker never decides whether the worker is healthy; thresholds and the
 * verdict are applied by [dataLoomHealthSnapshot]. Thread-safe: state is held
 * in one atomically updated flow value.
 *
 * @param clock timestamps every recorded event.
 */
public class QueueWorkerHealthTracker(
    private val clock: DataLoomClock,
) {
    private val state = MutableStateFlow(
        QueueWorkerObservedState(
            runsInFlight = 0,
            lastRunStartedAt = null,
            lastCompletedRunAt = null,
            lastEmptyQueueAt = null,
            lastRunOutcome = null,
            consecutiveFailedRuns = 0,
            lastFailure = null,
            lastEventAt = null,
            trackedSince = clock.now(),
        ),
    )

    /** The accumulated state. Synchronous and side-effect free. */
    public fun snapshot(): QueueWorkerObservedState = state.value

    internal fun runStarted() {
        val now = clock.now()
        state.update { it.copy(runsInFlight = it.runsInFlight + 1, lastRunStartedAt = now, lastEventAt = now) }
    }

    internal fun runFinished(outcome: QueueWorkerRunOutcome, failure: DataLoomError?) {
        val now = clock.now()
        val failed = outcome == QueueWorkerRunOutcome.RECOVERY_FAILED ||
            outcome == QueueWorkerRunOutcome.PROCESSING_FAILED ||
            outcome == QueueWorkerRunOutcome.UNEXPECTED_EXCEPTION
        val descriptor = failure?.toWorkerFailureDescriptor()
        state.update { current ->
            current.copy(
                runsInFlight = maxOf(0, current.runsInFlight - 1),
                lastRunOutcome = outcome,
                lastEventAt = now,
                consecutiveFailedRuns = if (failed) current.consecutiveFailedRuns + 1 else 0,
                lastFailure = if (failed) descriptor else null,
                lastCompletedRunAt = if (failed) current.lastCompletedRunAt else now,
                lastEmptyQueueAt = if (outcome == QueueWorkerRunOutcome.COMPLETED_NO_WORK) now else current.lastEmptyQueueAt,
            )
        }
    }

    /** A run ended by cancellation: it is no longer in flight, and nothing else about the worker changed. */
    internal fun runCancelled() {
        val now = clock.now()
        state.update { it.copy(runsInFlight = maxOf(0, it.runsInFlight - 1), lastEventAt = now) }
    }
}
