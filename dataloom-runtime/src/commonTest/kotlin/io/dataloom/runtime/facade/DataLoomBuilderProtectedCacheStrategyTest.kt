package io.dataloom.runtime.facade

import io.dataloom.api.circuit.CircuitBreakerCompareAndSetRequest
import io.dataloom.api.circuit.CircuitBreakerCompareAndSetResult
import io.dataloom.api.circuit.CircuitBreakerLoadResult
import io.dataloom.api.circuit.CircuitBreakerPhase
import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.circuit.CircuitBreakerState
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
import io.dataloom.api.strategy.StrategyDataOrigin
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyOperationInput
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.strategy.StrategyProviderHealth
import io.dataloom.api.strategy.StrategyRuntimeEvidence
import io.dataloom.api.strategy.StrategySynchronizationRequest
import io.dataloom.api.synchronization.CheckpointReadRequest
import io.dataloom.api.synchronization.CheckpointWriteRequest
import io.dataloom.api.synchronization.OutboundChangeAcknowledgementRequest
import io.dataloom.api.synchronization.SynchronizationCheckpoint
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.execution.protection.ProviderProtectionInvocation
import io.dataloom.runtime.execution.protection.ProviderProtectionPreExecutionReason
import io.dataloom.runtime.retry.CircuitBreakerConfiguration
import io.dataloom.runtime.retry.CircuitBreakerRejectionReason
import io.dataloom.runtime.retry.StorageCircuitOperation
import io.dataloom.runtime.retry.StorageCircuitScopes
import io.dataloom.runtime.retry.StrategyCacheAccessCircuitOperation
import io.dataloom.runtime.strategy.StrategyCacheUnavailableReason
import io.dataloom.runtime.strategy.StrategyExecutionRejectionReason
import io.dataloom.runtime.strategy.StrategySynchronizationExecutionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest

class DataLoomBuilderProtectedCacheStrategyTest {

    @Test
    fun `protected cache hit uses independent cache circuit evidence`() = runTest {
        val storage = RecordingCacheStorageProvider {
            ProviderOperationResult.Success(
                StrategyCacheAccessResult.Available(freshEvidence()),
            )
        }
        val storageStore = RecordingCircuitStore()
        val cacheStore = RecordingCircuitStore()
        val dataLoom = protectedDataLoom(
            storage = storage,
            storageStore = storageStore,
            cacheStore = cacheStore,
        )
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())

        val result = requireNotNull(dataLoom.protectedStrategySynchronization)
            .synchronize(cacheFirstRequest())
        val served = assertIs<StrategySynchronizationExecutionResult.CacheServed>(
            result.strategyResult,
        )
        val evidence = result.operationEvidence.single()

        assertEquals(StrategyDataOrigin.LOCAL, served.dataOrigin)
        assertEquals(StrategyCacheState.FRESH, served.freshness.cacheState)
        assertEquals(
            StrategyCacheAccessCircuitOperation.EVALUATE_CACHE_ACCESS.retryOperation,
            evidence.operation,
        )
        assertEquals(ProviderProtectionInvocation.SUCCEEDED, evidence.invocation)
        assertEquals(1, storage.cacheAccessCalls)
        assertEquals(0, storage.storageOperationCalls)
        assertEquals(0, storageStore.loadCalls)
        assertTrue(cacheStore.loadCalls >= 1)
    }

    @Test
    fun `typed cache unavailability remains circuit success`() = runTest {
        val storage = RecordingCacheStorageProvider {
            ProviderOperationResult.Success(
                StrategyCacheAccessResult.Unavailable(StrategyCacheState.MISSING),
            )
        }
        val storageStore = RecordingCircuitStore()
        val cacheStore = RecordingCircuitStore()
        val dataLoom = protectedDataLoom(
            storage = storage,
            storageStore = storageStore,
            cacheStore = cacheStore,
        )
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())

        val result = requireNotNull(dataLoom.protectedStrategySynchronization)
            .synchronize(cacheFirstRequest())
        val unavailable =
            assertIs<StrategySynchronizationExecutionResult.CacheUnavailable>(
                result.strategyResult,
            )
        val evidence = result.operationEvidence.single()

        assertEquals(
            StrategyCacheUnavailableReason.PROVIDER_REPORTED_UNAVAILABLE,
            unavailable.reason,
        )
        assertEquals(StrategyCacheState.MISSING, unavailable.providerCacheState)
        assertEquals(ProviderProtectionInvocation.SUCCEEDED, evidence.invocation)
        assertEquals(1, storage.cacheAccessCalls)
        assertEquals(0, storage.storageOperationCalls)
        assertEquals(0, storageStore.loadCalls)
        assertTrue(cacheStore.loadCalls >= 1)
    }

    @Test
    fun `missing cache protection rejects before cache invocation`() = runTest {
        val storage = RecordingCacheStorageProvider {
            ProviderOperationResult.Success(
                StrategyCacheAccessResult.Available(freshEvidence()),
            )
        }
        val storageStore = RecordingCircuitStore()
        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(runtimeDependencies())
            .provider(storage)
            .defaultStrategyProviderBindings(bindings(storage))
            .strategyProviderProtectionConfiguration(
                DataLoomStrategyProviderProtectionSpec(
                    storage = storageSpec(storage, storageStore),
                ),
            )
            .build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())

        val result = requireNotNull(dataLoom.protectedStrategySynchronization)
            .synchronize(cacheFirstRequest())
        val rejected = assertIs<StrategySynchronizationExecutionResult.Rejected>(
            result.strategyResult,
        )

        assertEquals(
            StrategyExecutionRejectionReason.PROVIDER_PROTECTION_NOT_CONFIGURED,
            rejected.reason,
        )
        assertTrue(result.operationEvidence.isEmpty())
        assertEquals(0, storage.cacheAccessCalls)
        assertEquals(0, storage.storageOperationCalls)
        assertEquals(0, storageStore.loadCalls)
    }

    @Test
    fun `open cache circuit rejects before provider invocation`() = runTest {
        val storage = RecordingCacheStorageProvider {
            ProviderOperationResult.Success(
                StrategyCacheAccessResult.Available(freshEvidence()),
            )
        }
        val scope = cacheScope(storage.descriptor.id)
        val cacheStore = RecordingCircuitStore(
            initialRecords = mapOf(scope to openRecord(scope)),
        )
        val dataLoom = protectedDataLoom(
            storage = storage,
            storageStore = RecordingCircuitStore(),
            cacheStore = cacheStore,
        )
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())

        val result = requireNotNull(dataLoom.protectedStrategySynchronization)
            .synchronize(cacheFirstRequest())
        val failed = assertIs<StrategySynchronizationExecutionResult.Failed>(
            result.strategyResult,
        )
        val evidence = result.operationEvidence.single()

        assertEquals("PROVIDER_CIRCUIT_OPEN", failed.error.code.value)
        assertEquals(ProviderProtectionInvocation.NOT_EXECUTED, evidence.invocation)
        assertEquals(
            ProviderProtectionPreExecutionReason.CIRCUIT_REJECTED,
            evidence.preExecutionReason,
        )
        assertEquals(CircuitBreakerRejectionReason.OPEN, evidence.rejectionReason)
        assertEquals(0, storage.cacheAccessCalls)
        assertEquals(0, storage.storageOperationCalls)
        assertEquals(1, cacheStore.loadCalls)
    }

    @Test
    fun `cache provider timeout is recorded as circuit failure`() = runTest {
        val storage = RecordingCacheStorageProvider {
            awaitCancellation()
        }
        val storageStore = RecordingCircuitStore()
        val cacheStore = RecordingCircuitStore()
        val dataLoom = protectedDataLoom(
            storage = storage,
            storageStore = storageStore,
            cacheStore = cacheStore,
            providerTimeout = SchedulingDelay(1L),
        )
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())

        val result = requireNotNull(dataLoom.protectedStrategySynchronization)
            .synchronize(cacheFirstRequest())
        val failed = assertIs<StrategySynchronizationExecutionResult.Failed>(
            result.strategyResult,
        )
        val evidence = result.operationEvidence.single()

        assertEquals(
            "STRATEGY_CACHE_ACCESS_PROVIDER_TIMEOUT",
            failed.error.code.value,
        )
        assertEquals(false, failed.transportAttempted)
        assertEquals(ProviderProtectionInvocation.CIRCUIT_FAILURE, evidence.invocation)
        assertEquals(1, storage.cacheAccessCalls)
        assertEquals(0, storage.storageOperationCalls)
        assertEquals(0, storageStore.loadCalls)
        assertTrue(cacheStore.loadCalls >= 1)
        assertTrue(cacheStore.compareCalls >= 1)
    }

    @Test
    fun `cache protection rejects an unrelated operation scope`() {
        val storage = RecordingCacheStorageProvider {
            ProviderOperationResult.Success(
                StrategyCacheAccessResult.Available(freshEvidence()),
            )
        }

        assertFailsWith<IllegalArgumentException> {
            DataLoomStrategyCacheAccessProtectionSpec(
                circuitBreakerConfiguration = configuration(),
                circuitBreakerStateStore = RecordingCircuitStore(),
                scope = CircuitBreakerScope.providerOperation(
                    providerId = storage.descriptor.id,
                    operation = StorageCircuitOperation.READ_CHECKPOINT.retryOperation,
                ),
            )
        }
    }

    private fun protectedDataLoom(
        storage: RecordingCacheStorageProvider,
        storageStore: CircuitBreakerStateStore,
        cacheStore: CircuitBreakerStateStore,
        providerTimeout: SchedulingDelay? = null,
    ): DataLoom = DataLoomBuilder()
        .runtimeDependencies(runtimeDependencies())
        .provider(storage)
        .defaultStrategyProviderBindings(bindings(storage))
        .strategyProviderProtectionConfiguration(
            DataLoomStrategyProviderProtectionSpec(
                storage = storageSpec(storage, storageStore),
                cacheAccess = DataLoomStrategyCacheAccessProtectionSpec(
                    circuitBreakerConfiguration = configuration(),
                    circuitBreakerStateStore = cacheStore,
                    scope = cacheScope(storage.descriptor.id),
                    providerTimeout = providerTimeout,
                ),
            ),
        )
        .build()

    private fun bindings(
        storage: RecordingCacheStorageProvider,
    ): StrategyProviderBindings = StrategyProviderBindings(
        storageProviderId = storage.descriptor.id,
    )

    private fun storageSpec(
        storage: RecordingCacheStorageProvider,
        store: CircuitBreakerStateStore,
    ): DataLoomStorageProtectionSpec = DataLoomStorageProtectionSpec(
        circuitBreakerConfiguration = configuration(),
        circuitBreakerStateStore = store,
        scopes = storageScopes(storage.descriptor.id),
    )

    private class RecordingCacheStorageProvider(
        private val cacheOperation: suspend (StrategyCacheAccessRequest) ->
            ProviderOperationResult<StrategyCacheAccessResult>,
    ) : StrategyCacheAccessProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("protected-cache-storage"),
            name = ProviderName("Protected Cache Storage"),
            type = ProviderType.STORAGE,
            version = ProviderVersion("1.0.0"),
        )

        var cacheAccessCalls: Int = 0
            private set
        var storageOperationCalls: Int = 0
            private set
        var lastCacheRequest: StrategyCacheAccessRequest? = null
            private set

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(
                ProviderHealth(ProviderHealthStatus.HEALTHY),
            )

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun evaluateCacheAccess(
            request: StrategyCacheAccessRequest,
        ): ProviderOperationResult<StrategyCacheAccessResult> {
            cacheAccessCalls++
            lastCacheRequest = request
            return cacheOperation(request)
        }

        override suspend fun readOutboundChanges(
            request: OutboundChangeReadRequest,
        ): ProviderOperationResult<OutboundChangeReadResult> {
            storageOperationCalls++
            return ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges)
        }

        override suspend fun applyInboundChanges(
            request: InboundChangeApplyRequest,
        ): ProviderOperationResult<Unit> {
            storageOperationCalls++
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun acknowledgeOutboundChanges(
            request: OutboundChangeAcknowledgementRequest,
        ): ProviderOperationResult<Unit> {
            storageOperationCalls++
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun readCheckpoint(
            request: CheckpointReadRequest,
        ): ProviderOperationResult<SynchronizationCheckpoint?> {
            storageOperationCalls++
            return ProviderOperationResult.Success(null)
        }

        override suspend fun writeCheckpoint(
            request: CheckpointWriteRequest,
        ): ProviderOperationResult<Unit> {
            storageOperationCalls++
            return ProviderOperationResult.Success(Unit)
        }
    }

    private class RecordingCircuitStore(
        initialRecords: Map<CircuitBreakerScope, CircuitBreakerStateRecord> = emptyMap(),
    ) : CircuitBreakerStateStore {
        private val records = initialRecords.toMutableMap()
        var loadCalls: Int = 0
            private set
        var compareCalls: Int = 0
            private set

        override suspend fun load(
            scope: CircuitBreakerScope,
        ): ProviderOperationResult<CircuitBreakerLoadResult> {
            loadCalls++
            return ProviderOperationResult.Success(
                records[scope]?.let(CircuitBreakerLoadResult::Found)
                    ?: CircuitBreakerLoadResult.Missing,
            )
        }

        override suspend fun compareAndSet(
            request: CircuitBreakerCompareAndSetRequest,
        ): ProviderOperationResult<CircuitBreakerCompareAndSetResult> {
            compareCalls++
            val current = records[request.scope]
            if (current?.version != request.expectedVersion) {
                return ProviderOperationResult.Success(
                    CircuitBreakerCompareAndSetResult.Conflict(current),
                )
            }
            val updated = CircuitBreakerStateRecord(
                state = request.nextState,
                version = (current?.version ?: -1L) + 1L,
            )
            records[request.scope] = updated
            return ProviderOperationResult.Success(
                CircuitBreakerCompareAndSetResult.Updated(updated),
            )
        }
    }

    private class FixedClock(
        private val instant: DataLoomInstant,
    ) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
    }

    private companion object {
        val now = DataLoomInstant(1_000L)

        fun configuration(): CircuitBreakerConfiguration =
            CircuitBreakerConfiguration(
                failureThreshold = 2,
                failureWindow = SchedulingDelay(1_000L),
                openDuration = SchedulingDelay(10_000L),
            )

        fun cacheFirstRequest(): StrategySynchronizationRequest =
            StrategySynchronizationRequest(
                request = SynchronizationRequest(
                    workflowId = WorkflowId("protected-cache-workflow"),
                    sessionId = SynchronizationSessionId("protected-cache-session"),
                    direction = SynchronizationDirection.PULL,
                    mode = SynchronizationMode.DELTA,
                    context = ExecutionContext(
                        executionId = ExecutionId("protected-cache-execution"),
                        correlationId = CorrelationId("protected-cache-correlation"),
                    ),
                ),
                decisionId = StrategyDecisionId("protected-cache-decision"),
                planId = StrategyPlanId("protected-cache-plan"),
                profile = CacheFirstStrategyProfile(
                    id = StrategyProfileId("protected-cache-profile"),
                    configurationVersion = StrategyConfigurationVersion(1L),
                    refreshOnFreshHit = false,
                ),
                evidence = StrategyRuntimeEvidence(
                    connectivity = StrategyConnectivity.AVAILABLE,
                    cacheState = StrategyCacheState.FRESH,
                    storageHealth = StrategyProviderHealth.HEALTHY,
                ),
                input = StrategyOperationInput.ProviderBacked,
            )

        fun freshEvidence(): StrategyCacheFreshnessEvidence =
            StrategyCacheFreshnessEvidence(
                cacheState = StrategyCacheState.FRESH,
                observedAt = DataLoomInstant(1_000L),
                validUntil = DataLoomInstant(2_000L),
            )

        fun cacheScope(providerId: ProviderId): CircuitBreakerScope =
            CircuitBreakerScope.providerOperation(
                providerId = providerId,
                operation =
                    StrategyCacheAccessCircuitOperation.EVALUATE_CACHE_ACCESS.retryOperation,
            )

        fun storageScopes(providerId: ProviderId): StorageCircuitScopes =
            StorageCircuitScopes(
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
                readCheckpoint = storageScope(
                    providerId,
                    StorageCircuitOperation.READ_CHECKPOINT,
                ),
                writeCheckpoint = storageScope(
                    providerId,
                    StorageCircuitOperation.WRITE_CHECKPOINT,
                ),
            )

        fun storageScope(
            providerId: ProviderId,
            operation: StorageCircuitOperation,
        ): CircuitBreakerScope = CircuitBreakerScope.providerOperation(
            providerId = providerId,
            operation = operation.retryOperation,
        )

        fun openRecord(scope: CircuitBreakerScope): CircuitBreakerStateRecord =
            CircuitBreakerStateRecord(
                state = CircuitBreakerState(
                    scope = scope,
                    phase = CircuitBreakerPhase.OPEN,
                    consecutiveFailures = 2,
                    failureWindowStartedAt = now,
                    openUntil = DataLoomInstant(20_000L),
                    probeGeneration = 0L,
                    probeInFlight = false,
                    updatedAt = now,
                ),
                version = 0L,
            )

        fun runtimeDependencies(): RuntimeDependencies = RuntimeDependencies(
            clock = FixedClock(now),
            identifiers = RuntimeIdentifierGenerators(
                synchronizationEventIds = generator {
                    SynchronizationEventId("protected-cache-event")
                },
                queueEntryIds = generator {
                    QueueEntryId("protected-cache-queue-entry")
                },
                queueLeaseIds = generator {
                    QueueLeaseId("protected-cache-queue-lease")
                },
                conflictIds = generator {
                    ConflictId("protected-cache-conflict")
                },
            ),
        )

        fun <T> generator(block: () -> T): IdentifierGenerator<T> =
            object : IdentifierGenerator<T> {
                override fun generate(): T = block()
            }
    }
}
