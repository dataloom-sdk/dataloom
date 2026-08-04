package io.dataloom.runtime.retry

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.retry.RetryOperation

/** Stable circuit operation identity for accepted-plan reconciliation. */
public enum class StrategyReconciliationCircuitOperation(
    public val retryOperation: RetryOperation,
) {
    RECONCILE(RetryOperation("strategy.reconcile")),
}

/** Circuit classification for the bounded reconciliation provider hook. */
public object StrategyReconciliationCircuitBreakerFailureClassifier :
    CircuitBreakerFailureClassifier {
    override fun classify(error: DataLoomError): CircuitBreakerFailureDisposition =
        if (error.code.value == StrategyReconciliationTimeoutErrors.PROVIDER_TIMEOUT_CODE) {
            CircuitBreakerFailureDisposition.RECORD_FAILURE
        } else {
            DefaultCircuitBreakerFailureClassifier.classify(error)
        }
}

/** Canonical timeout errors for accepted-plan reconciliation. */
internal object StrategyReconciliationTimeoutErrors {
    internal const val PROVIDER_TIMEOUT_CODE: String =
        "STRATEGY_RECONCILIATION_PROVIDER_TIMEOUT"

    fun providerTimedOut(): DataLoomError = Error(
        code = ErrorCode(PROVIDER_TIMEOUT_CODE),
        category = ErrorCategory.CONFLICT,
        recoverability = Recoverability.RECOVERABLE,
        message = "The strategy reconciliation provider exceeded its configured timeout.",
    )

    fun workflowDeadlineExceeded(): DataLoomError = Error(
        code = ErrorCode("STRATEGY_RECONCILIATION_WORKFLOW_DEADLINE_EXCEEDED"),
        category = ErrorCategory.STATE,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "The workflow deadline expired before strategy reconciliation completed.",
    )

    fun clockRegression(): DataLoomError = Error(
        code = ErrorCode("STRATEGY_RECONCILIATION_TIMEOUT_CLOCK_REGRESSION"),
        category = ErrorCategory.STATE,
        recoverability = Recoverability.NON_RECOVERABLE,
        message = "Clock regression prevented deterministic strategy reconciliation timeout enforcement.",
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
