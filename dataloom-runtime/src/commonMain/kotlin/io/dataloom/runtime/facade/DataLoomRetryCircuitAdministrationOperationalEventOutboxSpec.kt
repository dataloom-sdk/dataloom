package io.dataloom.runtime.facade

import io.dataloom.api.operational.DurableOperationalEventOutbox
import io.dataloom.api.operational.OperationalEventOutboxScope
import io.dataloom.api.operational.OperationalEventOutboxState
import io.dataloom.api.state.DurableStateStore

/**
 * Application-owned configuration that turns on the durable operational-event
 * outbox bridge for retry- and circuit-administration commands: every
 * terminal [io.dataloom.runtime.retry.RetryAdministrationResult] returned by
 * [DataLoomRetryAdministration.execute] and every terminal
 * [io.dataloom.runtime.retry.CircuitAdministrationResult] returned by
 * [DataLoomCircuitAdministration.execute] is translated into an
 * [io.dataloom.api.operational.OperationalEventEnvelope] by
 * [io.dataloom.runtime.observation.operational.RetryCircuitAdministrationOperationalEventBridge]
 * and durably appended to [DurableOperationalEventOutbox] -- for operator
 * visibility and debugging, never for replay, acknowledgement, or command
 * continuation.
 *
 * ## Why this is a second, separate spec rather than extending
 * [DataLoomOperationalEventOutboxSpec]
 *
 * [DataLoomOperationalEventOutboxSpec] is documented, named, and defaulted
 * entirely around [io.dataloom.api.synchronization.SynchronizationEvent] --
 * its own default scope is literally `"synchronization-events"`. Retry- and
 * circuit-administration commands are a structurally different subsystem:
 * they carry no [io.dataloom.api.identifier.CorrelationId]/
 * [io.dataloom.api.identifier.TraceId] of their own (this bridge derives
 * [io.dataloom.api.operational.OperationalEventEnvelope.correlationId] from
 * the command identifier instead -- see the bridge's own class doc), they are
 * available only when [DataLoomBuilder.retryAdministrationConfiguration]
 * and/or [DataLoomBuilder.circuitAdministrationConfiguration] are themselves
 * configured (independent of whether synchronization events are bridged at
 * all), and forcing both domains through one spec/one default scope would
 * conflate two independent subsystems' event streams under a name that
 * already means something narrower. A second, clearly-named opt-in
 * configuration point keeps both bridges genuinely independent: an
 * application may configure either, both, or neither.
 *
 * When [DataLoomBuilder.retryCircuitAdministrationOperationalEventOutboxConfiguration]
 * is not called, behavior is unchanged from before this spec existed: no
 * [io.dataloom.api.operational.OperationalEventEnvelope] is ever constructed
 * or appended for a retry- or circuit-administration command.
 *
 * ## One shared outbox, two independently-optional producers
 *
 * Retry-administration and circuit-administration bridging share this one
 * spec, one durable store, and one [scope] -- mirroring how
 * [io.dataloom.runtime.observation.retry.BoundedRetryCircuitTelemetry] already
 * treats both as one cohesive "retry/circuit administration" observability
 * domain. Configuring this spec alone does not by itself enable either
 * administration capability: an application must still separately call
 * [DataLoomBuilder.retryAdministrationConfiguration] and/or
 * [DataLoomBuilder.circuitAdministrationConfiguration] for
 * [DataLoomRetryAdministration]/[DataLoomCircuitAdministration] to exist at
 * all. Whichever of those two is configured has its results bridged; the
 * other is simply never invoked, exactly as before this spec existed.
 *
 * ## Scope
 *
 * The outbox primitive requires a caller to already know which
 * [OperationalEventOutboxScope] to read (see [DurableOperationalEventOutbox]'s
 * own "No enumeration across scopes" note), so a single well-known default
 * (`"retry-circuit-administration-events"`) is supplied; an application
 * separating streams for its own reason may override it. Because retry- and
 * circuit-administration command identifiers are independent spaces, the
 * bridge itself prefixes each derived
 * [io.dataloom.api.operational.OperationalEventEnvelope.id] with `retry.`/
 * `circuit.` so entries from both producers can never collide inside this one
 * shared scope.
 *
 * @param store a real [DurableStateStore] for [DurableOperationalEventOutbox]
 *   to persist bridged [io.dataloom.api.operational.OperationalEventEnvelope]
 *   instances into. The application chooses the backing implementation (Room,
 *   in-memory, or its own) -- [DataLoomBuilder] does not select one.
 * @param scope the single [OperationalEventOutboxScope] every bridged retry-
 *   or circuit-administration event is appended under. Defaults to
 *   `OperationalEventOutboxScope("retry-circuit-administration-events")`.
 * @param schemaVersion passed through to [DurableOperationalEventOutbox]'s own
 *   schema-version parameter.
 * @param maximumStateUpdateAttempts passed through to
 *   [DurableOperationalEventOutbox]'s own retry-bound parameter.
 */
public class DataLoomRetryCircuitAdministrationOperationalEventOutboxSpec(
    public val store: DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState>,
    public val scope: OperationalEventOutboxScope = OperationalEventOutboxScope(DEFAULT_SCOPE_VALUE),
    public val schemaVersion: Int = 1,
    public val maximumStateUpdateAttempts: Int = 8,
) {
    private companion object {
        const val DEFAULT_SCOPE_VALUE: String = "retry-circuit-administration-events"
    }
}
