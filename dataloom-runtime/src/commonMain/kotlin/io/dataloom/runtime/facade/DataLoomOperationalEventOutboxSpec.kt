package io.dataloom.runtime.facade

import io.dataloom.api.operational.DurableOperationalEventOutbox
import io.dataloom.api.operational.OperationalEventOutboxScope
import io.dataloom.api.operational.OperationalEventOutboxState
import io.dataloom.api.state.DurableStateStore

/**
 * Application-owned configuration that turns on the durable operational-event
 * outbox bridge for synchronization events: every
 * [io.dataloom.api.synchronization.SynchronizationEvent] the DataLoom runtime
 * constructs and dispatches is translated into an
 * [io.dataloom.api.operational.OperationalEventEnvelope] by
 * [io.dataloom.runtime.observation.operational.SynchronizationOperationalEventBridge]
 * and durably appended to [DurableOperationalEventOutbox] -- for operator
 * visibility and debugging, never for replay, acknowledgement, or
 * synchronization continuation.
 *
 * ## Why this exists
 *
 * [DurableOperationalEventOutbox] has existed since `#320` with no real
 * caller -- see
 * [the operational envelope/redaction doc](https://github.com/dataloom-sdk/dataloom/blob/main/docs/api/operational-envelope-redaction.md)'s
 * former "No wired caller" note. This spec is the missing connection,
 * mirroring [DataLoomStrategyDiagnosticsSpec]'s own shape and purpose for a
 * different durable-state domain.
 *
 * When [DataLoomBuilder.operationalEventOutboxConfiguration] is not called,
 * behavior is unchanged from before this spec existed: no
 * [io.dataloom.api.operational.OperationalEventEnvelope] is ever constructed
 * or appended. Configuring this spec is also, by itself, enough to make
 * [DataLoomBuilder] build [io.dataloom.runtime.execution.lifecycle.DispatchingSynchronizationLifecycleEventEmitter]
 * even with zero [io.dataloom.api.observation.SynchronizationObserver]s
 * registered, since that emitter is both the sole constructor and the sole
 * dispatch point of every [io.dataloom.api.synchronization.SynchronizationEvent].
 *
 * ## Scope
 *
 * All events bridged through this spec share one [scope]. The outbox
 * primitive requires a caller to already know which
 * [OperationalEventOutboxScope] to read (see [DurableOperationalEventOutbox]'s
 * own "No enumeration across scopes" note), so a single well-known default
 * (`"synchronization-events"`) is supplied; an application separating streams
 * for its own reason may override it.
 *
 * @param store a real [DurableStateStore] for [DurableOperationalEventOutbox]
 *   to persist bridged [io.dataloom.api.operational.OperationalEventEnvelope]
 *   instances into. The application chooses the backing implementation (Room,
 *   in-memory, or its own) -- [DataLoomBuilder] does not select one.
 * @param scope the single [OperationalEventOutboxScope] every bridged
 *   synchronization event is appended under. Defaults to
 *   `OperationalEventOutboxScope("synchronization-events")`.
 * @param schemaVersion passed through to [DurableOperationalEventOutbox]'s own
 *   schema-version parameter.
 * @param maximumStateUpdateAttempts passed through to
 *   [DurableOperationalEventOutbox]'s own retry-bound parameter.
 */
public class DataLoomOperationalEventOutboxSpec(
    public val store: DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState>,
    public val scope: OperationalEventOutboxScope = OperationalEventOutboxScope(DEFAULT_SCOPE_VALUE),
    public val schemaVersion: Int = 1,
    public val maximumStateUpdateAttempts: Int = 8,
) {
    private companion object {
        const val DEFAULT_SCOPE_VALUE: String = "synchronization-events"
    }
}
