package io.dataloom.runtime.execution.inbound

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.ChangeSet
import io.dataloom.api.change.EntityReference
import io.dataloom.api.conflict.ConflictDetectionRequest
import io.dataloom.api.conflict.ConflictDetectionResult
import io.dataloom.api.conflict.ConflictDetector
import io.dataloom.api.conflict.ConflictResolutionDecision
import io.dataloom.api.conflict.ConflictResolutionRequest
import io.dataloom.api.conflict.ConflictResolver
import io.dataloom.api.conflict.ConflictType
import io.dataloom.api.conflict.DurableResolvedConflictDecisionLog
import io.dataloom.api.conflict.DurableUnresolvedConflictLog
import io.dataloom.api.conflict.ResolvedConflictDecisionRecord
import io.dataloom.api.conflict.SynchronizationConflict
import io.dataloom.api.conflict.UnresolvedConflictRecord
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ChangeSetId
import io.dataloom.api.identifier.CheckpointKey
import io.dataloom.api.identifier.CheckpointToken
import io.dataloom.api.identifier.ConflictDetectorId
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.ConflictResolverId
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
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.runtime.RuntimeIdentifierGenerators
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateRecord
import io.dataloom.api.state.DurableStateStore
import io.dataloom.api.storage.InboundChangeApplyRequest
import io.dataloom.api.storage.LocalConflictCandidateReadRequest
import io.dataloom.api.storage.LocalConflictCandidateReadResult
import io.dataloom.api.storage.OutboundChangeReadRequest
import io.dataloom.api.storage.OutboundChangeReadResult
import io.dataloom.api.storage.StorageProvider
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
import io.dataloom.core.provider.ResolvedSynchronizationProviders
import io.dataloom.runtime.conflict.ConflictDetectorRegistry
import io.dataloom.runtime.conflict.ConflictOrchestrationBindings
import io.dataloom.runtime.conflict.ConflictResolverRegistry
import io.dataloom.runtime.conflict.DurableConflictDetectionCoordinator
import io.dataloom.runtime.conflict.SynchronizationConflictOrchestrator
import io.dataloom.runtime.execution.SynchronizationExecutionContext
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

abstract class ConflictDecisionApplicationFixture {

    protected val request = SynchronizationRequest(
        workflowId = WorkflowId("workflow-application"),
        sessionId = SynchronizationSessionId("session-application"),
        direction = SynchronizationDirection.PULL,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("execution-application"),
            correlationId = CorrelationId("correlation-application"),
        ),
    )
    protected val detectorId = ConflictDetectorId("test.detector")
    protected val resolverId = ConflictResolverId("test.resolver")
    protected val conflictId = ConflictId("conflict-stable")
    protected val entity = EntityReference(
        type = EntityType("Document"),
        id = EntityId("document-1"),
    )
    protected val localEvent = ChangeEvent(
        id = ChangeEventId("local-event"),
        entity = entity,
        operation = ChangeOperation.UPDATE,
    )
    protected val remoteEvent = ChangeEvent(
        id = ChangeEventId("remote-event"),
        entity = entity,
        operation = ChangeOperation.UPDATE,
    )
    protected val checkpoint = SynchronizationCheckpoint(
        key = CheckpointKey(request.workflowId.value),
        token = CheckpointToken("checkpoint-next"),
    )

    protected fun pipeline(
        detector: ConflictDetector = ExactConflictDetector(),
        resolver: ConflictResolver?,
        unresolvedStore: DurableStateStore<ConflictId, UnresolvedConflictRecord> =
            InMemoryDurableStore(),
        resolvedStore: DurableStateStore<ConflictId, ResolvedConflictDecisionRecord>?,
    ): InboundPullSynchronizationPipeline {
        val orchestrator = SynchronizationConflictOrchestrator(
            detectorRegistry = ConflictDetectorRegistry(listOf(detector)),
            resolverRegistry = ConflictResolverRegistry(
                if (resolver == null) emptyList() else listOf(resolver),
            ),
        )
        val coordinator = DurableConflictDetectionCoordinator(
            orchestrator = orchestrator,
            unresolvedConflictLog = DurableUnresolvedConflictLog(unresolvedStore),
            clock = FixedClock,
            resolvedConflictDecisionLog = resolvedStore?.let {
                DurableResolvedConflictDecisionLog(it)
            },
        )
        return InboundPullSynchronizationPipeline(
            configuration = InboundPullPipelineConfiguration(),
            conflictDetection = InboundPullConflictDetectionConfiguration(
                coordinator = coordinator,
                bindings = ConflictOrchestrationBindings(
                    detectorId = detector.id,
                    resolverId = resolver?.id,
                ),
            ),
        )
    }

    protected fun context(
        storage: StorageProvider,
        transport: TransportProvider,
    ): SynchronizationExecutionContext =
        SynchronizationExecutionContext(
            request = request,
            providers = ResolvedSynchronizationProviders(
                storageProvider = storage,
                transportProvider = transport,
                schedulerProvider = null,
                connectivityProvider = null,
                queueProvider = null,
            ),
            runtimeDependencies = RuntimeDependencies(
                clock = FixedClock,
                identifiers = RuntimeIdentifierGenerators(
                    synchronizationEventIds = generator {
                        SynchronizationEventId("event-generated")
                    },
                    queueEntryIds = generator { QueueEntryId("queue-generated") },
                    queueLeaseIds = generator { QueueLeaseId("lease-generated") },
                    conflictIds = generator { conflictId },
                ),
            ),
        )

    protected fun transport(
        changeSet: ChangeSet,
        nextCheckpoint: SynchronizationCheckpoint? = null,
    ): FakeTransportProvider =
        FakeTransportProvider(
            ProviderOperationResult.Success(
                PullChangesResult.Changes(
                    changeSet = changeSet,
                    hasMore = false,
                    nextCheckpoint = nextCheckpoint,
                ),
            ),
        )

    protected fun storageWithCandidate(
        candidate: ChangeEvent,
        applyResults: MutableList<ProviderOperationResult<Unit>> =
            mutableListOf(ProviderOperationResult.Success(Unit)),
    ): FakeStorageProvider =
        FakeStorageProvider(
            candidateResults = mutableMapOf(
                candidate.entity.id.value to ProviderOperationResult.Success(
                    LocalConflictCandidateReadResult.Found(candidate),
                ),
            ),
            applyResults = applyResults,
        )

    protected fun changeSet(vararg events: ChangeEvent): ChangeSet =
        ChangeSet(
            id = ChangeSetId("change-set-stable"),
            events = events.toList(),
        )

    protected fun <T> generator(block: () -> T): IdentifierGenerator<T> =
        object : IdentifierGenerator<T> {
            override fun generate(): T = block()
        }

    protected inner class ExactConflictDetector : ConflictDetector {
        override val id: ConflictDetectorId = detectorId

        override fun detect(request: ConflictDetectionRequest): ConflictDetectionResult =
            ConflictDetectionResult.ConflictDetected(
                SynchronizationConflict(
                    id = conflictId,
                    type = ConflictType.CONCURRENT_CHANGE,
                    entity = request.localChange.entity,
                    localChange = request.localChange,
                    remoteChange = request.remoteChange,
                ),
            )
    }

    protected class FixedDetector(
        protected val result: ConflictDetectionResult,
    ) : ConflictDetector {
        override val id: ConflictDetectorId = ConflictDetectorId("test.detector.fixed")
        override fun detect(request: ConflictDetectionRequest): ConflictDetectionResult = result
    }

    protected inner class MutableResolver(
        var decision: ConflictResolutionDecision,
    ) : ConflictResolver {
        override val id: ConflictResolverId = resolverId
        override fun resolve(request: ConflictResolutionRequest): ConflictResolutionDecision = decision
    }

    protected class FakeStorageProvider(
        protected val candidateResults:
            MutableMap<String, ProviderOperationResult<LocalConflictCandidateReadResult>> =
            mutableMapOf(),
        protected val applyResults: MutableList<ProviderOperationResult<Unit>> =
            mutableListOf(ProviderOperationResult.Success(Unit)),
        protected val writeResults: MutableList<ProviderOperationResult<Unit>> =
            mutableListOf(ProviderOperationResult.Success(Unit)),
    ) : StorageProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("storage-test"),
            name = ProviderName("Storage test"),
            type = ProviderType.STORAGE,
            version = ProviderVersion("1.0.0"),
        )

        val appliedChangeSets = mutableListOf<ChangeSet>()
        val writtenCheckpoints = mutableListOf<SynchronizationCheckpoint>()
        var applyCallCount = 0
            private set

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(
                ProviderHealth(status = ProviderHealthStatus.HEALTHY),
            )

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun readOutboundChanges(
            request: OutboundChangeReadRequest,
        ): ProviderOperationResult<OutboundChangeReadResult> =
            ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges)

        override suspend fun applyInboundChanges(
            request: InboundChangeApplyRequest,
        ): ProviderOperationResult<Unit> {
            applyCallCount++
            appliedChangeSets += request.changeSet
            return if (applyResults.isEmpty()) {
                ProviderOperationResult.Success(Unit)
            } else {
                applyResults.removeAt(0)
            }
        }

        override suspend fun acknowledgeOutboundChanges(
            request: OutboundChangeAcknowledgementRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun readCheckpoint(
            request: CheckpointReadRequest,
        ): ProviderOperationResult<SynchronizationCheckpoint?> =
            ProviderOperationResult.Success(null)

        override suspend fun writeCheckpoint(
            request: CheckpointWriteRequest,
        ): ProviderOperationResult<Unit> {
            writtenCheckpoints += request.checkpoint
            return if (writeResults.isEmpty()) {
                ProviderOperationResult.Success(Unit)
            } else {
                writeResults.removeAt(0)
            }
        }

        override suspend fun readLocalConflictCandidate(
            request: LocalConflictCandidateReadRequest,
        ): ProviderOperationResult<LocalConflictCandidateReadResult> =
            candidateResults[request.entity.id.value]
                ?: ProviderOperationResult.Success(
                    LocalConflictCandidateReadResult.NotFound,
                )
    }

    protected class FakeTransportProvider(
        protected val result: ProviderOperationResult<PullChangesResult>,
    ) : TransportProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("transport-test"),
            name = ProviderName("Transport test"),
            type = ProviderType.TRANSPORT,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(
                ProviderHealth(status = ProviderHealthStatus.HEALTHY),
            )

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun pushChanges(
            request: PushChangesRequest,
        ): ProviderOperationResult<ChangeSetAcknowledgement> =
            ProviderOperationResult.Failure(
                TestError(
                    code = ErrorCode("PUSH-NOT-USED"),
                    category = ErrorCategory.NETWORK,
                    recoverability = Recoverability.NON_RECOVERABLE,
                ),
            )

        override suspend fun pullChanges(
            request: PullChangesRequest,
        ): ProviderOperationResult<PullChangesResult> = result
    }

    protected class InMemoryDurableStore<TScope : Any, TState : Any>(
        protected val loadFailure: DataLoomError? = null,
        protected val compareAndSetFailure: DataLoomError? = null,
    ) : DurableStateStore<TScope, TState> {
        protected val records = mutableMapOf<TScope, DurableStateRecord<TState>>()

        fun state(scope: TScope): TState? = records[scope]?.state

        override suspend fun load(
            scope: TScope,
        ): ProviderOperationResult<DurableStateLoadResult<TState>> {
            loadFailure?.let { return ProviderOperationResult.Failure(it) }
            val current = records[scope]
            return ProviderOperationResult.Success(
                if (current == null) {
                    DurableStateLoadResult.Missing
                } else {
                    DurableStateLoadResult.Found(current)
                },
            )
        }

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<TScope, TState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<TState>> {
            compareAndSetFailure?.let {
                return ProviderOperationResult.Failure(it)
            }
            val current = records[request.scope]
            if (current?.version != request.expectedVersion) {
                return ProviderOperationResult.Success(
                    DurableStateCompareAndSetResult.Conflict(current),
                )
            }
            val updated = DurableStateRecord(
                state = request.nextState,
                version = (current?.version ?: -1L) + 1L,
                schemaVersion = request.nextSchemaVersion,
            )
            records[request.scope] = updated
            return ProviderOperationResult.Success(
                DurableStateCompareAndSetResult.Updated(updated),
            )
        }
    }

    protected data class TestError(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val recoverability: Recoverability,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val message: String = "Test error.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    protected object FixedClock : DataLoomClock {
        override fun now(): DataLoomInstant = DataLoomInstant(42_000L)
    }

    protected object Pending

    @Suppress("UNCHECKED_CAST")
    protected fun <T> runSuspend(block: suspend () -> T): T {
        var rawResult: Any? = Pending
        var thrown: Throwable? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context: CoroutineContext = EmptyCoroutineContext

                override fun resumeWith(result: Result<T>) {
                    if (result.isSuccess) {
                        rawResult = result.getOrNull()
                    } else {
                        thrown = result.exceptionOrNull()
                    }
                }
            },
        )
        thrown?.let { throw it }
        check(rawResult !== Pending) {
            "Suspend block did not complete synchronously in test."
        }
        return rawResult as T
    }
}
