package io.dataloom.runtime.facade

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.ChangeSet
import io.dataloom.api.change.EntityReference
import io.dataloom.api.circuit.CircuitBreakerCompareAndSetRequest
import io.dataloom.api.circuit.CircuitBreakerCompareAndSetResult
import io.dataloom.api.circuit.CircuitBreakerLoadResult
import io.dataloom.api.circuit.CircuitBreakerPhase
import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.circuit.CircuitBreakerState
import io.dataloom.api.circuit.CircuitBreakerStateRecord
import io.dataloom.api.circuit.CircuitBreakerStateStore
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ChangeSetId
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.IdentifierGenerator
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.SynchronizationEventId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.ChangeOperation
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
import io.dataloom.api.strategy.ClassifiedStrategyRemoteError
import io.dataloom.api.strategy.NetworkOnlyStrategyProfile
import io.dataloom.api.strategy.RemoteFirstStrategyProfile
import io.dataloom.api.strategy.StrategyCacheState
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyConnectivity
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyLocalFallbackProvider
import io.dataloom.api.strategy.StrategyLocalFallbackRequest
import io.dataloom.api.strategy.StrategyLocalFallbackResult
import io.dataloom.api.strategy.StrategyOperationInput
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.strategy.StrategyProviderHealth
import io.dataloom.api.strategy.StrategyRemoteOutcome
import io.dataloom.api.strategy.StrategyRuntimeEvidence
import io.dataloom.api.strategy.StrategySynchronizationRequest
import io.dataloom.api.strategy.StrategyTransportOutput
import io.dataloom.api.synchronization.ChangeAcknowledgementStatus
import io.dataloom.api.synchronization.ChangeEventAcknowledgement
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
import io.dataloom.runtime.retry.CircuitBreakerRecordResult
import io.dataloom.runtime.retry.StorageCircuitOperation
import io.dataloom.runtime.retry.StorageCircuitScopes
import io.dataloom.runtime.retry.StrategyLocalFallbackCircuitOperation
import io.dataloom.runtime.retry.TransportCircuitOperation
import io.dataloom.runtime.retry.TransportCircuitScopes
import io.dataloom.runtime.strategy.StrategyExecutionRejectionReason
import io.dataloom.runtime.strategy.StrategySynchronizationExecutionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class DataLoomBuilderProtectedStrategyTest {

    @Test
    fun `builder without strategy protection exposes no protected strategy capability`() {
        val transport = RecordingTransportProvider()
        val dataLoom = builder(
            storage = null,
            transport = transport,
            bindings = StrategyProviderBindings(
                transportProviderId = transport.descriptor.id,
            ),
        ).build()

        assertNull(dataLoom.protectedStrategySynchronization)
    }

    @Test
    fun `valid protected strategy build is side effect free`() {
        val transport = RecordingTransportProvider()
        val transportStore = RecordingCircuitStore()
        val clock = CountingClock(now)
        val dataLoom = builder(
            storage = null,
            transport = transport,
            bindings = StrategyProviderBindings(
                transportProviderId = transport.descriptor.id,
            ),
            clock = clock,
        ).strategyProviderProtectionConfiguration(
            DataLoomStrategyProviderProtectionSpec(
                transport = transportSpec(transport, transportStore),
            ),
        ).build()

        assertNotNull(dataLoom.protectedStrategySynchronization)
        assertEquals(0, transport.initializeCalls)
        assertEquals(0, transport.pullCalls)
        assertEquals(0, transportStore.loadCalls)
        assertEquals(0, transportStore.compareCalls)
        assertEquals(0, clock.readCalls)
    }

    @Test
    fun `network only pull protects transport without resolving storage`() = runTest {
        val storage = RecordingFallbackStorageProvider()
        val transport = RecordingTransportProvider(
            pullResult = ProviderOperationResult.Success(PullChangesResult.NoChanges()),
        )
        val storageStore = RecordingCircuitStore()
        val transportStore = RecordingCircuitStore()
        val dataLoom = builder(
            storage = storage,
            transport = transport,
            bindings = bindings(storage, transport),
        ).strategyProviderProtectionConfiguration(
            DataLoomStrategyProviderProtectionSpec(
                storage = storageSpec(storage, storageStore),
                transport = transportSpec(transport, transportStore),
            ),
        ).build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())

        val protected = requireNotNull(dataLoom.protectedStrategySynchronization)
        val result = protected.synchronize(networkOnlyPullRequest())
        val executed = assertIs<StrategySynchronizationExecutionResult.Executed>(
            result.strategyResult,
        )

        assertIs<StrategyTransportOutput.Pulled>(executed.output)
        assertEquals(1, result.operationEvidence.size)
        assertEquals(
            TransportCircuitOperation.PULL_CHANGES.retryOperation,
            result.operationEvidence.single().operation,
        )
        assertEquals(
            ProviderProtectionInvocation.SUCCEEDED,
            result.operationEvidence.single().invocation,
        )
        assertEquals(1, transport.pullCalls)
        assertEquals(0, storage.totalSynchronizationCalls)
        assertEquals(0, storage.fallbackCalls)
        assertEquals(0, storageStore.loadCalls)
        assertTrue(transportStore.loadCalls >= 1)
    }

    @Test
    fun `network only push protects transport without resolving storage`() = runTest {
        val storage = RecordingFallbackStorageProvider()
        val transport = RecordingTransportProvider(
            pushResult = ProviderOperationResult.Success(
                acknowledgementFor(changeSet, ChangeAcknowledgementStatus.ACCEPTED),
            ),
        )
        val storageStore = RecordingCircuitStore()
        val transportStore = RecordingCircuitStore()
        val dataLoom = builder(
            storage = storage,
            transport = transport,
            bindings = bindings(storage, transport),
        ).strategyProviderProtectionConfiguration(
            DataLoomStrategyProviderProtectionSpec(
                storage = storageSpec(storage, storageStore),
                transport = transportSpec(transport, transportStore),
            ),
        ).build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())

        val protected = requireNotNull(dataLoom.protectedStrategySynchronization)
        val result = protected.synchronize(networkOnlyPushRequest())
        val executed = assertIs<StrategySynchronizationExecutionResult.Executed>(
            result.strategyResult,
        )

        assertIs<StrategyTransportOutput.Pushed>(executed.output)
        assertEquals(1, result.operationEvidence.size)
        assertEquals(
            TransportCircuitOperation.PUSH_CHANGES.retryOperation,
            result.operationEvidence.single().operation,
        )
        assertEquals(
            ProviderProtectionInvocation.SUCCEEDED,
            result.operationEvidence.single().invocation,
        )
        assertEquals(1, transport.pushCalls)
        assertEquals(0, transport.pullCalls)
        assertEquals(0, storage.totalSynchronizationCalls)
        assertEquals(0, storage.fallbackCalls)
        assertEquals(0, storageStore.loadCalls)
        assertTrue(transportStore.loadCalls >= 1)
    }

    @Test
    fun `network only bidirectional protects transport for both operations`() = runTest {
        val storage = RecordingFallbackStorageProvider()
        val transport = RecordingTransportProvider(
            pushResult = ProviderOperationResult.Success(
                acknowledgementFor(changeSet, ChangeAcknowledgementStatus.ACCEPTED),
            ),
            pullResult = ProviderOperationResult.Success(PullChangesResult.NoChanges()),
        )
        val storageStore = RecordingCircuitStore()
        val transportStore = RecordingCircuitStore()
        val dataLoom = builder(
            storage = storage,
            transport = transport,
            bindings = bindings(storage, transport),
        ).strategyProviderProtectionConfiguration(
            DataLoomStrategyProviderProtectionSpec(
                storage = storageSpec(storage, storageStore),
                transport = transportSpec(transport, transportStore),
            ),
        ).build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())

        val protected = requireNotNull(dataLoom.protectedStrategySynchronization)
        val result = protected.synchronize(networkOnlyBidirectionalRequest())
        val executed = assertIs<StrategySynchronizationExecutionResult.Executed>(
            result.strategyResult,
        )

        assertIs<StrategyTransportOutput.Bidirectional>(executed.output)
        assertEquals(2, result.operationEvidence.size)
        assertEquals(
            TransportCircuitOperation.PUSH_CHANGES.retryOperation,
            result.operationEvidence[0].operation,
        )
        assertEquals(
            TransportCircuitOperation.PULL_CHANGES.retryOperation,
            result.operationEvidence[1].operation,
        )
        assertEquals(
            ProviderProtectionInvocation.SUCCEEDED,
            result.operationEvidence[0].invocation,
        )
        assertEquals(
            ProviderProtectionInvocation.SUCCEEDED,
            result.operationEvidence[1].invocation,
        )
        assertEquals(1, transport.pushCalls)
        assertEquals(1, transport.pullCalls)
        assertEquals(0, storage.totalSynchronizationCalls)
        assertEquals(0, storageStore.loadCalls)
    }

    @Test
    fun `network only bidirectional preserves push evidence when pull fails after successful push`() =
        runTest {
            val storage = RecordingFallbackStorageProvider()
            val transport = RecordingTransportProvider(
                pushResult = ProviderOperationResult.Success(
                    acknowledgementFor(changeSet, ChangeAcknowledgementStatus.ACCEPTED),
                ),
                pullResult = ProviderOperationResult.Failure(
                    RemoteFailure(StrategyRemoteOutcome.UNAVAILABLE),
                ),
            )
            val storageStore = RecordingCircuitStore()
            val transportStore = RecordingCircuitStore()
            val dataLoom = builder(
                storage = storage,
                transport = transport,
                bindings = bindings(storage, transport),
            ).strategyProviderProtectionConfiguration(
                DataLoomStrategyProviderProtectionSpec(
                    storage = storageSpec(storage, storageStore),
                    transport = transportSpec(transport, transportStore),
                ),
            ).build()
            assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())

            val protected = requireNotNull(dataLoom.protectedStrategySynchronization)
            val result = protected.synchronize(networkOnlyBidirectionalRequest())
            val failed = assertIs<StrategySynchronizationExecutionResult.Failed>(
                result.strategyResult,
            )

            val partial = assertIs<StrategyTransportOutput.Pushed>(failed.partialOutput)
            assertEquals(changeSet.id, partial.acknowledgement.changeSetId)
            assertEquals(2, result.operationEvidence.size)
            assertEquals(
                TransportCircuitOperation.PUSH_CHANGES.retryOperation,
                result.operationEvidence[0].operation,
            )
            assertEquals(
                ProviderProtectionInvocation.SUCCEEDED,
                result.operationEvidence[0].invocation,
            )
            assertEquals(
                TransportCircuitOperation.PULL_CHANGES.retryOperation,
                result.operationEvidence[1].operation,
            )
            assertEquals(
                ProviderProtectionInvocation.CIRCUIT_FAILURE,
                result.operationEvidence[1].invocation,
            )
            assertEquals(1, transport.pushCalls)
            assertEquals(1, transport.pullCalls)
            assertEquals(0, storage.totalSynchronizationCalls)
        }

    @Test
    fun `missing transport protection rejects network only before provider invocation`() = runTest {
        val storage = RecordingFallbackStorageProvider()
        val transport = RecordingTransportProvider(
            pullResult = ProviderOperationResult.Success(PullChangesResult.NoChanges()),
        )
        val storageStore = RecordingCircuitStore()
        val dataLoom = builder(
            storage = storage,
            transport = transport,
            bindings = bindings(storage, transport),
        ).strategyProviderProtectionConfiguration(
            DataLoomStrategyProviderProtectionSpec(
                storage = storageSpec(storage, storageStore),
            ),
        ).build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())

        val result = requireNotNull(dataLoom.protectedStrategySynchronization)
            .synchronize(networkOnlyPullRequest())
        val rejected = assertIs<StrategySynchronizationExecutionResult.Rejected>(
            result.strategyResult,
        )

        assertEquals(
            StrategyExecutionRejectionReason.PROVIDER_PROTECTION_NOT_CONFIGURED,
            rejected.reason,
        )
        assertTrue(result.operationEvidence.isEmpty())
        assertEquals(0, transport.pullCalls)
        assertEquals(0, storage.totalSynchronizationCalls)
        assertEquals(0, storageStore.loadCalls)
    }

    @Test
    fun `remote first fallback preserves transport and local fallback evidence`() = runTest {
        val storage = RecordingFallbackStorageProvider(
            fallbackResult = ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Available(StrategyCacheState.FRESH),
            ),
        )
        val transport = RecordingTransportProvider(
            pullResult = ProviderOperationResult.Failure(
                RemoteFailure(StrategyRemoteOutcome.UNAVAILABLE),
            ),
        )
        val storageStore = RecordingCircuitStore()
        val transportStore = RecordingCircuitStore()
        val fallbackStore = RecordingCircuitStore()
        val dataLoom = builder(
            storage = storage,
            transport = transport,
            bindings = bindings(storage, transport),
        ).strategyProviderProtectionConfiguration(
            DataLoomStrategyProviderProtectionSpec(
                storage = storageSpec(storage, storageStore),
                transport = transportSpec(transport, transportStore),
                localFallback = fallbackSpec(storage, fallbackStore),
            ),
        ).build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())

        val result = requireNotNull(dataLoom.protectedStrategySynchronization)
            .synchronize(remoteFirstFallbackRequest())
        val fallback = assertIs<StrategySynchronizationExecutionResult.FallbackActivated>(
            result.strategyResult,
        )

        assertEquals(StrategyCacheState.FRESH, fallback.cacheState)
        assertEquals(2, result.operationEvidence.size)
        assertEquals(
            TransportCircuitOperation.PULL_CHANGES.retryOperation,
            result.operationEvidence[0].operation,
        )
        assertEquals(
            StrategyLocalFallbackCircuitOperation.EVALUATE_LOCAL_FALLBACK.retryOperation,
            result.operationEvidence[1].operation,
        )
        assertEquals(
            ProviderProtectionInvocation.CIRCUIT_FAILURE,
            result.operationEvidence[0].invocation,
        )
        assertEquals(
            ProviderProtectionInvocation.SUCCEEDED,
            result.operationEvidence[1].invocation,
        )
        assertEquals(1, transport.pullCalls)
        assertEquals(1, storage.fallbackCalls)
        assertEquals(0, storage.totalSynchronizationCalls)
        assertEquals(0, storageStore.loadCalls)
        assertTrue(transportStore.loadCalls >= 1)
        assertTrue(fallbackStore.loadCalls >= 1)
    }

    @Test
    fun `missing local fallback protection rejects before remote or local invocation`() = runTest {
        val storage = RecordingFallbackStorageProvider(
            fallbackResult = ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Available(StrategyCacheState.FRESH),
            ),
        )
        val transport = RecordingTransportProvider(
            pullResult = ProviderOperationResult.Failure(
                RemoteFailure(StrategyRemoteOutcome.UNAVAILABLE),
            ),
        )
        val storageStore = RecordingCircuitStore()
        val transportStore = RecordingCircuitStore()
        val dataLoom = builder(
            storage = storage,
            transport = transport,
            bindings = bindings(storage, transport),
        ).strategyProviderProtectionConfiguration(
            DataLoomStrategyProviderProtectionSpec(
                storage = storageSpec(storage, storageStore),
                transport = transportSpec(transport, transportStore),
            ),
        ).build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())

        val result = requireNotNull(dataLoom.protectedStrategySynchronization)
            .synchronize(remoteFirstFallbackRequest())
        val rejected = assertIs<StrategySynchronizationExecutionResult.Rejected>(
            result.strategyResult,
        )

        assertEquals(
            StrategyExecutionRejectionReason.PROVIDER_PROTECTION_NOT_CONFIGURED,
            rejected.reason,
        )
        assertTrue(result.operationEvidence.isEmpty())
        assertEquals(0, transport.pullCalls)
        assertEquals(0, storage.fallbackCalls)
        assertEquals(0, storageStore.loadCalls)
        assertEquals(0, transportStore.loadCalls)
    }

    @Test
    fun `historical direct strategy execution remains unprotected`() = runTest {
        val transport = RecordingTransportProvider(
            pullResult = ProviderOperationResult.Success(PullChangesResult.NoChanges()),
        )
        val transportStore = RecordingCircuitStore()
        val bindings = StrategyProviderBindings(
            transportProviderId = transport.descriptor.id,
        )
        val dataLoom = builder(
            storage = null,
            transport = transport,
            bindings = bindings,
        ).strategyProviderProtectionConfiguration(
            DataLoomStrategyProviderProtectionSpec(
                transport = transportSpec(transport, transportStore),
            ),
        ).build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())

        val direct = dataLoom.synchronize(networkOnlyPullRequest(), bindings)

        assertIs<StrategySynchronizationExecutionResult.Executed>(direct)
        assertEquals(1, transport.pullCalls)
        assertEquals(0, transportStore.loadCalls)
        assertEquals(0, transportStore.compareCalls)
    }

    @Test
    fun `provider success with unconfirmed circuit recording stays fail closed`() = runTest {
        val transport = RecordingTransportProvider(
            pullResult = ProviderOperationResult.Success(PullChangesResult.NoChanges()),
        )
        val pullScope = transportScope(
            transport.descriptor.id,
            TransportCircuitOperation.PULL_CHANGES,
        )
        val storeError = error(
            code = "STRATEGY_CIRCUIT_STORE_WRITE_FAILED",
            category = ErrorCategory.STORAGE,
        )
        val transportStore = RecordingCircuitStore(
            initialRecords = mapOf(
                pullScope to closedFailureRecord(pullScope),
            ),
            compareError = storeError,
        )
        val dataLoom = builder(
            storage = null,
            transport = transport,
            bindings = StrategyProviderBindings(
                transportProviderId = transport.descriptor.id,
            ),
        ).strategyProviderProtectionConfiguration(
            DataLoomStrategyProviderProtectionSpec(
                transport = transportSpec(transport, transportStore),
            ),
        ).build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())

        val result = requireNotNull(dataLoom.protectedStrategySynchronization)
            .synchronize(networkOnlyPullRequest())
        val failed = assertIs<StrategySynchronizationExecutionResult.Failed>(
            result.strategyResult,
        )
        val evidence = result.operationEvidence.single()

        assertEquals("PROVIDER_CIRCUIT_RECORDING_UNCONFIRMED", failed.error.code.value)
        assertEquals(Recoverability.UNKNOWN, failed.error.recoverability)
        assertEquals(1, transport.pullCalls)
        assertEquals(ProviderProtectionInvocation.SUCCEEDED, evidence.invocation)
        assertIs<CircuitBreakerRecordResult.PersistenceFailure>(evidence.recordResult)
    }

    private fun builder(
        storage: RecordingFallbackStorageProvider?,
        transport: RecordingTransportProvider,
        bindings: StrategyProviderBindings,
        clock: DataLoomClock = FixedClock(now),
    ): DataLoomBuilder {
        val builder = DataLoomBuilder()
            .runtimeDependencies(runtimeDependencies(clock))
            .provider(transport)
            .defaultStrategyProviderBindings(bindings)
        if (storage != null) {
            builder.provider(storage)
        }
        return builder
    }

    private fun bindings(
        storage: RecordingFallbackStorageProvider,
        transport: RecordingTransportProvider,
    ): StrategyProviderBindings = StrategyProviderBindings(
        storageProviderId = storage.descriptor.id,
        transportProviderId = transport.descriptor.id,
    )

    private fun storageSpec(
        storage: RecordingFallbackStorageProvider,
        store: CircuitBreakerStateStore,
    ): DataLoomStorageProtectionSpec = DataLoomStorageProtectionSpec(
        circuitBreakerConfiguration = configuration(),
        circuitBreakerStateStore = store,
        scopes = storageScopes(storage.descriptor.id),
    )

    private fun transportSpec(
        transport: RecordingTransportProvider,
        store: CircuitBreakerStateStore,
    ): DataLoomTransportProtectionSpec = DataLoomTransportProtectionSpec(
        circuitBreakerConfiguration = configuration(),
        circuitBreakerStateStore = store,
        scopes = transportScopes(transport.descriptor.id),
    )

    private fun fallbackSpec(
        storage: RecordingFallbackStorageProvider,
        store: CircuitBreakerStateStore,
    ): DataLoomStrategyLocalFallbackProtectionSpec =
        DataLoomStrategyLocalFallbackProtectionSpec(
            circuitBreakerConfiguration = configuration(),
            circuitBreakerStateStore = store,
            scope = CircuitBreakerScope.providerOperation(
                providerId = storage.descriptor.id,
                operation =
                    StrategyLocalFallbackCircuitOperation.EVALUATE_LOCAL_FALLBACK.retryOperation,
            ),
        )

    private class RecordingTransportProvider(
        private val pullResult: ProviderOperationResult<PullChangesResult> =
            ProviderOperationResult.Success(PullChangesResult.NoChanges()),
        private val pushResult: ProviderOperationResult<ChangeSetAcknowledgement> =
            ProviderOperationResult.Failure(error("PUSH_UNUSED")),
    ) : TransportProvider {
        var initializeCalls: Int = 0
            private set
        var pullCalls: Int = 0
            private set
        var pushCalls: Int = 0
            private set

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("protected-strategy-transport"),
            name = ProviderName("Protected Strategy Transport"),
            type = ProviderType.TRANSPORT,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> {
            initializeCalls++
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(
                ProviderHealth(ProviderHealthStatus.HEALTHY),
            )

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun pushChanges(
            request: PushChangesRequest,
        ): ProviderOperationResult<ChangeSetAcknowledgement> {
            pushCalls++
            return pushResult
        }

        override suspend fun pullChanges(
            request: PullChangesRequest,
        ): ProviderOperationResult<PullChangesResult> {
            pullCalls++
            return pullResult
        }
    }

    private class RecordingFallbackStorageProvider(
        private val fallbackResult: ProviderOperationResult<StrategyLocalFallbackResult> =
            ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Unavailable(StrategyCacheState.MISSING),
            ),
    ) : StrategyLocalFallbackProvider {
        var fallbackCalls: Int = 0
            private set
        var totalSynchronizationCalls: Int = 0
            private set

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("protected-strategy-storage"),
            name = ProviderName("Protected Strategy Storage"),
            type = ProviderType.STORAGE,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(
                ProviderHealth(ProviderHealthStatus.HEALTHY),
            )

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun readOutboundChanges(
            request: OutboundChangeReadRequest,
        ): ProviderOperationResult<OutboundChangeReadResult> {
            totalSynchronizationCalls++
            return ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges)
        }

        override suspend fun applyInboundChanges(
            request: InboundChangeApplyRequest,
        ): ProviderOperationResult<Unit> {
            totalSynchronizationCalls++
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun acknowledgeOutboundChanges(
            request: OutboundChangeAcknowledgementRequest,
        ): ProviderOperationResult<Unit> {
            totalSynchronizationCalls++
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun readCheckpoint(
            request: CheckpointReadRequest,
        ): ProviderOperationResult<SynchronizationCheckpoint?> {
            totalSynchronizationCalls++
            return ProviderOperationResult.Success(null)
        }

        override suspend fun writeCheckpoint(
            request: CheckpointWriteRequest,
        ): ProviderOperationResult<Unit> {
            totalSynchronizationCalls++
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun evaluateLocalFallback(
            request: StrategyLocalFallbackRequest,
        ): ProviderOperationResult<StrategyLocalFallbackResult> {
            fallbackCalls++
            return fallbackResult
        }
    }

    private class RecordingCircuitStore(
        initialRecords: Map<CircuitBreakerScope, CircuitBreakerStateRecord> = emptyMap(),
        private val compareError: DataLoomError? = null,
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
            compareError?.let { return ProviderOperationResult.Failure(it) }
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

    private class CountingClock(
        private val instant: DataLoomInstant,
    ) : DataLoomClock {
        var readCalls: Int = 0
            private set

        override fun now(): DataLoomInstant {
            readCalls++
            return instant
        }
    }

    private data class RemoteFailure(
        override val remoteOutcome: StrategyRemoteOutcome,
        override val code: ErrorCode = ErrorCode("REMOTE_UNAVAILABLE"),
        override val category: ErrorCategory = ErrorCategory.NETWORK,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Remote dependency unavailable.",
        override val cause: Throwable? = null,
    ) : ClassifiedStrategyRemoteError

    private data class TestError(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Protected strategy test error.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private companion object {
        val now = DataLoomInstant(1_000L)

        fun configuration(): CircuitBreakerConfiguration =
            CircuitBreakerConfiguration(
                failureThreshold = 2,
                failureWindow = SchedulingDelay(1_000L),
                openDuration = SchedulingDelay(10_000L),
            )

        val changeSet: ChangeSet = ChangeSet(
            id = ChangeSetId("protected-network-only-change-set"),
            events = listOf(
                ChangeEvent(
                    id = ChangeEventId("protected-network-only-event-1"),
                    entity = EntityReference(
                        type = EntityType("protected-strategy-entity"),
                        id = EntityId("protected-strategy-entity-1"),
                    ),
                    operation = ChangeOperation.CREATE,
                ),
            ),
        )

        fun acknowledgementFor(
            changeSet: ChangeSet,
            status: ChangeAcknowledgementStatus,
        ): ChangeSetAcknowledgement = ChangeSetAcknowledgement(
            changeSetId = changeSet.id,
            events = changeSet.events.map { ChangeEventAcknowledgement(it.id, status) },
        )

        fun networkOnlyPullRequest(): StrategySynchronizationRequest =
            StrategySynchronizationRequest(
                request = synchronizationRequest(
                    suffix = "network-only",
                    direction = SynchronizationDirection.PULL,
                ),
                decisionId = StrategyDecisionId("protected-network-only-decision"),
                planId = StrategyPlanId("protected-network-only-plan"),
                profile = NetworkOnlyStrategyProfile(
                    id = StrategyProfileId("protected-network-only-profile"),
                    configurationVersion = StrategyConfigurationVersion(1L),
                ),
                evidence = StrategyRuntimeEvidence(
                    connectivity = StrategyConnectivity.AVAILABLE,
                    transportHealth = StrategyProviderHealth.HEALTHY,
                ),
                input = StrategyOperationInput.DirectTransport(),
            )

        fun networkOnlyPushRequest(): StrategySynchronizationRequest =
            StrategySynchronizationRequest(
                request = synchronizationRequest(
                    suffix = "network-only-push",
                    direction = SynchronizationDirection.PUSH,
                ),
                decisionId = StrategyDecisionId("protected-network-only-push-decision"),
                planId = StrategyPlanId("protected-network-only-push-plan"),
                profile = NetworkOnlyStrategyProfile(
                    id = StrategyProfileId("protected-network-only-push-profile"),
                    configurationVersion = StrategyConfigurationVersion(1L),
                ),
                evidence = StrategyRuntimeEvidence(
                    connectivity = StrategyConnectivity.AVAILABLE,
                    transportHealth = StrategyProviderHealth.HEALTHY,
                ),
                input = StrategyOperationInput.DirectTransport(outboundChangeSet = changeSet),
            )

        fun networkOnlyBidirectionalRequest(): StrategySynchronizationRequest =
            StrategySynchronizationRequest(
                request = synchronizationRequest(
                    suffix = "network-only-bidirectional",
                    direction = SynchronizationDirection.BIDIRECTIONAL,
                ),
                decisionId = StrategyDecisionId("protected-network-only-bidirectional-decision"),
                planId = StrategyPlanId("protected-network-only-bidirectional-plan"),
                profile = NetworkOnlyStrategyProfile(
                    id = StrategyProfileId("protected-network-only-bidirectional-profile"),
                    configurationVersion = StrategyConfigurationVersion(1L),
                ),
                evidence = StrategyRuntimeEvidence(
                    connectivity = StrategyConnectivity.AVAILABLE,
                    transportHealth = StrategyProviderHealth.HEALTHY,
                ),
                input = StrategyOperationInput.DirectTransport(outboundChangeSet = changeSet),
            )

        fun remoteFirstFallbackRequest(): StrategySynchronizationRequest =
            StrategySynchronizationRequest(
                request = synchronizationRequest(
                    suffix = "remote-first",
                    direction = SynchronizationDirection.PULL,
                ),
                decisionId = StrategyDecisionId("protected-remote-first-decision"),
                planId = StrategyPlanId("protected-remote-first-plan"),
                profile = RemoteFirstStrategyProfile(
                    id = StrategyProfileId("protected-remote-first-profile"),
                    configurationVersion = StrategyConfigurationVersion(1L),
                    fallbackOn = setOf(StrategyRemoteOutcome.UNAVAILABLE),
                    persistRemoteResult = false,
                ),
                evidence = StrategyRuntimeEvidence(
                    connectivity = StrategyConnectivity.AVAILABLE,
                    cacheState = StrategyCacheState.FRESH,
                    storageHealth = StrategyProviderHealth.HEALTHY,
                    transportHealth = StrategyProviderHealth.HEALTHY,
                ),
                input = StrategyOperationInput.ProviderBacked,
            )

        fun synchronizationRequest(
            suffix: String,
            direction: SynchronizationDirection,
        ): SynchronizationRequest = SynchronizationRequest(
            workflowId = WorkflowId("protected-strategy-$suffix-workflow"),
            sessionId = SynchronizationSessionId("protected-strategy-$suffix-session"),
            direction = direction,
            mode = SynchronizationMode.DELTA,
            context = ExecutionContext(
                executionId = ExecutionId("protected-strategy-$suffix-execution"),
                correlationId = CorrelationId("protected-strategy-$suffix-correlation"),
            ),
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

        fun transportScopes(providerId: ProviderId): TransportCircuitScopes =
            TransportCircuitScopes(
                initialization = transportScope(
                    providerId,
                    TransportCircuitOperation.INITIALIZE,
                ),
                health = transportScope(providerId, TransportCircuitOperation.HEALTH),
                close = transportScope(providerId, TransportCircuitOperation.CLOSE),
                pushChanges = transportScope(
                    providerId,
                    TransportCircuitOperation.PUSH_CHANGES,
                ),
                pullChanges = transportScope(
                    providerId,
                    TransportCircuitOperation.PULL_CHANGES,
                ),
            )

        fun storageScope(
            providerId: ProviderId,
            operation: StorageCircuitOperation,
        ): CircuitBreakerScope = CircuitBreakerScope.providerOperation(
            providerId = providerId,
            operation = operation.retryOperation,
        )

        fun transportScope(
            providerId: ProviderId,
            operation: TransportCircuitOperation,
        ): CircuitBreakerScope = CircuitBreakerScope.providerOperation(
            providerId = providerId,
            operation = operation.retryOperation,
        )

        fun closedFailureRecord(
            scope: CircuitBreakerScope,
        ): CircuitBreakerStateRecord = CircuitBreakerStateRecord(
            state = CircuitBreakerState(
                scope = scope,
                phase = CircuitBreakerPhase.CLOSED,
                consecutiveFailures = 1,
                failureWindowStartedAt = now,
                openUntil = null,
                probeGeneration = 0L,
                probeInFlight = false,
                updatedAt = now,
            ),
            version = 0L,
        )

        fun runtimeDependencies(clock: DataLoomClock): RuntimeDependencies =
            RuntimeDependencies(
                clock = clock,
                identifiers = RuntimeIdentifierGenerators(
                    synchronizationEventIds = generator {
                        SynchronizationEventId("protected-strategy-event")
                    },
                    queueEntryIds = generator {
                        QueueEntryId("protected-strategy-queue-entry")
                    },
                    queueLeaseIds = generator {
                        QueueLeaseId("protected-strategy-queue-lease")
                    },
                    conflictIds = generator {
                        ConflictId("protected-strategy-conflict")
                    },
                ),
            )

        fun <T> generator(block: () -> T): IdentifierGenerator<T> =
            object : IdentifierGenerator<T> {
                override fun generate(): T = block()
            }

        fun error(
            code: String,
            category: ErrorCategory = ErrorCategory.PROVIDER,
        ): DataLoomError = TestError(
            code = ErrorCode(code),
            category = category,
        )
    }
}
