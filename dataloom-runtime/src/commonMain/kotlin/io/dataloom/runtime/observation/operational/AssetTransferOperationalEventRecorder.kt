package io.dataloom.runtime.observation.operational

import io.dataloom.api.operational.DurableOperationalEventOutbox
import io.dataloom.api.operational.OperationalEventOutboxScope
import io.dataloom.api.time.DataLoomClock
import io.dataloom.assets.AssetTransferObserver
import io.dataloom.assets.AssetTransferOperation
import io.dataloom.assets.AssetTransferOutcome
import io.dataloom.assets.AssetTransferSessionId
import kotlin.coroutines.cancellation.CancellationException

/**
 * [AssetTransferObserver] that bridges every [AssetTransferOutcome] it is
 * notified about into an
 * [io.dataloom.api.operational.OperationalEventEnvelope] via
 * [AssetTransferOperationalEventBridge] and durably appends it to [outbox].
 *
 * Constructed by
 * [io.dataloom.runtime.facade.DataLoomBuilder] only when both
 * [io.dataloom.runtime.facade.DataLoomBuilder.assetTransferConfiguration] and
 * [io.dataloom.runtime.facade.DataLoomBuilder.assetTransferOperationalEventOutboxConfiguration]
 * are configured -- see the latter and
 * [io.dataloom.runtime.facade.DataLoomAssetTransferOperationalEventOutboxSpec]
 * for the full opt-in contract. When no spec is configured, no recorder is
 * ever constructed and [io.dataloom.assets.AssetTransferEngine] receives a
 * `null` observer -- behavior unchanged from before this recorder existed.
 *
 * ## Failure isolation
 *
 * A durable side-record failure -- whether envelope construction throws, or
 * [DurableOperationalEventOutbox.append] itself returns
 * [io.dataloom.api.operational.DurableOperationalEventOutboxAppendOutcome.Conflict],
 * [io.dataloom.api.operational.DurableOperationalEventOutboxAppendOutcome.PersistenceFailure],
 * or
 * [io.dataloom.api.operational.DurableOperationalEventOutboxAppendOutcome.ContentionLimitReached]
 * -- must never change or hide the real [AssetTransferOutcome] the calling
 * [io.dataloom.assets.AssetTransferEngine] operation is about to return. This
 * mirrors exactly [QueueLifecycleOperationalEventRecorder]'s own posture.
 * [CancellationException] still propagates normally.
 *
 * ## Clock ownership
 *
 * [clock] is read exactly once per witnessed outcome -- [AssetTransferOutcome]
 * carries no timestamp of its own (unlike
 * [io.dataloom.runtime.queue.QueueEntryExecutionOutcome.Completed]), so every
 * outcome needs this fallback, unlike [QueueLifecycleOperationalEventRecorder]
 * which only reads its clock for four of five outcome variants.
 *
 * @param outbox the durable outbox every bridged envelope is appended to.
 * @param scope the [OperationalEventOutboxScope] every bridged envelope is
 *   appended under.
 * @param clock the wall-clock source read once per witnessed outcome.
 */
public class AssetTransferOperationalEventRecorder(
    private val outbox: DurableOperationalEventOutbox,
    private val scope: OperationalEventOutboxScope,
    private val clock: DataLoomClock,
) : AssetTransferObserver {

    override suspend fun onOutcome(
        sessionId: AssetTransferSessionId,
        operation: AssetTransferOperation,
        outcome: AssetTransferOutcome,
    ) {
        try {
            val witnessedAt = clock.now()
            val envelope = AssetTransferOperationalEventBridge.toEnvelope(
                sessionId = sessionId,
                operation = operation,
                outcome = outcome,
                witnessedAt = witnessedAt,
            )
            outbox.append(scope, envelope)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (ordinary: Exception) {
            // Intentionally swallowed -- see class doc "Failure isolation" above.
        }
    }

    /** Bounded diagnostic output that excludes outbox/clock implementation details. */
    override fun toString(): String = "AssetTransferOperationalEventRecorder(scope=$scope)"
}
