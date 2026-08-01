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
    UNSUPPORTED_PLAN,
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

    /** The fallback transition was allowed, but synchronized local state was unavailable. */
    public data class FallbackUnavailable(
        override val evaluation: StrategyEvaluationResult,
        override val completedAt: DataLoomInstant,
        public val remoteOutcome: StrategyRemoteOutcome,
        public val remoteAttempted: Boolean,
        public val localResult: StrategyLocalFallbackResult.Unavailable,
        public val primaryError: DataLoomError? = null,
        public val partialOutput: StrategyTransportOutput? = null,
    ) : StrategySynchronizationExecutionResult {
        init {
            require(remoteAttempted == (primaryError != null)) {
                "Remote-attempt evidence and primaryError must agree."
            }
        }
    }

    /** A provider-backed pipeline returned explicit cancellation. */
    public data class Cancelled(
        override val evaluation: StrategyEvaluationResult,
        override val completedAt: DataLoomInstant,
        public val output: StrategyTransportOutput.ProviderBacked,
    ) : StrategySynchronizationExecutionResult

    public data class Deferred(
        override val evaluation: StrategyEvaluationResult,
        override val completedAt: DataLoomInstant,
    ) : StrategySynchronizationExecutionResult

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
            get() = missingSnapshot

        public val bindingFailures: List<ProviderBindingFailure>
            get() = failuresSnapshot

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
                "bindingFailures=$failuresSnapshot)"
    }
}
