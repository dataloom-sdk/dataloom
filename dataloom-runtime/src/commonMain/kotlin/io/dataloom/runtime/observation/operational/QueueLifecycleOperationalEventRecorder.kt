package io.dataloom.runtime.observation.operational

import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.operational.DurableOperationalEventOutbox
import io.dataloom.api.operational.OperationalEventOutboxScope
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.time.DataLoomClock
import io.dataloom.runtime.queue.QueueEntryExecutionOutcome
import io.dataloom.runtime.queue.QueueEntryTransitionObserver
import kotlin.coroutines.cancellation.CancellationException

/**
 * [QueueEntryTransitionObserver] that bridges every already-persisted queue-
 * entry transition it is notified about into an
 * [io.dataloom.api.operational.OperationalEventEnvelope] via
 * [QueueLifecycleOperationalEventBridge] and durably appends it to [outbox].
 *
 * Constructed by
 * [io.dataloom.runtime.facade.DataLoomBuilder] only when
 * [io.dataloom.runtime.facade.DataLoomBuilder.queueLifecycleOperationalEventOutboxConfiguration]
 * is configured -- see that method and
 * [io.dataloom.runtime.facade.DataLoomQueueLifecycleOperationalEventOutboxSpec]
 * for the full opt-in contract. When no spec is configured, no recorder is
 * ever constructed and
 * [io.dataloom.runtime.queue.DurableQueueExecutionProcessor] receives a `null`
 * observer -- behavior unchanged from before this recorder existed.
 *
 * ## Failure isolation
 *
 * A durable side-record failure -- whether envelope construction throws, or
 * [DurableOperationalEventOutbox.append] itself returns
 * [io.dataloom.api.operational.DurableOperationalEventOutboxAppendOutcome.Conflict],
 * [io.dataloom.api.operational.DurableOperationalEventOutboxAppendOutcome.PersistenceFailure],
 * or
 * [io.dataloom.api.operational.DurableOperationalEventOutboxAppendOutcome.ContentionLimitReached]
 * -- must never change or hide the real
 * [io.dataloom.runtime.queue.QueueProcessingResult] already computed and
 * about to be returned to the queue-worker caller. This mirrors exactly
 * [io.dataloom.runtime.execution.lifecycle.DispatchingSynchronizationLifecycleEventEmitter]'s
 * own `recordOperationalEvent`. [CancellationException] still propagates
 * normally.
 *
 * ## Clock ownership
 *
 * [clock] is read exactly once per witnessed transition, only when
 * [QueueLifecycleOperationalEventBridge.toEnvelope] needs a fallback
 * `witnessedAt` -- see that object's class doc for exactly which outcome
 * variants need one. This mirrors
 * [io.dataloom.runtime.execution.lifecycle.DispatchingSynchronizationLifecycleEventEmitter]'s
 * own single clock read per constructed event.
 *
 * @param outbox the durable outbox every bridged envelope is appended to.
 * @param scope the [OperationalEventOutboxScope] every bridged envelope is
 *   appended under.
 * @param clock the wall-clock source used as a fallback `witnessedAt`.
 */
public class QueueLifecycleOperationalEventRecorder(
    private val outbox: DurableOperationalEventOutbox,
    private val scope: OperationalEventOutboxScope,
    private val clock: DataLoomClock,
) : QueueEntryTransitionObserver {

    override suspend fun onTransition(
        entry: QueueEntry,
        leaseId: QueueLeaseId,
        outcome: QueueEntryExecutionOutcome,
    ) {
        try {
            // Completed already carries its own occurredAt (completedAt), which
            // toEnvelope always prefers over witnessedAt -- see
            // QueueLifecycleOperationalEventBridge's own class doc. The clock is
            // read only for the four variants that actually need a fallback, so
            // a Completed transition never triggers a clock read at all.
            val witnessedAt = if (outcome is QueueEntryExecutionOutcome.Completed) {
                outcome.completedAt
            } else {
                clock.now()
            }
            val envelope = QueueLifecycleOperationalEventBridge.toEnvelope(
                entry = entry,
                leaseId = leaseId,
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
    override fun toString(): String = "QueueLifecycleOperationalEventRecorder(scope=$scope)"
}
