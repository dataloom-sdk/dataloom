package io.dataloom.testing.strategy

import io.dataloom.api.connectivity.ConnectivityRequirement
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.IdentifierGenerator
import io.dataloom.api.identifier.QueueConsumerId
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.ScheduleId
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
import io.dataloom.api.queue.QueueAcquireRequest
import io.dataloom.api.queue.QueueAcquireResult
import io.dataloom.api.queue.QueueCompletionRequest
import io.dataloom.api.queue.QueueEnqueueRequest
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.queue.QueueIdempotentAdmissionProvider
import io.dataloom.api.queue.QueueIdempotentAdmissionResult
import io.dataloom.api.queue.QueueProvider
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.runtime.RuntimeIdentifierGenerators
import io.dataloom.api.scheduling.ExistingSchedulePolicy
import io.dataloom.api.scheduling.ScheduleCancellationRequest
import io.dataloom.api.scheduling.ScheduleReceipt
import io.dataloom.api.scheduling.ScheduleRequest
import io.dataloom.api.scheduling.SchedulerProvider
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
import io.dataloom.api.strategy.StrategyTransportOutput
import io.dataloom.api.strategy.correspondsTo
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
import io.dataloom.runtime.facade.DataLoom
import io.dataloom.runtime.facade.DataLoomBuilder
import io.dataloom.runtime.strategy.StrategyCacheDurableQueueAdmissionDisposition
import io.dataloom.runtime.strategy.StrategyCacheDurableRefreshResult
import io.dataloom.runtime.strategy.StrategyCacheServedWithDurableRefreshResult
import io.dataloom.runtime.strategy.StrategyExecutionRejectionReason
import io.dataloom.runtime.strategy.StrategySynchronizationExecutionResult
import io.dataloom.testing.queue.InMemoryQueueProvider
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CacheFirstDurableRefreshAdmissionIntegrationTest {

    @Test
    fun durableRefreshAdmitsBeforeScheduleAndReplaysFrozenContinuation() = runSuspend {
        val calls = mutableListOf<String>()
        val storage = RecordingStorage(calls = calls)
        val transport = RecordingTransport()
        val queue = RecordingQueue(calls)
        val scheduler = RecordingScheduler(calls = calls)
        val fixture = fixture(storage, transport, queue, scheduler)
        assertIs<ProviderLifecycleResult.InitializeSuccess>(fixture.dataLoom.initialize())

        val result = fixture.dataLoom.synchronize(request())

        val served = assertIs<StrategyCacheServedWithDurableRefreshResult>(result)
        val scheduled = assertIs<StrategyCacheDurableRefreshResult.Scheduled>(served.refresh)
        assertEquals(
            StrategyCacheDurableQueueAdmissionDisposition.ACCEPTED,
            scheduled.queueAdmissionDisposition,
        )
        assertEquals(listOf("cache", "queue", "schedule"), calls)
        assertEquals(1, queue.delegate.entryCount)
        val scheduleRequest = scheduler.requests.single()
        assertEquals(null, scheduleRequest.synchronizationRequest)
        assertEquals(
            ConnectivityRequirement.AVAILABLE,
            scheduleRequest.constraints.connectivity,
        )
        assertEquals(ExistingSchedulePolicy.KEEP, scheduleRequest.existingPolicy)

        val acquired = assertIs<QueueAcquireResult.Entries>(
            queue.acquire(acquireRequest()).successValue(),
        )
        val entry = acquired.entries.single()
        val plan = assertNotNull(entry.strategyPlan)
        val decision = assertNotNull(entry.strategyDecision)
        assertEquals(served.evaluation.plan, plan)
        assertTrue(decision.correspondsTo(plan))

        val replay = fixture.dataLoom.synchronizeAcceptedPlan(
            request = entry.synchronizationRequest,
            decision = decision,
            plan = plan,
            bindings = fixture.bindings,
        )

        val executed = assertIs<StrategySynchronizationExecutionResult.Executed>(replay)
        val output = assertIs<StrategyTransportOutput.ProviderBacked>(executed.output)
        assertIs<SynchronizationResult.Skipped>(output.result)
        assertEquals(1, storage.cacheCalls)
        assertEquals(1, storage.checkpointCalls)
        assertEquals(1, transport.pullCalls)
    }

    @Test
    fun schedulerFailurePreservesQueueAndRetryReconcilesSameIdentity() = runSuspend {
        val calls = mutableListOf<String>()
        val failure = TestError(ErrorCode("SCHEDULER_UNAVAILABLE"))
        val storage = RecordingStorage(calls = calls)
        val transport = RecordingTransport()
        val queue = RecordingQueue(calls)
        val scheduler = RecordingScheduler(
            calls = calls,
            results = mutableListOf(
                ProviderOperationResult.Failure(failure),
                ProviderOperationResult.Success(
                    ScheduleReceipt(ScheduleId("durable-refresh-schedule")),
                ),
            ),
        )
        val fixture = fixture(storage, transport, queue, scheduler)
        assertIs<ProviderLifecycleResult.InitializeSuccess>(fixture.dataLoom.initialize())

        val first = assertIs<StrategyCacheServedWithDurableRefreshResult>(
            fixture.dataLoom.synchronize(request()),
        )
        val firstFailure = assertIs<StrategyCacheDurableRefreshResult.ScheduleFailed>(
            first.refresh,
        )
        assertEquals(
            StrategyCacheDurableQueueAdmissionDisposition.ACCEPTED,
            firstFailure.queueAdmissionDisposition,
        )

        val second = assertIs<StrategyCacheServedWithDurableRefreshResult>(
            fixture.dataLoom.synchronize(request()),
        )
        val scheduled = assertIs<StrategyCacheDurableRefreshResult.Scheduled>(second.refresh)
        assertEquals(
            StrategyCacheDurableQueueAdmissionDisposition.ALREADY_ACCEPTED,
            scheduled.queueAdmissionDisposition,
        )
        assertEquals(1, queue.delegate.entryCount)
        assertEquals(
            listOf("cache", "queue", "schedule", "cache", "queue", "schedule"),
            calls,
        )
    }

    @Test
    fun leasedAndCompletedDuplicatesDoNotScheduleAgain() = runSuspend {
        val storage = RecordingStorage()
        val transport = RecordingTransport()
        val queue = RecordingQueue()
        val scheduler = RecordingScheduler()
        val fixture = fixture(storage, transport, queue, scheduler)
        assertIs<ProviderLifecycleResult.InitializeSuccess>(fixture.dataLoom.initialize())
        assertIs<StrategyCacheServedWithDurableRefreshResult>(
            fixture.dataLoom.synchronize(request()),
        )

        val acquired = assertIs<QueueAcquireResult.Entries>(
            queue.acquire(acquireRequest()).successValue(),
        )
        val leased = assertIs<StrategyCacheServedWithDurableRefreshResult>(
            fixture.dataLoom.synchronize(request()),
        )
        val inProgress =
            assertIs<StrategyCacheDurableRefreshResult.AlreadyInProgress>(leased.refresh)
        assertEquals(QueueEntryState.LEASED, inProgress.queueState)
        assertEquals(1, scheduler.requests.size)

        queue.complete(
            QueueCompletionRequest(
                entryId = acquired.entries.single().id,
                leaseId = acquired.lease.id,
                completedAt = DataLoomInstant(1_500L),
            ),
        ).successValue()
        val completed = assertIs<StrategyCacheServedWithDurableRefreshResult>(
            fixture.dataLoom.synchronize(request()),
        )
        val terminal =
            assertIs<StrategyCacheDurableRefreshResult.AlreadyTerminal>(completed.refresh)
        assertEquals(QueueEntryState.COMPLETED, terminal.queueState)
        assertEquals(1, scheduler.requests.size)
    }

    @Test
    fun changedScheduleIdentityReturnsConflictWithoutScheduling() = runSuspend {
        val storage = RecordingStorage()
        val transport = RecordingTransport()
        val queue = RecordingQueue()
        val scheduler = RecordingScheduler()
        val fixture = fixture(storage, transport, queue, scheduler)
        assertIs<ProviderLifecycleResult.InitializeSuccess>(fixture.dataLoom.initialize())
        fixture.dataLoom.synchronize(request())

        val second = assertIs<StrategyCacheServedWithDurableRefreshResult>(
            fixture.dataLoom.synchronize(
                request(schedule = "different-durable-refresh-schedule"),
            ),
        )

        assertIs<StrategyCacheDurableRefreshResult.IdentityConflict>(second.refresh)
        assertEquals(1, queue.delegate.entryCount)
        assertEquals(1, scheduler.requests.size)
    }

    @Test
    fun plainQueueProviderIsRejectedBeforeCacheOrSchedulerInvocation() = runSuspend {
        val storage = RecordingStorage()
        val transport = RecordingTransport()
        val queue = PlainQueueProvider(InMemoryQueueProvider())
        val scheduler = RecordingScheduler()
        val fixture = fixture(storage, transport, queue, scheduler)
        assertIs<ProviderLifecycleResult.InitializeSuccess>(fixture.dataLoom.initialize())

        val result = fixture.dataLoom.synchronize(request())

        val rejected = assertIs<StrategySynchronizationExecutionResult.Rejected>(result)
        assertEquals(
            StrategyExecutionRejectionReason
                .IDEMPOTENT_QUEUE_ADMISSION_PROVIDER_NOT_CONFIGURED,
            rejected.reason,
        )
        assertEquals(0, storage.cacheCalls)
        assertTrue(scheduler.requests.isEmpty())
    }

    @Test
    fun unavailableCacheStopsBeforeQueueAndScheduler() = runSuspend {
        val storage = RecordingStorage(
            cacheResult = ProviderOperationResult.Success(
                StrategyCacheAccessResult.Unavailable(StrategyCacheState.MISSING),
            ),
        )
        val transport = RecordingTransport()
        val queue = RecordingQueue()
        val scheduler = RecordingScheduler()
        val fixture = fixture(storage, transport, queue, scheduler)
        assertIs<ProviderLifecycleResult.InitializeSuccess>(fixture.dataLoom.initialize())

        val result = fixture.dataLoom.synchronize(request())

        assertIs<StrategySynchronizationExecutionResult.CacheUnavailable>(result)
        assertEquals(0, queue.admitCalls)
        assertTrue(scheduler.requests.isEmpty())
    }

    private fun fixture(
        storage: RecordingStorage,
        transport: RecordingTransport,
        queue: QueueProvider,
        scheduler: RecordingScheduler,
    ): Fixture {
        val bindings = StrategyProviderBindings(
            storageProviderId = storage.descriptor.id,
            transportProviderId = transport.descriptor.id,
            schedulerProviderId = scheduler.descriptor.id,
            queueProviderId = queue.descriptor.id,
        )
        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(runtimeDependencies())
            .providers(storage, transport, queue, scheduler)
            .defaultStrategyProviderBindings(bindings)
            .build()
        return Fixture(dataLoom, bindings)
    }

    private fun request(
        workflow: String = "durable-refresh-workflow",
        schedule: String = "durable-refresh-schedule",
    ): StrategySynchronizationRequest = StrategySynchronizationRequest(
        request = SynchronizationRequest(
            workflowId = WorkflowId(workflow),
            sessionId = SynchronizationSessionId("durable-refresh-session"),
            direction = SynchronizationDirection.PULL,
            mode = SynchronizationMode.DELTA,
            context = ExecutionContext(
                executionId = ExecutionId("durable-refresh-execution"),
                correlationId = CorrelationId("durable-refresh-correlation"),
            ),
        ),
        decisionId = StrategyDecisionId("durable-refresh-decision"),
        planId = StrategyPlanId("durable-refresh-plan"),
        profile = CacheFirstStrategyProfile(
            id = StrategyProfileId("durable-refresh-profile"),
            configurationVersion = StrategyConfigurationVersion(1),
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
            queueEntryId = QueueEntryId("durable-refresh-entry"),
            scheduleId = ScheduleId(schedule),
        ),
    )

    private fun acquireRequest(): QueueAcquireRequest = QueueAcquireRequest(
        consumerId = QueueConsumerId("durable-refresh-consumer"),
        leaseId = QueueLeaseId("durable-refresh-lease"),
        acquiredAt = DataLoomInstant(1_000L),
        leaseExpiresAt = DataLoomInstant(2_000L),
        maxEntries = 1,
    )

    private fun runtimeDependencies(): RuntimeDependencies = RuntimeDependencies(
        clock = FixedClock(DataLoomInstant(1_000L)),
        identifiers = RuntimeIdentifierGenerators(
            synchronizationEventIds = fixed(SynchronizationEventId("event")),
            queueEntryIds = fixed(QueueEntryId("generated-entry")),
            queueLeaseIds = fixed(QueueLeaseId("generated-lease")),
            conflictIds = fixed(ConflictId("conflict")),
        ),
    )

    private data class Fixture(
        val dataLoom: DataLoom,
        val bindings: StrategyProviderBindings,
    )

    private class RecordingQueue(
        private val calls: MutableList<String> = mutableListOf(),
        val delegate: InMemoryQueueProvider = InMemoryQueueProvider(),
    ) : QueueIdempotentAdmissionProvider by delegate {
        var admitCalls: Int = 0
            private set

        override suspend fun admit(
            request: QueueEnqueueRequest,
        ): ProviderOperationResult<QueueIdempotentAdmissionResult> {
            admitCalls++
            calls += "queue"
            return delegate.admit(request)
        }
    }

    private class PlainQueueProvider(
        delegate: QueueProvider,
    ) : QueueProvider by delegate

    private class RecordingScheduler(
        private val calls: MutableList<String> = mutableListOf(),
        private val results: MutableList<ProviderOperationResult<ScheduleReceipt>> =
            mutableListOf(),
    ) : SchedulerProvider {
        override val descriptor: ProviderDescriptor =
            descriptor("durable-refresh-scheduler", ProviderType.SCHEDULER)

        val requests: MutableList<ScheduleRequest> = mutableListOf()

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(
                ProviderHealth(ProviderHealthStatus.HEALTHY),
            )

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun schedule(
            request: ScheduleRequest,
        ): ProviderOperationResult<ScheduleReceipt> {
            calls += "schedule"
            requests += request
            return if (results.isEmpty()) {
                ProviderOperationResult.Success(ScheduleReceipt(request.id))
            } else {
                results.removeAt(0)
            }
        }

        override suspend fun cancel(
            request: ScheduleCancellationRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)
    }

    private class RecordingStorage(
        private val calls: MutableList<String> = mutableListOf(),
        private val cacheResult: ProviderOperationResult<StrategyCacheAccessResult> =
            ProviderOperationResult.Success(
                StrategyCacheAccessResult.Available(
                    StrategyCacheFreshnessEvidence(
                        cacheState = StrategyCacheState.FRESH,
                        observedAt = DataLoomInstant(1_000L),
                        validUntil = DataLoomInstant(2_000L),
                    ),
                ),
            ),
    ) : StrategyCacheAccessProvider {
        override val descriptor: ProviderDescriptor =
            descriptor("durable-refresh-storage", ProviderType.STORAGE)

        var cacheCalls: Int = 0
            private set
        var checkpointCalls: Int = 0
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
            calls += "cache"
            cacheCalls++
            return cacheResult
        }

        override suspend fun readOutboundChanges(
            request: OutboundChangeReadRequest,
        ): ProviderOperationResult<OutboundChangeReadResult> =
            ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges)

        override suspend fun applyInboundChanges(
            request: InboundChangeApplyRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun acknowledgeOutboundChanges(
            request: OutboundChangeAcknowledgementRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun readCheckpoint(
            request: CheckpointReadRequest,
        ): ProviderOperationResult<SynchronizationCheckpoint?> {
            checkpointCalls++
            return ProviderOperationResult.Success(null)
        }

        override suspend fun writeCheckpoint(
            request: CheckpointWriteRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)
    }

    private class RecordingTransport : TransportProvider {
        override val descriptor: ProviderDescriptor =
            descriptor("durable-refresh-transport", ProviderType.TRANSPORT)

        var pullCalls: Int = 0
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
        ): ProviderOperationResult<ChangeSetAcknowledgement> =
            error("Durable cache PULL replay must not push.")

        override suspend fun pullChanges(
            request: PullChangesRequest,
        ): ProviderOperationResult<PullChangesResult> {
            pullCalls++
            return ProviderOperationResult.Success(PullChangesResult.NoChanges())
        }
    }

    private class FixedClock(
        private val instant: DataLoomInstant,
    ) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
    }

    private data class TestError(
        override val code: ErrorCode,
        override val category: ErrorCategory = ErrorCategory.PROVIDER,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Durable refresh test error.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    /** Runs the deterministic, immediately completing suspend test body. */
    private fun <T> runSuspend(block: suspend () -> T): T {
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
            "Durable refresh test operation did not complete synchronously."
        }.getOrThrow()
    }

    private companion object {
        fun descriptor(id: String, type: ProviderType): ProviderDescriptor =
            ProviderDescriptor(
                id = ProviderId(id),
                name = ProviderName(id),
                type = type,
                version = ProviderVersion("1.0.0"),
            )

        fun <T> fixed(value: T): IdentifierGenerator<T> =
            object : IdentifierGenerator<T> {
                override fun generate(): T = value
            }

        fun <T> ProviderOperationResult<T>.successValue(): T =
            assertIs<ProviderOperationResult.Success<T>>(this).value
    }
}
