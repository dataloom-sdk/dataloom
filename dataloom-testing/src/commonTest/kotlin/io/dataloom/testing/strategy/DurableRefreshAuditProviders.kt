package io.dataloom.testing.strategy

import io.dataloom.api.circuit.CircuitBreakerCompareAndSetRequest
import io.dataloom.api.circuit.CircuitBreakerCompareAndSetResult
import io.dataloom.api.circuit.CircuitBreakerLoadResult
import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.circuit.CircuitBreakerStateRecord
import io.dataloom.api.circuit.CircuitBreakerStateStore
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.queue.QueueEnqueueRequest
import io.dataloom.api.queue.QueueIdempotentAdmissionProvider
import io.dataloom.api.queue.QueueIdempotentAdmissionResult
import io.dataloom.api.scheduling.ScheduleCancellationRequest
import io.dataloom.api.scheduling.ScheduleReceipt
import io.dataloom.api.scheduling.ScheduleRequest
import io.dataloom.api.scheduling.SchedulerProvider
import io.dataloom.api.storage.InboundChangeApplyRequest
import io.dataloom.api.storage.OutboundChangeReadRequest
import io.dataloom.api.storage.OutboundChangeReadResult
import io.dataloom.api.strategy.StrategyCacheAccessProvider
import io.dataloom.api.strategy.StrategyCacheAccessRequest
import io.dataloom.api.strategy.StrategyCacheAccessResult
import io.dataloom.api.strategy.StrategyCacheFreshnessEvidence
import io.dataloom.api.strategy.StrategyCacheState
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.synchronization.CheckpointReadRequest
import io.dataloom.api.synchronization.CheckpointWriteRequest
import io.dataloom.api.synchronization.OutboundChangeAcknowledgementRequest
import io.dataloom.api.synchronization.SynchronizationCheckpoint
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.testing.queue.InMemoryQueueProvider

internal class DurableRefreshAuditQueue(
    private val admission: suspend (
        QueueEnqueueRequest,
        InMemoryQueueProvider,
    ) -> ProviderOperationResult<QueueIdempotentAdmissionResult>,
    internal val delegate: InMemoryQueueProvider = InMemoryQueueProvider(),
) : QueueIdempotentAdmissionProvider by delegate {
    internal var admitCalls: Int = 0
        private set

    override suspend fun admit(
        request: QueueEnqueueRequest,
    ): ProviderOperationResult<QueueIdempotentAdmissionResult> {
        admitCalls++
        return admission(request, delegate)
    }
}

internal class DurableRefreshAuditScheduler(
    private val behavior: suspend (ScheduleRequest) -> ProviderOperationResult<ScheduleReceipt>,
) : SchedulerProvider {
    override val descriptor: ProviderDescriptor =
        durableRefreshDescriptor("durable-refresh-audit-scheduler", ProviderType.SCHEDULER)

    internal var scheduleCalls: Int = 0
        private set

    override suspend fun initialize(
        context: ProviderInitializationContext,
    ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

    override suspend fun health(): ProviderOperationResult<ProviderHealth> =
        ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

    override suspend fun close(): ProviderOperationResult<Unit> =
        ProviderOperationResult.Success(Unit)

    override suspend fun schedule(
        request: ScheduleRequest,
    ): ProviderOperationResult<ScheduleReceipt> {
        scheduleCalls++
        return behavior(request)
    }

    override suspend fun cancel(
        request: ScheduleCancellationRequest,
    ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)
}

internal class DurableRefreshAuditStorage : StrategyCacheAccessProvider {
    override val descriptor: ProviderDescriptor =
        durableRefreshDescriptor("durable-refresh-audit-storage", ProviderType.STORAGE)

    internal var cacheCalls: Int = 0
        private set
    internal var storageCalls: Int = 0
        private set

    override suspend fun initialize(
        context: ProviderInitializationContext,
    ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

    override suspend fun health(): ProviderOperationResult<ProviderHealth> =
        ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

    override suspend fun close(): ProviderOperationResult<Unit> =
        ProviderOperationResult.Success(Unit)

    override suspend fun evaluateCacheAccess(
        request: StrategyCacheAccessRequest,
    ): ProviderOperationResult<StrategyCacheAccessResult> {
        cacheCalls++
        return ProviderOperationResult.Success(
            StrategyCacheAccessResult.Available(
                StrategyCacheFreshnessEvidence(
                    cacheState = StrategyCacheState.FRESH,
                    observedAt = DataLoomInstant(1_000L),
                    validUntil = DataLoomInstant(2_000L),
                ),
            ),
        )
    }

    override suspend fun readOutboundChanges(
        request: OutboundChangeReadRequest,
    ): ProviderOperationResult<OutboundChangeReadResult> {
        storageCalls++
        return ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges)
    }

    override suspend fun applyInboundChanges(
        request: InboundChangeApplyRequest,
    ): ProviderOperationResult<Unit> {
        storageCalls++
        return ProviderOperationResult.Success(Unit)
    }

    override suspend fun acknowledgeOutboundChanges(
        request: OutboundChangeAcknowledgementRequest,
    ): ProviderOperationResult<Unit> {
        storageCalls++
        return ProviderOperationResult.Success(Unit)
    }

    override suspend fun readCheckpoint(
        request: CheckpointReadRequest,
    ): ProviderOperationResult<SynchronizationCheckpoint?> {
        storageCalls++
        return ProviderOperationResult.Success(null)
    }

    override suspend fun writeCheckpoint(
        request: CheckpointWriteRequest,
    ): ProviderOperationResult<Unit> {
        storageCalls++
        return ProviderOperationResult.Success(Unit)
    }
}

internal class DurableRefreshAuditTransport : TransportProvider {
    override val descriptor: ProviderDescriptor =
        durableRefreshDescriptor("durable-refresh-audit-transport", ProviderType.TRANSPORT)

    internal var pushCalls: Int = 0
        private set
    internal var pullCalls: Int = 0
        private set

    override suspend fun initialize(
        context: ProviderInitializationContext,
    ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

    override suspend fun health(): ProviderOperationResult<ProviderHealth> =
        ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

    override suspend fun close(): ProviderOperationResult<Unit> =
        ProviderOperationResult.Success(Unit)

    override suspend fun pushChanges(
        request: PushChangesRequest,
    ): ProviderOperationResult<ChangeSetAcknowledgement> {
        pushCalls++
        error("Durable cache admission must not push.")
    }

    override suspend fun pullChanges(
        request: PullChangesRequest,
    ): ProviderOperationResult<PullChangesResult> {
        pullCalls++
        error("Durable cache admission must not pull.")
    }
}

internal class DurableRefreshAuditCircuitStore : CircuitBreakerStateStore {
    internal var loadCalls: Int = 0
        private set

    override suspend fun load(
        scope: CircuitBreakerScope,
    ): ProviderOperationResult<CircuitBreakerLoadResult> {
        loadCalls++
        return ProviderOperationResult.Success(CircuitBreakerLoadResult.Missing)
    }

    override suspend fun compareAndSet(
        request: CircuitBreakerCompareAndSetRequest,
    ): ProviderOperationResult<CircuitBreakerCompareAndSetResult> =
        ProviderOperationResult.Success(
            CircuitBreakerCompareAndSetResult.Updated(
                CircuitBreakerStateRecord(
                    state = request.nextState,
                    version = (request.expectedVersion ?: -1L) + 1L,
                ),
            ),
        )
}

internal data class DurableRefreshAuditError(
    override val code: ErrorCode,
    override val category: ErrorCategory = ErrorCategory.PROVIDER,
    override val severity: ErrorSeverity = ErrorSeverity.ERROR,
    override val recoverability: Recoverability = Recoverability.RECOVERABLE,
    override val message: String = "Durable refresh audit test error.",
    override val cause: Throwable? = null,
) : DataLoomError

private fun durableRefreshDescriptor(
    id: String,
    type: ProviderType,
): ProviderDescriptor = ProviderDescriptor(
    id = ProviderId(id),
    name = ProviderName(id),
    type = type,
    version = ProviderVersion("1.0.0"),
)
