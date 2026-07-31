package io.dataloom.runtime.facade

import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.circuit.CircuitBreakerStateStore
import io.dataloom.runtime.queue.QueueProcessingCircuitScopes
import io.dataloom.runtime.retry.CircuitBreakerConfiguration
import io.dataloom.runtime.retry.CircuitBreakerFailureClassifier
import io.dataloom.runtime.retry.QueueCircuitBreakerFailureClassifier

/**
 * Immutable builder specification for [DataLoomCircuitQueueWorker].
 *
 * [workerSpec] owns queue execution, retry, scheduling, and optional queue-
 * provider timeout configuration. This specification adds one explicit circuit
 * state store, deterministic circuit configuration, recovery scope, processing
 * scopes, and queue-aware failure classification.
 *
 * Construction performs no state-store access, provider operation, clock read,
 * queue mutation, scheduling, identifier generation, or coroutine launch.
 */
public class DataLoomCircuitQueueWorkerSpec(
    /** Existing queue-worker dependencies and timeout configuration. */
    public val workerSpec: DataLoomQueueWorkerSpec,

    /** Deterministic circuit thresholds, windows, and half-open probe lease. */
    public val circuitBreakerConfiguration: CircuitBreakerConfiguration,

    /** Application-supplied durable circuit state store. */
    public val circuitBreakerStateStore: CircuitBreakerStateStore,

    /** Exact circuit scope for expired-lease recovery. */
    public val recoveryScope: CircuitBreakerScope,

    /** Exact scopes for acquisition and every durable queue transition. */
    public val processingScopes: QueueProcessingCircuitScopes,

    /** Queue-aware classifier used by every protected queue-provider operation. */
    public val failureClassifier: CircuitBreakerFailureClassifier =
        QueueCircuitBreakerFailureClassifier,
)
