package io.dataloom.runtime.retry

import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider

/**
 * Applies explicit durable circuit permission to transport lifecycle, push, and
 * pull operations while preserving the complete execution and recording result.
 *
 * This adapter deliberately does not implement [TransportProvider]. A plain
 * provider result cannot represent both an already-executed remote operation and
 * a later circuit-state persistence failure without losing replay-critical
 * evidence.
 */
public class CircuitBreakerTransportOperationAdapter(
    private val transportProvider: TransportProvider,
    executionGate: CircuitBreakerExecutionGate,
    failureClassifier: CircuitBreakerFailureClassifier =
        TransportCircuitBreakerFailureClassifier,
) {
    private val providerOperationAdapter = CircuitBreakerProviderOperationAdapter(
        executionGate = executionGate,
        failureClassifier = failureClassifier,
    )

    public val descriptor: ProviderDescriptor
        get() = transportProvider.descriptor

    public suspend fun initialize(
        scope: CircuitBreakerScope,
        context: ProviderInitializationContext,
    ): CircuitBreakerExecutionResult<Unit> = execute(
        scope = scope,
        operation = TransportCircuitOperation.INITIALIZE,
    ) {
        transportProvider.initialize(context)
    }

    public suspend fun health(
        scope: CircuitBreakerScope,
    ): CircuitBreakerExecutionResult<ProviderHealth> = execute(
        scope = scope,
        operation = TransportCircuitOperation.HEALTH,
    ) {
        transportProvider.health()
    }

    public suspend fun close(
        scope: CircuitBreakerScope,
    ): CircuitBreakerExecutionResult<Unit> = execute(
        scope = scope,
        operation = TransportCircuitOperation.CLOSE,
    ) {
        transportProvider.close()
    }

    public suspend fun pushChanges(
        scope: CircuitBreakerScope,
        request: PushChangesRequest,
    ): CircuitBreakerExecutionResult<ChangeSetAcknowledgement> = execute(
        scope = scope,
        operation = TransportCircuitOperation.PUSH_CHANGES,
    ) {
        transportProvider.pushChanges(request)
    }

    public suspend fun pullChanges(
        scope: CircuitBreakerScope,
        request: PullChangesRequest,
    ): CircuitBreakerExecutionResult<PullChangesResult> = execute(
        scope = scope,
        operation = TransportCircuitOperation.PULL_CHANGES,
    ) {
        transportProvider.pullChanges(request)
    }

    private suspend fun <T> execute(
        scope: CircuitBreakerScope,
        operation: TransportCircuitOperation,
        block: suspend () -> io.dataloom.api.provider.ProviderOperationResult<T>,
    ): CircuitBreakerExecutionResult<T> {
        require(scope.providerId == null || scope.providerId == transportProvider.descriptor.id) {
            "Transport circuit scope provider must match the transport provider."
        }
        require(scope.operation == null || scope.operation == operation.retryOperation) {
            "Transport circuit scope operation must match ${operation.retryOperation.value}."
        }
        return providerOperationAdapter.execute(scope, block)
    }
}
