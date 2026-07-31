package io.dataloom.runtime.worker

import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.runtime.retry.CircuitBreakerExecutionGate
import io.dataloom.runtime.retry.CircuitBreakerFailureClassifier
import io.dataloom.runtime.retry.DefaultCircuitBreakerFailureClassifier

/**
 * Explicit runtime assembly for circuit-protecting the queue worker's follow-up
 * scheduler call.
 *
 * The scheduler circuit is intentionally separate from queue operation circuits.
 * Construction performs no state-store access, provider call, clock read, or
 * coroutine launch.
 */
public class CircuitBreakerQueueWorkerSchedulerCircuit(
    public val executionGate: CircuitBreakerExecutionGate,
    public val scope: CircuitBreakerScope,
    public val failureClassifier: CircuitBreakerFailureClassifier =
        DefaultCircuitBreakerFailureClassifier,
)
