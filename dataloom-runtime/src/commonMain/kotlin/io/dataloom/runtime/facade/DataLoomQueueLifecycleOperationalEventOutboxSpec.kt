package io.dataloom.runtime.facade

import io.dataloom.api.operational.DurableOperationalEventOutbox
import io.dataloom.api.operational.OperationalEventOutboxScope
import io.dataloom.api.operational.OperationalEventOutboxState
import io.dataloom.api.state.DurableStateStore

/**
 * Application-owned configuration that turns on the durable operational-event
 * outbox bridge for queue lifecycle: every already-persisted durable queue-
 * entry state transition
 * [io.dataloom.runtime.queue.DurableQueueExecutionProcessor] computes and
 * commits during a queue-worker processing cycle (see
 * [io.dataloom.runtime.queue.QueueEntryTransitionObserver]) is also
 * translated into an [io.dataloom.api.operational.OperationalEventEnvelope]
 * by
 * [io.dataloom.runtime.observation.operational.QueueLifecycleOperationalEventBridge]
 * and durably appended to [DurableOperationalEventOutbox] -- for operator
 * visibility and debugging, never for replay, acknowledgement, or queue
 * continuation.
 *
 * ## Why this is a fourth, separate spec rather than extending an existing one
 *
 * Applying the exact same three questions
 * [DataLoomRetryCircuitAdministrationOperationalEventOutboxSpec]'s and
 * [DataLoomStrategyDecisionOperationalEventOutboxSpec]'s own class docs
 * already ask of a candidate shared spec:
 *
 * - **Does this domain have its own correlation identity?** Yes -- the
 *   `(entryId, leaseId)` pair
 *   [io.dataloom.runtime.observation.operational.QueueLifecycleOperationalEventBridge]
 *   derives [io.dataloom.api.operational.OperationalEventEnvelope.id] from --
 *   an identifier space distinct from
 *   [io.dataloom.api.synchronization.SynchronizationEvent.id], the retry/
 *   circuit `commandId` spaces, and
 *   [io.dataloom.api.strategy.StrategyDecisionId].
 * - **Is it independently configurable from the other bridged domains?**
 *   Yes. A queue-entry transition is only ever witnessed by this bridge's
 *   [io.dataloom.runtime.queue.QueueEntryTransitionObserver] hook when
 *   [DataLoomBuilder.queueWorkerConfiguration] is also configured -- see
 *   [io.dataloom.runtime.queue.DurableQueueExecutionProcessor]'s own class
 *   doc, the sole place [io.dataloom.runtime.queue.QueueEntryExecutionOutcome]
 *   is computed and a transition observer is ever invoked. This spec's
 *   recorder therefore reuses that already-computed, already-persisted
 *   outcome rather than building a second one from scratch, which means
 *   configuring this spec without also configuring
 *   [DataLoomBuilder.queueWorkerConfiguration] has no effect -- there is
 *   never a transition to bridge. This mirrors exactly how
 *   [DataLoomStrategyDecisionOperationalEventOutboxSpec] alone, without
 *   [DataLoomBuilder.strategyDiagnosticsConfiguration], has no effect either.
 * - **Would sharing a scope/name conflate unrelated subsystems?** Yes. Queue
 *   entry lifecycle is a semantically distinct subsystem from synchronization
 *   lifecycle events, retry/circuit administration commands, and strategy
 *   admission/execution decisions -- exactly the reasoning that already
 *   justified three prior separate specs.
 *
 * This intentionally covers only [DataLoomBuilder.queueWorkerConfiguration]'s
 * non-circuit-aware queue worker --
 * [io.dataloom.runtime.queue.CircuitBreakerDurableQueueExecutionProcessor]
 * (backing [DataLoomBuilder.circuitQueueWorkerConfiguration]) already records
 * its own separate, circuit-specific outcome evidence
 * ([io.dataloom.runtime.queue.QueueCircuitOperationRecord]) and is out of
 * scope for this bridge, the same kind of scoping boundary already drawn
 * between retry/circuit *administration* (bridged) and ordinary retry/circuit
 * *execution* (not bridged by that spec).
 *
 * When [DataLoomBuilder.queueLifecycleOperationalEventOutboxConfiguration] is
 * not called, behavior is unchanged from before this spec existed: no
 * [io.dataloom.api.operational.OperationalEventEnvelope] is ever constructed
 * or appended for a queue-entry transition, and
 * [io.dataloom.runtime.queue.DurableQueueExecutionProcessor] receives a
 * `null` transition observer -- byte-for-byte the same behavior as before
 * this spec existed.
 *
 * ## Ordering relative to the real durable transition
 *
 * [io.dataloom.runtime.queue.DurableQueueExecutionProcessor] always persists
 * the real [io.dataloom.api.queue.QueueProvider] transition before notifying
 * this bridge's observer, never the reverse -- see
 * [io.dataloom.runtime.queue.QueueEntryTransitionObserver]'s own "Only
 * genuinely persisted transitions are reported" -- and a bridging failure
 * never changes or hides the real
 * [io.dataloom.runtime.queue.QueueProcessingResult] the processing cycle
 * returns, the same posture every other bridge in this codebase already
 * follows.
 *
 * ## Scope
 *
 * The outbox primitive requires a caller to already know which
 * [OperationalEventOutboxScope] to read (see [DurableOperationalEventOutbox]'s
 * own "No enumeration across scopes" note), so a single well-known default
 * (`"queue-lifecycle-events"`) is supplied; an application separating streams
 * for its own reason may override it -- including pointing it at the same
 * scope as any other operational-event-outbox spec to get one merged,
 * cross-subsystem chronological stream, since every bridge derives its own
 * [io.dataloom.api.operational.OperationalEventEnvelope.id] from a domain-
 * specific identifier space that cannot collide with another bridge's
 * identifiers.
 *
 * @param store a real [DurableStateStore] for [DurableOperationalEventOutbox]
 *   to persist bridged [io.dataloom.api.operational.OperationalEventEnvelope]
 *   instances into. The application chooses the backing implementation (Room,
 *   in-memory, or its own) -- [DataLoomBuilder] does not select one.
 * @param scope the single [OperationalEventOutboxScope] every bridged
 *   queue-entry transition is appended under. Defaults to
 *   `OperationalEventOutboxScope("queue-lifecycle-events")`.
 * @param schemaVersion passed through to [DurableOperationalEventOutbox]'s own
 *   schema-version parameter.
 * @param maximumStateUpdateAttempts passed through to
 *   [DurableOperationalEventOutbox]'s own retry-bound parameter.
 */
public class DataLoomQueueLifecycleOperationalEventOutboxSpec(
    public val store: DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState>,
    public val scope: OperationalEventOutboxScope = OperationalEventOutboxScope(DEFAULT_SCOPE_VALUE),
    public val schemaVersion: Int = 1,
    public val maximumStateUpdateAttempts: Int = 8,
) {
    private companion object {
        const val DEFAULT_SCOPE_VALUE: String = "queue-lifecycle-events"
    }
}
