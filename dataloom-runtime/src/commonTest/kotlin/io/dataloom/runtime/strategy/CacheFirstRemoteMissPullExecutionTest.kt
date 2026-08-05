package io.dataloom.runtime.strategy

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.CheckpointKey
import io.dataloom.api.identifier.CheckpointToken
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
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.provider.StrategyProviderBindings
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.runtime.RuntimeIdentifierGenerators
import io.dataloom.api.storage.InboundChangeApplyRequest
import io.dataloom.api.storage.OutboundChangeReadRequest
import io.dataloom.api.storage.OutboundChangeReadResult
import io.dataloom.api.storage.StorageProvider
import io.dataloom.api.strategy.AdaptiveStrategyProfile
import io.dataloom.api.strategy.BuiltInSynchronizationStrategy
import io.dataloom.api.strategy.CacheFirstStrategyProfile
import io.dataloom.api.strategy.ClassifiedStrategyRemoteError
import io.dataloom.api.strategy.StrategyCacheState
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyConnectivity
import io.dataloom.api.strategy.StrategyDataOrigin
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.strategy.StrategyProviderHealth
import io.dataloom.api.strategy.StrategyRemoteOutcome
import io.dataloom.api.strategy.StrategyRuntimeEvidence
import io.dataloom.api.strategy.StrategySynchronizationRequest
import io.dataloom.api.strategy.StrategyTransportOutput
import io.dataloom.api.strategy.SynchronizationStrategyProfile
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.synchronization.CheckpointReadRequest
import io.dataloom.api.synchronization.CheckpointWriteRequest
import io.dataloom.api.synchronization.OutboundChangeAcknowledgementRequest
import io.dataloom.api.synchronization.SynchronizationCheckpoint
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.core.provider.ProviderLifecycleCoordinator
import io.dataloom.core.provider.ProviderRegistry
import io.dataloom.core.provider.StrategyProviderResolver
import io.dataloom.runtime.execution.SynchronizationPipelineRegistry
import io.dataloom.runtime.execution.inbound.InboundPullPipelineConfiguration
import io.dataloom.runtime.execution.inbound.InboundPullSynchronizationPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest

class CacheFirstRemoteMissPullExecutionTest {

    @Test
    fun cacheMissPullUsesCanonicalRemotePipelineWithoutCacheAccessContract() = runTest {
        val storage = RecordingStorage()
        val transport = RecordingTransport(
            ProviderOperationResult.Success(PullChangesResult.NoChanges()),
        )
        val fixture = fixture(storage, transport)

        val result = fixture.coordinator.execute(
            request = request(),
            bindings = fixture.bindings,
        )

        val executed = assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        val output = assertIs<StrategyTransportOutput.ProviderBacked>(executed.output)
        assertIs<SynchronizationResult.Skipped>(output.result)
        assertEquals(StrategyDataOrigin.REMOTE, executed.evaluation.plan.dataOrigin)
        assertEquals(
            listOf(
                StrategyOperation.READ_CHECKPOINT,
                StrategyOperation.PULL_REMOTE,
                StrategyOperation.PERSIST_REMOTE,
            ),
            executed.evaluation.plan.operations,
        )
        assertEquals(1, storage.readCheckpointCalls)
        assertEquals(1, transport.pullCalls)
        assertEquals(0, storage.applyCalls)
        assertEquals(0, storage.writeCheckpointCalls)
        assertEquals(0, storage.outboundCalls)
        assertEquals(0, transport.pushCalls)
    }

    @Test
    fun cacheMissPullPersistsRemoteNoChangeCheckpoint() = runTest {
        val checkpoint = checkpoint("next-cache-miss")
        val storage = RecordingStorage()
        val transport = RecordingTransport(
            ProviderOperationResult.Success(
                PullChangesResult.NoChanges(nextCheckpoint = checkpoint),
            ),
        )
        val fixture = fixture(storage, transport)

        val result = fixture.coordinator.execute(request(), fixture.bindings)

        val executed = assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        val output = assertIs<StrategyTransportOutput.ProviderBacked>(executed.output)
        assertIs<SynchronizationResult.Skipped>(output.result)
        assertEquals(1, storage.writeCheckpointCalls)
        assertEquals(checkpoint, storage.lastWrittenCheckpoint)
    }

    @Test
    fun checkpointFailureStopsBeforeTransport() = runTest {
        val failure = CacheMissFailure(
            code = ErrorCode("CACHE_MISS_CHECKPOINT_FAILED"),
            category = ErrorCategory.STORAGE,
        )
        val storage = RecordingStorage(
            readCheckpointResult = ProviderOperationResult.Failure(failure),
        )
        val transport = RecordingTransport(
            ProviderOperationResult.Success(PullChangesResult.NoChanges()),
        )
        val fixture = fixture(storage, transport)

        val result = fixture.coordinator.execute(request(), fixture.bindings)

        val failed = assertIs<StrategySynchronizationExecutionResult.Failed>(result)
        assertSame(failure, failed.error)
        assertEquals(false, failed.transportAttempted)
        assertEquals(emptyList(), failed.completedOperations)
        assertEquals(0, transport.pullCalls)
        val output = assertIs<StrategyTransportOutput.ProviderBacked>(failed.partialOutput)
        assertIs<SynchronizationResult.Failed>(output.result)
    }

    @Test
    fun transportFailureReportsAttemptWithoutPersistenceOrStrategySwitch() = runTest {
        val failure = CacheMissRemoteFailure()
        val storage = RecordingStorage()
        val transport = RecordingTransport(ProviderOperationResult.Failure(failure))
        val fixture = fixture(storage, transport)

        val result = fixture.coordinator.execute(request(), fixture.bindings)

        val failed = assertIs<StrategySynchronizationExecutionResult.Failed>(result)
        assertSame(failure, failed.error)
        assertEquals(true, failed.transportAttempted)
        assertEquals(StrategyRemoteOutcome.UNAVAILABLE, failed.remoteOutcome)
        assertEquals(emptyList(), failed.completedOperations)
        assertEquals(1, transport.pullCalls)
        assertEquals(0, storage.applyCalls)
        assertEquals(0, storage.writeCheckpointCalls)
    }

    @Test
    fun adaptiveSelectionCanExecuteConcreteCacheMissRemotePull() = runTest {
        val storage = RecordingStorage()
        val transport = RecordingTransport(
            ProviderOperationResult.Success(PullChangesResult.NoChanges()),
        )
        val fixture = fixture(storage, transport)
        val cacheProfile = cacheProfile()
        val adaptive = AdaptiveStrategyProfile(
            id = StrategyProfileId("adaptive-cache-miss"),
            configurationVersion = StrategyConfigurationVersion(3),
            candidates = listOf(cacheProfile),
        )

        val result = fixture.coordinator.execute(
            request = request(profile = adaptive),
            bindings = fixture.bindings,
        )

        val executed = assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        assertEquals(
            BuiltInSynchronizationStrategy.ADAPTIVE,
            executed.evaluation.plan.requestedStrategy,
        )
        assertEquals(
            BuiltInSynchronizationStrategy.CACHE_FIRST,
            executed.evaluation.plan.effectiveStrategy,
        )
        assertEquals(cacheProfile.id, executed.evaluation.plan.effectiveProfileId)
        assertEquals(1, transport.pullCalls)
    }

    @Test
    fun bidirectionalCacheMissRemainsRejectedBeforeProviderInvocation() = runTest {
        val storage = RecordingStorage()
        val transport = RecordingTransport(
            ProviderOperationResult.Success(PullChangesResult.NoChanges()),
        )
        val fixture = fixture(storage, transport)

        val result = fixture.coordinator.execute(
            request = request(direction = SynchronizationDirection.BIDIRECTIONAL),
            bindings = fixture.bindings,
        )

        val rejected = assertIs<StrategySynchronizationExecutionResult.Rejected>(result)
        assertEquals(StrategyExecutionRejectionReason.UNSUPPORTED_PLAN, rejected.reason)
        assertEquals(0, storage.readCheckpointCalls)
        assertEquals(0, storage.outboundCalls)
        assertEquals(0, transport.pullCalls)
        assertEquals(0, transport.pushCalls)
    }

    private suspend fun fixture(
        storage: RecordingStorage,
        transport: RecordingTransport,
    ): Fixture {
        val registry = ProviderRegistry(listOf(storage, transport))
        val lifecycle = ProviderLifecycleCoordinator(
            registry = registry,
            context = ProviderInitializationContext(),
        )
        lifecycle.initialize()
        val dependencies = runtimeDependencies()
        return Fixture(
            coordinator = StrategySynchronizationExecutionCoordinator(
                lifecycleCoordinator = lifecycle,
                evaluator = BuiltInSynchronizationStrategyEvaluator(),
                providerResolver = StrategyProviderResolver(registry),
                clock = dependencies.clock,
                runtimeDependencies = dependencies,
                pipelineRegistry = SynchronizationPipelineRegistry(
                    listOf(
                        InboundPullSynchronizationPipeline(
                            InboundPullPipelineConfiguration(),
                        ),
                    ),
                ),
                lifecycleEventEmitter = null,
            ),
            bindings = StrategyProviderBindings(
                storageProviderId = storage.descriptor.id,
                transportProviderId = transport.descriptor.id,
            ),
        )
    }

    private fun request(
        profile: SynchronizationStrategyProfile = cacheProfile(),
        direction: SynchronizationDirection = SynchronizationDirection.PULL,
    ): StrategySynchronizationRequest =
        StrategySynchronizationRequest(
            request = SynchronizationRequest(
                workflowId = WorkflowId("cache-miss-workflow"),
                sessionId = SynchronizationSessionId("cache-miss-session"),
                direction = direction,
                mode = SynchronizationMode.DELTA,
                context = ExecutionContext(
                    executionId = ExecutionId("cache-miss-execution"),
                    correlationId = CorrelationId("cache-miss-correlation"),
                ),
            ),
            decisionId = StrategyDecisionId("cache-miss-decision"),
            planId = StrategyPlanId("cache-miss-plan"),
            profile = profile,
            evidence = StrategyRuntimeEvidence(
                connectivity = StrategyConnectivity.AVAILABLE,
                cacheState = StrategyCacheState.MISSING,
                storageHealth = StrategyProviderHealth.HEALTHY,
                transportHealth = StrategyProviderHealth.HEALTHY,
            ),
        )

    private fun cacheProfile(): CacheFirstStrategyProfile =
        CacheFirstStrategyProfile(
            id = StrategyProfileId("cache-miss-profile"),
            configurationVersion = StrategyConfigurationVersion(1),
        )

    private fun checkpoint(token: String): SynchronizationCheckpoint =
        SynchronizationCheckpoint(
            key = CheckpointKey("cache-miss-workflow"),
            token = CheckpointToken(token),
        )

    private fun runtimeDependencies(): RuntimeDependencies =
        RuntimeDependencies(
            clock = FixedClock(DataLoomInstant(12_000L)),
            identifiers = RuntimeIdentifierGenerators(
                synchronizationEventIds =
                    fixedGenerator(SynchronizationEventId("cache-miss-event")),
                queueEntryIds = fixedGenerator(QueueEntryId("cache-miss-entry")),
                queueLeaseIds = fixedGenerator(QueueLeaseId("cache-miss-lease")),
                conflictIds = fixedGenerator(ConflictId("cache-miss-conflict")),
            ),
        )

    private fun <T> fixedGenerator(value: T): IdentifierGenerator<T> =
        object : IdentifierGenerator<T> {
            override fun generate(): T = value
        }

    private data class Fixture(
        val coordinator: StrategySynchronizationExecutionCoordinator,
        val bindings: StrategyProviderBindings,
    )

    private class RecordingStorage(
        private val readCheckpointResult: ProviderOperationResult<SynchronizationCheckpoint?> =
            ProviderOperationResult.Success(null),
        private val writeCheckpointResult: ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit),
    ) : StorageProvider {
        override val descriptor: ProviderDescriptor = descriptor(
            id = "cache-miss-storage",
            type = ProviderType.STORAGE,
        )

        var readCheckpointCalls: Int = 0
            private set
        var writeCheckpointCalls: Int = 0
            private set
        var applyCalls: Int = 0
            private set
        var outboundCalls: Int = 0
            private set
        var lastWrittenCheckpoint: SynchronizationCheckpoint? = null
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

        override suspend fun readOutboundChanges(
            request: OutboundChangeReadRequest,
        ): ProviderOperationResult<OutboundChangeReadResult> {
            outboundCalls++
            return ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges)
        }

        override suspend fun applyInboundChanges(
            request: InboundChangeApplyRequest,
        ): ProviderOperationResult<Unit> {
            applyCalls++
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun acknowledgeOutboundChanges(
            request: OutboundChangeAcknowledgementRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun readCheckpoint(
            request: CheckpointReadRequest,
        ): ProviderOperationResult<SynchronizationCheckpoint?> {
            readCheckpointCalls++
            return readCheckpointResult
        }

        override suspend fun writeCheckpoint(
            request: CheckpointWriteRequest,
        ): ProviderOperationResult<Unit> {
            writeCheckpointCalls++
            lastWrittenCheckpoint = request.checkpoint
            return writeCheckpointResult
        }
    }

    private class RecordingTransport(
        private val pullResult: ProviderOperationResult<PullChangesResult>,
    ) : TransportProvider {
        override val descriptor: ProviderDescriptor = descriptor(
            id = "cache-miss-transport",
            type = ProviderType.TRANSPORT,
        )

        var pullCalls: Int = 0
            private set
        var pushCalls: Int = 0
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

        override suspend fun pushChanges(
            request: PushChangesRequest,
        ): ProviderOperationResult<ChangeSetAcknowledgement> {
            pushCalls++
            error("Cache-miss PULL must not invoke pushChanges.")
        }

        override suspend fun pullChanges(
            request: PullChangesRequest,
        ): ProviderOperationResult<PullChangesResult> {
            pullCalls++
            return pullResult
        }
    }

    private class FixedClock(
        private val instant: DataLoomInstant,
    ) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
    }

    private data class CacheMissFailure(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Cache-miss provider failure.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private data class CacheMissRemoteFailure(
        override val remoteOutcome: StrategyRemoteOutcome = StrategyRemoteOutcome.UNAVAILABLE,
        override val code: ErrorCode = ErrorCode("CACHE_MISS_REMOTE_UNAVAILABLE"),
        override val category: ErrorCategory = ErrorCategory.NETWORK,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Cache-miss remote dependency unavailable.",
        override val cause: Throwable? = null,
    ) : ClassifiedStrategyRemoteError

    private companion object {
        fun descriptor(id: String, type: ProviderType): ProviderDescriptor =
            ProviderDescriptor(
                id = ProviderId(id),
                name = ProviderName(id),
                type = type,
                version = ProviderVersion("1.0.0"),
            )
    }
}
