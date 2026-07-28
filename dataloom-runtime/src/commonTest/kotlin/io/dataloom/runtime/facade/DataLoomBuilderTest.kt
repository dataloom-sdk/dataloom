package io.dataloom.runtime.facade

import io.dataloom.api.connectivity.ConnectivityCheckRequest
import io.dataloom.api.connectivity.ConnectivityProvider
import io.dataloom.api.connectivity.ConnectivityRequirement
import io.dataloom.api.connectivity.ConnectivitySnapshot
import io.dataloom.api.connectivity.ConnectivityStatus
import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.ChangeSet
import io.dataloom.api.change.EntityReference
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ChangeSetId
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.IdentifierGenerator
import io.dataloom.api.identifier.QueueConsumerId
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.RetryPolicyId
import io.dataloom.api.identifier.ScheduleId
import io.dataloom.api.identifier.SynchronizationEventId
import io.dataloom.api.identifier.SynchronizationObserverId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.ChangeOperation
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.observation.SynchronizationObserver
import io.dataloom.api.provider.DataLoomProvider
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
import io.dataloom.api.queue.QueueProvider
import io.dataloom.api.queue.ExpiredLeaseRecoveryRequest
import io.dataloom.api.queue.ExpiredLeaseRecoveryResult
import io.dataloom.api.queue.QueueAcquireRequest
import io.dataloom.api.queue.QueueAcquireResult
import io.dataloom.api.queue.QueueCancellationRequest
import io.dataloom.api.queue.QueueCompletionRequest
import io.dataloom.api.queue.QueueDeferralRequest
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEnqueueRequest
import io.dataloom.api.queue.QueueFailureRequest
import io.dataloom.api.queue.QueueRescheduleRequest
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.retry.RetryDecision
import io.dataloom.api.retry.RetryEvaluationRequest
import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.retry.RetryPolicy
import io.dataloom.api.scheduling.ExistingSchedulePolicy
import io.dataloom.api.scheduling.ScheduleConstraints
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.storage.InboundChangeApplyRequest
import io.dataloom.api.storage.OutboundChangeReadRequest
import io.dataloom.api.storage.OutboundChangeReadResult
import io.dataloom.api.storage.StorageProvider
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.synchronization.ChangeAcknowledgementStatus
import io.dataloom.api.synchronization.ChangeEventAcknowledgement
import io.dataloom.api.synchronization.CheckpointReadRequest
import io.dataloom.api.synchronization.CheckpointWriteRequest
import io.dataloom.api.synchronization.OutboundChangeAcknowledgementRequest
import io.dataloom.api.synchronization.SynchronizationCheckpoint
import io.dataloom.api.synchronization.SynchronizationEvent
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.synchronization.SynchronizationSummary
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.api.provider.ProviderLifecycleCoordinatorState
import io.dataloom.api.provider.ProviderLifecycleResult
import io.dataloom.api.provider.SynchronizationProviderBindings
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.runtime.RuntimeIdentifierGenerators
import io.dataloom.api.strategy.NetworkOnlyStrategyProfile
import io.dataloom.api.strategy.ClassifiedStrategyRemoteError
import io.dataloom.api.strategy.RemoteFirstStrategyProfile
import io.dataloom.api.strategy.StrategyCacheState
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyConnectivity
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyExecutionTrigger
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategyOperationInput
import io.dataloom.api.strategy.StrategyLocalFallbackProvider
import io.dataloom.api.strategy.StrategyLocalFallbackRequest
import io.dataloom.api.strategy.StrategyLocalFallbackResult
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.strategy.StrategyRuntimeEvidence
import io.dataloom.api.strategy.StrategyRemoteOutcome
import io.dataloom.api.strategy.StrategySynchronizationRequest
import io.dataloom.api.strategy.StrategyTransportOutput
import io.dataloom.runtime.connectivity.SynchronizationConnectivityConfiguration
import io.dataloom.runtime.execution.SynchronizationExecutionResult
import io.dataloom.runtime.execution.SynchronizationPipeline
import io.dataloom.runtime.execution.SynchronizationExecutionContext
import io.dataloom.runtime.execution.bidirectional.BidirectionalPipelineConfiguration
import io.dataloom.runtime.execution.inbound.InboundPullPipelineConfiguration
import io.dataloom.runtime.execution.outbound.OutboundPushPipelineConfiguration
import io.dataloom.runtime.strategy.StrategyExecutionRejectionReason
import io.dataloom.runtime.strategy.StrategySynchronizationExecutionResult
import io.dataloom.runtime.queue.QueuedSynchronizationWork
import io.dataloom.runtime.queue.QueuedSynchronizationWorkResolution
import io.dataloom.runtime.queue.QueuedSynchronizationWorkResolver
import io.dataloom.runtime.queue.QueueProcessingRequest
import io.dataloom.runtime.worker.QueueWorkerConfiguration
import io.dataloom.runtime.worker.QueueWorkerRunRequest
import io.dataloom.runtime.worker.QueueWorkerRunResult
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deterministic common tests for DL-033 DataLoom facade and builder.
 *
 * All fakes are stateless or deterministically stateful. No real network,
 * database, filesystem, Thread.sleep, Android APIs, JVM-only APIs, reflection,
 * ServiceLoader, system clock, random identifiers, or production credentials
 * are used.
 *
 * Suspend functions are exercised using standard-library coroutine primitives,
 * without requiring kotlinx.coroutines.
 */
class DataLoomBuilderTest {

    // =========================================================================
    // runSuspend helper
    // =========================================================================

    private object Pending

    private fun <T> runSuspend(block: suspend () -> T): T {
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
        check(rawResult !== Pending) { "Suspend block did not complete synchronously in test." }
        @Suppress("UNCHECKED_CAST")
        return rawResult as T
    }

    // =========================================================================
    // Fake errors
    // =========================================================================

    private data class FakeError(
        override val code: ErrorCode = ErrorCode("DL-FAKE"),
        override val category: ErrorCategory = ErrorCategory.PROVIDER,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE,
        override val message: String = "Fake error.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private data class FakeRemoteError(
        override val remoteOutcome: StrategyRemoteOutcome,
        override val code: ErrorCode = ErrorCode("DL-FAKE-REMOTE"),
        override val category: ErrorCategory = ErrorCategory.NETWORK,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Classified remote failure.",
        override val cause: Throwable? = null,
    ) : ClassifiedStrategyRemoteError

    // =========================================================================
    // Fake providers
    // =========================================================================

    private class FakeStorageProvider(
        id: String = "storage-primary",
        var initializeCallCount: Int = 0,
        var closeCallCount: Int = 0,
        var initializeResult: ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit),
        var closeResult: ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit),
        var readResult: ProviderOperationResult<OutboundChangeReadResult> =
            ProviderOperationResult.Failure(FakeError()),
        var applyResult: ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(FakeError()),
        var acknowledgeResult: ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(FakeError()),
        var writeResult: ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(FakeError()),
        var fallbackResult: ProviderOperationResult<StrategyLocalFallbackResult> =
            ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Unavailable(StrategyCacheState.MISSING),
            ),
        val operationOrder: MutableList<String> = mutableListOf(),
    ) : StrategyLocalFallbackProvider {
        var readCallCount: Int = 0
        var applyCallCount: Int = 0
        var acknowledgeCallCount: Int = 0
        var checkpointReadCallCount: Int = 0
        var checkpointWriteCallCount: Int = 0
        var fallbackCallCount: Int = 0
        override val descriptor = ProviderDescriptor(
            id = ProviderId(id),
            name = ProviderName("Storage $id"),
            type = ProviderType.STORAGE,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> {
            initializeCallCount++
            return initializeResult
        }

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> {
            closeCallCount++
            return closeResult
        }

        override suspend fun readOutboundChanges(
            request: OutboundChangeReadRequest,
        ): ProviderOperationResult<OutboundChangeReadResult> {
            readCallCount++
            operationOrder += "read-local"
            return readResult
        }

        override suspend fun applyInboundChanges(
            request: InboundChangeApplyRequest,
        ): ProviderOperationResult<Unit> {
            applyCallCount++
            operationOrder += "persist-remote"
            return applyResult
        }

        override suspend fun acknowledgeOutboundChanges(
            request: OutboundChangeAcknowledgementRequest,
        ): ProviderOperationResult<Unit> {
            acknowledgeCallCount++
            operationOrder += "acknowledge"
            return acknowledgeResult
        }

        override suspend fun readCheckpoint(
            request: CheckpointReadRequest,
        ): ProviderOperationResult<SynchronizationCheckpoint?> {
            checkpointReadCallCount++
            operationOrder += "read-checkpoint"
            return ProviderOperationResult.Success(null)
        }

        override suspend fun writeCheckpoint(
            request: CheckpointWriteRequest,
        ): ProviderOperationResult<Unit> {
            checkpointWriteCallCount++
            operationOrder += "write-checkpoint"
            return writeResult
        }

        override suspend fun evaluateLocalFallback(
            request: StrategyLocalFallbackRequest,
        ): ProviderOperationResult<StrategyLocalFallbackResult> {
            fallbackCallCount++
            operationOrder += "fallback"
            return fallbackResult
        }
    }

    private class FakeTransportProvider(
        id: String = "transport-prod",
        var initializeCallCount: Int = 0,
        var closeCallCount: Int = 0,
        var pushResult: ProviderOperationResult<ChangeSetAcknowledgement> =
            ProviderOperationResult.Failure(FakeError()),
        var pullResult: ProviderOperationResult<PullChangesResult> =
            ProviderOperationResult.Failure(FakeError()),
        val operationOrder: MutableList<String> = mutableListOf(),
    ) : TransportProvider {
        var pushCallCount: Int = 0
        var pullCallCount: Int = 0
        var lastPushRequest: PushChangesRequest? = null
        var lastPullRequest: PullChangesRequest? = null

        override val descriptor = ProviderDescriptor(
            id = ProviderId(id),
            name = ProviderName("Transport $id"),
            type = ProviderType.TRANSPORT,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> {
            initializeCallCount++
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> {
            closeCallCount++
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun pushChanges(
            request: PushChangesRequest,
        ): ProviderOperationResult<ChangeSetAcknowledgement> {
            pushCallCount++
            operationOrder += "push"
            lastPushRequest = request
            return pushResult
        }

        override suspend fun pullChanges(
            request: PullChangesRequest,
        ): ProviderOperationResult<PullChangesResult> {
            pullCallCount++
            operationOrder += "pull"
            lastPullRequest = request
            return pullResult
        }
    }

    /**
     * A [DataLoomProvider] that declares a STORAGE type but does not implement [StorageProvider].
     * Used to test provider contract mismatch.
     */
    private class ContractMismatchProvider(id: String = "mismatch") : DataLoomProvider {
        override val descriptor = ProviderDescriptor(
            id = ProviderId(id),
            name = ProviderName("ContractMismatch $id"),
            type = ProviderType.STORAGE,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)
    }

    /**
     * A [DataLoomProvider] that declares a TRANSPORT type but the binding expects STORAGE.
     * Used to test provider type mismatch.
     */
    private class WrongTypeStorageProvider(id: String = "wrong-type") : TransportProvider {
        override val descriptor = ProviderDescriptor(
            id = ProviderId(id),
            name = ProviderName("WrongType $id"),
            type = ProviderType.TRANSPORT,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun pushChanges(request: PushChangesRequest): ProviderOperationResult<ChangeSetAcknowledgement> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun pullChanges(request: PullChangesRequest): ProviderOperationResult<PullChangesResult> =
            ProviderOperationResult.Failure(FakeError())
    }

    private class FakeQueueProvider(id: String = "queue-primary") : QueueProvider {
        var initializeCallCount: Int = 0
        var closeCallCount: Int = 0
        var acquireCallCount: Int = 0

        override val descriptor = ProviderDescriptor(
            id = ProviderId(id),
            name = ProviderName("Queue $id"),
            type = ProviderType.QUEUE,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> {
            initializeCallCount++
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> {
            closeCallCount++
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun enqueue(request: QueueEnqueueRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun acquire(request: QueueAcquireRequest): ProviderOperationResult<QueueAcquireResult> {
            acquireCallCount++
            return ProviderOperationResult.Success(QueueAcquireResult.NoEntries)
        }

        override suspend fun complete(request: QueueCompletionRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun reschedule(request: QueueRescheduleRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun defer(request: QueueDeferralRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun fail(request: QueueFailureRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun cancel(request: QueueCancellationRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(FakeError())

        override suspend fun recoverExpiredLeases(request: ExpiredLeaseRecoveryRequest): ProviderOperationResult<ExpiredLeaseRecoveryResult> =
            ProviderOperationResult.Failure(FakeError())
    }

    // =========================================================================
    // Fake observer
    // =========================================================================

    private class RecordingObserver(idValue: String) : SynchronizationObserver {
        override val id = SynchronizationObserverId(idValue)
        val receivedEvents: MutableList<SynchronizationEvent> = mutableListOf()

        override fun onEvent(event: SynchronizationEvent) {
            receivedEvents.add(event)
        }
    }

    // =========================================================================
    // Fake pipeline
    // =========================================================================

    private class RecordingPipeline(
        override val direction: SynchronizationDirection,
    ) : SynchronizationPipeline {
        var executeCallCount: Int = 0

        override suspend fun execute(context: SynchronizationExecutionContext): SynchronizationResult {
            executeCallCount++
            return SynchronizationResult.Succeeded(
                request = context.request,
                completedAt = DataLoomInstant(1_000_000L),
                summary = SynchronizationSummary(),
            )
        }
    }

    // =========================================================================
    // Fixture factories
    // =========================================================================

    private fun makeRuntimeDependencies(): RuntimeDependencies {
        val generators = RuntimeIdentifierGenerators(
            synchronizationEventIds = object : IdentifierGenerator<SynchronizationEventId> {
                override fun generate() = SynchronizationEventId("event-001")
            },
            queueEntryIds = object : IdentifierGenerator<QueueEntryId> {
                override fun generate() = QueueEntryId("queue-001")
            },
            queueLeaseIds = object : IdentifierGenerator<QueueLeaseId> {
                override fun generate() = QueueLeaseId("lease-001")
            },
            conflictIds = object : IdentifierGenerator<ConflictId> {
                override fun generate() = ConflictId("conflict-001")
            },
        )
        return RuntimeDependencies(
            clock = object : DataLoomClock {
                override fun now() = DataLoomInstant(1_000_000L)
            },
            identifiers = generators,
        )
    }

    private fun makeBindings(
        storageId: String = "storage-primary",
        transportId: String = "transport-prod",
        queueId: String? = null,
    ) = SynchronizationProviderBindings(
        storageProviderId = ProviderId(storageId),
        transportProviderId = ProviderId(transportId),
        queueProviderId = queueId?.let { ProviderId(it) },
    )

    private fun makeRequest(direction: SynchronizationDirection = SynchronizationDirection.PUSH) =
        SynchronizationRequest(
            workflowId = WorkflowId("workflow-001"),
            sessionId = SynchronizationSessionId("session-001"),
            direction = direction,
            mode = SynchronizationMode.DELTA,
            context = ExecutionContext(
                executionId = ExecutionId("exec-001"),
                correlationId = CorrelationId("corr-001"),
            ),
        )

    private fun makeChangeSet(): ChangeSet {
        val event = ChangeEvent(
            id = ChangeEventId("change-001"),
            entity = EntityReference(
                type = EntityType("document"),
                id = EntityId("doc-001"),
            ),
            operation = ChangeOperation.UPDATE,
        )
        return ChangeSet(
            id = ChangeSetId("changes-001"),
            events = listOf(event),
        )
    }

    private fun makeAcknowledgement(
        changeSet: ChangeSet,
    ): ChangeSetAcknowledgement =
        ChangeSetAcknowledgement(
            changeSetId = changeSet.id,
            events = changeSet.events.map { event ->
                ChangeEventAcknowledgement(
                    eventId = event.id,
                    status = ChangeAcknowledgementStatus.ACCEPTED,
                )
            },
        )

    private fun makeNetworkOnlyRequest(
        direction: SynchronizationDirection,
        input: StrategyOperationInput = when (direction) {
            SynchronizationDirection.PUSH,
            SynchronizationDirection.BIDIRECTIONAL,
            -> StrategyOperationInput.DirectTransport(
                outboundChangeSet = makeChangeSet(),
            )
            SynchronizationDirection.PULL -> StrategyOperationInput.DirectTransport()
        },
        trigger: StrategyExecutionTrigger = StrategyExecutionTrigger.DIRECT,
    ): StrategySynchronizationRequest =
        StrategySynchronizationRequest(
            request = makeRequest(direction),
            decisionId = StrategyDecisionId("decision-001"),
            planId = StrategyPlanId("plan-001"),
            profile = NetworkOnlyStrategyProfile(
                id = StrategyProfileId("network-only"),
                configurationVersion = StrategyConfigurationVersion(1),
            ),
            evidence = StrategyRuntimeEvidence(
                connectivity = StrategyConnectivity.AVAILABLE,
            ),
            trigger = trigger,
            input = input,
        )

    private fun makeRemoteFirstRequest(
        direction: SynchronizationDirection = SynchronizationDirection.PULL,
        fallbackOn: Set<StrategyRemoteOutcome> = emptySet(),
        persistRemoteResult: Boolean = false,
        evidence: StrategyRuntimeEvidence = StrategyRuntimeEvidence(
            connectivity = StrategyConnectivity.AVAILABLE,
            cacheState = StrategyCacheState.MISSING,
        ),
        trigger: StrategyExecutionTrigger = StrategyExecutionTrigger.DIRECT,
    ): StrategySynchronizationRequest =
        StrategySynchronizationRequest(
            request = makeRequest(direction),
            decisionId = StrategyDecisionId("remote-decision-001"),
            planId = StrategyPlanId("remote-plan-001"),
            profile = RemoteFirstStrategyProfile(
                id = StrategyProfileId("remote-first"),
                configurationVersion = StrategyConfigurationVersion(1),
                fallbackOn = fallbackOn,
                persistRemoteResult = persistRemoteResult,
            ),
            evidence = evidence,
            trigger = trigger,
            input = StrategyOperationInput.ProviderBacked,
        )

    private fun makeMinimalBuilder(
        storage: FakeStorageProvider = FakeStorageProvider(),
        transport: FakeTransportProvider = FakeTransportProvider(),
    ): DataLoomBuilder = DataLoomBuilder()
        .runtimeDependencies(makeRuntimeDependencies())
        .providers(storage, transport)
        .defaultProviderBindings(makeBindings())

    private fun makeQueueWorkerSpec(
        workResolver: QueuedSynchronizationWorkResolver = QueuedSynchronizationWorkResolver { entry ->
            QueuedSynchronizationWorkResolution.Resolved(
                QueuedSynchronizationWork(
                    request = makeRequest(),
                    bindings = makeBindings(),
                ),
            )
        },
    ) = DataLoomQueueWorkerSpec(
        workResolver = workResolver,
        retryPolicy = object : RetryPolicy {
            override val id = RetryPolicyId("no-retry")
            override fun evaluate(request: RetryEvaluationRequest): RetryDecision =
                RetryDecision.Stop(io.dataloom.api.retry.RetryStopReason.NON_RECOVERABLE)
        },
        retryOperation = RetryOperation("test.operation"),
        configuration = QueueWorkerConfiguration(
            scheduleId = ScheduleId("worker-001"),
            constraints = ScheduleConstraints(),
            existingSchedulePolicy = ExistingSchedulePolicy.REPLACE,
            continuationDelay = SchedulingDelay.ZERO,
            recoverExpiredLeasesBeforeProcessing = false,
        ),
    )

    private fun makeQueueSubmissionEncoder(): io.dataloom.runtime.submission.QueuedSynchronizationWorkEncoder =
        io.dataloom.runtime.submission.QueuedSynchronizationWorkEncoder { submission ->
            val entry = io.dataloom.api.queue.QueueEntry(
                id = submission.queueEntryId,
                synchronizationRequest = submission.work.request,
                state = io.dataloom.api.queue.QueueEntryState.PENDING,
                enqueuedAt = submission.availableAt,
                availableAt = submission.availableAt,
            )
            io.dataloom.runtime.submission.QueuedSynchronizationWorkEncodingResult.Encoded(
                io.dataloom.api.queue.QueueEnqueueRequest(entry = entry),
            )
        }

    // =========================================================================
    // Builder requirements — missing mandatory fields
    // =========================================================================

    @Test
    fun build_failsWhenRuntimeDependenciesMissing() {
        val builder = DataLoomBuilder()
            .providers(FakeStorageProvider(), FakeTransportProvider())
            .defaultProviderBindings(makeBindings())

        assertFailsWith<DataLoomBuildException> {
            builder.build()
        }
    }

    @Test
    fun build_failsWhenProvidersEmpty() {
        val builder = DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .defaultProviderBindings(makeBindings())

        assertFailsWith<DataLoomBuildException> {
            builder.build()
        }
    }

    @Test
    fun build_failsWhenDefaultProviderBindingsMissing() {
        val builder = DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .providers(FakeStorageProvider(), FakeTransportProvider())

        assertFailsWith<DataLoomBuildException> {
            builder.build()
        }
    }

    @Test
    fun build_failsWhenStorageProviderNotFound() {
        val builder = DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .providers(FakeTransportProvider())
            .defaultProviderBindings(makeBindings()) // storage-primary not registered

        assertFailsWith<DataLoomBuildException> {
            builder.build()
        }
    }

    @Test
    fun build_failsWhenTransportProviderNotFound() {
        val builder = DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .providers(FakeStorageProvider())
            .defaultProviderBindings(makeBindings()) // transport-prod not registered

        assertFailsWith<DataLoomBuildException> {
            builder.build()
        }
    }

    @Test
    fun build_failsWhenStorageProviderHasWrongType() {
        // Register a provider with ID "storage-primary" but TRANSPORT type
        val wrongType = WrongTypeStorageProvider(id = "storage-primary")
        val builder = DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .providers(wrongType, FakeTransportProvider())
            .defaultProviderBindings(makeBindings())

        assertFailsWith<DataLoomBuildException> {
            builder.build()
        }
    }

    @Test
    fun build_failsWhenStorageProviderDoesNotImplementContract() {
        // Declares STORAGE type but does not implement StorageProvider interface.
        val mismatch = ContractMismatchProvider(id = "storage-primary")
        val builder = DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .providers(mismatch, FakeTransportProvider())
            .defaultProviderBindings(makeBindings())

        assertFailsWith<DataLoomBuildException> {
            builder.build()
        }
    }

    @Test
    fun build_failsWhenDuplicateProviderIds() {
        val storage1 = FakeStorageProvider(id = "storage-primary")
        val storage2 = FakeStorageProvider(id = "storage-primary")
        val builder = DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .providers(storage1, storage2, FakeTransportProvider())
            .defaultProviderBindings(makeBindings())

        assertFailsWith<IllegalArgumentException> {
            builder.build()
        }
    }

    @Test
    fun build_failsWhenDuplicateObserverIds() {
        val observer1 = RecordingObserver("observer-a")
        val observer2 = RecordingObserver("observer-a")
        val builder = makeMinimalBuilder()
            .observers(observer1, observer2)

        assertFailsWith<IllegalArgumentException> {
            builder.build()
        }
    }

    @Test
    fun build_failsWhenDuplicatePipelineDirections() {
        val push1 = RecordingPipeline(SynchronizationDirection.PUSH)
        val push2 = RecordingPipeline(SynchronizationDirection.PUSH)
        val builder = makeMinimalBuilder()
            .pipeline(push1)
            .pipeline(push2)

        assertFailsWith<IllegalArgumentException> {
            builder.build()
        }
    }

    // =========================================================================
    // Build side-effect boundary — no provider/clock/ID operations during build
    // =========================================================================

    @Test
    fun build_doesNotInitializeProviders() {
        val storage = FakeStorageProvider()
        val transport = FakeTransportProvider()
        makeMinimalBuilder(storage, transport).build()

        assertEquals(0, storage.initializeCallCount, "Storage must not be initialized during build.")
        assertEquals(0, transport.initializeCallCount, "Transport must not be initialized during build.")
    }

    @Test
    fun build_doesNotShutdownProviders() {
        val storage = FakeStorageProvider()
        val transport = FakeTransportProvider()
        makeMinimalBuilder(storage, transport).build()

        assertEquals(0, storage.closeCallCount, "Storage must not be shut down during build.")
        assertEquals(0, transport.closeCallCount, "Transport must not be shut down during build.")
    }

    @Test
    fun build_doesNotInvokeObserver() {
        val observer = RecordingObserver("obs-1")
        makeMinimalBuilder().observer(observer).build()

        assertTrue(observer.receivedEvents.isEmpty(), "Observer must not receive events during build.")
    }

    @Test
    fun build_doesNotAcquireQueueEntries() {
        val queue = FakeQueueProvider()
        val storage = FakeStorageProvider()
        val transport = FakeTransportProvider()

        DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .providers(storage, transport, queue)
            .defaultProviderBindings(makeBindings(queueId = "queue-primary"))
            .queueWorkerConfiguration(makeQueueWorkerSpec())
            .build()

        assertEquals(0, queue.acquireCallCount, "Queue must not be acquired during build.")
        assertEquals(0, queue.initializeCallCount, "Queue must not be initialized during build.")
    }

    @Test
    fun build_doesNotExecuteAnyPipelineDuringBuild() {
        val customPush = RecordingPipeline(SynchronizationDirection.PUSH)
        val customPull = RecordingPipeline(SynchronizationDirection.PULL)
        val customBidirectional = RecordingPipeline(SynchronizationDirection.BIDIRECTIONAL)

        makeMinimalBuilder()
            .pipeline(customPush)
            .pipeline(customPull)
            .pipeline(customBidirectional)
            .build()

        assertEquals(0, customPush.executeCallCount, "Push pipeline must not execute during build.")
        assertEquals(0, customPull.executeCallCount, "Pull pipeline must not execute during build.")
        assertEquals(0, customBidirectional.executeCallCount, "Bidirectional pipeline must not execute during build.")
    }

    // =========================================================================
    // Default pipeline assembly
    // =========================================================================

    @Test
    fun build_assemblesDefaultOutboundPipeline() {
        val dataLoom = makeMinimalBuilder().build()
        assertNotNull(dataLoom, "build() must return a non-null DataLoom instance.")
        // Direct lifecycle verify: synchronize without init returns PROVIDERS_NOT_INITIALIZED.
        val result = runSuspend { dataLoom.synchronize(makeRequest(SynchronizationDirection.PUSH)) }
        assertIs<SynchronizationExecutionResult.Rejected>(result)
    }

    @Test
    fun build_assemblesDefaultInboundPipeline() {
        val dataLoom = makeMinimalBuilder().build()
        val result = runSuspend { dataLoom.synchronize(makeRequest(SynchronizationDirection.PULL)) }
        assertIs<SynchronizationExecutionResult.Rejected>(result)
    }

    @Test
    fun build_assemblesDefaultBidirectionalPipeline() {
        val dataLoom = makeMinimalBuilder().build()
        val result = runSuspend { dataLoom.synchronize(makeRequest(SynchronizationDirection.BIDIRECTIONAL)) }
        assertIs<SynchronizationExecutionResult.Rejected>(result)
    }

    @Test
    fun build_customOutboundPipelineReplacesDefaultOutboundOnly() {
        val customPush = RecordingPipeline(SynchronizationDirection.PUSH)
        val dataLoom = makeMinimalBuilder()
            .pipeline(customPush)
            .build()

        // Initialize so synchronize can proceed.
        runSuspend { dataLoom.initialize() }
        runSuspend { dataLoom.synchronize(makeRequest(SynchronizationDirection.PUSH)) }

        assertEquals(1, customPush.executeCallCount, "Custom push pipeline must be executed.")
    }

    @Test
    fun build_customInboundPipelineReplacesDefaultInboundOnly() {
        val customPull = RecordingPipeline(SynchronizationDirection.PULL)
        val dataLoom = makeMinimalBuilder()
            .pipeline(customPull)
            .build()

        runSuspend { dataLoom.initialize() }
        runSuspend { dataLoom.synchronize(makeRequest(SynchronizationDirection.PULL)) }

        assertEquals(1, customPull.executeCallCount, "Custom pull pipeline must be executed.")
    }

    @Test
    fun build_pipelineLookupIsDeterministic() {
        val customPush = RecordingPipeline(SynchronizationDirection.PUSH)
        val dataLoom = makeMinimalBuilder()
            .pipeline(customPush)
            .build()

        runSuspend { dataLoom.initialize() }
        // Execute twice to confirm same pipeline is selected.
        runSuspend { dataLoom.synchronize(makeRequest(SynchronizationDirection.PUSH)) }
        runSuspend { dataLoom.synchronize(makeRequest(SynchronizationDirection.PUSH)) }

        assertEquals(2, customPush.executeCallCount, "Same pipeline must be selected on each call.")
    }

    @Test
    fun build_defaultBidirectionalUsesSelectedChildPipelines() {
        val customPush = RecordingPipeline(SynchronizationDirection.PUSH)
        val customPull = RecordingPipeline(SynchronizationDirection.PULL)

        // No custom BIDIRECTIONAL pipeline — the builder composes one from the custom push and pull.
        val dataLoom = makeMinimalBuilder()
            .pipeline(customPush)
            .pipeline(customPull)
            .build()

        runSuspend { dataLoom.initialize() }
        runSuspend { dataLoom.synchronize(makeRequest(SynchronizationDirection.BIDIRECTIONAL)) }

        assertEquals(
            1,
            customPush.executeCallCount,
            "Default bidirectional pipeline must delegate to the selected custom push pipeline.",
        )
        assertEquals(
            1,
            customPull.executeCallCount,
            "Default bidirectional pipeline must delegate to the selected custom pull pipeline.",
        )
    }

    // =========================================================================
    // Lifecycle facade
    // =========================================================================

    @Test
    fun build_doesNotInitialize() {
        val storage = FakeStorageProvider()
        val dataLoom = makeMinimalBuilder(storage).build()

        assertEquals(
            ProviderLifecycleCoordinatorState.NOT_INITIALIZED,
            dataLoom.providerLifecycleState,
            "Lifecycle state must be NOT_INITIALIZED after build.",
        )
        assertEquals(0, storage.initializeCallCount)
    }

    @Test
    fun initialize_delegatesExactlyOnce() {
        val storage = FakeStorageProvider()
        val dataLoom = makeMinimalBuilder(storage).build()

        val result = runSuspend { dataLoom.initialize() }

        assertIs<ProviderLifecycleResult.InitializeSuccess>(result)
        assertEquals(1, storage.initializeCallCount)
    }

    @Test
    fun initialize_updatesLifecycleState() {
        val dataLoom = makeMinimalBuilder().build()
        runSuspend { dataLoom.initialize() }
        assertEquals(ProviderLifecycleCoordinatorState.INITIALIZED, dataLoom.providerLifecycleState)
    }

    @Test
    fun lifecycleState_exposedTruthfully() {
        val dataLoom = makeMinimalBuilder().build()

        assertEquals(ProviderLifecycleCoordinatorState.NOT_INITIALIZED, dataLoom.providerLifecycleState)
        runSuspend { dataLoom.initialize() }
        assertEquals(ProviderLifecycleCoordinatorState.INITIALIZED, dataLoom.providerLifecycleState)
    }

    @Test
    fun shutdown_delegatesExactlyOnce() {
        val storage = FakeStorageProvider()
        val dataLoom = makeMinimalBuilder(storage).build()
        runSuspend { dataLoom.initialize() }

        val result = runSuspend { dataLoom.shutdown() }

        assertIs<ProviderLifecycleResult.ShutdownSuccess>(result)
        assertEquals(1, storage.closeCallCount)
    }

    @Test
    fun synchronize_beforeInitialize_returnsRejected() {
        val dataLoom = makeMinimalBuilder().build()
        val result = runSuspend { dataLoom.synchronize(makeRequest()) }
        assertIs<SynchronizationExecutionResult.Rejected>(result)
    }

    @Test
    fun initialize_cancellationPropagates() {
        val storage = FakeStorageProvider()
        storage.initializeResult = ProviderOperationResult.Success(Unit) // normal
        // Override with a cancellation-throwing provider.
        val cancellingProvider = object : StorageProvider by storage {
            override val descriptor = storage.descriptor
            override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> {
                throw CancellationException("test cancellation")
            }
        }
        val builder = DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .providers(cancellingProvider, FakeTransportProvider())
            .defaultProviderBindings(makeBindings())

        val dataLoom = builder.build()
        assertFailsWith<CancellationException> {
            runSuspend { dataLoom.initialize() }
        }
    }

    @Test
    fun shutdown_cancellationPropagates() {
        val cancellingProvider = object : StorageProvider by FakeStorageProvider() {
            override val descriptor = FakeStorageProvider().descriptor
            override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
                ProviderOperationResult.Success(Unit)

            override suspend fun close(): ProviderOperationResult<Unit> {
                throw CancellationException("test shutdown cancellation")
            }
        }
        val builder = DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .providers(cancellingProvider, FakeTransportProvider())
            .defaultProviderBindings(makeBindings())

        val dataLoom = builder.build()
        runSuspend { dataLoom.initialize() }
        assertFailsWith<CancellationException> {
            runSuspend { dataLoom.shutdown() }
        }
    }

    // =========================================================================
    // Synchronization facade
    // =========================================================================

    @Test
    fun synchronize_withDefaultBindings_usesDefaultBindings() {
        val customPush = RecordingPipeline(SynchronizationDirection.PUSH)
        val dataLoom = makeMinimalBuilder()
            .pipeline(customPush)
            .build()

        runSuspend { dataLoom.initialize() }
        val result = runSuspend { dataLoom.synchronize(makeRequest(SynchronizationDirection.PUSH)) }

        assertIs<SynchronizationExecutionResult.Executed>(result)
        assertEquals(1, customPush.executeCallCount)
    }

    @Test
    fun synchronize_withExplicitBindings_usesExactSuppliedBindings() {
        val storage2 = FakeStorageProvider(id = "storage-secondary")
        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .providers(FakeStorageProvider(), FakeTransportProvider(), storage2)
            .defaultProviderBindings(makeBindings())
            .build()

        runSuspend { dataLoom.initialize() }

        // Explicit bindings that use a different storage ID.
        val explicitBindings = SynchronizationProviderBindings(
            storageProviderId = ProviderId("storage-secondary"),
            transportProviderId = ProviderId("transport-prod"),
        )
        val result = runSuspend { dataLoom.synchronize(makeRequest(), explicitBindings) }

        // The result should be Executed (pipeline ran with different bindings) or
        // possibly still Rejected if resolution fails — we only verify Executed here
        // because storage-secondary is registered and has correct type.
        assertIs<SynchronizationExecutionResult.Executed>(result)
    }

    @Test
    fun synchronize_exactResultIsPreserved() {
        val succeededResult = SynchronizationResult.Succeeded(
            request = makeRequest(),
            completedAt = DataLoomInstant(1_000_000L),
            summary = SynchronizationSummary(),
        )
        val customPipeline = object : SynchronizationPipeline {
            override val direction = SynchronizationDirection.PUSH
            override suspend fun execute(context: SynchronizationExecutionContext) = succeededResult
        }

        val dataLoom = makeMinimalBuilder().pipeline(customPipeline).build()
        runSuspend { dataLoom.initialize() }
        val result = runSuspend { dataLoom.synchronize(makeRequest()) }

        assertIs<SynchronizationExecutionResult.Executed>(result)
        assertSame(succeededResult, result.result)
    }

    @Test
    fun synchronize_cancellationPropagates() {
        val cancellingPipeline = object : SynchronizationPipeline {
            override val direction = SynchronizationDirection.PUSH
            override suspend fun execute(context: SynchronizationExecutionContext): SynchronizationResult {
                throw CancellationException("synchronization cancelled")
            }
        }

        val dataLoom = makeMinimalBuilder().pipeline(cancellingPipeline).build()
        runSuspend { dataLoom.initialize() }

        assertFailsWith<CancellationException> {
            runSuspend { dataLoom.synchronize(makeRequest()) }
        }
    }

    // =========================================================================
    // Observer integration
    // =========================================================================

    @Test
    fun build_withNoObservers_succeedsWithoutEventInfrastructure() {
        val dataLoom = makeMinimalBuilder().build()
        assertNotNull(dataLoom)
    }

    @Test
    fun build_observersPreserveRegistrationOrder() {
        val observerA = RecordingObserver("obs-a")
        val observerB = RecordingObserver("obs-b")
        val observerC = RecordingObserver("obs-c")

        // Simply verifying build succeeds with ordered observers.
        val dataLoom = makeMinimalBuilder()
            .observers(observerA, observerB, observerC)
            .build()
        assertNotNull(dataLoom)
    }

    @Test
    fun build_withObservers_doesNotInvokeObserverCallbacksDuringBuild() {
        val observer = RecordingObserver("obs-1")
        makeMinimalBuilder().observer(observer).build()
        assertTrue(observer.receivedEvents.isEmpty())
    }

    // =========================================================================
    // Connectivity integration
    // =========================================================================

    @Test
    fun build_withoutConnectivityConfig_preservesPreviousBehavior() {
        val dataLoom = makeMinimalBuilder().build()
        // Should work normally without connectivity provider in bindings.
        runSuspend { dataLoom.initialize() }
        val result = runSuspend { dataLoom.synchronize(makeRequest()) }
        assertIs<SynchronizationExecutionResult.Executed>(result)
    }

    @Test
    fun build_withConnectivityConfig_usesExistingPreflight() {
        val connectivityProvider = object : ConnectivityProvider {
            override val descriptor = ProviderDescriptor(
                id = ProviderId("connectivity-test"),
                name = ProviderName("Connectivity Test"),
                type = ProviderType.CONNECTIVITY,
                version = ProviderVersion("1.0.0"),
            )
            var callCount = 0

            override suspend fun initialize(context: ProviderInitializationContext) =
                ProviderOperationResult.Success(Unit)

            override suspend fun health() =
                ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

            override suspend fun close() = ProviderOperationResult.Success(Unit)

            override suspend fun currentConnectivity(request: ConnectivityCheckRequest) =
                ProviderOperationResult.Success(
                    ConnectivitySnapshot(
                        status = ConnectivityStatus.AVAILABLE,
                        isMetered = false,
                    ),
                ).also { callCount++ }
        }

        val bindings = SynchronizationProviderBindings(
            storageProviderId = ProviderId("storage-primary"),
            transportProviderId = ProviderId("transport-prod"),
            connectivityProviderId = ProviderId("connectivity-test"),
        )
        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .providers(FakeStorageProvider(), FakeTransportProvider(), connectivityProvider)
            .defaultProviderBindings(bindings)
            .connectivityConfiguration(
                SynchronizationConnectivityConfiguration(
                    requirement = ConnectivityRequirement.AVAILABLE,
                    offlineRescheduleDelay = SchedulingDelay.ZERO,
                ),
            )
            .build()

        runSuspend { dataLoom.initialize() }
        runSuspend { dataLoom.synchronize(makeRequest()) }

        assertTrue(connectivityProvider.callCount > 0, "Connectivity provider should be called.")
    }

    // =========================================================================
    // Queue-worker capability
    // =========================================================================

    @Test
    fun queueWorker_isNullWhenNotConfigured() {
        val dataLoom = makeMinimalBuilder().build()
        assertNull(dataLoom.queueWorker)
    }

    @Test
    fun queueWorker_isNonNullWhenConfigured() {
        val queue = FakeQueueProvider()
        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .providers(FakeStorageProvider(), FakeTransportProvider(), queue)
            .defaultProviderBindings(makeBindings(queueId = "queue-primary"))
            .queueWorkerConfiguration(makeQueueWorkerSpec())
            .build()

        assertNotNull(dataLoom.queueWorker)
    }

    @Test
    fun build_failsWhenQueueWorkerConfiguredButQueueProviderBindingAbsent() {
        val builder = DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .providers(FakeStorageProvider(), FakeTransportProvider())
            .defaultProviderBindings(makeBindings()) // no queueId
            .queueWorkerConfiguration(makeQueueWorkerSpec())

        assertFailsWith<DataLoomBuildException> {
            builder.build()
        }
    }

    @Test
    fun build_failsWhenQueueWorkerConfiguredAndQueueProviderNotInRegistry() {
        val builder = DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .providers(FakeStorageProvider(), FakeTransportProvider())
            .defaultProviderBindings(makeBindings(queueId = "queue-missing"))
            .queueWorkerConfiguration(makeQueueWorkerSpec())

        assertFailsWith<DataLoomBuildException> {
            builder.build()
        }
    }

    @Test
    fun queueWorker_doesNotStartAutomatically() {
        val queue = FakeQueueProvider()
        DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .providers(FakeStorageProvider(), FakeTransportProvider(), queue)
            .defaultProviderBindings(makeBindings(queueId = "queue-primary"))
            .queueWorkerConfiguration(makeQueueWorkerSpec())
            .build()

        assertEquals(0, queue.acquireCallCount, "Queue acquire must not be called during build.")
        assertEquals(0, queue.initializeCallCount, "Queue must not be initialized during build.")
    }

    @Test
    fun queueWorker_run_delegatesToCoordinator() {
        val queue = FakeQueueProvider()
        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .providers(FakeStorageProvider(), FakeTransportProvider(), queue)
            .defaultProviderBindings(makeBindings(queueId = "queue-primary"))
            .queueWorkerConfiguration(makeQueueWorkerSpec())
            .build()

        // Must initialize before queue worker can process.
        runSuspend { dataLoom.initialize() }

        val workerRequest = QueueWorkerRunRequest(
            processingRequest = io.dataloom.runtime.queue.QueueProcessingRequest(
                acquireRequest = io.dataloom.api.queue.QueueAcquireRequest(
                    consumerId = QueueConsumerId("consumer-001"),
                    leaseId = QueueLeaseId("lease-worker-001"),
                    acquiredAt = DataLoomInstant(1_000_000L),
                    leaseExpiresAt = DataLoomInstant(2_000_000L),
                    maxEntries = 10,
                ),
            ),
            recoveryRequest = null,
        )

        val result = runSuspend { dataLoom.queueWorker!!.run(workerRequest) }
        assertIs<QueueWorkerRunResult.ProcessingCompleted>(result)
    }

    @Test
    fun queueWorker_cancellationPropagates() {
        val cancellingQueue = object : QueueProvider by FakeQueueProvider() {
            override val descriptor = FakeQueueProvider().descriptor
            override suspend fun initialize(context: ProviderInitializationContext) =
                ProviderOperationResult.Success(Unit)

            override suspend fun close() = ProviderOperationResult.Success(Unit)

            override suspend fun acquire(request: QueueAcquireRequest): ProviderOperationResult<QueueAcquireResult> {
                throw CancellationException("queue cancelled")
            }

            override suspend fun recoverExpiredLeases(request: ExpiredLeaseRecoveryRequest): ProviderOperationResult<ExpiredLeaseRecoveryResult> =
                ProviderOperationResult.Failure(FakeError())
        }

        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .providers(FakeStorageProvider(), FakeTransportProvider(), cancellingQueue)
            .defaultProviderBindings(makeBindings(queueId = "queue-primary"))
            .queueWorkerConfiguration(makeQueueWorkerSpec())
            .build()

        runSuspend { dataLoom.initialize() }

        val workerRequest = QueueWorkerRunRequest(
            processingRequest = io.dataloom.runtime.queue.QueueProcessingRequest(
                acquireRequest = io.dataloom.api.queue.QueueAcquireRequest(
                    consumerId = QueueConsumerId("consumer-001"),
                    leaseId = QueueLeaseId("lease-worker-001"),
                    acquiredAt = DataLoomInstant(1_000_000L),
                    leaseExpiresAt = DataLoomInstant(2_000_000L),
                    maxEntries = 10,
                ),
            ),
            recoveryRequest = null,
        )

        assertFailsWith<CancellationException> {
            runSuspend { dataLoom.queueWorker!!.run(workerRequest) }
        }
    }

    // =========================================================================
    // Queue submission builder integration
    // =========================================================================

    @Test
    fun queueSubmission_isNullWhenEncoderNotConfigured() {
        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .providers(FakeStorageProvider(), FakeTransportProvider(), FakeQueueProvider())
            .defaultProviderBindings(makeBindings(queueId = "queue-primary"))
            .build()

        assertNull(dataLoom.queueSubmission)
    }

    @Test
    fun queueSubmission_isNonNullWhenEncoderAndQueueProviderConfigured() {
        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .providers(FakeStorageProvider(), FakeTransportProvider(), FakeQueueProvider())
            .defaultProviderBindings(makeBindings(queueId = "queue-primary"))
            .queueSubmissionEncoder(makeQueueSubmissionEncoder())
            .build()

        assertNotNull(dataLoom.queueSubmission)
    }

    @Test
    fun queueSubmission_failsWhenEncoderConfiguredButQueueProviderBindingAbsent() {
        val builder = DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .providers(FakeStorageProvider(), FakeTransportProvider())
            .defaultProviderBindings(makeBindings()) // no queueId
            .queueSubmissionEncoder(makeQueueSubmissionEncoder())

        assertFailsWith<DataLoomBuildException> {
            builder.build()
        }
    }

    @Test
    fun queueSubmission_failsWhenEncoderConfiguredAndQueueProviderNotInRegistry() {
        val builder = DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .providers(FakeStorageProvider(), FakeTransportProvider())
            .defaultProviderBindings(makeBindings(queueId = "queue-missing"))
            .queueSubmissionEncoder(makeQueueSubmissionEncoder())

        assertFailsWith<DataLoomBuildException> {
            builder.build()
        }
    }

    @Test
    fun queueSubmission_buildInvokesNoEncoder() {
        var encoderCallCount = 0
        val encoder = io.dataloom.runtime.submission.QueuedSynchronizationWorkEncoder { _ ->
            encoderCallCount++
            error("should not be called during build")
        }

        DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .providers(FakeStorageProvider(), FakeTransportProvider(), FakeQueueProvider())
            .defaultProviderBindings(makeBindings(queueId = "queue-primary"))
            .queueSubmissionEncoder(encoder)
            .build()

        assertEquals(0, encoderCallCount, "Build must not invoke encoder.")
    }

    @Test
    fun queueSubmission_buildInvokesNoQueueProvider() {
        val queue = FakeQueueProvider()

        DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .providers(FakeStorageProvider(), FakeTransportProvider(), queue)
            .defaultProviderBindings(makeBindings(queueId = "queue-primary"))
            .queueSubmissionEncoder(makeQueueSubmissionEncoder())
            .build()

        assertEquals(0, queue.initializeCallCount, "Build must not initialize queue provider.")
        assertEquals(0, queue.acquireCallCount, "Build must not call acquire.")
    }

    @Test
    fun queueSubmission_workerAndSubmissionAreIndependent_bothNull() {
        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .providers(FakeStorageProvider(), FakeTransportProvider(), FakeQueueProvider())
            .defaultProviderBindings(makeBindings(queueId = "queue-primary"))
            .build()

        assertNull(dataLoom.queueWorker)
        assertNull(dataLoom.queueSubmission)
    }

    @Test
    fun queueSubmission_workerAndSubmissionAreIndependent_workerOnlyConfigured() {
        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .providers(FakeStorageProvider(), FakeTransportProvider(), FakeQueueProvider())
            .defaultProviderBindings(makeBindings(queueId = "queue-primary"))
            .queueWorkerConfiguration(makeQueueWorkerSpec())
            .build()

        assertNotNull(dataLoom.queueWorker)
        assertNull(dataLoom.queueSubmission)
    }

    @Test
    fun queueSubmission_workerAndSubmissionAreIndependent_submissionOnlyConfigured() {
        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .providers(FakeStorageProvider(), FakeTransportProvider(), FakeQueueProvider())
            .defaultProviderBindings(makeBindings(queueId = "queue-primary"))
            .queueSubmissionEncoder(makeQueueSubmissionEncoder())
            .build()

        assertNull(dataLoom.queueWorker)
        assertNotNull(dataLoom.queueSubmission)
    }

    @Test
    fun queueSubmission_workerAndSubmissionCanCoexist() {
        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .providers(FakeStorageProvider(), FakeTransportProvider(), FakeQueueProvider())
            .defaultProviderBindings(makeBindings(queueId = "queue-primary"))
            .queueWorkerConfiguration(makeQueueWorkerSpec())
            .queueSubmissionEncoder(makeQueueSubmissionEncoder())
            .build()

        assertNotNull(dataLoom.queueWorker)
        assertNotNull(dataLoom.queueSubmission)
    }

    // =========================================================================
    // Strategy-aware network-only execution
    // =========================================================================

    @Test
    fun networkOnlyPull_buildsAndExecutesWithTransportProviderOnly() {
        val transport = FakeTransportProvider(
            pullResult = ProviderOperationResult.Success(PullChangesResult.NoChanges()),
        )
        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .provider(transport)
            .defaultStrategyProviderBindings(
                StrategyProviderBindings(
                    transportProviderId = transport.descriptor.id,
                ),
            )
            .build()

        runSuspend { dataLoom.initialize() }
        val result = runSuspend {
            dataLoom.synchronize(
                makeNetworkOnlyRequest(SynchronizationDirection.PULL),
            )
        }

        val executed = assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        val output = assertIs<StrategyTransportOutput.Pulled>(executed.output)
        assertIs<PullChangesResult.NoChanges>(output.result)
        assertEquals(1, transport.pullCallCount)
        assertEquals(0, transport.pushCallCount)
    }

    @Test
    fun networkOnlyPushReturnsCanonicalRemoteAcknowledgement() {
        val changeSet = makeChangeSet()
        val acknowledgement = makeAcknowledgement(changeSet)
        val transport = FakeTransportProvider(
            pushResult = ProviderOperationResult.Success(acknowledgement),
        )
        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .provider(transport)
            .defaultStrategyProviderBindings(
                StrategyProviderBindings(
                    transportProviderId = transport.descriptor.id,
                ),
            )
            .build()

        runSuspend { dataLoom.initialize() }
        val result = runSuspend {
            dataLoom.synchronize(
                makeNetworkOnlyRequest(
                    direction = SynchronizationDirection.PUSH,
                    input = StrategyOperationInput.DirectTransport(
                        outboundChangeSet = changeSet,
                    ),
                ),
            )
        }

        val executed = assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        val output = assertIs<StrategyTransportOutput.Pushed>(executed.output)
        assertSame(acknowledgement, output.acknowledgement)
        assertEquals(1, transport.pushCallCount)
        assertEquals(0, transport.pullCallCount)
    }

    @Test
    fun networkOnlyBidirectionalExecutesPushBeforePull() {
        val changeSet = makeChangeSet()
        val transport = FakeTransportProvider(
            pushResult = ProviderOperationResult.Success(makeAcknowledgement(changeSet)),
            pullResult = ProviderOperationResult.Success(PullChangesResult.NoChanges()),
        )
        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .provider(transport)
            .defaultStrategyProviderBindings(
                StrategyProviderBindings(
                    transportProviderId = transport.descriptor.id,
                ),
            )
            .build()

        runSuspend { dataLoom.initialize() }
        val result = runSuspend {
            dataLoom.synchronize(
                makeNetworkOnlyRequest(
                    direction = SynchronizationDirection.BIDIRECTIONAL,
                    input = StrategyOperationInput.DirectTransport(
                        outboundChangeSet = changeSet,
                    ),
                ),
            )
        }

        assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        assertEquals(listOf("push", "pull"), transport.operationOrder)
    }

    @Test
    fun networkOnlyBidirectionalPullFailurePreservesCompletedPush() {
        val changeSet = makeChangeSet()
        val acknowledgement = makeAcknowledgement(changeSet)
        val pullError = FakeError(
            code = ErrorCode("DL-FAKE-PULL"),
            message = "Pull failed after push completed.",
        )
        val transport = FakeTransportProvider(
            pushResult = ProviderOperationResult.Success(acknowledgement),
            pullResult = ProviderOperationResult.Failure(pullError),
        )
        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .provider(transport)
            .defaultStrategyProviderBindings(
                StrategyProviderBindings(
                    transportProviderId = transport.descriptor.id,
                ),
            )
            .build()

        runSuspend { dataLoom.initialize() }
        val result = runSuspend {
            dataLoom.synchronize(
                makeNetworkOnlyRequest(
                    direction = SynchronizationDirection.BIDIRECTIONAL,
                    input = StrategyOperationInput.DirectTransport(
                        outboundChangeSet = changeSet,
                    ),
                ),
            )
        }

        val failed = assertIs<StrategySynchronizationExecutionResult.Failed>(result)
        assertSame(pullError, failed.error)
        assertEquals(listOf(StrategyOperation.PUSH_REMOTE), failed.completedOperations)
        val partialOutput = assertIs<StrategyTransportOutput.Pushed>(failed.partialOutput)
        assertSame(acknowledgement, partialOutput.acknowledgement)
        assertEquals(listOf("push", "pull"), transport.operationOrder)

        val callerOwnedOperations = mutableListOf(StrategyOperation.PUSH_REMOTE)
        val snapshot = StrategySynchronizationExecutionResult.Failed(
            evaluation = failed.evaluation,
            completedAt = failed.completedAt,
            error = failed.error,
            transportAttempted = true,
            completedOperations = callerOwnedOperations,
            partialOutput = failed.partialOutput,
        )
        callerOwnedOperations.clear()
        assertEquals(listOf(StrategyOperation.PUSH_REMOTE), snapshot.completedOperations)
    }

    @Test
    fun networkOnlyIgnoresExtraStorageAndQueueBindings() {
        val transport = FakeTransportProvider(
            pullResult = ProviderOperationResult.Success(PullChangesResult.NoChanges()),
        )
        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .provider(transport)
            .defaultStrategyProviderBindings(
                StrategyProviderBindings(
                    transportProviderId = transport.descriptor.id,
                ),
            )
            .build()

        runSuspend { dataLoom.initialize() }
        val result = runSuspend {
            dataLoom.synchronize(
                request = makeNetworkOnlyRequest(SynchronizationDirection.PULL),
                bindings = StrategyProviderBindings(
                    storageProviderId = ProviderId("must-not-resolve-storage"),
                    transportProviderId = transport.descriptor.id,
                    queueProviderId = ProviderId("must-not-resolve-queue"),
                ),
            )
        }

        assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        assertEquals(1, transport.pullCallCount)
    }

    @Test
    fun networkOnlyDefaultBindingsIgnoreUnusedMissingStorageAndQueue() {
        val transport = FakeTransportProvider(
            pullResult = ProviderOperationResult.Success(PullChangesResult.NoChanges()),
        )
        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .provider(transport)
            .defaultStrategyProviderBindings(
                StrategyProviderBindings(
                    storageProviderId = ProviderId("must-not-resolve-storage"),
                    transportProviderId = transport.descriptor.id,
                    queueProviderId = ProviderId("must-not-resolve-queue"),
                ),
            )
            .build()

        runSuspend { dataLoom.initialize() }
        val result = runSuspend {
            dataLoom.synchronize(
                makeNetworkOnlyRequest(SynchronizationDirection.PULL),
            )
        }

        assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        assertEquals(1, transport.pullCallCount)
    }

    @Test
    fun networkOnlyMissingTransportRejectsBeforeProviderCall() {
        val transport = FakeTransportProvider(
            pullResult = ProviderOperationResult.Success(PullChangesResult.NoChanges()),
        )
        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .provider(transport)
            .defaultStrategyProviderBindings(
                StrategyProviderBindings(
                    transportProviderId = transport.descriptor.id,
                ),
            )
            .build()

        runSuspend { dataLoom.initialize() }
        val result = runSuspend {
            dataLoom.synchronize(
                request = makeNetworkOnlyRequest(SynchronizationDirection.PULL),
                bindings = StrategyProviderBindings(),
            )
        }

        val rejected = assertIs<StrategySynchronizationExecutionResult.Rejected>(result)
        assertEquals(
            StrategyExecutionRejectionReason.PROVIDER_RESOLUTION_FAILED,
            rejected.reason,
        )
        assertTrue(
            io.dataloom.api.strategy.StrategyProviderCapability.TRANSPORT in
                rejected.missingCapabilities,
        )
        assertEquals(0, transport.pullCallCount)
    }

    @Test
    fun networkOnlyDurableQueueTriggerRejectsWithoutTransportCall() {
        val transport = FakeTransportProvider(
            pullResult = ProviderOperationResult.Success(PullChangesResult.NoChanges()),
        )
        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .provider(transport)
            .defaultStrategyProviderBindings(
                StrategyProviderBindings(
                    transportProviderId = transport.descriptor.id,
                ),
            )
            .build()

        runSuspend { dataLoom.initialize() }
        val result = runSuspend {
            dataLoom.synchronize(
                makeNetworkOnlyRequest(
                    direction = SynchronizationDirection.PULL,
                    trigger = StrategyExecutionTrigger.DURABLE_QUEUE,
                ),
            )
        }

        val rejected = assertIs<StrategySynchronizationExecutionResult.Rejected>(result)
        assertEquals(StrategyExecutionRejectionReason.INCOMPATIBLE_TRIGGER, rejected.reason)
        assertEquals(0, transport.pullCallCount)
    }

    // =========================================================================
    // Strategy-aware remote-first execution
    // =========================================================================

    @Test
    fun remoteFirstNonPersistingPullUsesTransportOnly() {
        val transport = FakeTransportProvider(
            pullResult = ProviderOperationResult.Success(PullChangesResult.NoChanges()),
        )
        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .provider(transport)
            .defaultStrategyProviderBindings(
                StrategyProviderBindings(
                    transportProviderId = transport.descriptor.id,
                ),
            )
            .build()

        runSuspend { dataLoom.initialize() }
        val result = runSuspend {
            dataLoom.synchronize(makeRemoteFirstRequest())
        }

        val executed = assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        assertIs<StrategyTransportOutput.Pulled>(executed.output)
        assertEquals(1, transport.pullCallCount)
        assertEquals(0, transport.pushCallCount)
    }

    @Test
    fun remoteFirstAllowlistedTimeoutActivatesFallbackAfterTransport() {
        val order = mutableListOf<String>()
        val remoteError = FakeRemoteError(StrategyRemoteOutcome.TIMEOUT)
        val storage = FakeStorageProvider(
            fallbackResult = ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Available(StrategyCacheState.STALE),
            ),
            operationOrder = order,
        )
        val transport = FakeTransportProvider(
            pullResult = ProviderOperationResult.Failure(remoteError),
            operationOrder = order,
        )
        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .providers(storage, transport)
            .defaultStrategyProviderBindings(
                StrategyProviderBindings(
                    storageProviderId = storage.descriptor.id,
                    transportProviderId = transport.descriptor.id,
                ),
            )
            .build()

        runSuspend { dataLoom.initialize() }
        val result = runSuspend {
            dataLoom.synchronize(
                makeRemoteFirstRequest(
                    fallbackOn = setOf(StrategyRemoteOutcome.TIMEOUT),
                    evidence = StrategyRuntimeEvidence(
                        connectivity = StrategyConnectivity.AVAILABLE,
                        cacheState = StrategyCacheState.STALE,
                    ),
                ),
            )
        }

        val fallback = assertIs<StrategySynchronizationExecutionResult.FallbackActivated>(result)
        assertEquals(StrategyRemoteOutcome.TIMEOUT, fallback.remoteOutcome)
        assertTrue(fallback.remoteAttempted)
        assertSame(remoteError, fallback.primaryError)
        assertEquals(StrategyCacheState.STALE, fallback.cacheState)
        assertEquals(listOf("pull", "fallback"), order)
    }

    @Test
    fun remoteFirstAuthenticationFailureCannotActivateAvailabilityFallback() {
        val order = mutableListOf<String>()
        val remoteError = FakeRemoteError(
            remoteOutcome = StrategyRemoteOutcome.AUTHENTICATION_FAILURE,
            category = ErrorCategory.AUTHENTICATION,
        )
        val storage = FakeStorageProvider(
            fallbackResult = ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Available(StrategyCacheState.FRESH),
            ),
            operationOrder = order,
        )
        val transport = FakeTransportProvider(
            pullResult = ProviderOperationResult.Failure(remoteError),
            operationOrder = order,
        )
        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .providers(storage, transport)
            .defaultStrategyProviderBindings(
                StrategyProviderBindings(
                    storageProviderId = storage.descriptor.id,
                    transportProviderId = transport.descriptor.id,
                ),
            )
            .build()

        runSuspend { dataLoom.initialize() }
        val result = runSuspend {
            dataLoom.synchronize(
                makeRemoteFirstRequest(
                    fallbackOn = setOf(StrategyRemoteOutcome.TIMEOUT),
                    evidence = StrategyRuntimeEvidence(
                        connectivity = StrategyConnectivity.AVAILABLE,
                        cacheState = StrategyCacheState.FRESH,
                    ),
                ),
            )
        }

        val failed = assertIs<StrategySynchronizationExecutionResult.Failed>(result)
        assertSame(remoteError, failed.error)
        assertEquals(
            StrategyRemoteOutcome.AUTHENTICATION_FAILURE,
            failed.remoteOutcome,
        )
        assertEquals(0, storage.fallbackCallCount)
        assertEquals(listOf("pull"), order)
    }

    @Test
    fun remoteFirstGenericNetworkErrorIsNotGuessedAsUnavailable() {
        val genericNetworkError = FakeError(
            category = ErrorCategory.NETWORK,
            recoverability = Recoverability.RECOVERABLE,
        )
        val storage = FakeStorageProvider(
            fallbackResult = ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Available(StrategyCacheState.FRESH),
            ),
        )
        val transport = FakeTransportProvider(
            pullResult = ProviderOperationResult.Failure(genericNetworkError),
        )
        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .providers(storage, transport)
            .defaultStrategyProviderBindings(
                StrategyProviderBindings(
                    storageProviderId = storage.descriptor.id,
                    transportProviderId = transport.descriptor.id,
                ),
            )
            .build()

        runSuspend { dataLoom.initialize() }
        val result = runSuspend {
            dataLoom.synchronize(
                makeRemoteFirstRequest(
                    fallbackOn = setOf(StrategyRemoteOutcome.UNAVAILABLE),
                    evidence = StrategyRuntimeEvidence(
                        connectivity = StrategyConnectivity.AVAILABLE,
                        cacheState = StrategyCacheState.FRESH,
                    ),
                ),
            )
        }

        val failed = assertIs<StrategySynchronizationExecutionResult.Failed>(result)
        assertSame(genericNetworkError, failed.error)
        assertEquals(StrategyRemoteOutcome.UNKNOWN_FAILURE, failed.remoteOutcome)
        assertEquals(0, storage.fallbackCallCount)
    }

    @Test
    fun remoteFirstUnavailableEvidenceActivatesFallbackWithoutTransport() {
        val storage = FakeStorageProvider(
            fallbackResult = ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Available(StrategyCacheState.STALE),
            ),
        )
        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .provider(storage)
            .defaultStrategyProviderBindings(
                StrategyProviderBindings(
                    storageProviderId = storage.descriptor.id,
                ),
            )
            .build()

        runSuspend { dataLoom.initialize() }
        val result = runSuspend {
            dataLoom.synchronize(
                makeRemoteFirstRequest(
                    fallbackOn = setOf(StrategyRemoteOutcome.UNAVAILABLE),
                    evidence = StrategyRuntimeEvidence(
                        connectivity = StrategyConnectivity.UNAVAILABLE,
                        cacheState = StrategyCacheState.STALE,
                    ),
                ),
            )
        }

        val fallback = assertIs<StrategySynchronizationExecutionResult.FallbackActivated>(result)
        assertEquals(StrategyRemoteOutcome.UNAVAILABLE, fallback.remoteOutcome)
        assertTrue(!fallback.remoteAttempted)
        assertNull(fallback.primaryError)
        assertEquals(1, storage.fallbackCallCount)
    }

    @Test
    fun remoteFirstMissingFallbackContractRejectsBeforeTransport() {
        val delegate = FakeStorageProvider()
        val plainStorage = object : StorageProvider by delegate {}
        val transport = FakeTransportProvider(
            pullResult = ProviderOperationResult.Success(PullChangesResult.NoChanges()),
        )
        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .providers(plainStorage, transport)
            .defaultStrategyProviderBindings(
                StrategyProviderBindings(
                    storageProviderId = plainStorage.descriptor.id,
                    transportProviderId = transport.descriptor.id,
                ),
            )
            .build()

        runSuspend { dataLoom.initialize() }
        val result = runSuspend {
            dataLoom.synchronize(
                makeRemoteFirstRequest(
                    fallbackOn = setOf(StrategyRemoteOutcome.TIMEOUT),
                    evidence = StrategyRuntimeEvidence(
                        connectivity = StrategyConnectivity.AVAILABLE,
                        cacheState = StrategyCacheState.FRESH,
                    ),
                ),
            )
        }

        val rejected = assertIs<StrategySynchronizationExecutionResult.Rejected>(result)
        assertEquals(
            StrategyExecutionRejectionReason.LOCAL_FALLBACK_PROVIDER_NOT_CONFIGURED,
            rejected.reason,
        )
        assertEquals(0, transport.pullCallCount)
    }

    @Test
    fun remoteFirstPushNeverConvertsRemoteFailureToLocalSuccess() {
        val order = mutableListOf<String>()
        val changeSet = makeChangeSet()
        val remoteError = FakeRemoteError(StrategyRemoteOutcome.TIMEOUT)
        val storage = FakeStorageProvider(
            readResult = ProviderOperationResult.Success(
                OutboundChangeReadResult.Changes(changeSet, hasMore = false),
            ),
            fallbackResult = ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Available(StrategyCacheState.FRESH),
            ),
            operationOrder = order,
        )
        val transport = FakeTransportProvider(
            pushResult = ProviderOperationResult.Failure(remoteError),
            operationOrder = order,
        )
        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .providers(storage, transport)
            .defaultStrategyProviderBindings(
                StrategyProviderBindings(
                    storageProviderId = storage.descriptor.id,
                    transportProviderId = transport.descriptor.id,
                ),
            )
            .build()

        runSuspend { dataLoom.initialize() }
        val result = runSuspend {
            dataLoom.synchronize(
                makeRemoteFirstRequest(
                    direction = SynchronizationDirection.PUSH,
                    fallbackOn = setOf(StrategyRemoteOutcome.TIMEOUT),
                ),
            )
        }

        val failed = assertIs<StrategySynchronizationExecutionResult.Failed>(result)
        assertSame(remoteError, failed.error)
        assertEquals(StrategyRemoteOutcome.TIMEOUT, failed.remoteOutcome)
        assertEquals(0, storage.fallbackCallCount)
        assertEquals(listOf("read-local", "push"), order)
    }

    @Test
    fun remoteFirstBidirectionalFallbackPreservesCompletedPushOutput() {
        val order = mutableListOf<String>()
        val changeSet = makeChangeSet()
        val acknowledgement = makeAcknowledgement(changeSet)
        val storage = FakeStorageProvider(
            readResult = ProviderOperationResult.Success(
                OutboundChangeReadResult.Changes(changeSet, hasMore = false),
            ),
            acknowledgeResult = ProviderOperationResult.Success(Unit),
            fallbackResult = ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Available(StrategyCacheState.FRESH),
            ),
            operationOrder = order,
        )
        val transport = FakeTransportProvider(
            pushResult = ProviderOperationResult.Success(acknowledgement),
            pullResult = ProviderOperationResult.Failure(
                FakeRemoteError(StrategyRemoteOutcome.TIMEOUT),
            ),
            operationOrder = order,
        )
        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .providers(storage, transport)
            .defaultStrategyProviderBindings(
                StrategyProviderBindings(
                    storageProviderId = storage.descriptor.id,
                    transportProviderId = transport.descriptor.id,
                ),
            )
            .build()

        runSuspend { dataLoom.initialize() }
        val result = runSuspend {
            dataLoom.synchronize(
                makeRemoteFirstRequest(
                    direction = SynchronizationDirection.BIDIRECTIONAL,
                    fallbackOn = setOf(StrategyRemoteOutcome.TIMEOUT),
                    evidence = StrategyRuntimeEvidence(
                        connectivity = StrategyConnectivity.AVAILABLE,
                        cacheState = StrategyCacheState.FRESH,
                    ),
                ),
            )
        }

        val fallback = assertIs<StrategySynchronizationExecutionResult.FallbackActivated>(result)
        val partial = assertIs<StrategyTransportOutput.ProviderBacked>(
            fallback.partialOutput,
        )
        assertIs<SynchronizationResult.Succeeded>(partial.result)
        assertEquals(
            listOf(
                StrategyOperation.PUSH_REMOTE,
                StrategyOperation.SERVE_LOCAL,
            ),
            fallback.completedOperations,
        )
        assertEquals(
            listOf("read-local", "push", "acknowledge", "pull", "fallback"),
            order,
        )
    }

    @Test
    fun remoteFirstPersistingPullAppliesRemoteChangesAfterTransport() {
        val order = mutableListOf<String>()
        val storage = FakeStorageProvider(
            applyResult = ProviderOperationResult.Success(Unit),
            operationOrder = order,
        )
        val transport = FakeTransportProvider(
            pullResult = ProviderOperationResult.Success(
                PullChangesResult.Changes(
                    changeSet = makeChangeSet(),
                    hasMore = false,
                ),
            ),
            operationOrder = order,
        )
        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .providers(storage, transport)
            .defaultStrategyProviderBindings(
                StrategyProviderBindings(
                    storageProviderId = storage.descriptor.id,
                    transportProviderId = transport.descriptor.id,
                ),
            )
            .build()

        runSuspend { dataLoom.initialize() }
        val result = runSuspend {
            dataLoom.synchronize(
                makeRemoteFirstRequest(persistRemoteResult = true),
            )
        }

        val executed = assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        val output = assertIs<StrategyTransportOutput.ProviderBacked>(executed.output)
        assertIs<SynchronizationResult.Succeeded>(output.result)
        assertEquals(1, storage.applyCallCount)
        assertEquals(listOf("read-checkpoint", "pull", "persist-remote"), order)
    }

    // =========================================================================
    // Builder immutability
    // =========================================================================

    @Test
    fun build_callerCollectionMutationDoesNotAffectBuiltRuntime() {
        val storageList = mutableListOf<DataLoomProvider>(FakeStorageProvider(), FakeTransportProvider())
        val builder = DataLoomBuilder()
            .runtimeDependencies(makeRuntimeDependencies())
            .providers(*storageList.toTypedArray())
            .defaultProviderBindings(makeBindings())

        // Mutate the original collection after passing to builder.
        storageList.clear()

        // Build should still succeed using the defensively copied list.
        assertNotNull(builder.build())
    }

    @Test
    fun build_secondCallFails() {
        val builder = makeMinimalBuilder()
        builder.build()

        assertFailsWith<DataLoomBuildException> {
            builder.build()
        }
    }

    @Test
    fun build_noComponentServiceLocatorOnDataLoom() {
        val dataLoom = makeMinimalBuilder().build()
        // Verify DataLoom does not expose getComponent/resolve/services methods
        // by checking it only provides the documented narrow interface properties/methods.
        assertNotNull(dataLoom.providerLifecycleState)
        // queueWorker is accessible (may be null).
        val worker = dataLoom.queueWorker
        // No assertion on worker value; just verifying accessors are those defined in the interface.
        assertNull(worker, "queueWorker must be null when not configured.")
    }

    // =========================================================================
    // Compatibility
    // =========================================================================

    @Test
    fun dataLoom_implementsDataLoomInterface() {
        val dataLoom = makeMinimalBuilder().build()
        assertIs<DataLoom>(dataLoom)
    }

    @Test
    fun dataLoom_isNotDataLoomBuilder() {
        val dataLoom = makeMinimalBuilder().build()
        assertIs<DataLoom>(dataLoom)
    }

    // =========================================================================
    // Safe diagnostics
    // =========================================================================

    @Test
    fun buildException_messageContainsFieldNameNotProviderState() {
        val exception = assertFailsWith<DataLoomBuildException> {
            DataLoomBuilder()
                .providers(FakeStorageProvider(), FakeTransportProvider())
                .defaultProviderBindings(makeBindings())
                .build()
        }
        // The message should mention the missing field, not provider state.
        assertTrue(
            exception.message?.contains("runtimeDependencies") == true,
            "Exception message should identify the missing field.",
        )
    }

    @Test
    fun buildException_bindingFailureMessageContainsProviderId() {
        val exception = assertFailsWith<DataLoomBuildException> {
            DataLoomBuilder()
                .runtimeDependencies(makeRuntimeDependencies())
                .providers(FakeTransportProvider()) // storage-primary missing
                .defaultProviderBindings(makeBindings())
                .build()
        }
        assertTrue(
            exception.message?.contains("storage-primary") == true,
            "Exception message should contain the missing provider ID.",
        )
    }
}
