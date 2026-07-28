package io.dataloom.api.strategy

import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode

/** Provider capabilities derived from an effective strategy plan. */
public enum class StrategyProviderCapability {
    STORAGE,
    TRANSPORT,
    QUEUE,
    CONNECTIVITY,
    SCHEDULER,
    RETRY_STATE,
    CONFLICT_STATE,
    EVENT_OUTBOX,
}
/** Ordered, side-effecting or observable operations in a strategy plan. */
public enum class StrategyOperation {
    READ_LOCAL,
    ACCEPT_LOCAL,
    SERVE_LOCAL,
    ENQUEUE_DURABLE_WORK,
    PUSH_REMOTE,
    PULL_REMOTE,
    PERSIST_REMOTE,
    SCHEDULE_REFRESH,
    RECONCILE,
}

/** How the runtime disposes an admitted strategy request. */
public enum class StrategyDisposition {
    EXECUTE,
    SERVE_AND_REFRESH,
    DEFER,
    REJECT,
}

/** Typed reason for deferring work without consuming a retry attempt. */
public enum class StrategyDeferralReason {
    CONNECTIVITY_UNAVAILABLE,
    CONNECTIVITY_UNKNOWN,
    TRANSPORT_UNAVAILABLE,
    BACKGROUND_EXECUTION_UNAVAILABLE,
}

/** Typed reason for rejecting a strategy before provider side effects. */
public enum class StrategyRejectionReason {
    NO_ELIGIBLE_ADAPTIVE_PROFILE,
    CACHE_MISS,
    STALE_CACHE_NOT_ALLOWED,
    CONNECTIVITY_UNAVAILABLE,
    CONNECTIVITY_UNKNOWN,
    REQUIRED_CAPABILITY_UNAVAILABLE,
    UNSUPPORTED_DIRECTION,
}

/**
 * Immutable execution plan produced before provider resolution.
 *
 * Provider requirements are derived from [operations], not from a universal
 * storage-plus-transport assumption.
 */
public class StrategyExecutionPlan(
    public val id: StrategyPlanId,
    public val requestedStrategy: BuiltInSynchronizationStrategy,
    public val effectiveProfileId: StrategyProfileId,
    public val effectiveStrategy: BuiltInSynchronizationStrategy,
    public val configurationVersion: StrategyConfigurationVersion,
    public val direction: SynchronizationDirection,
    public val mode: SynchronizationMode,
    public val disposition: StrategyDisposition,
    operations: List<StrategyOperation>,
    requiredCapabilities: Set<StrategyProviderCapability>,
    public val dataOrigin: StrategyDataOrigin,
    public val consistency: StrategyConsistency,
    public val deferralReason: StrategyDeferralReason? = null,
    public val rejectionReason: StrategyRejectionReason? = null,
) {
    private val orderedOperations: List<StrategyOperation> = operations.toList()
    private val providerCapabilities: Set<StrategyProviderCapability> =
        requiredCapabilities.toSet()

    init {
        require(
            effectiveStrategy != BuiltInSynchronizationStrategy.ADAPTIVE,
        ) {
            "StrategyExecutionPlan effectiveStrategy must be a concrete strategy."
        }
        require(
            disposition != StrategyDisposition.DEFER || deferralReason != null,
        ) {
            "Deferred strategy plans require a deferralReason."
        }
        require(
            disposition != StrategyDisposition.REJECT || rejectionReason != null,
        ) {
            "Rejected strategy plans require a rejectionReason."
        }
        require(
            disposition == StrategyDisposition.DEFER || deferralReason == null,
        ) {
            "Only deferred strategy plans may define a deferralReason."
        }
        require(
            disposition == StrategyDisposition.REJECT || rejectionReason == null,
        ) {
            "Only rejected strategy plans may define a rejectionReason."
        }
        require(
            disposition == StrategyDisposition.REJECT || orderedOperations.isNotEmpty(),
        ) {
            "Non-rejected strategy plans require at least one operation."
        }
        if (effectiveStrategy == BuiltInSynchronizationStrategy.NETWORK_ONLY) {
            require(StrategyProviderCapability.STORAGE !in providerCapabilities) {
                "Network-only plans must not require storage."
            }
            require(StrategyProviderCapability.QUEUE !in providerCapabilities) {
                "Network-only plans must not require queue."
            }
            require(
                orderedOperations.none {
                    it == StrategyOperation.READ_LOCAL ||
                        it == StrategyOperation.ACCEPT_LOCAL ||
                        it == StrategyOperation.SERVE_LOCAL ||
                        it == StrategyOperation.ENQUEUE_DURABLE_WORK ||
                        it == StrategyOperation.PERSIST_REMOTE ||
                        it == StrategyOperation.SCHEDULE_REFRESH ||
                        it == StrategyOperation.RECONCILE
                },
            ) {
                "Network-only plans must contain remote transport operations only."
            }
        }
    }

    public val operations: List<StrategyOperation>
        get() = orderedOperations

    public val requiredCapabilities: Set<StrategyProviderCapability>
        get() = providerCapabilities

    override fun equals(other: Any?): Boolean =
        other is StrategyExecutionPlan &&
            id == other.id &&
            requestedStrategy == other.requestedStrategy &&
            effectiveProfileId == other.effectiveProfileId &&
            effectiveStrategy == other.effectiveStrategy &&
            configurationVersion == other.configurationVersion &&
            direction == other.direction &&
            mode == other.mode &&
            disposition == other.disposition &&
            orderedOperations == other.orderedOperations &&
            providerCapabilities == other.providerCapabilities &&
            dataOrigin == other.dataOrigin &&
            consistency == other.consistency &&
            deferralReason == other.deferralReason &&
            rejectionReason == other.rejectionReason

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + requestedStrategy.hashCode()
        result = 31 * result + effectiveProfileId.hashCode()
        result = 31 * result + effectiveStrategy.hashCode()
        result = 31 * result + configurationVersion.hashCode()
        result = 31 * result + direction.hashCode()
        result = 31 * result + mode.hashCode()
        result = 31 * result + disposition.hashCode()
        result = 31 * result + orderedOperations.hashCode()
        result = 31 * result + providerCapabilities.hashCode()
        result = 31 * result + dataOrigin.hashCode()
        result = 31 * result + consistency.hashCode()
        result = 31 * result + (deferralReason?.hashCode() ?: 0)
        result = 31 * result + (rejectionReason?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "StrategyExecutionPlan(id=$id, requestedStrategy=$requestedStrategy, " +
            "effectiveProfileId=$effectiveProfileId, effectiveStrategy=$effectiveStrategy, " +
            "configurationVersion=$configurationVersion, direction=$direction, mode=$mode, " +
            "disposition=$disposition, operations=$orderedOperations, " +
            "requiredCapabilities=$providerCapabilities, dataOrigin=$dataOrigin, " +
            "consistency=$consistency, deferralReason=$deferralReason, " +
            "rejectionReason=$rejectionReason)"
}
