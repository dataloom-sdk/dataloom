package io.dataloom.runtime.facade

import io.dataloom.api.operational.DurableOperationalEventOutbox
import io.dataloom.api.operational.OperationalEventOutboxScope
import io.dataloom.api.operational.OperationalEventOutboxState
import io.dataloom.api.state.DurableStateStore

/**
 * Application-owned configuration that turns on the durable operational-event
 * outbox bridge for strategy-decision diagnostics: every
 * [io.dataloom.api.strategy.StrategyDecisionEvent]
 * [io.dataloom.runtime.strategy.StrategySynchronizationExecutionCoordinator]
 * already constructs and durably records via
 * [io.dataloom.api.strategy.DurableStrategyDecisionEventLog] is also
 * translated into an [io.dataloom.api.operational.OperationalEventEnvelope] by
 * [io.dataloom.runtime.observation.operational.StrategyDecisionOperationalEventBridge]
 * and durably appended to [DurableOperationalEventOutbox] -- for operator
 * visibility and debugging, never for replay, acknowledgement, or strategy
 * continuation.
 *
 * ## Why this is a third, separate spec rather than extending
 * [DataLoomOperationalEventOutboxSpec] or
 * [DataLoomRetryCircuitAdministrationOperationalEventOutboxSpec]
 *
 * Applying the exact same three questions
 * [DataLoomRetryCircuitAdministrationOperationalEventOutboxSpec]'s own class
 * doc already asks of a candidate shared spec:
 *
 * - **Does this domain have its own correlation identity?** Yes --
 *   [io.dataloom.api.strategy.StrategyDecisionId] -- but it is a single,
 *   independent identifier space, distinct from both
 *   [io.dataloom.api.synchronization.SynchronizationEvent.id] and the
 *   retry/circuit `commandId` spaces the other two bridges key off of.
 * - **Is it independently configurable from the other bridged domains?**
 *   Partially. Unlike synchronization events (always constructed once any
 *   observer or this bridge is configured) and retry/circuit administration
 *   (always durably recorded once
 *   [DataLoomBuilder.retryAdministrationConfiguration]/
 *   [DataLoomBuilder.circuitAdministrationConfiguration] is configured), a
 *   [io.dataloom.api.strategy.StrategyDecisionEvent] is only ever constructed
 *   at all when [DataLoomBuilder.strategyDiagnosticsConfiguration] is also
 *   configured -- see
 *   [io.dataloom.runtime.strategy.StrategySynchronizationExecutionCoordinator]'s
 *   `recordDecisionEvent`, the sole place the event is built, guarded by its
 *   own `strategyDecisionEventLog` collaborator. This spec's bridge therefore
 *   reuses that already-constructed event rather than building a second one
 *   from scratch (see the bridge's own class doc for why re-deriving it would
 *   be redundant, not additive), which means configuring this spec without
 *   also configuring [DataLoomBuilder.strategyDiagnosticsConfiguration] has no
 *   effect -- there is never an event to bridge. This mirrors exactly how
 *   [DataLoomRetryCircuitAdministrationOperationalEventOutboxSpec] alone,
 *   without `retryAdministrationConfiguration`/`circuitAdministrationConfiguration`,
 *   has no effect either -- an operational-event-outbox spec only ever bridges
 *   a domain result that some other, separately-configured capability already
 *   produces.
 * - **Would sharing a scope/name conflate unrelated subsystems?** Yes.
 *   Strategy admission/execution decisions are a semantically distinct
 *   subsystem from both synchronization lifecycle events and retry/circuit
 *   administration commands, exactly the reasoning that already justified a
 *   second, separate spec for retry/circuit administration rather than
 *   folding it into the synchronization-events one.
 *
 * When [DataLoomBuilder.strategyDecisionOperationalEventOutboxConfiguration]
 * is not called, behavior is unchanged from before this spec existed: no
 * [io.dataloom.api.operational.OperationalEventEnvelope] is ever constructed
 * or appended for a strategy decision.
 *
 * ## Ordering relative to the durable diagnostics log
 *
 * [io.dataloom.runtime.strategy.StrategySynchronizationExecutionCoordinator]
 * always calls
 * [io.dataloom.api.strategy.DurableStrategyDecisionEventLog.record] before
 * bridging into this outbox, never the reverse, and a bridging failure never
 * changes or hides the real [io.dataloom.runtime.strategy.StrategySynchronizationExecutionResult]
 * the coordinator returns -- the same posture every other bridge in this
 * codebase already follows.
 *
 * ## Scope
 *
 * The outbox primitive requires a caller to already know which
 * [OperationalEventOutboxScope] to read (see [DurableOperationalEventOutbox]'s
 * own "No enumeration across scopes" note), so a single well-known default
 * (`"strategy-decision-events"`) is supplied; an application separating
 * streams for its own reason may override it -- including pointing it at the
 * same scope as [DataLoomOperationalEventOutboxSpec]/
 * [DataLoomRetryCircuitAdministrationOperationalEventOutboxSpec] to get one
 * merged, cross-subsystem chronological stream, since every bridge derives
 * its own [io.dataloom.api.operational.OperationalEventEnvelope.id] from a
 * domain-specific identifier space that cannot collide with the other two
 * bridges' identifiers.
 *
 * @param store a real [DurableStateStore] for [DurableOperationalEventOutbox]
 *   to persist bridged [io.dataloom.api.operational.OperationalEventEnvelope]
 *   instances into. The application chooses the backing implementation (Room,
 *   in-memory, or its own) -- [DataLoomBuilder] does not select one.
 * @param scope the single [OperationalEventOutboxScope] every bridged
 *   strategy-decision event is appended under. Defaults to
 *   `OperationalEventOutboxScope("strategy-decision-events")`.
 * @param schemaVersion passed through to [DurableOperationalEventOutbox]'s own
 *   schema-version parameter.
 * @param maximumStateUpdateAttempts passed through to
 *   [DurableOperationalEventOutbox]'s own retry-bound parameter.
 */
public class DataLoomStrategyDecisionOperationalEventOutboxSpec(
    public val store: DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState>,
    public val scope: OperationalEventOutboxScope = OperationalEventOutboxScope(DEFAULT_SCOPE_VALUE),
    public val schemaVersion: Int = 1,
    public val maximumStateUpdateAttempts: Int = 8,
) {
    private companion object {
        const val DEFAULT_SCOPE_VALUE: String = "strategy-decision-events"
    }
}
