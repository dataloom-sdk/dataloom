package io.dataloom.runtime.facade

import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.circuit.CircuitBreakerStateStore
import io.dataloom.runtime.retry.CircuitBreakerConfiguration
import io.dataloom.runtime.retry.CircuitBreakerFailureClassifier
import io.dataloom.runtime.retry.DefaultCircuitBreakerFailureClassifier

/**
 * Explicit scheduler-circuit policy for [DataLoomCircuitQueueWorker].
 *
 * Queue-provider circuit configuration is deliberately not reused. Applications
 * supply the scheduler's own durable state store, deterministic circuit
 * configuration, exact scope, and optional classifier. Construction performs no
 * store access, provider call, timeout execution, clock read, or scheduling.
 */
public class DataLoomCircuitQueueWorkerSchedulerSpec(
    /** Deterministic thresholds, windows, and half-open probe lease. */
    public val circuitBreakerConfiguration: CircuitBreakerConfiguration,

    /** Application-supplied durable state store for the scheduler circuit. */
    public val circuitBreakerStateStore: CircuitBreakerStateStore,

    /** Exact global, workflow, provider, or scheduler.schedule scope. */
    public val scope: CircuitBreakerScope,

    /** Scheduler failure classification used after provider invocation. */
    public val failureClassifier: CircuitBreakerFailureClassifier =
        DefaultCircuitBreakerFailureClassifier,
)
