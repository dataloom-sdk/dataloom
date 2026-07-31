package io.dataloom.runtime.retry

import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest

/**
 * Immutable transport-operation surface with one exact scope bound to every call.
 *
 * Construction validates all scopes before provider, state-store, clock, or
 * timeout activity. Every method preserves the complete provider execution and
 * later circuit-recording result.
 */
public class ProtectedTransportOperations internal constructor(
    private val adapter: CircuitBreakerTransportOperationAdapter,
    public val scopes: TransportCircuitScopes,
) {
    /** Exact descriptor of the protected transport provider. */
    public val descriptor: ProviderDescriptor
        get() = adapter.descriptor

    init {
        validate(scopes.initialization, TransportCircuitOperation.INITIALIZE)
        validate(scopes.health, TransportCircuitOperation.HEALTH)
        validate(scopes.close, TransportCircuitOperation.CLOSE)
        validate(scopes.pushChanges, TransportCircuitOperation.PUSH_CHANGES)
        validate(scopes.pullChanges, TransportCircuitOperation.PULL_CHANGES)
    }

    public suspend fun initialize(
        context: ProviderInitializationContext,
    ): CircuitBreakerExecutionResult<Unit> =
        adapter.initialize(scopes.initialization, context)

    public suspend fun health(): CircuitBreakerExecutionResult<ProviderHealth> =
        adapter.health(scopes.health)

    public suspend fun close(): CircuitBreakerExecutionResult<Unit> =
        adapter.close(scopes.close)

    public suspend fun pushChanges(
        request: PushChangesRequest,
    ): CircuitBreakerExecutionResult<ChangeSetAcknowledgement> =
        adapter.pushChanges(scopes.pushChanges, request)

    public suspend fun pullChanges(
        request: PullChangesRequest,
    ): CircuitBreakerExecutionResult<PullChangesResult> =
        adapter.pullChanges(scopes.pullChanges, request)

    private fun validate(
        scope: CircuitBreakerScope,
        operation: TransportCircuitOperation,
    ) {
        require(scope.providerId == null || scope.providerId == descriptor.id) {
            "Transport protection scope provider must match the protected transport provider."
        }
        require(scope.operation == null || scope.operation == operation.retryOperation) {
            "Transport protection scope operation must match ${operation.retryOperation.value}."
        }
    }
}
