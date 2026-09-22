package io.dataloom.runtime.facade

import io.dataloom.api.operational.DurableOperationalEventOutbox
import io.dataloom.api.operational.OperationalEventOutboxScope
import io.dataloom.api.operational.OperationalEventOutboxState
import io.dataloom.api.state.DurableStateStore

/**
 * Application-owned configuration that turns on the durable operational-event
 * outbox bridge for the queue worker's wake-up scheduling: every
 * [io.dataloom.runtime.worker.QueueWorkerSchedulingResult] other than
 * `NotRequired` that a [DataLoomQueueWorker] or [DataLoomCircuitQueueWorker]
 * run returns is translated into an
 * [io.dataloom.api.operational.OperationalEventEnvelope] by
 * [io.dataloom.runtime.observation.operational.QueueWorkerSchedulingOperationalEventBridge]
 * and durably appended to [DurableOperationalEventOutbox] -- for operator
 * visibility and debugging, never for replay-driven re-scheduling.
 *
 * ## What is (and is not) a scheduler event here
 *
 * The scheduler-side fact this runtime really produces is the result of a
 * worker's wake-up scheduling attempt: scheduled, scheduled through a circuit
 * gate, rejected by the gate, failed, or impossible because no scheduler
 * provider is configured. That is what is bridged. A synchronization retry's
 * scheduling is already covered by the `RetryScheduled` synchronization event
 * ([DataLoomOperationalEventOutboxSpec]) and is not duplicated; the runtime
 * has no other scheduler call (nothing calls `cancel`), so nothing else is
 * bridged or invented.
 *
 * ## How it attaches
 *
 * The bridge wraps the worker [DataLoomBuilder.build] returns and inspects
 * each run's returned scheduling result -- it adds no hook to the
 * coordinators. The run result is returned to the caller unchanged; a
 * bridging failure is swallowed (only cancellation propagates). Configuring
 * this spec without [DataLoomBuilder.queueWorkerConfiguration] or
 * [DataLoomBuilder.circuitQueueWorkerConfiguration] has no effect, and when
 * [DataLoomBuilder.queueWorkerSchedulingOperationalEventOutboxConfiguration]
 * is not called, behavior is unchanged from before this spec existed.
 *
 * ## Scope
 *
 * One [scope] holds every bridged wake-up event; default
 * `"queue-worker-scheduling-events"`.
 *
 * @param store a real [DurableStateStore] for [DurableOperationalEventOutbox]
 *   to persist bridged envelopes into.
 * @param scope the [OperationalEventOutboxScope] every bridged wake-up event is
 *   appended under.
 * @param schemaVersion passed through to [DurableOperationalEventOutbox]'s own
 *   schema-version parameter.
 * @param maximumStateUpdateAttempts passed through to
 *   [DurableOperationalEventOutbox]'s own retry-bound parameter.
 */
public class DataLoomQueueWorkerSchedulingOperationalEventOutboxSpec(
    public val store: DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState>,
    public val scope: OperationalEventOutboxScope = OperationalEventOutboxScope(DEFAULT_SCOPE_VALUE),
    public val schemaVersion: Int = DurableOperationalEventOutbox.CURRENT_SCHEMA_VERSION,
    public val maximumStateUpdateAttempts: Int = 8,
) {
    private companion object {
        const val DEFAULT_SCOPE_VALUE: String = "queue-worker-scheduling-events"
    }
}
