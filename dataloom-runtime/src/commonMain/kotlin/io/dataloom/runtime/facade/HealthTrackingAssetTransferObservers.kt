package io.dataloom.runtime.facade

import io.dataloom.assets.AssetTransferObserver
import io.dataloom.assets.AssetTransferOperation
import io.dataloom.assets.AssetTransferOutcome
import io.dataloom.assets.AssetTransferSessionId
import io.dataloom.runtime.observation.health.AssetTransferHealthTracker

/**
 * Returns an [AssetTransferObserver] that reports every outcome to [tracker]
 * and additionally forwards it, unchanged, to this observer (if non-null).
 *
 * Mirrors [withHealthTracking] for queue workers, adapted to
 * [io.dataloom.assets.AssetTransferEngine]'s single-observer constructor seam
 * rather than a wrappable worker interface: there is only ever one `observer`
 * slot, so composing health tracking with an already-configured
 * [io.dataloom.runtime.observation.operational.AssetTransferOperationalEventRecorder]
 * (or any other observer) needs this explicit combinator rather than nested
 * delegation through an interface.
 */
public fun AssetTransferObserver?.withAssetTransferHealthTracking(
    tracker: AssetTransferHealthTracker,
): AssetTransferObserver = HealthTrackingAssetTransferObserver(this, tracker)

private class HealthTrackingAssetTransferObserver(
    private val delegate: AssetTransferObserver?,
    private val tracker: AssetTransferHealthTracker,
) : AssetTransferObserver {
    override suspend fun onOutcome(
        sessionId: AssetTransferSessionId,
        operation: AssetTransferOperation,
        outcome: AssetTransferOutcome,
    ) {
        tracker.record(operation, outcome)
        delegate?.onOutcome(sessionId, operation, outcome)
    }
}
