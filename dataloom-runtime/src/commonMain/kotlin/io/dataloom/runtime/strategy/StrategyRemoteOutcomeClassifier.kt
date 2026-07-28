package io.dataloom.runtime.strategy

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.strategy.ClassifiedStrategyRemoteError
import io.dataloom.api.strategy.StrategyRemoteOutcome

/** Safe built-in classification used by remote-first fallback policy. */
internal object StrategyRemoteOutcomeClassifier {
    public fun classify(error: DataLoomError): StrategyRemoteOutcome =
        when (error) {
            is ClassifiedStrategyRemoteError -> error.remoteOutcome
            else -> when (error.category) {
                ErrorCategory.AUTHENTICATION ->
                    StrategyRemoteOutcome.AUTHENTICATION_FAILURE
                ErrorCategory.AUTHORIZATION ->
                    StrategyRemoteOutcome.AUTHORIZATION_FAILURE
                ErrorCategory.VALIDATION ->
                    StrategyRemoteOutcome.VALIDATION_FAILURE
                ErrorCategory.SERIALIZATION,
                ErrorCategory.SECURITY,
                -> StrategyRemoteOutcome.INTEGRITY_FAILURE
                ErrorCategory.CONFLICT ->
                    StrategyRemoteOutcome.CONFLICT
                else ->
                    StrategyRemoteOutcome.UNKNOWN_FAILURE
            }
        }
}
