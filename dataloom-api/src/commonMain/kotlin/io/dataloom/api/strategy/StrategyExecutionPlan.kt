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
    /** Storage can atomically admit local intent and its durable continuation. */
    ATOMIC_LOCAL_ADMISSION,
}

/** Ordered, side-effecting or observable operations in a strategy plan. */
public enum class StrategyOperation {
    READ_LOCAL,
    READ_CHECKPOINT,
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

private val protectedFallbackOutcomes: Set<StrategyRemoteOutcome> = setOf(
    StrategyRemoteOutcome.CANCELLED,
    StrategyRemoteOutcome.AUTHENTICATION_FAILURE,
    StrategyRemoteOutcome.AUTHORIZATION_FAILURE,
    StrategyRemoteOutcome.VALIDATION_FAILURE,
    StrategyRemoteOutcome.INTEGRITY_FAILURE,
    StrategyRemoteOutcome.CONFLICT,
)

private fun minimumCapabilitiesFor(
    operations: Iterable<StrategyOperation>,
): Set<StrategyProviderCapability> {
    val capabilities = mutableSetOf<StrategyProviderCapability>()
    operations.forEach { operation ->
        when (operation) {
            StrategyOperation.READ_LOCAL,
            StrategyOperation.READ_CHECKPOINT,
            StrategyOperation.ACCEPT_LOCAL,
            StrategyOperation.SERVE_LOCAL,
            StrategyOperation.PERSIST_REMOTE,
            -> capabilities += StrategyProviderCapability.STORAGE
            StrategyOperation.ENQUEUE_DURABLE_WORK ->
                capabilities += StrategyProviderCapability.QUEUE
            StrategyOperation.PUSH_REMOTE,
            StrategyOperation.PULL_REMOTE,
            -> capabilities += StrategyProviderCapability.TRANSPORT
            StrategyOperation.SCHEDULE_REFRESH -> {
                capabilities += StrategyProviderCapability.SCHEDULER
                capabilities += StrategyProviderCapability.QUEUE
            }
            StrategyOperation.RECONCILE -> {
                capabilities += StrategyProviderCapability.STORAGE
                capabilities += StrategyProviderCapability.CONFLICT_STATE
            }
        }
    }
    return capabilities
}

/**
 * Finite fallback branch admitted with a primary strategy plan.
 *
 * [remoteOutcomes] is an explicit allowlist. Runtime failures that are not
 * classified into one of these outcomes must not enter [operations].
 */
public class StrategyFallbackPlan(
    remoteOutcomes: Set<StrategyRemoteOutcome>,
    operations: List<StrategyOperation>,
    public val dataOrigin: StrategyDataOrigin,
) {
    private val remoteOutcomeSnapshot: Set<StrategyRemoteOutcome> =
        remoteOutcomes.toSet()
    private val operationSnapshot: List<StrategyOperation> = operations.toList()

    init {
        require(remoteOutcomeSnapshot.isNotEmpty()) {
            "StrategyFallbackPlan requires at least one remote outcome."
        }
        require(operationSnapshot.isNotEmpty()) {
            "StrategyFallbackPlan requires at least one fallback operation."
        }
        require(operationSnapshot.size == operationSnapshot.distinct().size) {
            "StrategyFallbackPlan operations must be unique and ordered."
        }
        require(StrategyOperation.SERVE_LOCAL in operationSnapshot) {
            "A local fallback branch must explicitly serve local state."
        }
        require(remoteOutcomeSnapshot.none { it in protectedFallbackOutcomes }) {
            "A fallback branch must not hide cancellation, protected failures, or conflict."
        }
        require(
            operationSnapshot.all {
                it == StrategyOperation.READ_LOCAL ||
                    it == StrategyOperation.SERVE_LOCAL
            },
        ) {
            "A local fallback branch may only read and serve local state."
        }
        require(dataOrigin != StrategyDataOrigin.REMOTE) {
            "A fallback branch must not claim REMOTE data origin."
        }
        require(
            operationSnapshot.none {
                it == StrategyOperation.PUSH_REMOTE ||
                    it == StrategyOperation.PULL_REMOTE ||
                    it == StrategyOperation.PERSIST_REMOTE
            },
        ) {
            "A local fallback branch must not contain remote operations."
        }
    }

    public val remoteOutcomes: Set<StrategyRemoteOutcome>
        get() = remoteOutcomeSnapshot.toSet()

    public val operations: List<StrategyOperation>
        get() = operationSnapshot.toList()

    override fun equals(other: Any?): Boolean =
        other is StrategyFallbackPlan &&
            remoteOutcomeSnapshot == other.remoteOutcomeSnapshot &&
            operationSnapshot == other.operationSnapshot &&
            dataOrigin == other.dataOrigin

    override fun hashCode(): Int {
        var result = remoteOutcomeSnapshot.hashCode()
        result = 31 * result + operationSnapshot.hashCode()
        result = 31 * result + dataOrigin.hashCode()
        return result
    }

    override fun toString(): String =
        "StrategyFallbackPlan(remoteOutcomes=$remoteOutcomeSnapshot, " +
            "operations=$operationSnapshot, dataOrigin=$dataOrigin)"
}

/**
 * Immutable execution projection that is admitted for later durable replay.
 *
 * The continuation is selected during the original deterministic evaluation.
 * Queue acquisition executes this projection without evaluating current policy,
 * connectivity, provider health, cache state, or configuration again.
 */
public class StrategyDurableContinuationPlan(
    operations: List<StrategyOperation>,
    requiredCapabilities: Set<StrategyProviderCapability>,
    public val dataOrigin: StrategyDataOrigin,
    public val consistency: StrategyConsistency,
    public val evaluatedCacheState: StrategyCacheState? = null,
    public val fallbackPlan: StrategyFallbackPlan? = null,
) {
    private val orderedOperations: List<StrategyOperation> = operations.toList()
    private val providerCapabilities: Set<StrategyProviderCapability> =
        requiredCapabilities.toSet()

    init {
        require(orderedOperations.isNotEmpty()) {
            "StrategyDurableContinuationPlan requires at least one operation."
        }
        require(orderedOperations.size == orderedOperations.distinct().size) {
            "StrategyDurableContinuationPlan operations must be unique and ordered."
        }
        require(StrategyOperation.ACCEPT_LOCAL !in orderedOperations) {
            "A durable continuation must not repeat original local admission."
        }
        require(
            StrategyOperation.ENQUEUE_DURABLE_WORK !in orderedOperations &&
                StrategyOperation.SCHEDULE_REFRESH !in orderedOperations,
        ) {
            "A durable continuation must not enqueue or schedule another copy of itself."
        }
        require(
            fallbackPlan == null ||
                StrategyOperation.PULL_REMOTE in orderedOperations,
        ) {
            "A durable fallback branch requires a remote pull operation."
        }
        require(
            evaluatedCacheState != null ||
                (
                    fallbackPlan == null &&
                        StrategyOperation.SERVE_LOCAL !in orderedOperations
                    ),
        ) {
            "Durable local fallback or serving requires persisted cache-state evidence."
        }
        val minimumCapabilities = minimumCapabilitiesFor(
            orderedOperations + (fallbackPlan?.operations ?: emptyList()),
        )
        require(providerCapabilities.containsAll(minimumCapabilities)) {
            "Durable continuation capabilities must cover every admitted operation."
        }
    }

    public val operations: List<StrategyOperation>
        get() = orderedOperations.toList()

    public val requiredCapabilities: Set<StrategyProviderCapability>
        get() = providerCapabilities.toSet()

    override fun equals(other: Any?): Boolean =
        other is StrategyDurableContinuationPlan &&
            orderedOperations == other.orderedOperations &&
            providerCapabilities == other.providerCapabilities &&
            dataOrigin == other.dataOrigin &&
            consistency == other.consistency &&
            evaluatedCacheState == other.evaluatedCacheState &&
            fallbackPlan == other.fallbackPlan

    override fun hashCode(): Int {
        var result = orderedOperations.hashCode()
        result = 31 * result + providerCapabilities.hashCode()
        result = 31 * result + dataOrigin.hashCode()
        result = 31 * result + consistency.hashCode()
        result = 31 * result + (evaluatedCacheState?.hashCode() ?: 0)
        result = 31 * result + (fallbackPlan?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "StrategyDurableContinuationPlan(" +
            "operations=$orderedOperations, " +
            "requiredCapabilities=$providerCapabilities, " +
            "dataOrigin=$dataOrigin, consistency=$consistency, " +
            "evaluatedCacheState=$evaluatedCacheState, " +
            "fallbackPlan=$fallbackPlan)"
}

/**
 * Immutable execution plan produced before provider resolution.
 *
 * Provider requirements are derived from [operations], not from a universal
 * storage-plus-transport assumption. [durableContinuation] freezes the exact
 * later execution projection whenever the immediate plan admits durable work.
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
    public val fallbackPlan: StrategyFallbackPlan? = null,
    public val durableContinuation: StrategyDurableContinuationPlan? = null,
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
        require(orderedOperations.size == orderedOperations.distinct().size) {
            "StrategyExecutionPlan operations must be unique and ordered."
        }
        require(
            fallbackPlan == null || disposition == StrategyDisposition.EXECUTE,
        ) {
            "Only executable strategy plans may define a fallback branch."
        }
        require(
            durableContinuation == null || disposition != StrategyDisposition.REJECT,
        ) {
            "Rejected strategy plans must not define a durable continuation."
        }
        require(
            durableContinuation == null ||
                StrategyOperation.ENQUEUE_DURABLE_WORK in orderedOperations,
        ) {
            "A durable continuation requires explicit durable queue admission."
        }
        if (effectiveStrategy == BuiltInSynchronizationStrategy.NETWORK_ONLY) {
            require(fallbackPlan == null) {
                "Network-only plans must not define a fallback branch."
            }
            require(StrategyProviderCapability.STORAGE !in providerCapabilities) {
                "Network-only plans must not require storage."
            }
            require(StrategyProviderCapability.QUEUE !in providerCapabilities) {
                "Network-only plans must not require queue."
            }
            require(
                orderedOperations.none {
                    it == StrategyOperation.READ_LOCAL ||
                        it == StrategyOperation.READ_CHECKPOINT ||
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
        get() = orderedOperations.toList()

    public val requiredCapabilities: Set<StrategyProviderCapability>
        get() = providerCapabilities.toSet()

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
            rejectionReason == other.rejectionReason &&
            fallbackPlan == other.fallbackPlan &&
            durableContinuation == other.durableContinuation

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
        result = 31 * result + (fallbackPlan?.hashCode() ?: 0)
        result = 31 * result + (durableContinuation?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "StrategyExecutionPlan(id=$id, requestedStrategy=$requestedStrategy, " +
            "effectiveProfileId=$effectiveProfileId, effectiveStrategy=$effectiveStrategy, " +
            "configurationVersion=$configurationVersion, direction=$direction, mode=$mode, " +
            "disposition=$disposition, operations=$orderedOperations, " +
            "requiredCapabilities=$providerCapabilities, dataOrigin=$dataOrigin, " +
            "consistency=$consistency, deferralReason=$deferralReason, " +
            "rejectionReason=$rejectionReason, fallbackPlan=$fallbackPlan, " +
            "durableContinuation=$durableContinuation)"
}
