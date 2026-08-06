package io.dataloom.testing.strategy

import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.IdentifierGenerator
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.ScheduleId
import io.dataloom.api.identifier.SynchronizationEventId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.StrategyProviderBindings
import io.dataloom.api.queue.QueueEnqueueRequest
import io.dataloom.api.queue.QueueIdempotentAdmissionResult
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.runtime.RuntimeIdentifierGenerators
import io.dataloom.api.scheduling.ScheduleReceipt
import io.dataloom.api.scheduling.ScheduleRequest
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.strategy.CacheFirstStrategyProfile
import io.dataloom.api.strategy.StrategyCacheState
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyConnectivity
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyOperationInput
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.strategy.StrategyProviderHealth
import io.dataloom.api.strategy.StrategyRuntimeEvidence
import io.dataloom.api.strategy.StrategySynchronizationRequest
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.facade.DataLoom
import io.dataloom.runtime.facade.DataLoomBuilder
import io.dataloom.runtime.facade.DataLoomStorageProtectionSpec
import io.dataloom.runtime.facade.DataLoomStrategyCacheAccessProtectionSpec
import io.dataloom.runtime.facade.DataLoomStrategyProviderProtectionSpec
import io.dataloom.runtime.retry.CircuitBreakerConfiguration
import io.dataloom.runtime.retry.StorageCircuitOperation
import io.dataloom.runtime.retry.StorageCircuitScopes
import io.dataloom.runtime.retry.StrategyCacheAccessCircuitOperation
import io.dataloom.testing.queue.InMemoryQueueProvider
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

internal data class DurableRefreshAuditFixture(
    val dataLoom: DataLoom,
    val storage: DurableRefreshAuditStorage,
    val transport: DurableRefreshAuditTransport,
    val queue: DurableRefreshAuditQueue,
    val scheduler: DurableRefreshAuditScheduler,
    val storageCircuitStore: DurableRefreshAuditCircuitStore,
    val cacheCircuitStore: DurableRefreshAuditCircuitStore,
)

internal fun durableRefreshAuditFixture(
    protected: Boolean = false,
    queueAdmission: suspend (
        QueueEnqueueRequest,
        InMemoryQueueProvider,
    ) -> ProviderOperationResult<QueueIdempotentAdmissionResult> = { request, delegate ->
        delegate.admit(request)
    },
    schedulerBehavior: suspend (
        ScheduleRequest,
    ) -> ProviderOperationResult<ScheduleReceipt> = { request ->
        ProviderOperationResult.Success(ScheduleReceipt(request.id))
    },
): DurableRefreshAuditFixture {
    val storage = DurableRefreshAuditStorage()
    val transport = DurableRefreshAuditTransport()
    val queue = DurableRefreshAuditQueue(queueAdmission)
    val scheduler = DurableRefreshAuditScheduler(schedulerBehavior)
    val storageStore = DurableRefreshAuditCircuitStore()
    val cacheStore = DurableRefreshAuditCircuitStore()
    val bindings = StrategyProviderBindings(
        storageProviderId = storage.descriptor.id,
        transportProviderId = transport.descriptor.id,
        queueProviderId = queue.descriptor.id,
        schedulerProviderId = scheduler.descriptor.id,
    )
    val builder = DataLoomBuilder()
        .runtimeDependencies(durableRefreshRuntimeDependencies())
        .providers(storage, transport, queue, scheduler)
        .defaultStrategyProviderBindings(bindings)
    if (protected) {
        builder.strategyProviderProtectionConfiguration(
            DataLoomStrategyProviderProtectionSpec(
                storage = DataLoomStorageProtectionSpec(
                    circuitBreakerConfiguration = durableRefreshCircuitConfiguration(),
                    circuitBreakerStateStore = storageStore,
                    scopes = durableRefreshStorageScopes(storage.descriptor.id),
                ),
                cacheAccess = DataLoomStrategyCacheAccessProtectionSpec(
                    circuitBreakerConfiguration = durableRefreshCircuitConfiguration(),
                    circuitBreakerStateStore = cacheStore,
                    scope = CircuitBreakerScope.providerOperation(
                        providerId = storage.descriptor.id,
                        operation = StrategyCacheAccessCircuitOperation
                            .EVALUATE_CACHE_ACCESS
                            .retryOperation,
                    ),
                ),
            ),
        )
    }
    return DurableRefreshAuditFixture(
        dataLoom = builder.build(),
        storage = storage,
        transport = transport,
        queue = queue,
        scheduler = scheduler,
        storageCircuitStore = storageStore,
        cacheCircuitStore = cacheStore,
    )
}

internal fun durableRefreshAuditRequest(): StrategySynchronizationRequest =
    StrategySynchronizationRequest(
        request = SynchronizationRequest(
            workflowId = WorkflowId("durable-refresh-audit-workflow"),
            sessionId = SynchronizationSessionId("durable-refresh-audit-session"),
            direction = SynchronizationDirection.PULL,
            mode = SynchronizationMode.DELTA,
            context = ExecutionContext(
                executionId = ExecutionId("durable-refresh-audit-execution"),
                correlationId = CorrelationId("durable-refresh-audit-correlation"),
            ),
        ),
        decisionId = StrategyDecisionId("durable-refresh-audit-decision"),
        planId = StrategyPlanId("durable-refresh-audit-plan"),
        profile = CacheFirstStrategyProfile(
            id = StrategyProfileId("durable-refresh-audit-profile"),
            configurationVersion = StrategyConfigurationVersion(1L),
            refreshOnFreshHit = true,
            requireDurableRefresh = true,
        ),
        evidence = StrategyRuntimeEvidence(
            connectivity = StrategyConnectivity.AVAILABLE,
            cacheState = StrategyCacheState.FRESH,
            storageHealth = StrategyProviderHealth.HEALTHY,
            transportHealth = StrategyProviderHealth.HEALTHY,
        ),
        input = StrategyOperationInput.CacheFirstDurableRefresh(
            queueEntryId = QueueEntryId("durable-refresh-audit-entry"),
            scheduleId = ScheduleId("durable-refresh-audit-schedule"),
        ),
    )

internal fun <T> runDurableRefreshAudit(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(
        object : Continuation<T> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<T>) {
                outcome = result
            }
        },
    )
    return requireNotNull(outcome) {
        "Durable refresh audit operation did not complete synchronously."
    }.getOrThrow()
}

private fun durableRefreshCircuitConfiguration(): CircuitBreakerConfiguration =
    CircuitBreakerConfiguration(
        failureThreshold = 2,
        failureWindow = SchedulingDelay(1_000L),
        openDuration = SchedulingDelay(10_000L),
    )

private fun durableRefreshStorageScopes(providerId: ProviderId): StorageCircuitScopes =
    StorageCircuitScopes(
        initialization = durableRefreshStorageScope(providerId, StorageCircuitOperation.INITIALIZE),
        health = durableRefreshStorageScope(providerId, StorageCircuitOperation.HEALTH),
        close = durableRefreshStorageScope(providerId, StorageCircuitOperation.CLOSE),
        readOutboundChanges = durableRefreshStorageScope(
            providerId,
            StorageCircuitOperation.READ_OUTBOUND_CHANGES,
        ),
        applyInboundChanges = durableRefreshStorageScope(
            providerId,
            StorageCircuitOperation.APPLY_INBOUND_CHANGES,
        ),
        acknowledgeOutboundChanges = durableRefreshStorageScope(
            providerId,
            StorageCircuitOperation.ACKNOWLEDGE_OUTBOUND_CHANGES,
        ),
        readCheckpoint = durableRefreshStorageScope(
            providerId,
            StorageCircuitOperation.READ_CHECKPOINT,
        ),
        writeCheckpoint = durableRefreshStorageScope(
            providerId,
            StorageCircuitOperation.WRITE_CHECKPOINT,
        ),
    )

private fun durableRefreshStorageScope(
    providerId: ProviderId,
    operation: StorageCircuitOperation,
): CircuitBreakerScope = CircuitBreakerScope.providerOperation(
    providerId = providerId,
    operation = operation.retryOperation,
)

private fun durableRefreshRuntimeDependencies(): RuntimeDependencies = RuntimeDependencies(
    clock = object : DataLoomClock {
        override fun now(): DataLoomInstant = DataLoomInstant(1_000L)
    },
    identifiers = RuntimeIdentifierGenerators(
        synchronizationEventIds = durableRefreshFixed(SynchronizationEventId("event")),
        queueEntryIds = durableRefreshFixed(QueueEntryId("generated-entry")),
        queueLeaseIds = durableRefreshFixed(QueueLeaseId("generated-lease")),
        conflictIds = durableRefreshFixed(ConflictId("conflict")),
    ),
)

private fun <T> durableRefreshFixed(value: T): IdentifierGenerator<T> =
    object : IdentifierGenerator<T> {
        override fun generate(): T = value
    }
