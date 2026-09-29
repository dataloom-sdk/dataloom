package io.dataloom.runtime.facade

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.IdentifierGenerator
import io.dataloom.api.identifier.QueueConsumerId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.lifecycle.AppLifecycleObservationException
import io.dataloom.api.lifecycle.AppLifecycleProvider
import io.dataloom.api.lifecycle.AppLifecycleState
import io.dataloom.api.queue.ExpiredLeaseRecoveryRequest
import io.dataloom.api.queue.QueueAcquireRequest
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.queue.QueueProcessingRequest
import io.dataloom.runtime.worker.QueueWorkerRunRequest
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Internal [DataLoomLifecycleDrain] assembled by [DataLoomBuilder].
 *
 * The drain owns no scope: each drain is a child of the [run] caller's
 * coroutine, so the caller's cancellation is the only way one is stopped.
 *
 * [drainGuard] is the single source of truth for "a drain is in flight". It is
 * taken before a drain is decided on and released by the drain job's own
 * completion handler, which also runs when the job is cancelled before it
 * starts, so a cancelled collector cannot leave it held. [lastDrainStartedAt]
 * is only read and written by the holder of the guard.
 *
 * @param runWorker runs the queue worker once for a request. Callers pass the
 *   fully assembled, health-tracked worker; its result is discarded because
 *   the wrappers around it already record every outcome.
 * @param recoverExpiredLeases whether the worker configuration requires a
 *   recovery request alongside the processing request.
 */
internal class DefaultDataLoomLifecycleDrain(
    private val lifecycleProvider: AppLifecycleProvider,
    private val consumerId: QueueConsumerId,
    private val policy: LifecycleDrainPolicy,
    private val clock: DataLoomClock,
    private val leaseIds: IdentifierGenerator<QueueLeaseId>,
    private val recoverExpiredLeases: Boolean,
    private val runWorker: suspend (QueueWorkerRunRequest) -> Unit,
) : DataLoomLifecycleDrain {

    private val drainGuard = Mutex()
    private var lastDrainStartedAt: DataLoomInstant? = null

    override suspend fun run(): LifecycleDrainEnd = coroutineScope {
        try {
            // The first emission is the state at collection start, not a
            // transition. conflate() keeps a provider that buffers from
            // queueing unboundedly behind a slow collector.
            lifecycleProvider.states().drop(1).conflate().collect { state -> onState(state) }
            LifecycleDrainEnd.StreamCompleted
        } catch (failure: AppLifecycleObservationException) {
            LifecycleDrainEnd.ObservationFailed(failure.error)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unexpected: Exception) {
            LifecycleDrainEnd.ObservationFailed(LifecycleDrainObservationError)
        }
    }

    private fun CoroutineScope.onState(state: AppLifecycleState) {
        if (state !in policy.triggers) return
        // Coalesced: a drain is already in flight.
        if (!drainGuard.tryLock()) return
        val job: Job? = try {
            val now = clock.now()
            if (isWithinMinimumInterval(now)) {
                null
            } else {
                lastDrainStartedAt = now
                launch { drain(now) }
            }
        } catch (cancelled: CancellationException) {
            drainGuard.unlock()
            throw cancelled
        } catch (unexpected: Exception) {
            null
        }
        if (job == null) {
            drainGuard.unlock()
        } else {
            job.invokeOnCompletion { drainGuard.unlock() }
        }
    }

    private fun isWithinMinimumInterval(now: DataLoomInstant): Boolean {
        val last = lastDrainStartedAt ?: return false
        val elapsed = now.epochMilliseconds - last.epochMilliseconds
        // A clock that moved backwards (negative elapsed) must not block drains.
        return elapsed >= 0L && elapsed < policy.minimumInterval.milliseconds
    }

    private suspend fun drain(now: DataLoomInstant) {
        try {
            runWorker(buildRequest(now))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Recorded by the worker's own health tracking when configured;
            // a drain failure must never end the collection.
        }
    }

    private fun buildRequest(now: DataLoomInstant): QueueWorkerRunRequest = QueueWorkerRunRequest(
        processingRequest = QueueProcessingRequest(
            acquireRequest = QueueAcquireRequest(
                consumerId = consumerId,
                leaseId = leaseIds.generate(),
                acquiredAt = now,
                leaseExpiresAt = DataLoomInstant(now.epochMilliseconds + policy.leaseDuration.milliseconds),
                maxEntries = policy.maxEntriesPerDrain,
            ),
        ),
        recoveryRequest = if (recoverExpiredLeases) ExpiredLeaseRecoveryRequest(currentTime = now) else null,
    )
}

/** Sanitized error for a lifecycle stream that failed without the contract's exception. */
private object LifecycleDrainObservationError : DataLoomError {
    override val code: ErrorCode = ErrorCode("LIFECYCLE_DRAIN_OBSERVATION_FAILED")
    override val category: ErrorCategory = ErrorCategory.PROVIDER
    override val severity: ErrorSeverity = ErrorSeverity.ERROR
    override val recoverability: Recoverability = Recoverability.RECOVERABLE
    override val message: String = "The application lifecycle stream failed while observing state."
    override val cause: Throwable? = null
}
