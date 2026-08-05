package io.dataloom.runtime.facade

import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.circuit.CircuitBreakerStateStore
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.runtime.retry.CircuitBreakerConfiguration
import io.dataloom.runtime.retry.CircuitBreakerFailureClassifier
import io.dataloom.runtime.retry.StrategyCacheAccessCircuitBreakerFailureClassifier
import io.dataloom.runtime.retry.StrategyCacheAccessCircuitOperation
import io.dataloom.runtime.retry.StrategyLocalFallbackCircuitBreakerFailureClassifier
import io.dataloom.runtime.retry.StrategyLocalFallbackCircuitOperation
import io.dataloom.runtime.retry.StrategyReconciliationCircuitBreakerFailureClassifier
import io.dataloom.runtime.retry.StrategyReconciliationCircuitOperation

private object StrategyProviderProtectionPrimaryConstructorMarker

/**
 * Explicit independent circuit/timeout protection for cache-first local-state
 * verification.
 *
 * The state store and scope are not inferred from generic storage protection.
 * The operation-bearing scope, when present, must identify exactly
 * `strategy.evaluate-cache-access`.
 */
public class DataLoomStrategyCacheAccessProtectionSpec(
    public val circuitBreakerConfiguration: CircuitBreakerConfiguration,
    public val circuitBreakerStateStore: CircuitBreakerStateStore,
    public val scope: CircuitBreakerScope,
    public val providerTimeout: SchedulingDelay? = null,
    public val failureClassifier: CircuitBreakerFailureClassifier =
        StrategyCacheAccessCircuitBreakerFailureClassifier,
) {
    init {
        require(
            scope.operation == null ||
                scope.operation ==
                StrategyCacheAccessCircuitOperation.EVALUATE_CACHE_ACCESS.retryOperation,
        ) {
            "Strategy cache-access scope operation must be " +
                "'${StrategyCacheAccessCircuitOperation.EVALUATE_CACHE_ACCESS.retryOperation.value}'."
        }
    }

    /** Bounded diagnostics that exclude the state store and classifier. */
    override fun toString(): String =
        "DataLoomStrategyCacheAccessProtectionSpec(" +
            "providerTimeoutConfigured=${providerTimeout != null}, " +
            "providerScoped=${scope.providerId != null}, " +
            "operationScoped=${scope.operation != null}" +
            ")"
}

/**
 * Explicit protection specification for application-owned remote-first local
 * fallback evaluation.
 *
 * The state store and scope are never inferred from storage protection. The
 * operation-bearing scope, when present, must identify exactly
 * `strategy.evaluate-local-fallback`.
 */
public class DataLoomStrategyLocalFallbackProtectionSpec(
    public val circuitBreakerConfiguration: CircuitBreakerConfiguration,
    public val circuitBreakerStateStore: CircuitBreakerStateStore,
    public val scope: CircuitBreakerScope,
    public val providerTimeout: SchedulingDelay? = null,
    public val failureClassifier: CircuitBreakerFailureClassifier =
        StrategyLocalFallbackCircuitBreakerFailureClassifier,
) {
    init {
        require(
            scope.operation == null ||
                scope.operation ==
                StrategyLocalFallbackCircuitOperation.EVALUATE_LOCAL_FALLBACK.retryOperation,
        ) {
            "Strategy local fallback scope operation must be " +
                "'${StrategyLocalFallbackCircuitOperation.EVALUATE_LOCAL_FALLBACK.retryOperation.value}'."
        }
    }

    /** Bounded diagnostics that exclude the state store and classifier. */
    override fun toString(): String =
        "DataLoomStrategyLocalFallbackProtectionSpec(" +
            "providerTimeoutConfigured=${providerTimeout != null}, " +
            "providerScoped=${scope.providerId != null}, " +
            "operationScoped=${scope.operation != null}" +
            ")"
}

/**
 * Explicit independent circuit/timeout protection for accepted-plan
 * reconciliation.
 */
public class DataLoomStrategyReconciliationProtectionSpec(
    public val circuitBreakerConfiguration: CircuitBreakerConfiguration,
    public val circuitBreakerStateStore: CircuitBreakerStateStore,
    public val scope: CircuitBreakerScope,
    public val providerTimeout: SchedulingDelay? = null,
    public val failureClassifier: CircuitBreakerFailureClassifier =
        StrategyReconciliationCircuitBreakerFailureClassifier,
) {
    init {
        require(
            scope.operation == null ||
                scope.operation ==
                StrategyReconciliationCircuitOperation.RECONCILE.retryOperation,
        ) {
            "Strategy reconciliation scope operation must be " +
                "'${StrategyReconciliationCircuitOperation.RECONCILE.retryOperation.value}'."
        }
    }

    override fun toString(): String =
        "DataLoomStrategyReconciliationProtectionSpec(" +
            "providerTimeoutConfigured=${providerTimeout != null}, " +
            "providerScoped=${scope.providerId != null}, " +
            "operationScoped=${scope.operation != null}" +
            ")"
}

/**
 * Plan-aware provider protection for built-in and accepted-plan strategy
 * execution.
 *
 * Every role is optional because the immutable plan decides which capabilities
 * are required. A protected execution is rejected before provider invocation
 * when a resolved required provider has no corresponding protection spec.
 */
public class DataLoomStrategyProviderProtectionSpec private constructor(
    public val storage: DataLoomStorageProtectionSpec?,
    public val transport: DataLoomTransportProtectionSpec?,
    public val localFallback: DataLoomStrategyLocalFallbackProtectionSpec?,
    public val reconciliation: DataLoomStrategyReconciliationProtectionSpec?,
    public val cacheAccess: DataLoomStrategyCacheAccessProtectionSpec?,
    @Suppress("UNUSED_PARAMETER")
    marker: StrategyProviderProtectionPrimaryConstructorMarker,
) {
    /** Historical no-argument constructor retained with its fail-fast behavior. */
    public constructor() : this(
        null,
        null,
        null,
        null,
        null,
        StrategyProviderProtectionPrimaryConstructorMarker,
    )

    /** Existing source and binary constructor retained unchanged. */
    public constructor(
        storage: DataLoomStorageProtectionSpec? = null,
        transport: DataLoomTransportProtectionSpec? = null,
        localFallback: DataLoomStrategyLocalFallbackProtectionSpec? = null,
        reconciliation: DataLoomStrategyReconciliationProtectionSpec? = null,
    ) : this(
        storage,
        transport,
        localFallback,
        reconciliation,
        null,
        StrategyProviderProtectionPrimaryConstructorMarker,
    )

    /** Additive constructor for independently protected cache access. */
    public constructor(
        cacheAccess: DataLoomStrategyCacheAccessProtectionSpec,
        storage: DataLoomStorageProtectionSpec? = null,
        transport: DataLoomTransportProtectionSpec? = null,
        localFallback: DataLoomStrategyLocalFallbackProtectionSpec? = null,
        reconciliation: DataLoomStrategyReconciliationProtectionSpec? = null,
    ) : this(
        storage,
        transport,
        localFallback,
        reconciliation,
        cacheAccess,
        StrategyProviderProtectionPrimaryConstructorMarker,
    )

    init {
        require(
            storage != null ||
                transport != null ||
                cacheAccess != null ||
                localFallback != null ||
                reconciliation != null,
        ) {
            "DataLoomStrategyProviderProtectionSpec requires at least one configured role."
        }
    }

    /** Bounded diagnostics that exclude stores and classifiers. */
    override fun toString(): String =
        "DataLoomStrategyProviderProtectionSpec(" +
            "storageConfigured=${storage != null}, " +
            "transportConfigured=${transport != null}, " +
            "cacheAccessConfigured=${cacheAccess != null}, " +
            "localFallbackConfigured=${localFallback != null}, " +
            "reconciliationConfigured=${reconciliation != null}" +
            ")"
}
