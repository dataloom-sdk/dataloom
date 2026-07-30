package io.dataloom.consumer

import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.queue.QueueAcquireRequest
import io.dataloom.api.queue.QueueAcquireResult
import io.dataloom.api.queue.QueueEnqueueRequest
import io.dataloom.api.queue.QueueProvider
import io.dataloom.runtime.retry.CircuitBreakerExecutionGate
import io.dataloom.runtime.retry.CircuitBreakerExecutionResult
import io.dataloom.runtime.retry.CircuitBreakerQueueOperationAdapter
import io.dataloom.runtime.retry.QueueCircuitBreakerFailureClassifier
import io.dataloom.runtime.retry.QueueCircuitOperation

/** Proves the queue-aware classifier and explicit operation identities are public. */
public fun queueCircuitOperationIdentity(): String =
    QueueCircuitOperation.ENQUEUE.retryOperation.value

/** Proves the circuit-protected queue adapter is consumable from common code. */
public fun queueCircuitAdapter(
    queueProvider: QueueProvider,
    executionGate: CircuitBreakerExecutionGate,
): CircuitBreakerQueueOperationAdapter = CircuitBreakerQueueOperationAdapter(
    queueProvider = queueProvider,
    executionGate = executionGate,
    failureClassifier = QueueCircuitBreakerFailureClassifier,
)

/** Proves enriched enqueue evidence remains visible to external consumers. */
public suspend fun circuitProtectedEnqueue(
    adapter: CircuitBreakerQueueOperationAdapter,
    scope: CircuitBreakerScope,
    request: QueueEnqueueRequest,
): CircuitBreakerExecutionResult<Unit> = adapter.enqueue(scope, request)

/** Proves enriched acquisition evidence remains visible to external consumers. */
public suspend fun circuitProtectedAcquire(
    adapter: CircuitBreakerQueueOperationAdapter,
    scope: CircuitBreakerScope,
    request: QueueAcquireRequest,
): CircuitBreakerExecutionResult<QueueAcquireResult> = adapter.acquire(scope, request)
