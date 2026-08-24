package io.dataloom.runtime.facade

import io.dataloom.api.operational.DurableOperationalEventOutbox
import io.dataloom.api.operational.OperationalEventOutboxScope
import io.dataloom.api.operational.OperationalEventOutboxState
import io.dataloom.api.state.DurableStateStore

/**
 * Application-owned configuration that turns on the durable operational-event
 * outbox bridge for conflict resolution: every
 * [io.dataloom.api.conflict.UnresolvedConflictRecord]/
 * [io.dataloom.api.conflict.ResolvedConflictDecisionRecord]
 * [io.dataloom.runtime.conflict.DurableConflictDetectionCoordinator] already
 * constructs and durably records via
 * [io.dataloom.api.conflict.DurableUnresolvedConflictLog]/
 * [io.dataloom.api.conflict.DurableResolvedConflictDecisionLog] is also
 * translated into an [io.dataloom.api.operational.OperationalEventEnvelope] by
 * [io.dataloom.runtime.observation.operational.ConflictResolutionOperationalEventBridge]
 * and durably appended to [DurableOperationalEventOutbox] -- for operator
 * visibility and debugging, never for replay, decision continuation, or
 * application of a decision to storage.
 *
 * ## Why this is a fifth, separate spec rather than extending an existing one
 *
 * Applying the exact same three questions
 * [DataLoomRetryCircuitAdministrationOperationalEventOutboxSpec]'s own class
 * doc already asks of a candidate shared spec:
 *
 * - **Does this domain have its own correlation identity?** Yes --
 *   [io.dataloom.api.identifier.ConflictId] -- but it is a single,
 *   independent identifier space, distinct from every other bridged domain's
 *   own identifier space.
 * - **Is it independently configurable from the other bridged domains?**
 *   Partially. An [io.dataloom.api.conflict.UnresolvedConflictRecord]/
 *   [io.dataloom.api.conflict.ResolvedConflictDecisionRecord] is only ever
 *   constructed at all when [DataLoomBuilder.conflictDetectionConfiguration]
 *   is also configured -- see
 *   [io.dataloom.runtime.conflict.DurableConflictDetectionCoordinator]'s
 *   `recordUnresolved`/`recordResolved`, the sole places either record is
 *   built. This spec's bridge therefore reuses those already-constructed
 *   records rather than building new ones from scratch (see the bridge's own
 *   class doc for why re-deriving them from
 *   [io.dataloom.runtime.conflict.ConflictOrchestrationResult] would be
 *   redundant, not additive), which means configuring this spec without also
 *   configuring [DataLoomBuilder.conflictDetectionConfiguration] has no
 *   effect -- there is never a record to bridge. This mirrors exactly how
 *   [DataLoomStrategyDecisionOperationalEventOutboxSpec] alone, without
 *   [DataLoomBuilder.strategyDiagnosticsConfiguration], has no effect either.
 * - **Would sharing a scope/name conflate unrelated subsystems?** Yes.
 *   Conflict resolution is a semantically distinct subsystem from
 *   synchronization lifecycle events, retry/circuit administration commands,
 *   strategy-decision diagnostics, and queue lifecycle -- the same reasoning
 *   that already justified four separate specs for those four domains rather
 *   than folding any of them together.
 *
 * When [DataLoomBuilder.conflictResolutionOperationalEventOutboxConfiguration]
 * is not called, behavior is unchanged from before this spec existed: no
 * [io.dataloom.api.operational.OperationalEventEnvelope] is ever constructed
 * or appended for a conflict-resolution outcome.
 *
 * ## Ordering relative to the durable conflict logs
 *
 * [io.dataloom.runtime.conflict.DurableConflictDetectionCoordinator] always
 * calls [io.dataloom.api.conflict.DurableUnresolvedConflictLog.record]/
 * [io.dataloom.api.conflict.DurableResolvedConflictDecisionLog.record] before
 * bridging into this outbox, never the reverse, and a bridging failure never
 * changes or hides the real [io.dataloom.runtime.conflict.DurableConflictDetectionResult]
 * the coordinator returns -- the same posture every other bridge in this
 * codebase already follows.
 *
 * ## Scope
 *
 * The outbox primitive requires a caller to already know which
 * [OperationalEventOutboxScope] to read (see [DurableOperationalEventOutbox]'s
 * own "No enumeration across scopes" note), so a single well-known default
 * (`"conflict-resolution-events"`) is supplied; an application separating
 * streams for its own reason may override it -- including pointing it at the
 * same scope as any other operational-event-outbox spec to get one merged,
 * cross-subsystem chronological stream, since every bridge derives its own
 * [io.dataloom.api.operational.OperationalEventEnvelope.id] from a
 * domain-specific identifier space that cannot collide with another bridge's
 * identifiers.
 *
 * @param store a real [DurableStateStore] for [DurableOperationalEventOutbox]
 *   to persist bridged [io.dataloom.api.operational.OperationalEventEnvelope]
 *   instances into. The application chooses the backing implementation (Room,
 *   in-memory, or its own) -- [DataLoomBuilder] does not select one.
 * @param scope the single [OperationalEventOutboxScope] every bridged
 *   conflict-resolution outcome is appended under. Defaults to
 *   `OperationalEventOutboxScope("conflict-resolution-events")`.
 * @param schemaVersion passed through to [DurableOperationalEventOutbox]'s own
 *   schema-version parameter.
 * @param maximumStateUpdateAttempts passed through to
 *   [DurableOperationalEventOutbox]'s own retry-bound parameter.
 */
public class DataLoomConflictResolutionOperationalEventOutboxSpec(
    public val store: DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState>,
    public val scope: OperationalEventOutboxScope = OperationalEventOutboxScope(DEFAULT_SCOPE_VALUE),
    public val schemaVersion: Int = 1,
    public val maximumStateUpdateAttempts: Int = 8,
) {
    private companion object {
        const val DEFAULT_SCOPE_VALUE: String = "conflict-resolution-events"
    }
}
