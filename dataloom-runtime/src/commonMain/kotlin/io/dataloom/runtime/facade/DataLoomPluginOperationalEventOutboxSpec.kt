package io.dataloom.runtime.facade

import io.dataloom.api.operational.DurableOperationalEventOutbox
import io.dataloom.api.operational.OperationalEventOutboxScope
import io.dataloom.api.operational.OperationalEventOutboxState
import io.dataloom.api.state.DurableStateStore

/**
 * Application-owned configuration that turns on the durable operational-event
 * outbox bridge for the plugin engine: every result [DataLoomPluginEngine]
 * returns for a lifecycle transition or a bounded invocation is translated
 * into an [io.dataloom.api.operational.OperationalEventEnvelope] and durably
 * appended to [DurableOperationalEventOutbox] for operator visibility and
 * audit.
 *
 * - [DataLoomPluginEngine.transition] results (`Allowed`, `Rejected`,
 *   `PermissionDenied`, `AuthorizationDenied`, `IncompatibleRuntime`) go
 *   through [io.dataloom.plugin.PluginLifecycleAdministrationOperationalEventBridge].
 * - [DataLoomPluginEngine.execute] results (`Completed`, `TimedOut`,
 *   `ConcurrencyLimitExceeded`, `NotActive`) go through
 *   [io.dataloom.plugin.PluginExecutionBoundsOperationalEventBridge]. The
 *   operation's returned value, and any exception it throws, never reach an
 *   envelope.
 *
 * ## Opt-in and inert when absent
 *
 * When [DataLoomBuilder.pluginOperationalEventOutboxConfiguration] is not
 * called, no envelope is ever constructed or appended and the plugin engine
 * behaves exactly as without this spec. Configuring this spec alone does not
 * create [DataLoom.pluginEngine]: [DataLoomBuilder.pluginConfiguration] is
 * still required, and without it nothing is recorded.
 *
 * ## Never alters the result
 *
 * Recording happens after the engine's real result exists. An append failure
 * (a store failure, contention, or an envelope validation failure) is
 * swallowed and never changes the returned result or throws; only cancellation
 * propagates. A refused or failed transition is recorded like a successful
 * one, since the audit trail is meant to answer "who tried what".
 *
 * ## Identity and ordering
 *
 * A transition's envelope id is derived from its
 * [io.dataloom.plugin.PluginLifecycleAdministrationCommandId], so re-running
 * the same command id is idempotent (a differing outcome for a reused id is
 * dropped by the outbox as a conflict). A bounded invocation has no natural
 * unique id, so the engine mints one from the plugin id, the runtime clock's
 * millisecond reading, and an in-process counter. That is unique within a
 * process and, across restarts, unless the same plugin records at the same
 * millisecond with the same counter value; the worst case is one audit record
 * dropped as an idempotent duplicate, never a corrupted entry.
 *
 * Envelopes carry no workflow id, so the outbox assigns them per-key sequence
 * numbers under its global ordering key, in append order. Both kinds of event
 * share one [scope] and therefore one ordered stream.
 *
 * Every completed invocation is recorded, which can be high-volume for a
 * busy plugin; use the outbox's retention and acknowledgement to bound it.
 *
 * @param store a real [DurableStateStore] for [DurableOperationalEventOutbox]
 *   to persist envelopes into. The application chooses the backing
 *   implementation.
 * @param scope the single [OperationalEventOutboxScope] every plugin event is
 *   appended under. Defaults to `OperationalEventOutboxScope("plugin-events")`.
 * @param schemaVersion passed through to [DurableOperationalEventOutbox].
 * @param maximumStateUpdateAttempts passed through to
 *   [DurableOperationalEventOutbox].
 */
public class DataLoomPluginOperationalEventOutboxSpec(
    public val store: DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState>,
    public val scope: OperationalEventOutboxScope = OperationalEventOutboxScope(DEFAULT_SCOPE_VALUE),
    public val schemaVersion: Int = DurableOperationalEventOutbox.CURRENT_SCHEMA_VERSION,
    public val maximumStateUpdateAttempts: Int = 8,
) {
    private companion object {
        const val DEFAULT_SCOPE_VALUE: String = "plugin-events"
    }
}
