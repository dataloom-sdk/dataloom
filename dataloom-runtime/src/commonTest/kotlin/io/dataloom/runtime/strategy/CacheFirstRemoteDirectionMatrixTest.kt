package io.dataloom.runtime.strategy

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.ChangeSet
import io.dataloom.api.change.EntityReference
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
import io.dataloom.api.synchronization.ChangeAcknowledgementStatus
import io.dataloom.api.synchronization.ChangeEventAcknowledgement
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
import io.dataloom.runtime.execution.bidirectional.BidirectionalPipelineConfiguration
import io.dataloom.runtime.execution.bidirectional.BidirectionalSynchronizationPipeline
import io.dataloom.runtime.execution.inbound.InboundPullPipelineConfiguration
import io.dataloom.runtime.execution.inbound.InboundPullSynchronizationPipeline
import io.dataloom.runtime.execution.outbound.OutboundPushPipelineConfiguration
import io.dataloom.runtime.execution.outbound.OutboundPushSynchronizationPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest

class CacheFirstRemoteDirectionMatrixTest {

    @Test
    fun pushWithNoLocalChangesUsesCanonicalOutboundPipeline() = runTest {
        val storage = RecordingStorage(
            outboundResults = mutableListOf(
                ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges),
            ),
        )
        val transport = RecordingTransport()
        val fixture = fixture(storage, transport)

        val result = fixture.coordinator.execute(
            request = request(SynchronizationDirection.PUSH),
            bindings = fixture.bindings,
        )

        val executed = assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        val output = assertIs<StrategyTransportOutput.ProviderBacked>(executed.output)
        assertIs<SynchronizationResult.Skipped>(output.result)
        assertEquals(
            listOf(StrategyOperation.READ_LOCAL, StrategyOperation.PUSH_REMOTE),
            executed.evaluation.plan.operations,
        )
        assertEquals(StrategyDataOrigin.LOCAL, executed.evaluation.plan.dataOrigin)
        assertEquals(1, storage.outboundCalls)
        assertEquals(0, storage.checkpointReads)
        assertEquals(0, transport.pushCalls)
        assertEquals(0, transport.pullCalls)
    }

    @Test
    fun pushWithLocalChangesPushesAndAcknowledgesExactlyOnce() = runTest {
        val changeSet = changeSet()
        val storage = RecordingStorage(
            outboundResults = mutableListOf(
                ProviderOperationResult.Success(
                    OutboundChangeReadResult.Changes(changeSet, hasMore = false),
                ),
            ),
        )
        val transport = RecordingTransport(
            pushResults = mutableListOf(
                ProviderOperationResult.Success(acknowledgement(changeSet)),
            ),
        )
        val fixture = fixture(storage, transport)

        val result = fixture.coordinator.execute(
            request = request(SynchronizationDirection.PUSH),
            bindings = fixture.bindings,
        )

        val executed = assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        val output = assertIs<StrategyTransportOutput.ProviderBacked>(executed.output)
        assertIs<SynchronizationResult.Succeeded>(output.result)
        assertEquals(1, transport.pushCalls)
        assertEquals(1, storage.acknowledgeCalls)
        assertSame(changeSet, transport.lastPushedChangeSet)
        assertEquals(0, transport.pullCalls)
        assertEquals(0, storage.checkpointReads)
    }

    @Test
    fun pushTransportFailureReportsAttemptAndTypedRemoteOutcome() = runTest {
        val changeSet = changeSet()
        val failure = RemoteUnavailable()
        val storage = RecordingStorage(
            outboundResults = mutableListOf(
                ProviderOperationResult.Success(
                    OutboundChangeReadResult.Changes(changeSet, hasMore = false),
                ),
            ),
        )
        val transport = RecordingTransport(
            pushResults = mutableListOf(ProviderOperationResult.Failure(failure)),
        )
        val fixture = fixture(storage, transport)

        val result = fixture.coordinator.execute(
            request = request(SynchronizationDirection.PUSH),
            bindings = fixture.bindings,
        )

        val failed = assertIs<StrategySynchronizationExecutionResult.Failed>(result)
        assertSame(failure, failed.error)
        assertEquals(true, failed.transportAttempted)
        assertEquals(StrategyRemoteOutcome.UNAVAILABLE, failed.remoteOutcome)
        assertEquals(emptyList(), failed.completedOperations)
        assertEquals(1, transport.pushCalls)
        assertEquals(0, storage.acknowledgeCalls)
        assertEquals(0, transport.pullCalls)
    }

    @Test
    fun bidirectionalCacheMissRunsOutboundThenInboundWithoutStrategySwitch() = runTest {
        val storage = RecordingStorage(
            outboundResults = mutableListOf(
                ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges),
            ),
        )
        val transport = RecordingTransport(
            pullResults = mutableListOf(
                ProviderOperationResult.Success(PullChangesResult.NoChanges()),
            ),
        )
        val fixture = fixture(storage, transport)

        val result = fixture.coordinator.execute(
            request = request(SynchronizationDirection.BIDIRECTIONAL),
            bindings = fixture.bindings,
        )

        val executed = assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        val output = assertIs<StrategyTransportOutput.ProviderBacked>(executed.output)
        assertIs<SynchronizationResult.Skipped>(output.result)
        assertEquals(
            listOf(
                StrategyOperation.READ_LOCAL,
                StrategyOperation.PUSH_REMOTE,
                StrategyOperation.READ_CHECKPOINT,
                StrategyOperation.PULL_REMOTE,
                StrategyOperation.PERSIST_REMOTE,
            ),
            executed.evaluation.plan.operations,
        )
        assertEquals(StrategyDataOrigin.MIXED, executed.evaluation.plan.dataOrigin)
        assertEquals(1, storage.outboundCalls)
        assertEquals(1, storage.checkpointReads)
        assertEquals(0, transport.pushCalls)
        assertEquals(1, transport.pullCalls)
    }

    @Test
    fun bidirectionalPullFailurePreservesCompletedPushEvidence() = runTest {
        val changeSet = changeSet()
        val failure = RemoteUnavailable(code = ErrorCode("CACHE_BIDI_PULL_UNAVAILABLE"))
        val storage = RecordingStorage(
            outboundResults = mutableListOf(
                ProviderOperationResult.Success(
                    OutboundChangeReadResult.Changes(changeSet, hasMore = false),
                ),
            ),
        )
        val transport = RecordingTransport(
            pushResults = mutableListOf(
                ProviderOperationResult.Success(acknowledgement(changeSet)),
            ),
            pullResults = mutableListOf(ProviderOperationResult.Failure(failure)),
        )
        val fixture = fixture(storage, transport)

        val result = fixture.coordinator.execute(
            request = request(SynchronizationDirection.BIDIRECTIONAL),
            bindings = fixture.bindings,
        )

        val failed = assertIs<StrategySynchronizationExecutionResult.Failed>(result)
        assertSame(failure, failed.error)
        assertEquals(true, failed.transportAttempted)
        assertEquals(StrategyRemoteOutcome.UNAVAILABLE, failed.remoteOutcome)
        assertEquals(listOf(StrategyOperation.PUSH_REMOTE), failed.completedOperations)
        assertEquals(1, transport.pushCalls)
        assertEquals(1, storage.acknowledgeCalls)
        assertEquals(1, transport.pullCalls)
        assertEquals(0, storage.applyCalls)
        assertEquals(0, storage.checkpointWrites)
    }

    @Test
    fun bidirectionalOutboundStorageFailureStopsBeforeTransport() = runTest {
        val failure = MatrixFailure(
            code = ErrorCode("CACHE_BIDI_OUTBOUND_READ_FAILED"),
            category = ErrorCategory.STORAGE,
        )
        val storage = RecordingStorage(
            outboundResults = mutableListOf(ProviderOperationResult.Failure(failure)),
        )
        val transport = RecordingTransport()
        val fixture = fixture(storage, transport)

        val result = fixture.coordinator.execute(
            request = request(SynchronizationDirection.BIDIRECTIONAL),
            bindings = fixture.bindings,
        )

        val failed = assertIs<StrategySynchronizationExecutionResult.Failed>(result)
        assertSame(failure, failed.error)
        assertEquals(false, failed.transportAttempted)
        assertEquals(emptyList(), failed.completedOperations)
        assertEquals(0, transport.pushCalls)
        assertEquals(0, transport.pullCalls)
        assertEquals(0, storage.checkpointReads)
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
        val outbound = OutboundPushSynchronizationPipeline(
            OutboundPushPipelineConfiguration(),
        )
        val inbound = InboundPullSynchronizationPipeline(
            InboundPullPipelineConfiguration(),
        )
        val bidirectional = BidirectionalSynchronizationPipeline(
            outboundPipeline = outbound,
            inboundPipeline = inbound,
            configuration = BidirectionalPipelineConfiguration(),
        )
        return Fixture(
            coordinator = StrategySynchronizationExecutionCoordinator(
                lifecycleCoordinator = lifecycle,
                evaluator = BuiltInSynchronizationStrategyEvaluator(),
                providerResolver = StrategyProviderResolver(registry),
                clock = dependencies.clock,
                runtimeDependencies = dependencies,
                pipelineRegistry = SynchronizationPipelineRegistry(
                    listOf(outbound, inbound, bidirectional),
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
        direction: SynchronizationDirection,
    ): StrategySynchronizationRequest =
        StrategySynchronizationRequest(
            request = SynchronizationRequest(
                workflowId = WorkflowId("cache-matrix-$direction-workflow"),
                sessionId = SynchronizationSessionId("cache-matrix-$direction-session"),
                direction = direction,
                mode = SynchronizationMode.DELTA,
                context = ExecutionContext(
                    executionId = ExecutionId("cache-matrix-$direction-execution"),
                    correlationId = CorrelationId("cache-matrix-$direction-correlation"),
                ),
            ),
            decisionId = StrategyDecisionId("cache-matrix-$direction-decision"),
            planId = StrategyPlanId("cache-matrix-$direction-plan"),
            profile = CacheFirstStrategyProfile(
                id = StrategyProfileId("cache-matrix-profile"),
                configurationVersion = StrategyConfigurationVersion(1),
            ),
            evidence = StrategyRuntimeEvidence(
                connectivity = StrategyConnectivity.AVAILABLE,
                cacheState = if (direction == SynchronizationDirection.PUSH) {
                    StrategyCacheState.NOT_EVALUATED
                } else {
                    StrategyCacheState.MISSING
                },
                storageHealth = StrategyProviderHealth.HEALTHY,
                transportHealth = StrategyProviderHealth.HEALTHY,
            ),
        )

    private fun changeSet(): ChangeSet = ChangeSet(
        id = ChangeSetId("cache-matrix-change-set"),
        events = listOf(
            ChangeEvent(
                id = ChangeEventId("cache-matrix-event"),
                entity = EntityReference(
                    type = EntityType("Order"),
                    id = EntityId("cache-matrix-order"),
                ),
                operation = ChangeOperation.UPDATE,
            ),
        ),
    )

    private fun acknowledgement(changeSet: ChangeSet): ChangeSetAcknowledgement =
        ChangeSetAcknowledgement(
            changeSetId = changeSet.id,
            events = changeSet.events.map { event ->
                ChangeEventAcknowledgement(
                    eventId = event.id,
                    status = ChangeAcknowledgementStatus.ACCEPTED,
                )
            },
        )

    private fun runtimeDependencies(): RuntimeDependencies = RuntimeDependencies(
        clock = FixedClock(DataLoomInstant(14_000L)),
        identifiers = RuntimeIdentifierGenerators(
            synchronizationEventIds =
                fixedGenerator(SynchronizationEventId("cache-matrix-runtime-event")),
            queueEntryIds = fixedGenerator(QueueEntryId("cache-matrix-entry")),
            queueLeaseIds = fixedGenerator(QueueLeaseId("cache-matrix-lease")),
            conflictIds = fixedGenerator(ConflictId("cache-matrix-conflict")),
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
        private val outboundResults: MutableList<ProviderOperationResult<OutboundChangeReadResult>> =
            mutableListOf(),
        private val checkpointResult: ProviderOperationResult<SynchronizationCheckpoint?> =
            ProviderOperationResult.Success(null),
        private val applyResult: ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit),
        private val acknowledgeResult: ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit),
        private val checkpointWriteResult: ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit),
    ) : StorageProvider {
        override val descriptor: ProviderDescriptor = descriptor(
            id = "cache-matrix-storage",
            type = ProviderType.STORAGE,
        )

        var outboundCalls: Int = 0
            private set
        var acknowledgeCalls: Int = 0
            private set
        var checkpointReads: Int = 0
            private set
        var checkpointWrites: Int = 0
            private set
        var applyCalls: Int = 0
            private set

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun readOutboundChanges(
            request: OutboundChangeReadRequest,
        ): ProviderOperationResult<OutboundChangeReadResult> {
            outboundCalls++
            check(outboundResults.isNotEmpty()) {
                "No queued outbound result for cache-first direction matrix."
            }
            return outboundResults.removeAt(0)
        }

        override suspend fun applyInboundChanges(
            request: InboundChangeApplyRequest,
        ): ProviderOperationResult<Unit> {
            applyCalls++
            return applyResult
        }

        override suspend fun acknowledgeOutboundChanges(
            request: OutboundChangeAcknowledgementRequest,
        ): ProviderOperationResult<Unit> {
            acknowledgeCalls++
            return acknowledgeResult
        }

        override suspend fun readCheckpoint(
            request: CheckpointReadRequest,
        ): ProviderOperationResult<SynchronizationCheckpoint?> {
            checkpointReads++
            return checkpointResult
        }

        override suspend fun writeCheckpoint(
            request: CheckpointWriteRequest,
        ): ProviderOperationResult<Unit> {
            checkpointWrites++
            return checkpointWriteResult
        }
    }

    private class RecordingTransport(
        private val pushResults: MutableList<ProviderOperationResult<ChangeSetAcknowledgement>> =
            mutableListOf(),
        private val pullResults: MutableList<ProviderOperationResult<PullChangesResult>> =
            mutableListOf(),
    ) : TransportProvider {
        override val descriptor: ProviderDescriptor = descriptor(
            id = "cache-matrix-transport",
            type = ProviderType.TRANSPORT,
        )

        var pushCalls: Int = 0
            private set
        var pullCalls: Int = 0
            private set
        var lastPushedChangeSet: ChangeSet? = null
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
            lastPushedChangeSet = request.changeSet
            check(pushResults.isNotEmpty()) {
                "No queued push result for cache-first direction matrix."
            }
            return pushResults.removeAt(0)
        }

        override suspend fun pullChanges(
            request: PullChangesRequest,
        ): ProviderOperationResult<PullChangesResult> {
            pullCalls++
            check(pullResults.isNotEmpty()) {
                "No queued pull result for cache-first direction matrix."
            }
            return pullResults.removeAt(0)
        }
    }

    private class FixedClock(
        private val instant: DataLoomInstant,
    ) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
    }

    private data class MatrixFailure(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Cache-first direction matrix failure.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private data class RemoteUnavailable(
        override val code: ErrorCode = ErrorCode("CACHE_MATRIX_REMOTE_UNAVAILABLE"),
        override val remoteOutcome: StrategyRemoteOutcome = StrategyRemoteOutcome.UNAVAILABLE,
        override val category: ErrorCategory = ErrorCategory.NETWORK,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Cache-first matrix remote unavailable.",
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
