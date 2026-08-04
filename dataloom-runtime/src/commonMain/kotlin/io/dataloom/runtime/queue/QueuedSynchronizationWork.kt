package io.dataloom.runtime.queue

import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.SynchronizationProviderBindings
import io.dataloom.api.strategy.PersistedStrategyDecision

/**
 * Immutable work resolved from, or prepared for, one durable queue entry.
 *
 * [request], [bindings], and optional [strategyDecision] are preserved exactly.
 * A non-null strategy decision is the immutable identity selected before queue
 * admission. Retry, deferral, lease recovery, process restart, and later
 * configuration changes must not replace it.
 *
 * Construction performs no provider access, strategy evaluation, clock read,
 * identifier generation, queue transition, scheduling, or I/O.
 */
public class QueuedSynchronizationWork(
    /** Exact synchronization request carried by the queue entry. */
    public val request: SynchronizationRequest,

    /** Exact provider bindings required to execute the request. */
    public val bindings: SynchronizationProviderBindings,

    /** Optional immutable strategy decision associated with durable work. */
    public val strategyDecision: PersistedStrategyDecision? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QueuedSynchronizationWork) return false
        return request == other.request &&
            bindings == other.bindings &&
            strategyDecision == other.strategyDecision
    }

    override fun hashCode(): Int {
        var result = request.hashCode()
        result = 31 * result + bindings.hashCode()
        result = 31 * result + (strategyDecision?.hashCode() ?: 0)
        return result
    }

    /** Safe diagnostic representation that excludes payloads and strategy IDs. */
    override fun toString(): String =
        "QueuedSynchronizationWork(" +
            "workflowId=${request.workflowId.value}, " +
            "sessionId=${request.sessionId.value}, " +
            "direction=${request.direction}, " +
            "storageProviderId=${bindings.storageProviderId.value}, " +
            "transportProviderId=${bindings.transportProviderId.value}, " +
            "hasStrategyDecision=${strategyDecision != null}" +
            ")"
}
