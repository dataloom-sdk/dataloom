package io.dataloom.runtime.facade

import io.dataloom.api.circuit.CircuitBreakerCompareAndSetRequest
import io.dataloom.api.circuit.CircuitBreakerCompareAndSetResult
import io.dataloom.api.circuit.CircuitBreakerLoadResult
import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.circuit.CircuitBreakerStateRecord
import io.dataloom.api.circuit.CircuitBreakerStateStore
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.IdentifierGenerator
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.SynchronizationEventId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderLifecycleResult
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.provider.StrategyProviderBindings
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.runtime.RuntimeIdentifierGenerators
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.storage.InboundChangeApplyRequest
import io.dataloom.api.storage.OutboundChangeReadRequest
import io.dataloom.api.storage.OutboundChangeReadResult
import io.dataloom.api.strategy.CacheFirstStrategyProfile
import io.dataloom.api.strategy.StrategyCacheAccessProvider
import io.dataloom.api.strategy.StrategyCacheAccessRequest
import io.dataloom.api.strategy.StrategyCacheAccessResult
import io.dataloom.api.strategy.StrategyCacheFreshnessEvidence
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
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.synchronization.CheckpointReadRequest
import io.dataloom.api.synchronization.CheckpointWriteRequest
import io.dataloom.api.synchronization.OutboundChangeAcknowledgementRequest
import io.dataloom.api.synchronization.SynchronizationCheckpoint
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.runtime.execution.protection.ProviderProtectionInvocation
import io.dataloom.runtime.retry.CircuitBreakerConfiguration
import io.dataloom.runtime.retry.StorageCircuitOperation
import io.dataloom.runtime.retry.StorageCircuitScopes
import io.dataloom.runtime.retry.StrategyCacheAccessCircuitOperation
import io.dataloom.runtime.retry.TransportCircuitOperation
import io.dataloom.runtime.retry.TransportCircuitScopes
import io.dataloom.runtime.strategy.StrategyCacheInlineRefreshResult
import io.dataloom.runtime.strategy.StrategyCacheServedWithInlineRefreshResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class DataLoomBuilderProtectedInlineCacheRefreshTest {

    @Test
    fun protectedInlineRefreshUsesIndependentCacheStorageAndTransportBoundaries() = runTest {
        val storage = CacheStorage()
        val transport = NoChangeTransport()
        val storageStore = CircuitStore()
        val cacheStore = CircuitStore()
        val transportStore = CircuitStore()
        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(dependencies())
            .provider(storage)
            .provider(transport)
            .defaultStrategyProviderBindings(
                StrategyProviderBindings(
                    storageProviderId = storage.descriptor.id,
                    transportProviderId = transport.descriptor.id,
                ),
            )
            .strategyProviderProtectionConfiguration(
                DataLoomStrategyProviderProtectionSpec(
                    cacheAccess = DataLoomStrategyCacheAccessProtectionSpec(
                        circuitBreakerConfiguration = configuration(),
                        circuitBreakerStateStore = cacheStore,
                        scope = CircuitBreakerScope.providerOperation(
                            storage.descriptor.id,
                            StrategyCacheAccessCircuitOperation
                                .EVALUATE_CACHE_ACCESS.retryOperation,
                        ),
                    ),
                    storage = DataLoomStorageProtectionSpec(
                        circuitBreakerConfiguration = configuration(),
                        circuitBreakerStateStore = storageStore,
                        scopes = storageScopes(storage.descriptor.id),
                    ),
                    transport = DataLoomTransportProtectionSpec(
                        circuitBreakerConfiguration = configuration(),
                        circuitBreakerStateStore = transportStore,
                        scopes = transportScopes(transport.descriptor.id),
                    ),
                ),
            )
            .build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())

        val result = requireNotNull(dataLoom.protectedStrategySynchronization)
            .synchronize(request())
        val served = assertIs<StrategyCacheServedWithInlineRefreshResult>(
            result.strategyResult,
        )
        assertIs<StrategyCacheInlineRefreshResult.Completed>(served.refresh)

        assertEquals(
            listOf(
                StrategyCacheAccessCircuitOperation.EVALUATE_CACHE_ACCESS.retryOperation,
                StorageCircuitOperation.READ_CHECKPOINT.retryOperation,
                TransportCircuitOperation.PULL_CHANGES.retryOperation,
            ),
            result.operationEvidence.map { it.operation },
        )
        assertTrue(
            result.operationEvidence.all {
                it.invocation == ProviderProtectionInvocation.SUCCEEDED
            },
        )
        assertEquals(1, storage.cacheCalls)
        assertEquals(1, storage.readCheckpointCalls)
        assertEquals(1, transport.pullCalls)
        assertTrue(cacheStore.loadCalls >= 1)
        assertTrue(storageStore.loadCalls >= 1)
        assertTrue(transportStore.loadCalls >= 1)
    }

    private class CacheStorage : StrategyCacheAccessProvider {
        override val descriptor = descriptor("protected-inline-storage", ProviderType.STORAGE)
        var cacheCalls = 0
        var readCheckpointCalls = 0

        override suspend fun initialize(context: ProviderInitializationContext) = success(Unit)
        override suspend fun health() = success(ProviderHealth(ProviderHealthStatus.HEALTHY))
        override suspend fun close() = success(Unit)

        override suspend fun evaluateCacheAccess(
            request: StrategyCacheAccessRequest,
        ): ProviderOperationResult<StrategyCacheAccessResult> {
            cacheCalls++
            return success(
                StrategyCacheAccessResult.Available(
                    StrategyCacheFreshnessEvidence(
                        StrategyCacheState.FRESH,
                        DataLoomInstant(1_000L),
                        DataLoomInstant(2_000L),
                    ),
                ),
            )
        }

        override suspend fun readOutboundChanges(request: OutboundChangeReadRequest) =
            success(OutboundChangeReadResult.NoChanges)

        override suspend fun applyInboundChanges(request: InboundChangeApplyRequest) =
            success(Unit)

        override suspend fun acknowledgeOutboundChanges(
            request: OutboundChangeAcknowledgementRequest,
        ) = success(Unit)

        override suspend fun readCheckpoint(
            request: CheckpointReadRequest,
        ): ProviderOperationResult<SynchronizationCheckpoint?> {
            readCheckpointCalls++
            return success(null)
        }

        override suspend fun writeCheckpoint(request: CheckpointWriteRequest) = success(Unit)
    }

    private class NoChangeTransport : TransportProvider {
        override val descriptor = descriptor("protected-inline-transport", ProviderType.TRANSPORT)
        var pullCalls = 0

        override suspend fun initialize(context: ProviderInitializationContext) = success(Unit)
        override suspend fun health() = success(ProviderHealth(ProviderHealthStatus.HEALTHY))
        override suspend fun close() = success(Unit)
        override suspend fun pushChanges(request: PushChangesRequest):
            ProviderOperationResult<ChangeSetAcknowledgement> =
            error("Protected inline PULL refresh must not push.")

        override suspend fun pullChanges(
            request: PullChangesRequest,
        ): ProviderOperationResult<PullChangesResult> {
            pullCalls++
            return success(PullChangesResult.NoChanges())
        }
    }

    private class CircuitStore : CircuitBreakerStateStore {
        private val records = mutableMapOf<CircuitBreakerScope, CircuitBreakerStateRecord>()
        var loadCalls = 0

        override suspend fun load(
            scope: CircuitBreakerScope,
        ): ProviderOperationResult<CircuitBreakerLoadResult> {
            loadCalls++
            return success(
                records[scope]?.let(CircuitBreakerLoadResult::Found)
                    ?: CircuitBreakerLoadResult.Missing,
            )
        }

        override suspend fun compareAndSet(
            request: CircuitBreakerCompareAndSetRequest,
        ): ProviderOperationResult<CircuitBreakerCompareAndSetResult> {
            val current = records[request.scope]
            if (current?.version != request.expectedVersion) {
                return success(CircuitBreakerCompareAndSetResult.Conflict(current))
            }
            val updated = CircuitBreakerStateRecord(
                request.nextState,
                (current?.version ?: -1L) + 1L,
            )
            records[request.scope] = updated
            return success(CircuitBreakerCompareAndSetResult.Updated(updated))
        }
    }

    private class FixedClock(private val instant: DataLoomInstant) : DataLoomClock {
        override fun now() = instant
    }

    private companion object {
        fun request() = StrategySynchronizationRequest(
            request = SynchronizationRequest(
                WorkflowId("protected-inline-workflow"),
                SynchronizationSessionId("protected-inline-session"),
                SynchronizationDirection.PULL,
                SynchronizationMode.DELTA,
                ExecutionContext(
                    ExecutionId("protected-inline-execution"),
                    CorrelationId("protected-inline-correlation"),
                ),
            ),
            decisionId = StrategyDecisionId("protected-inline-decision"),
            planId = StrategyPlanId("protected-inline-plan"),
            profile = CacheFirstStrategyProfile(
                id = StrategyProfileId("protected-inline-profile"),
                configurationVersion = StrategyConfigurationVersion(1),
                refreshOnFreshHit = true,
                requireDurableRefresh = false,
            ),
            evidence = StrategyRuntimeEvidence(
                connectivity = StrategyConnectivity.AVAILABLE,
                cacheState = StrategyCacheState.FRESH,
                storageHealth = StrategyProviderHealth.HEALTHY,
                transportHealth = StrategyProviderHealth.HEALTHY,
            ),
            input = StrategyOperationInput.ProviderBacked,
        )

        fun configuration() = CircuitBreakerConfiguration(
            failureThreshold = 2,
            failureWindow = SchedulingDelay(1_000L),
            openDuration = SchedulingDelay(10_000L),
        )

        fun storageScopes(providerId: ProviderId) = StorageCircuitScopes(
            initialization = storageScope(providerId, StorageCircuitOperation.INITIALIZE),
            health = storageScope(providerId, StorageCircuitOperation.HEALTH),
            close = storageScope(providerId, StorageCircuitOperation.CLOSE),
            readOutboundChanges = storageScope(
                providerId,
                StorageCircuitOperation.READ_OUTBOUND_CHANGES,
            ),
            applyInboundChanges = storageScope(
                providerId,
                StorageCircuitOperation.APPLY_INBOUND_CHANGES,
            ),
            acknowledgeOutboundChanges = storageScope(
                providerId,
                StorageCircuitOperation.ACKNOWLEDGE_OUTBOUND_CHANGES,
            ),
            readCheckpoint = storageScope(providerId, StorageCircuitOperation.READ_CHECKPOINT),
            writeCheckpoint = storageScope(providerId, StorageCircuitOperation.WRITE_CHECKPOINT),
        )

        fun transportScopes(providerId: ProviderId) = TransportCircuitScopes(
            initialization = transportScope(providerId, TransportCircuitOperation.INITIALIZE),
            health = transportScope(providerId, TransportCircuitOperation.HEALTH),
            close = transportScope(providerId, TransportCircuitOperation.CLOSE),
            pushChanges = transportScope(providerId, TransportCircuitOperation.PUSH_CHANGES),
            pullChanges = transportScope(providerId, TransportCircuitOperation.PULL_CHANGES),
        )

        fun storageScope(providerId: ProviderId, operation: StorageCircuitOperation) =
            CircuitBreakerScope.providerOperation(providerId, operation.retryOperation)

        fun transportScope(providerId: ProviderId, operation: TransportCircuitOperation) =
            CircuitBreakerScope.providerOperation(providerId, operation.retryOperation)

        fun dependencies() = RuntimeDependencies(
            FixedClock(DataLoomInstant(3_000L)),
            RuntimeIdentifierGenerators(
                fixed(SynchronizationEventId("protected-inline-event")),
                fixed(QueueEntryId("protected-inline-entry")),
                fixed(QueueLeaseId("protected-inline-lease")),
                fixed(ConflictId("protected-inline-conflict")),
            ),
        )

        fun descriptor(id: String, type: ProviderType) = ProviderDescriptor(
            ProviderId(id),
            ProviderName(id),
            type,
            ProviderVersion("1.0.0"),
        )

        fun <T> fixed(value: T): IdentifierGenerator<T> = object : IdentifierGenerator<T> {
            override fun generate() = value
        }

        fun <T> success(value: T): ProviderOperationResult<T> =
            ProviderOperationResult.Success(value)
    }
}
