package io.dataloom.core.provider

import io.dataloom.api.provider.ProviderBindingFailure
import io.dataloom.api.strategy.StrategyProviderCapability

/** Result of resolving only the provider roles required by a strategy plan. */
public sealed interface StrategyProviderResolutionResult {
    public data class Success(
        public val providers: ResolvedStrategyProviders,
    ) : StrategyProviderResolutionResult

    public class Failure(
        missingCapabilities: Set<StrategyProviderCapability> = emptySet(),
        bindingFailures: List<ProviderBindingFailure> = emptyList(),
    ) : StrategyProviderResolutionResult {
        private val missingSnapshot: Set<StrategyProviderCapability> =
            missingCapabilities.toSet()
        private val failuresSnapshot: List<ProviderBindingFailure> =
            bindingFailures.toList()

        init {
            require(missingSnapshot.isNotEmpty() || failuresSnapshot.isNotEmpty()) {
                "Strategy provider resolution failure requires at least one failure."
            }
        }

        public val missingCapabilities: Set<StrategyProviderCapability>
            get() = missingSnapshot

        public val bindingFailures: List<ProviderBindingFailure>
            get() = failuresSnapshot

        override fun equals(other: Any?): Boolean =
            other is Failure &&
                missingSnapshot == other.missingSnapshot &&
                failuresSnapshot == other.failuresSnapshot

        override fun hashCode(): Int =
            (31 * missingSnapshot.hashCode()) + failuresSnapshot.hashCode()

        override fun toString(): String =
            "StrategyProviderResolutionResult.Failure(" +
                "missingCapabilities=$missingSnapshot, " +
                "bindingFailures=$failuresSnapshot)"
    }
}
