package io.dataloom.runtime.retry

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.error.safeDiagnosticString
import io.dataloom.api.retry.RetryOperation

/** Stable circuit operation identity for application-owned strategy fallback. */
public enum class StrategyLocalFallbackCircuitOperation(
    public val retryOperation: RetryOperation,
) {
    EVALUATE_LOCAL_FALLBACK(
        RetryOperation("strategy.evaluate-local-fallback"),
    ),
}

/**
 * Circuit classification for the application-owned local fallback capability.
 *
 * A fallback timeout is a local dependency availability failure and therefore
 * contributes to circuit health while remaining independently classified from
 * semantic fallback unavailability.
 */
public object StrategyLocalFallbackCircuitBreakerFailureClassifier :
    CircuitBreakerFailureClassifier {
    override fun classify(error: DataLoomError): CircuitBreakerFailureDisposition =
        if (error.code.value == StrategyLocalFallbackTimeoutErrors.PROVIDER_TIMEOUT_CODE) {
            CircuitBreakerFailureDisposition.RECORD_FAILURE
        } else {
            DefaultCircuitBreakerFailureClassifier.classify(error)
        }
}

/** Canonical timeout errors for the strategy-local-fallback operation. */
internal object StrategyLocalFallbackTimeoutErrors {
    internal const val PROVIDER_TIMEOUT_CODE: String =
        "STRATEGY_LOCAL_FALLBACK_PROVIDER_TIMEOUT"

    fun providerTimedOut(): DataLoomError = Error(
        code = ErrorCode(PROVIDER_TIMEOUT_CODE),
        category = ErrorCategory.STORAGE,
        recoverability = Recoverability.RECOVERABLE,
        message = "The strategy local fallback provider exceeded its configured timeout.",
    )

    fun workflowDeadlineExceeded(): DataLoomError = Error(
        code = ErrorCode("STRATEGY_LOCAL_FALLBACK_WORKFLOW_DEADLINE_EXCEEDED"),
        category = ErrorCategory.STATE,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "The workflow deadline expired before strategy local fallback completed.",
    )

    fun clockRegression(): DataLoomError = Error(
        code = ErrorCode("STRATEGY_LOCAL_FALLBACK_TIMEOUT_CLOCK_REGRESSION"),
        category = ErrorCategory.STATE,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "Clock regression prevented deterministic strategy local fallback timeout enforcement.",
    )

    private data class Error(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val recoverability: Recoverability,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val message: String,
        override val cause: Throwable? = null,
    ) : DataLoomError {
        override fun toString(): String = safeDiagnosticString()
    }
}
