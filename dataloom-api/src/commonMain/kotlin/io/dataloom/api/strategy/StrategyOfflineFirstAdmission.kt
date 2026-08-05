package io.dataloom.api.strategy

import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.storage.StorageProvider

/**
 * Immutable request for the offline-first local-intent/outbox boundary.
 *
 * The provider owns one atomic transaction (or an equivalent atomic protocol)
 * that records the application-owned local intent and its durable
 * reconciliation record. The runtime must not implement this as separate
 * storage and queue calls.
 */
public class StrategyOfflineFirstAdmissionRequest(
    public val request: SynchronizationRequest,
    public val decisionId: StrategyDecisionId,
    public val plan: StrategyExecutionPlan,
    public val trigger: StrategyExecutionTrigger,
    public val queueEntryId: QueueEntryId,
    idempotencyKey: String,
) {
    private val idempotencyKeySnapshot: String = idempotencyKey

    init {
        require(plan.effectiveStrategy == BuiltInSynchronizationStrategy.OFFLINE_FIRST) {
            "Offline-first admission requires an OFFLINE_FIRST plan."
        }
        require(
            plan.disposition == StrategyDisposition.EXECUTE ||
                plan.disposition == StrategyDisposition.DEFER,
        ) {
            "Offline-first admission requires an executable or deferred plan."
        }
        require(StrategyOperation.ACCEPT_LOCAL in plan.operations) {
            "Offline-first admission requires ACCEPT_LOCAL."
        }
        require(StrategyOperation.ENQUEUE_DURABLE_WORK in plan.operations) {
            "Offline-first admission requires ENQUEUE_DURABLE_WORK."
        }
        require(plan.durableContinuation != null) {
            "Offline-first admission requires an immutable durable continuation."
        }
        require(idempotencyKeySnapshot.isNotBlank()) {
            "Offline-first admission idempotencyKey must not be blank."
        }
    }

    /** Stable caller-owned idempotency identity for duplicate admission calls. */
    public val idempotencyKey: String
        get() = idempotencyKeySnapshot

    /** Bounded diagnostics that exclude the request payload and dynamic IDs. */
    override fun toString(): String =
        "StrategyOfflineFirstAdmissionRequest(" +
            "direction=${request.direction}, " +
            "mode=${request.mode}, " +
            "trigger=$trigger, " +
            "effectiveStrategy=${plan.effectiveStrategy}, " +
            "configurationVersion=${plan.configurationVersion.value})"
}

/** Result of one atomic local-intent/outbox admission attempt. */
public sealed interface StrategyOfflineFirstAdmissionResult {
    /** The local intent and durable reconciliation record committed together. */
    public data class Accepted(
        public val queueEntryId: QueueEntryId,
        public val idempotencyKey: String,
    ) : StrategyOfflineFirstAdmissionResult

    /** The same idempotent admission was already durably committed. */
    public data class AlreadyAccepted(
        public val queueEntryId: QueueEntryId,
        public val idempotencyKey: String,
    ) : StrategyOfflineFirstAdmissionResult
}

/**
 * Storage extension that can atomically accept local intent and durable work.
 *
 * Implementations must commit both records together, return a typed failure
 * when the transaction cannot commit, preserve cancellation, and avoid remote
 * calls. A successful result is the only point at which the runtime may report
 * offline-first local acceptance.
 */
public interface StrategyOfflineFirstAdmissionProvider : StorageProvider {
    public suspend fun admitLocalIntentAndOutbox(
        request: StrategyOfflineFirstAdmissionRequest,
    ): ProviderOperationResult<StrategyOfflineFirstAdmissionResult>
}
