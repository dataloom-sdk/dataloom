package io.dataloom.runtime.strategy

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.provider.ProviderBindingFailure
import io.dataloom.api.strategy.BuiltInSynchronizationStrategy
import io.dataloom.api.strategy.StrategyCacheFreshnessEvidence
import io.dataloom.api.strategy.StrategyCacheState
import io.dataloom.api.strategy.StrategyDataOrigin
import io.dataloom.api.strategy.StrategyEvaluationResult
import io.dataloom.api.strategy.StrategyLocalFallbackResult
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategyProviderCapability
import io.dataloom.api.strategy.StrategyRemoteOutcome
import io.dataloom.api.strategy.StrategyTransportOutput
import io.dataloom.api.time.DataLoomInstant

/** Runtime reason for rejecting a strategy request before transport execution. */
public enum class StrategyExecutionRejectionReason {
    PROVIDERS_NOT_INITIALIZED,
    STRATEGY_REJECTED,
    INCOMPATIBLE_TRIGGER,
    INCOMPATIBLE_INPUT,
    PROVIDER_RESOLUTION_FAILED,
    PROVIDER_PROTECTION_NOT_CONFIGURED,
    PROVIDER_PROTECTION_SCOPE_MISMATCH,
    LOCAL_FALLBACK_PROVIDER_NOT_CONFIGURED,
    ATOMIC_LOCAL_ADMISSION_PROVIDER_NOT_CONFIGURED,
    CACHE_ACCESS_PROVIDER_NOT_CONFIGURED,
    ACCEPTED_PLAN_MISMATCH,
    ACCEPTED_PLAN_CONTINUATION_MISSING,
    RECONCILIATION_PROVIDER_NOT_CONFIGURED,
    UNSUPPORTED_PLAN,
}

/** Idempotent outcome reported by the atomic offline-first admission boundary. */
public enum class StrategyOfflineFirstAdmissionDisposition {
    ACCEPTED,
    ALREADY_ACCEPTED,
}

/** Why a cache-first local-serving plan did not expose local state. */
public enum class StrategyCacheUnavailableReason {
    /** The provider reported missing, unknown, or otherwise unavailable state. */
    PROVIDER_REPORTED_UNAVAILABLE,

    /** A cache admitted as fresh became stale before the serving boundary. */
    FRESHNESS_DOWNGRADED,
}

/** Observable result of strategy admission and execution. */
public sealed interface StrategySynchronizationExecutionResult {
    public val evaluation: StrategyEvaluationResult
    public val completedAt: DataLoomInstant

    public data class Executed(
        override val evaluation: StrategyEvaluationResult,
        override val completedAt: DataLoomInstant,
        public val output: StrategyTransportOutput,
    ) : StrategySynchronizationExecutionResult

    public class Failed(
        override val evaluation: StrategyEvaluationResult,
        override val completedAt: DataLoomInstant,
        public val error: DataLoomError,
        public val transportAttempted: Boolean,
        completedOperations: List<StrategyOperation> = emptyList(),
        public val partialOutput: StrategyTransportOutput? = null,
        public val remoteOutcome: StrategyRemoteOutcome? = null,
        public val primaryError: DataLoomError? = null,
        public val fallbackAttempted: Boolean = false,
    ) : StrategySynchronizationExecutionResult {
        private val completedOperationsSnapshot: List<StrategyOperation> =
            completedOperations.toList()

        public val completedOperations: List<StrategyOperation>
            get() = completedOperationsSnapshot.toList()

        override fun equals(other: Any?): Boolean =
            other is Failed &&
                evaluation == other.evaluation &&
                completedAt == other.completedAt &&
                error == other.error &&
                transportAttempted == other.transportAttempted &&
                completedOperationsSnapshot == other.completedOperationsSnapshot &&
                partialOutput == other.partialOutput &&
                remoteOutcome == other.remoteOutcome &&
                primaryError == other.primaryError &&
                fallbackAttempted == other.fallbackAttempted

        override fun hashCode(): Int {
            var result = evaluation.hashCode()
            result = (31 * result) + completedAt.hashCode()
            result = (31 * result) + error.hashCode()
            result = (31 * result) + transportAttempted.hashCode()
            result = (31 * result) + completedOperationsSnapshot.hashCode()
            result = (31 * result) + (partialOutput?.hashCode() ?: 0)
            result = (31 * result) + (remoteOutcome?.hashCode() ?: 0)
            result = (31 * result) + (primaryError?.hashCode() ?: 0)
            result = (31 * result) + fallbackAttempted.hashCode()
            return result
        }

        override fun toString(): String =
            "StrategySynchronizationExecutionResult.Failed(" +
                "decisionId=${evaluation.decisionId}, " +
                "planId=${evaluation.plan.id}, " +
                "errorCode=${error.code}, " +
                "transportAttempted=$transportAttempted, " +
                "completedOperations=$completedOperationsSnapshot, " +
                "partialOutput=$partialOutput, " +
                "remoteOutcome=$remoteOutcome, " +
                "primaryErrorCode=${primaryError?.code}, " +
                "fallbackAttempted=$fallbackAttempted)"
    }

    /** An allowlisted remote outcome activated application-owned local state. */
    public class FallbackActivated(
        override val evaluation: StrategyEvaluationResult,
        override val completedAt: DataLoomInstant,
        public val remoteOutcome: StrategyRemoteOutcome,
        public val remoteAttempted: Boolean,
        public val cacheState: StrategyCacheState,
        public val primaryError: DataLoomError? = null,
        completedOperations: List<StrategyOperation> = emptyList(),
        public val partialOutput: StrategyTransportOutput? = null,
    ) : StrategySynchronizationExecutionResult {
        private val completedOperationsSnapshot: List<StrategyOperation> =
            completedOperations.toList()

        init {
            require(
                cacheState == StrategyCacheState.FRESH ||
                    cacheState == StrategyCacheState.STALE,
            ) {
                "Activated fallback requires FRESH or STALE cache state."
            }
            require(remoteAttempted == (primaryError != null)) {
                "Remote-attempt evidence and primaryError must agree."
            }
        }

        public val completedOperations: List<StrategyOperation>
            get() = completedOperationsSnapshot.toList()

        override fun equals(other: Any?): Boolean =
            other is FallbackActivated &&
                evaluation == other.evaluation &&
                completedAt == other.completedAt &&
                remoteOutcome == other.remoteOutcome &&
                remoteAttempted == other.remoteAttempted &&
                cacheState == other.cacheState &&
                primaryError == other.primaryError &&
                completedOperationsSnapshot == other.completedOperationsSnapshot &&
                partialOutput == other.partialOutput

        override fun hashCode(): Int {
            var result = evaluation.hashCode()
            result = (31 * result) + completedAt.hashCode()
            result = (31 * result) + remoteOutcome.hashCode()
            result = (31 * result) + remoteAttempted.hashCode()
            result = (31 * result) + cacheState.hashCode()
            result = (31 * result) + (primaryError?.hashCode() ?: 0)
            result = (31 * result) + completedOperationsSnapshot.hashCode()
            result = (31 * result) + (partialOutput?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String =
            "StrategySynchronizationExecutionResult.FallbackActivated(" +
                "decisionId=${evaluation.decisionId}, " +
                "planId=${evaluation.plan.id}, " +
                "remoteOutcome=$remoteOutcome, " +
                "remoteAttempted=$remoteAttempted, " +
                "cacheState=$cacheState, " +
                "primaryErrorCode=${primaryError?.code}, " +
                "completedOperations=$completedOperationsSnapshot, " +
                "partialOutput=$partialOutput)"
    }

    /** Fallback was allowed but no acceptable local state was available. */
    public data class FallbackUnavailable(
        override val evaluation: StrategyEvaluationResult,
        override val completedAt: DataLoomInstant,
        public val remoteOutcome: StrategyRemoteOutcome,
        public val remoteAttempted: Boolean,
        public val localResult: StrategyLocalFallbackResult.Unavailable,
        public val primaryError: DataLoomError? = null,
        public val partialOutput: StrategyTransportOutput? = null,
    ) : StrategySynchronizationExecutionResult

    /**
     * Application-owned synchronized local state is available for cache-first use.
     *
     * DataLoom returns only origin and provider-observed freshness metadata. The
     * application continues to read its domain value through its own repository.
     */
    public data class CacheServed(
        override val evaluation: StrategyEvaluationResult,
        override val completedAt: DataLoomInstant,
        public val evaluatedCacheState: StrategyCacheState,
        public val freshness: StrategyCacheFreshnessEvidence,
    ) : StrategySynchronizationExecutionResult {
        init {
            require(
                evaluation.plan.effectiveStrategy ==
                    BuiltInSynchronizationStrategy.CACHE_FIRST &&
                    StrategyOperation.SERVE_LOCAL in evaluation.plan.operations,
            ) {
                "CacheServed requires a cache-first local-serving plan."
            }
            require(
                evaluatedCacheState == StrategyCacheState.FRESH ||
                    evaluatedCacheState == StrategyCacheState.STALE,
            ) {
                "CacheServed requires evaluated FRESH or STALE state."
            }
            require(
                evaluatedCacheState != StrategyCacheState.FRESH ||
                    freshness.cacheState == StrategyCacheState.FRESH,
            ) {
                "A fresh admission must not be served from stale provider evidence."
            }
        }

        public val dataOrigin: StrategyDataOrigin = StrategyDataOrigin.LOCAL

        override fun toString(): String =
            "StrategySynchronizationExecutionResult.CacheServed(" +
                "decisionId=${evaluation.decisionId}, " +
                "planId=${evaluation.plan.id}, " +
                "evaluatedCacheState=$evaluatedCacheState, " +
                "providerCacheState=${freshness.cacheState}, " +
                "dataOrigin=$dataOrigin)"
    }

    /**
     * A cache-first plan reached the provider boundary but local state was not
     * exposed. The runtime does not silently switch to remote or another strategy.
     */
    public data class CacheUnavailable(
        override val evaluation: StrategyEvaluationResult,
        override val completedAt: DataLoomInstant,
        public val evaluatedCacheState: StrategyCacheState,
        public val providerCacheState: StrategyCacheState,
        public val reason: StrategyCacheUnavailableReason,
        public val providerFreshness: StrategyCacheFreshnessEvidence? = null,
    ) : StrategySynchronizationExecutionResult {
        init {
            require(
                evaluation.plan.effectiveStrategy ==
                    BuiltInSynchronizationStrategy.CACHE_FIRST &&
                    StrategyOperation.SERVE_LOCAL in evaluation.plan.operations,
            ) {
                "CacheUnavailable requires a cache-first local-serving plan."
            }
            require(
                evaluatedCacheState == StrategyCacheState.FRESH ||
                    evaluatedCacheState == StrategyCacheState.STALE,
            ) {
                "CacheUnavailable requires evaluated FRESH or STALE state."
            }
            when (reason) {
                StrategyCacheUnavailableReason.PROVIDER_REPORTED_UNAVAILABLE -> {
                    require(
                        providerCacheState != StrategyCacheState.FRESH &&
                            providerCacheState != StrategyCacheState.STALE,
                    ) {
                        "Provider-unavailable cache state must not be FRESH or STALE."
                    }
                    require(providerFreshness == null) {
                        "Provider-unavailable cache state must not include freshness."
                    }
                }
                StrategyCacheUnavailableReason.FRESHNESS_DOWNGRADED -> {
                    require(evaluatedCacheState == StrategyCacheState.FRESH) {
                        "Freshness downgrade requires an originally FRESH admission."
                    }
                    require(providerCacheState == StrategyCacheState.STALE) {
                        "Freshness downgrade requires provider-observed STALE state."
                    }
                    require(providerFreshness?.cacheState == StrategyCacheState.STALE) {
                        "Freshness downgrade requires stale provider evidence."
                    }
                }
            }
        }

        public val dataOrigin: StrategyDataOrigin = StrategyDataOrigin.NONE

        override fun toString(): String =
            "StrategySynchronizationExecutionResult.CacheUnavailable(" +
                "decisionId=${evaluation.decisionId}, " +
                "planId=${evaluation.plan.id}, " +
                "evaluatedCacheState=$evaluatedCacheState, " +
                "providerCacheState=$providerCacheState, " +
                "reason=$reason, dataOrigin=$dataOrigin)"
    }

    /** Cancellation remains terminal and is never converted into fallback. */
    public data class Cancelled(
        override val evaluation: StrategyEvaluationResult,
        override val completedAt: DataLoomInstant,
        public val output: StrategyTransportOutput,
    ) : StrategySynchronizationExecutionResult

    /**
     * Strategy admitted durable work instead of running transport directly.
     *
     * [admissionDisposition] is non-null only after the atomic offline-first
     * provider boundary confirms the durable local-intent/outbox transaction.
     */
    public data class Deferred(
        override val evaluation: StrategyEvaluationResult,
        override val completedAt: DataLoomInstant,
        public val admissionDisposition: StrategyOfflineFirstAdmissionDisposition? = null,
    ) : StrategySynchronizationExecutionResult

    /** Execution was rejected before a provider operation. */
    public class Rejected(
        override val evaluation: StrategyEvaluationResult,
        override val completedAt: DataLoomInstant,
        public val reason: StrategyExecutionRejectionReason,
        missingCapabilities: Set<StrategyProviderCapability> = emptySet(),
        bindingFailures: List<ProviderBindingFailure> = emptyList(),
    ) : StrategySynchronizationExecutionResult {
        private val missingSnapshot: Set<StrategyProviderCapability> =
            missingCapabilities.toSet()
        private val failuresSnapshot: List<ProviderBindingFailure> =
            bindingFailures.toList()

        public val missingCapabilities: Set<StrategyProviderCapability>
            get() = missingSnapshot.toSet()

        public val bindingFailures: List<ProviderBindingFailure>
            get() = failuresSnapshot.toList()

        override fun equals(other: Any?): Boolean =
            other is Rejected &&
                evaluation == other.evaluation &&
                completedAt == other.completedAt &&
                reason == other.reason &&
                missingSnapshot == other.missingSnapshot &&
                failuresSnapshot == other.failuresSnapshot

        override fun hashCode(): Int {
            var result = evaluation.hashCode()
            result = (31 * result) + completedAt.hashCode()
            result = (31 * result) + reason.hashCode()
            result = (31 * result) + missingSnapshot.hashCode()
            result = (31 * result) + failuresSnapshot.hashCode()
            return result
        }

        override fun toString(): String =
            "StrategySynchronizationExecutionResult.Rejected(" +
                "decisionId=${evaluation.decisionId}, " +
                "planId=${evaluation.plan.id}, " +
                "reason=$reason, " +
                "missingCapabilities=$missingSnapshot, " +
                "bindingFailureCount=${failuresSnapshot.size})"
    }
}
