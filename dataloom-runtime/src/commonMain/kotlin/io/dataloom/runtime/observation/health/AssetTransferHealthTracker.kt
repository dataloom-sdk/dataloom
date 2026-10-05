package io.dataloom.runtime.observation.health

import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.assets.AssetTransferOperation
import io.dataloom.assets.AssetTransferOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Closed classification of one [AssetTransferOutcome], for [AssetTransferObservedState]. */
public enum class AssetTransferOutcomeKind {
    /** [AssetTransferOutcome.Completed]. */
    COMPLETED,

    /** [AssetTransferOutcome.Interrupted]. */
    INTERRUPTED,

    /** [AssetTransferOutcome.Failed]. */
    FAILED,

    /** [AssetTransferOutcome.Cancelled]. */
    CANCELLED,

    /** [AssetTransferOutcome.NotStarted]. */
    NOT_STARTED,

    /** [AssetTransferOutcome.SessionStoreFailure]. */
    SESSION_STORE_FAILURE,
}

internal fun AssetTransferOutcome.toHealthKind(): AssetTransferOutcomeKind = when (this) {
    is AssetTransferOutcome.Completed -> AssetTransferOutcomeKind.COMPLETED
    is AssetTransferOutcome.Interrupted -> AssetTransferOutcomeKind.INTERRUPTED
    is AssetTransferOutcome.Failed -> AssetTransferOutcomeKind.FAILED
    is AssetTransferOutcome.Cancelled -> AssetTransferOutcomeKind.CANCELLED
    is AssetTransferOutcome.NotStarted -> AssetTransferOutcomeKind.NOT_STARTED
    is AssetTransferOutcome.SessionStoreFailure -> AssetTransferOutcomeKind.SESSION_STORE_FAILURE
}

/**
 * Everything [AssetTransferHealthTracker] currently knows about the asset
 * transfer engine it observes.
 *
 * @param lastOutcome how the most recent observed call ended.
 * @param lastOperation which [io.dataloom.assets.AssetTransferEngine] call
 *   ([AssetTransferOperation.UPLOAD]/`.DOWNLOAD`/`.CANCEL`) produced [lastOutcome].
 * @param consecutiveSessionStoreFailures consecutive
 *   [AssetTransferOutcome.SessionStoreFailure] outcomes since the last
 *   non-`SessionStoreFailure` outcome -- see [AssetTransferHealthTracker] for
 *   why only this outcome is counted.
 * @param lastEventAt when the most recent outcome was recorded, `null` if none has.
 * @param trackedSince when the tracker was created.
 */
public data class AssetTransferObservedState(
    public val lastOutcome: AssetTransferOutcomeKind?,
    public val lastOperation: AssetTransferOperation?,
    public val consecutiveSessionStoreFailures: Int,
    public val lastEventAt: DataLoomInstant?,
    public val trackedSince: DataLoomInstant,
)

/**
 * The synchronous health read path for [io.dataloom.assets.AssetTransferEngine].
 *
 * Fed by wrapping the engine's optional
 * [io.dataloom.assets.AssetTransferObserver] seam --
 * `DataLoomBuilder.assetTransferHealthTracker(tracker)`, which composes this
 * tracker with any already-configured
 * [io.dataloom.runtime.observation.operational.AssetTransferOperationalEventRecorder]
 * via [io.dataloom.runtime.facade.withAssetTransferHealthTracking] -- the same
 * "wrap, never replace, the existing seam" posture
 * `DataLoomQueueWorker.withHealthTracking` already establishes for the queue
 * worker. [snapshot] then returns the accumulated state without suspending,
 * doing I/O, or touching the engine.
 *
 * ## Why only [AssetTransferOutcome.SessionStoreFailure] drives severity
 *
 * [io.dataloom.assets.AssetTransferEngine.upload]/`.download`/`.cancel` can
 * end in several outcomes that are *not* evidence the engine itself is
 * unhealthy: [AssetTransferOutcome.Failed] is reached for a
 * [io.dataloom.api.error.Recoverability.NON_RECOVERABLE] error, and that set
 * routinely includes caller input the engine correctly rejected (a quota
 * exceeded, a content-policy denial, a chunk that does not match its
 * manifest) -- counting those toward severity would flag the SDK as degraded
 * because an application tried to upload something too large, exactly the
 * false-positive [dataLoomHealthSnapshot]'s existing sections (provider
 * health, queue-worker failures) are careful to avoid by only counting
 * infrastructure-level failure. [AssetTransferOutcome.Interrupted] is
 * explicitly documented as expected and resumable ("the session is intact
 * and durable; calling the same operation again resumes it"), not a failure.
 * [AssetTransferOutcome.SessionStoreFailure] is the one outcome with no such
 * ambiguity: it means the engine's own durable session store threw
 * ([io.dataloom.assets.AssetTransferSessionStoreException]) before any
 * business decision could even be evaluated -- the asset-domain analogue of
 * `OUTBOX_LAST_CYCLE_READ_FAILED`'s "the outbox could not even read" finding.
 * Every other outcome kind is still recorded in [AssetTransferObservedState]
 * for diagnostics, exactly as `providerLifecycleState` is reported without
 * influencing [DataLoomHealthSnapshot.severity].
 *
 * Thread-safe: state is held in one atomically updated flow value.
 *
 * @param clock timestamps every recorded event.
 */
public class AssetTransferHealthTracker(
    private val clock: DataLoomClock,
) {
    private val state = MutableStateFlow(
        AssetTransferObservedState(
            lastOutcome = null,
            lastOperation = null,
            consecutiveSessionStoreFailures = 0,
            lastEventAt = null,
            trackedSince = clock.now(),
        ),
    )

    /** The accumulated state. Synchronous and side-effect free. */
    public fun snapshot(): AssetTransferObservedState = state.value

    /** Records one already-decided [outcome] of [operation]. Never suspends beyond the clock read. */
    internal fun record(operation: AssetTransferOperation, outcome: AssetTransferOutcome) {
        val kind = outcome.toHealthKind()
        val now = clock.now()
        state.update { current ->
            current.copy(
                lastOutcome = kind,
                lastOperation = operation,
                consecutiveSessionStoreFailures = if (kind == AssetTransferOutcomeKind.SESSION_STORE_FAILURE) {
                    current.consecutiveSessionStoreFailures + 1
                } else {
                    0
                },
                lastEventAt = now,
            )
        }
    }
}
