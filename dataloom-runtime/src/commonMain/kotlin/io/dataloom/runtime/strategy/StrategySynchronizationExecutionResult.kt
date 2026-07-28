package io.dataloom.runtime.strategy

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.provider.ProviderBindingFailure
import io.dataloom.api.strategy.StrategyEvaluationResult
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategyProviderCapability
import io.dataloom.api.strategy.StrategyTransportOutput
import io.dataloom.api.time.DataLoomInstant

/** Runtime reason for rejecting a strategy request before transport execution. */
public enum class StrategyExecutionRejectionReason {
    PROVIDERS_NOT_INITIALIZED,
    STRATEGY_REJECTED,
    INCOMPATIBLE_TRIGGER,
    INCOMPATIBLE_INPUT,
    PROVIDER_RESOLUTION_FAILED,
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
                partialOutput == other.partialOutput

        override fun hashCode(): Int {
            var result = evaluation.hashCode()
            result = (31 * result) + completedAt.hashCode()
            result = (31 * result) + error.hashCode()
            result = (31 * result) + transportAttempted.hashCode()
            result = (31 * result) + completedOperationsSnapshot.hashCode()
            result = (31 * result) + (partialOutput?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String =
            "StrategySynchronizationExecutionResult.Failed(" +
                "decisionId=${evaluation.decisionId}, " +
                "planId=${evaluation.plan.id}, " +
                "errorCode=${error.code}, " +
                "transportAttempted=$transportAttempted, " +
                "completedOperations=$completedOperationsSnapshot, " +
                "partialOutput=$partialOutput)"
    }

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
