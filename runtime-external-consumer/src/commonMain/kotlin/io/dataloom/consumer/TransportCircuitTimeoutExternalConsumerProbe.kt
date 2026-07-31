package io.dataloom.consumer

import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.runtime.retry.CircuitBreakerExecutionResult
import io.dataloom.runtime.retry.CircuitBreakerExecutionGate
import io.dataloom.runtime.retry.CircuitBreakerTransportOperationAdapter
import io.dataloom.runtime.retry.TransportCircuitOperation
import io.dataloom.runtime.retry.TransportProviderTimeoutRuntime

/** External JVM/iOS compilation probe for transport timeout and circuit APIs. */
public object TransportCircuitTimeoutExternalConsumerProbe {

    public fun timeoutProtected(
        provider: TransportProvider,
        clock: DataLoomClock,
        timeout: SchedulingDelay,
    ): TransportProvider = TransportProviderTimeoutRuntime.create(
        transportProvider = provider,
        clock = clock,
        providerTimeout = timeout,
    )

    public fun adapter(
        provider: TransportProvider,
        gate: CircuitBreakerExecutionGate,
    ): CircuitBreakerTransportOperationAdapter =
        CircuitBreakerTransportOperationAdapter(provider, gate)

    public suspend fun initialize(
        adapter: CircuitBreakerTransportOperationAdapter,
        scope: CircuitBreakerScope,
        context: ProviderInitializationContext,
    ): CircuitBreakerExecutionResult<Unit> = adapter.initialize(scope, context)

    public suspend fun health(
        adapter: CircuitBreakerTransportOperationAdapter,
        scope: CircuitBreakerScope,
    ): CircuitBreakerExecutionResult<ProviderHealth> = adapter.health(scope)

    public suspend fun push(
        adapter: CircuitBreakerTransportOperationAdapter,
        scope: CircuitBreakerScope,
        request: PushChangesRequest,
    ): CircuitBreakerExecutionResult<ChangeSetAcknowledgement> =
        adapter.pushChanges(scope, request)

    public suspend fun pull(
        adapter: CircuitBreakerTransportOperationAdapter,
        scope: CircuitBreakerScope,
        request: PullChangesRequest,
    ): CircuitBreakerExecutionResult<PullChangesResult> =
        adapter.pullChanges(scope, request)

    public fun pushOperation(): io.dataloom.api.retry.RetryOperation =
        TransportCircuitOperation.PUSH_CHANGES.retryOperation
}
