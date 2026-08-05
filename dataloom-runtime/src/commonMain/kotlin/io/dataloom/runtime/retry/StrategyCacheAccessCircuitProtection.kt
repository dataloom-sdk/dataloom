package io.dataloom.runtime.retry

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.retry.RetryOperation

/** Stable circuit operation identity for application-owned cache verification. */
public enum class StrategyCacheAccessCircuitOperation(
    public val retryOperation: RetryOperation,
) {
    EVALUATE_CACHE_ACCESS(
        RetryOperation("strategy.evaluate-cache-access"),
    ),
}

/**
 * Circuit classification for the application-owned cache-access capability.
 *
 * A cache-access timeout is a local dependency availability failure and
 * therefore contributes to circuit health. A normal typed cache miss or
 * unavailable result remains provider success and does not open the circuit.
 */
public object StrategyCacheAccessCircuitBreakerFailureClassifier :
    CircuitBreakerFailureClassifier {
    override fun classify(error: DataLoomError): CircuitBreakerFailureDisposition =
        if (error.code.value == StrategyCacheAccessTimeoutErrors.PROVIDER_TIMEOUT_CODE) {
            CircuitBreakerFailureDisposition.RECORD_FAILURE
        } else {
            DefaultCircuitBreakerFailureClassifier.classify(error)
        }
}

/** Canonical timeout errors for the strategy cache-access operation. */
internal object StrategyCacheAccessTimeoutErrors {
    internal const val PROVIDER_TIMEOUT_CODE: String =
        "STRATEGY_CACHE_ACCESS_PROVIDER_TIMEOUT"

    fun providerTimedOut(): DataLoomError = Error(
        code = ErrorCode(PROVIDER_TIMEOUT_CODE),
        category = ErrorCategory.STORAGE,
        recoverability = Recoverability.RECOVERABLE,
        message = "The strategy cache-access provider exceeded its configured timeout.",
    )

    fun workflowDeadlineExceeded(): DataLoomError = Error(
        code = ErrorCode("STRATEGY_CACHE_ACCESS_WORKFLOW_DEADLINE_EXCEEDED"),
        category = ErrorCategory.STATE,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "The workflow deadline expired before strategy cache access completed.",
    )

    fun clockRegression(): DataLoomError = Error(
        code = ErrorCode("STRATEGY_CACHE_ACCESS_TIMEOUT_CLOCK_REGRESSION"),
        category = ErrorCategory.STATE,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "Clock regression prevented deterministic strategy cache-access timeout enforcement.",
    )

    private data class Error(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val recoverability: Recoverability,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val message: String,
        override val cause: Throwable? = null,
    ) : DataLoomError
}
