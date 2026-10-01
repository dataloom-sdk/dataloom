package io.dataloom.runtime.facade

import io.dataloom.api.operational.DurableOperationalEventOutbox
import io.dataloom.api.operational.OperationalEventOutboxScope
import io.dataloom.api.operational.OperationalEventOutboxState
import io.dataloom.api.state.DurableStateStore

/**
 * Application-owned configuration that turns on the durable operational-event
 * outbox bridge for asset transfers: every outcome
 * [io.dataloom.assets.AssetTransferEngine.upload]/`.download`/`.cancel`
 * already computes and is about to return (see
 * [io.dataloom.assets.AssetTransferObserver]) is also translated into an
 * [io.dataloom.api.operational.OperationalEventEnvelope] by
 * [io.dataloom.runtime.observation.operational.AssetTransferOperationalEventBridge]
 * and durably appended to [DurableOperationalEventOutbox] -- for operator
 * visibility and debugging, never for replay, acknowledgement, or transfer
 * continuation.
 *
 * ## Why this is its own spec rather than extending an existing one
 *
 * Applying the same three questions every prior
 * `*OperationalEventOutboxSpec`'s own class doc already asks:
 *
 * - **Does this domain have its own correlation identity?** Yes --
 *   [io.dataloom.assets.AssetTransferSessionId], an identifier space distinct
 *   from every other bridged domain's own (queue entry/lease, policy
 *   decision, synchronization event, retry/circuit command id, strategy
 *   decision id, plugin invocation).
 * - **Is it independently configurable from the other bridged domains?**
 *   Yes. An [io.dataloom.assets.AssetTransferOutcome] is only ever witnessed
 *   by this bridge's [io.dataloom.assets.AssetTransferObserver] hook when
 *   [DataLoomBuilder.assetTransferConfiguration] is also configured -- the
 *   only place [io.dataloom.assets.AssetTransferEngine] is built and an
 *   observer is ever passed to it. Configuring this spec alone, without
 *   [DataLoomBuilder.assetTransferConfiguration], has no effect: there is
 *   never an outcome to bridge.
 * - **Would sharing a scope/name conflate unrelated subsystems?** Yes. Asset
 *   transfer is a semantically distinct subsystem from every other bridged
 *   domain.
 *
 * When [DataLoomBuilder.assetTransferOperationalEventOutboxConfiguration] is
 * not called, behavior is unchanged from before this spec existed: no
 * [io.dataloom.api.operational.OperationalEventEnvelope] is ever constructed
 * or appended for an asset transfer, and
 * [io.dataloom.assets.AssetTransferEngine] receives a `null` observer --
 * byte-for-byte the same behavior as before this spec existed.
 *
 * ## Scope
 *
 * The outbox primitive requires a caller to already know which
 * [OperationalEventOutboxScope] to read (see [DurableOperationalEventOutbox]'s
 * own "No enumeration across scopes" note), so a single well-known default
 * (`"asset-transfer-events"`) is supplied; an application separating streams
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
 * @param scope the single [OperationalEventOutboxScope] every bridged asset-
 *   transfer outcome is appended under. Defaults to
 *   `OperationalEventOutboxScope("asset-transfer-events")`.
 * @param schemaVersion passed through to [DurableOperationalEventOutbox]'s own
 *   schema-version parameter.
 * @param maximumStateUpdateAttempts passed through to
 *   [DurableOperationalEventOutbox]'s own retry-bound parameter.
 */
public class DataLoomAssetTransferOperationalEventOutboxSpec(
    public val store: DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState>,
    public val scope: OperationalEventOutboxScope = OperationalEventOutboxScope(DEFAULT_SCOPE_VALUE),
    public val schemaVersion: Int = DurableOperationalEventOutbox.CURRENT_SCHEMA_VERSION,
    public val maximumStateUpdateAttempts: Int = 8,
) {
    private companion object {
        const val DEFAULT_SCOPE_VALUE: String = "asset-transfer-events"
    }
}
