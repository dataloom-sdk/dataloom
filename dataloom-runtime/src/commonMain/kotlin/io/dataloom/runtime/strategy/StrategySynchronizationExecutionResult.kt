package io.dataloom.runtime.strategy

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.provider.ProviderBindingFailure
import io.dataloom.api.strategy.StrategyCacheState
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
    ACCEPTED_PLAN_MISMATCH,
    ACCEPTED_PLAN_CONTINUATION_MISSING,
    RECONCILIATION_PROVIDER_NOT_CONFIGURED,
    UNSUPPORTED_PLAN,

    /**
     * The evaluated plan requires durable queue admission
     * (`ENQUEUE_DURABLE_WORK`, alongside `SCHEDULE_REFRESH` for cache-first
     * or on its own for offline-first), but no coordinator currently wires
     * evaluated plans into durable queue admission
     * ([StrategyQueueAdmissionEvaluator] exists but has no caller). Only the
     * inline/synchronous path is supported today:
     * `CacheFirstStrategyProfile.requireDurableRefresh = false` for
     * [CacheFirstStrategyExecutor], `OfflineFirstStrategyProfile.requireDurableQueue
     * = false` for [OfflineFirstStrategyExecutor]. Named for the first
     * executor that needed it; the underlying gap (no queue-admission wiring)
     * is identical for both, so this one reason covers both rather than two
     * near-duplicate reasons for the same root cause.
     */
    DURABLE_REFRESH_NOT_YET_SUPPORTED,

    /**
     * A [HybridStrategyExecutor] plan selected `HybridSource.LOCAL` for a
     * PUSH-direction request. The evaluator's local-fallback operation set
     * for PUSH is `[READ_LOCAL]` only — there is no `SERVE_LOCAL` operation
     * to serve (nothing to serve for a push) and, since `LOCAL` was
     * explicitly selected over `REMOTE`, no remote operation either. No
     * variant of [io.dataloom.api.strategy.StrategyTransportOutput]
     * represents a transport-free success, and unlike the `ACCEPT_LOCAL`
     * no-op branches in [CacheFirstStrategyExecutor]/[OfflineFirstStrategyExecutor]
     * (always paired with a required remote leg in the same plan), this is
     * the first genuinely transport-free plan shape in the strategy engine.
     * Rather than invent a signature-incompatible zero-effort success value
     * for one narrow branch, it is rejected explicitly until a proper
     * transport-free result type is added to [StrategySynchronizationExecutionResult].
     */
    HYBRID_LOCAL_PUSH_NOT_YET_SUPPORTED,
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

    /**
     * Local cache state was served as the primary, expected outcome — not a
     * fallback after a remote failure.
     *
     * Distinct from [FallbackActivated]: that variant means a remote attempt
     * failed and local state was substituted afterward. This variant means
     * cache-first policy chose to serve local state directly, by design,
     * with no implication that remote was ever attempted or unavailable.
     *
     * [refreshOutput] is non-null only when the plan also admitted a
     * synchronous, non-durable refresh alongside serving local state
     * (`CacheFirstStrategyProfile.staleCachePolicy = SERVE_STALE_AND_REFRESH`
     * or `refreshOnFreshHit = true`, with `requireDurableRefresh = false`).
     * When present, it carries the refresh's own terminal output; the refresh
     * having run does not change [cacheState], which always describes what
     * was actually served to evidence at admission time.
     */
    public data class ServedFromCache(
        override val evaluation: StrategyEvaluationResult,
        override val completedAt: DataLoomInstant,
        public val cacheState: StrategyCacheState,
        public val refreshOutput: StrategyTransportOutput? = null,
    ) : StrategySynchronizationExecutionResult {
        init {
            require(
                cacheState == StrategyCacheState.FRESH ||
                    cacheState == StrategyCacheState.STALE,
            ) {
                "ServedFromCache requires FRESH or STALE cache state."
            }
        }
    }

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

    /** Cancellation remains terminal and is never converted into fallback. */
    public data class Cancelled(
        override val evaluation: StrategyEvaluationResult,
        override val completedAt: DataLoomInstant,
        public val output: StrategyTransportOutput,
    ) : StrategySynchronizationExecutionResult

    /** Strategy admitted durable work instead of running transport directly. */
    public data class Deferred(
        override val evaluation: StrategyEvaluationResult,
        override val completedAt: DataLoomInstant,
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
