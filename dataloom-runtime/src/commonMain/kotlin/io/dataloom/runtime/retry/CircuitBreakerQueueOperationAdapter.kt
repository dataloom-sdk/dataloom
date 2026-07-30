package io.dataloom.runtime.retry

import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.queue.ExpiredLeaseRecoveryRequest
import io.dataloom.api.queue.ExpiredLeaseRecoveryResult
import io.dataloom.api.queue.QueueAcquireRequest
import io.dataloom.api.queue.QueueAcquireResult
import io.dataloom.api.queue.QueueCancellationRequest
import io.dataloom.api.queue.QueueCompletionRequest
import io.dataloom.api.queue.QueueDeferralRequest
import io.dataloom.api.queue.QueueEnqueueRequest
import io.dataloom.api.queue.QueueFailureRequest
import io.dataloom.api.queue.QueueProvider
import io.dataloom.api.queue.QueueRescheduleRequest
import io.dataloom.api.retry.RetryOperation

/** Stable queue operations that may own an explicit circuit scope. */
public enum class QueueCircuitOperation(
    /** Stable operation identity used by provider-operation circuit scopes. */
    public val retryOperation: RetryOperation,
) {
    INITIALIZE(RetryOperation("queue.initialize")),
    HEALTH(RetryOperation("queue.health")),
    CLOSE(RetryOperation("queue.close")),
    ENQUEUE(RetryOperation("queue.enqueue")),
    ACQUIRE(RetryOperation("queue.acquire")),
    COMPLETE(RetryOperation("queue.complete")),
    RESCHEDULE(RetryOperation("queue.reschedule")),
    DEFER(RetryOperation("queue.defer")),
    FAIL(RetryOperation("queue.fail")),
    CANCEL(RetryOperation("queue.cancel")),
    RECOVER_EXPIRED_LEASES(RetryOperation("queue.recover-expired-leases")),
}

/**
 * Circuit-protects explicit [QueueProvider] operations without collapsing the
 * enriched circuit result back into a plain provider result.
 *
 * ## Evidence preservation
 *
 * Every method returns [CircuitBreakerExecutionResult]. When the queue operation
 * ran, [CircuitBreakerExecutionResult.Executed] preserves both:
 *
 * 1. the exact canonical provider outcome; and
 * 2. the complete post-execution circuit recording outcome.
 *
 * This is required for durable queue safety. Returning only a
 * `ProviderOperationResult` would hide whether an operation already ran when a
 * later circuit-state write failed, which could cause an unsafe replay of an
 * already-committed mutation.
 *
 * ## Scope validation
 *
 * The caller supplies the exact [CircuitBreakerScope] for every operation. No
 * provider, operation, tenant, workflow, or global fallback is inferred.
 * Provider-bearing scopes must identify [descriptor]. Provider-operation scopes
 * must also use the exact [QueueCircuitOperation.retryOperation] for the method
 * being invoked.
 *
 * ## Cancellation and exceptions
 *
 * Caller cancellation and unexpected provider exceptions propagate unchanged.
 * The adapter invokes the queue provider at most once after circuit permission
 * is granted.
 */
public class CircuitBreakerQueueOperationAdapter(
    private val queueProvider: QueueProvider,
    executionGate: CircuitBreakerExecutionGate,
    failureClassifier: CircuitBreakerFailureClassifier =
        QueueCircuitBreakerFailureClassifier,
) {
    private val providerOperationAdapter = CircuitBreakerProviderOperationAdapter(
        executionGate = executionGate,
        failureClassifier = failureClassifier,
    )

    /** Exact descriptor of the protected queue provider. */
    public val descriptor: ProviderDescriptor
        get() = queueProvider.descriptor

    public suspend fun initialize(
        scope: CircuitBreakerScope,
        context: io.dataloom.api.provider.ProviderInitializationContext,
    ): CircuitBreakerExecutionResult<Unit> = execute(
        scope = scope,
        operation = QueueCircuitOperation.INITIALIZE,
    ) {
        queueProvider.initialize(context)
    }

    public suspend fun health(
        scope: CircuitBreakerScope,
    ): CircuitBreakerExecutionResult<io.dataloom.api.provider.ProviderHealth> = execute(
        scope = scope,
        operation = QueueCircuitOperation.HEALTH,
    ) {
        queueProvider.health()
    }

    public suspend fun close(
        scope: CircuitBreakerScope,
    ): CircuitBreakerExecutionResult<Unit> = execute(
        scope = scope,
        operation = QueueCircuitOperation.CLOSE,
    ) {
        queueProvider.close()
    }

    public suspend fun enqueue(
        scope: CircuitBreakerScope,
        request: QueueEnqueueRequest,
    ): CircuitBreakerExecutionResult<Unit> = execute(
        scope = scope,
        operation = QueueCircuitOperation.ENQUEUE,
    ) {
        queueProvider.enqueue(request)
    }

    public suspend fun acquire(
        scope: CircuitBreakerScope,
        request: QueueAcquireRequest,
    ): CircuitBreakerExecutionResult<QueueAcquireResult> = execute(
        scope = scope,
        operation = QueueCircuitOperation.ACQUIRE,
    ) {
        queueProvider.acquire(request)
    }

    public suspend fun complete(
        scope: CircuitBreakerScope,
        request: QueueCompletionRequest,
    ): CircuitBreakerExecutionResult<Unit> = execute(
        scope = scope,
        operation = QueueCircuitOperation.COMPLETE,
    ) {
        queueProvider.complete(request)
    }

    public suspend fun reschedule(
        scope: CircuitBreakerScope,
        request: QueueRescheduleRequest,
    ): CircuitBreakerExecutionResult<Unit> = execute(
        scope = scope,
        operation = QueueCircuitOperation.RESCHEDULE,
    ) {
        queueProvider.reschedule(request)
    }

    public suspend fun defer(
        scope: CircuitBreakerScope,
        request: QueueDeferralRequest,
    ): CircuitBreakerExecutionResult<Unit> = execute(
        scope = scope,
        operation = QueueCircuitOperation.DEFER,
    ) {
        queueProvider.defer(request)
    }

    public suspend fun fail(
        scope: CircuitBreakerScope,
        request: QueueFailureRequest,
    ): CircuitBreakerExecutionResult<Unit> = execute(
        scope = scope,
        operation = QueueCircuitOperation.FAIL,
    ) {
        queueProvider.fail(request)
    }

    public suspend fun cancel(
        scope: CircuitBreakerScope,
        request: QueueCancellationRequest,
    ): CircuitBreakerExecutionResult<Unit> = execute(
        scope = scope,
        operation = QueueCircuitOperation.CANCEL,
    ) {
        queueProvider.cancel(request)
    }

    public suspend fun recoverExpiredLeases(
        scope: CircuitBreakerScope,
        request: ExpiredLeaseRecoveryRequest,
    ): CircuitBreakerExecutionResult<ExpiredLeaseRecoveryResult> = execute(
        scope = scope,
        operation = QueueCircuitOperation.RECOVER_EXPIRED_LEASES,
    ) {
        queueProvider.recoverExpiredLeases(request)
    }

    private suspend fun <T> execute(
        scope: CircuitBreakerScope,
        operation: QueueCircuitOperation,
        block: suspend () -> io.dataloom.api.provider.ProviderOperationResult<T>,
    ): CircuitBreakerExecutionResult<T> {
        validateScope(scope, operation)
        return providerOperationAdapter.execute(scope, block)
    }

    private fun validateScope(
        scope: CircuitBreakerScope,
        operation: QueueCircuitOperation,
    ) {
        require(scope.providerId == null || scope.providerId == descriptor.id) {
            "Queue circuit scope provider must match the protected queue provider."
        }
        require(scope.operation == null || scope.operation == operation.retryOperation) {
            "Queue circuit scope operation must match ${operation.retryOperation.value}."
        }
    }
}
