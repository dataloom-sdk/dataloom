package io.dataloom.runtime.execution.protection

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.retry.CircuitBreakerRecordResult
import io.dataloom.runtime.retry.CircuitBreakerRejectionReason

/** Whether the protected provider operation ran and how it completed. */
public enum class ProviderProtectionInvocation {
    /** Circuit permission stopped execution before the provider was invoked. */
    NOT_EXECUTED,

    /** The provider ran and returned success. */
    SUCCEEDED,

    /** The provider ran and returned a circuit-eligible canonical failure. */
    CIRCUIT_FAILURE,

    /** The provider ran and returned a semantic non-circuit failure. */
    NON_CIRCUIT_FAILURE,
}

/** Exact pre-execution boundary that prevented provider invocation. */
public enum class ProviderProtectionPreExecutionReason {
    CIRCUIT_REJECTED,
    PERMISSION_PERSISTENCE_FAILURE,
    PERMISSION_CONTENTION_LIMIT_REACHED,
}

/**
 * Bounded operational evidence for one storage or transport provider call.
 *
 * Provider return values, payloads, credentials, headers, checkpoint contents,
 * exception text, and arbitrary metadata are intentionally excluded. The exact
 * post-execution [recordResult] is retained because it determines whether a
 * provider operation may be safely retried or reconciled.
 */
public data class ProviderProtectionOperationEvidence(
    /** Exact protected provider identity. */
    public val providerId: ProviderId,

    /** Stable logical provider operation identity. */
    public val operation: RetryOperation,

    /** Whether and how the provider operation executed. */
    public val invocation: ProviderProtectionInvocation,

    /** Pre-execution reason when [invocation] is [ProviderProtectionInvocation.NOT_EXECUTED]. */
    public val preExecutionReason: ProviderProtectionPreExecutionReason? = null,

    /** Exact rejection reason when the selected circuit denied permission. */
    public val rejectionReason: CircuitBreakerRejectionReason? = null,

    /** Exact next eligible time supplied by the circuit, when available. */
    public val retryAt: DataLoomInstant? = null,

    /** Exact canonical failure returned by the provider or circuit store. */
    public val error: DataLoomError? = null,

    /** Exact post-execution circuit recording result, present only after provider execution. */
    public val recordResult: CircuitBreakerRecordResult? = null,
) {
    init {
        when (invocation) {
            ProviderProtectionInvocation.NOT_EXECUTED -> {
                requireNotNull(preExecutionReason) {
                    "Non-executed provider evidence requires a pre-execution reason."
                }
                require(recordResult == null) {
                    "Non-executed provider evidence cannot contain a circuit recording result."
                }
                when (preExecutionReason) {
                    ProviderProtectionPreExecutionReason.CIRCUIT_REJECTED -> {
                        requireNotNull(rejectionReason) {
                            "Circuit rejection evidence requires a rejection reason."
                        }
                    }
                    ProviderProtectionPreExecutionReason.PERMISSION_PERSISTENCE_FAILURE -> {
                        requireNotNull(error) {
                            "Permission persistence failure evidence requires a canonical error."
                        }
                        require(rejectionReason == null && retryAt == null) {
                            "Permission persistence failure cannot contain rejection timing."
                        }
                    }
                    ProviderProtectionPreExecutionReason.PERMISSION_CONTENTION_LIMIT_REACHED -> {
                        require(error == null && rejectionReason == null && retryAt == null) {
                            "Permission contention evidence cannot contain unrelated failure data."
                        }
                    }
                }
            }
            ProviderProtectionInvocation.SUCCEEDED -> {
                require(preExecutionReason == null && rejectionReason == null && retryAt == null) {
                    "Executed provider evidence cannot contain pre-execution rejection data."
                }
                require(error == null) {
                    "Successful provider evidence cannot contain an error."
                }
                requireNotNull(recordResult) {
                    "Executed provider evidence requires a circuit recording result."
                }
            }
            ProviderProtectionInvocation.CIRCUIT_FAILURE,
            ProviderProtectionInvocation.NON_CIRCUIT_FAILURE,
            -> {
                require(preExecutionReason == null && rejectionReason == null && retryAt == null) {
                    "Executed provider evidence cannot contain pre-execution rejection data."
                }
                requireNotNull(error) {
                    "Failed provider evidence requires a canonical error."
                }
                requireNotNull(recordResult) {
                    "Executed provider evidence requires a circuit recording result."
                }
            }
        }
    }

    /** True only when the provider method was invoked. */
    public val providerExecuted: Boolean
        get() = invocation != ProviderProtectionInvocation.NOT_EXECUTED

    /** True only when the provider returned success. */
    public val providerSucceeded: Boolean
        get() = invocation == ProviderProtectionInvocation.SUCCEEDED

    /** True when circuit recording was accepted or no state transition was required. */
    public val circuitRecordingAccepted: Boolean
        get() = recordResult is CircuitBreakerRecordResult.Recorded ||
            recordResult is CircuitBreakerRecordResult.Ignored

    /**
     * Returns bounded diagnostics without rendering provider values, state
     * records, error messages, or arbitrary metadata.
     */
    override fun toString(): String =
        "ProviderProtectionOperationEvidence(" +
            "providerId=${providerId.value}, " +
            "operation=${operation.value}, " +
            "invocation=$invocation, " +
            "preExecutionReason=$preExecutionReason, " +
            "rejectionReason=$rejectionReason, " +
            "errorCode=${error?.code?.value ?: "null"}, " +
            "recordStatus=${recordStatus(recordResult)}" +
            ")"
}

private fun recordStatus(result: CircuitBreakerRecordResult?): String = when (result) {
    null -> "NONE"
    is CircuitBreakerRecordResult.Recorded -> "RECORDED"
    CircuitBreakerRecordResult.Ignored -> "IGNORED"
    CircuitBreakerRecordResult.StaleProbe -> "STALE_PROBE"
    is CircuitBreakerRecordResult.ProbeLeaseExpired -> "PROBE_LEASE_EXPIRED"
    is CircuitBreakerRecordResult.ClockRegression -> "CLOCK_REGRESSION"
    is CircuitBreakerRecordResult.PersistenceFailure -> "PERSISTENCE_FAILURE"
    CircuitBreakerRecordResult.ContentionLimitReached -> "CONTENTION_LIMIT_REACHED"
}
