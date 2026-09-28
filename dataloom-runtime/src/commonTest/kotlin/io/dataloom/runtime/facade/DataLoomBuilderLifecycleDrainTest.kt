package io.dataloom.runtime.facade

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.IdentifierGenerator
import io.dataloom.api.identifier.QueueConsumerId
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.RetryPolicyId
import io.dataloom.api.identifier.ScheduleId
import io.dataloom.api.identifier.SynchronizationEventId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.lifecycle.AppLifecycleState
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
import io.dataloom.api.provider.SynchronizationProviderBindings
import io.dataloom.api.queue.ExpiredLeaseRecoveryRequest
import io.dataloom.api.queue.ExpiredLeaseRecoveryResult
import io.dataloom.api.queue.QueueAcquireRequest
import io.dataloom.api.queue.QueueAcquireResult
import io.dataloom.api.queue.QueueCancellationRequest
import io.dataloom.api.queue.QueueCompletionRequest
import io.dataloom.api.queue.QueueDeferralRequest
import io.dataloom.api.queue.QueueEnqueueRequest
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.queue.QueueFailureRequest
import io.dataloom.api.queue.QueueLease
import io.dataloom.api.queue.QueueProvider
import io.dataloom.api.queue.QueueRescheduleRequest
import io.dataloom.api.retry.RetryDecision
import io.dataloom.api.retry.RetryEvaluationRequest
import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.retry.RetryPolicy
import io.dataloom.api.retry.RetryStopReason
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.runtime.RuntimeIdentifierGenerators
import io.dataloom.api.scheduling.ExistingSchedulePolicy
import io.dataloom.api.scheduling.ScheduleConstraints
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.storage.InboundChangeApplyRequest
import io.dataloom.api.storage.OutboundChangeReadRequest
import io.dataloom.api.storage.OutboundChangeReadResult
import io.dataloom.api.storage.StorageProvider
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.synchronization.CheckpointReadRequest
import io.dataloom.api.synchronization.CheckpointWriteRequest
import io.dataloom.api.synchronization.OutboundChangeAcknowledgementRequest
import io.dataloom.api.synchronization.SynchronizationCheckpoint
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.synchronization.SynchronizationSummary
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.runtime.execution.SynchronizationExecutionContext
import io.dataloom.runtime.execution.SynchronizationPipeline
import io.dataloom.runtime.observation.health.QueueWorkerHealthTracker
import io.dataloom.runtime.observation.health.QueueWorkerRunOutcome
import io.dataloom.runtime.queue.QueuedSynchronizationWork
import io.dataloom.runtime.queue.QueuedSynchronizationWorkResolution
import io.dataloom.runtime.queue.QueuedSynchronizationWorkResolver
import io.dataloom.runtime.worker.QueueWorkerConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Proves [DataLoomLifecycleDrainSpec]/`lifecycleDrainConfiguration` wires the
 * lifecycle drain into a real [DataLoomBuilder]-built [DataLoom]: absent means
 * inert, present means nothing runs until the host collects, and a real
 * lifecycle transition reaches the real queue provider through the real,
 * health-tracked queue worker.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataLoomBuilderLifecycleDrainTest {

    @Test
    fun lifecycleDrainIsNullAndInertWhenNotConfigured() = runTest {
        val queue = RecordingQueueProvider()
        val dataLoom = builder(queue)
            .pipeline(SucceedingPipeline())
            .queueWorkerConfiguration(queueWorkerSpec())
            .build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())

        assertNull(dataLoom.lifecycleDrain)
        assertEquals(0, queue.acquireRequests.size)
    }

    @Test
    fun buildingWithTheSpecCollectsNothingAndDrainsNothing() = runTest {
        val queue = RecordingQueueProvider()
        val lifecycle = FakeAppLifecycleProvider()
        val dataLoom = builder(queue)
            .pipeline(SucceedingPipeline())
            .queueWorkerConfiguration(queueWorkerSpec())
            .lifecycleDrainConfiguration(DataLoomLifecycleDrainSpec(lifecycle, QueueConsumerId("drain")))
            .build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())

        assertNotNull(dataLoom.lifecycleDrain)
        assertEquals(0, lifecycle.statesCallCount)
        assertEquals(0, queue.acquireRequests.size)
    }

    @Test
    fun theSpecRequiresAQueueWorker() {
        val builder = builder(RecordingQueueProvider())
            .lifecycleDrainConfiguration(
                DataLoomLifecycleDrainSpec(FakeAppLifecycleProvider(), QueueConsumerId("drain")),
            )

        assertFailsWith<DataLoomBuildException> { builder.build() }
    }

    @Test
    fun aRealLifecycleTransitionDrainsTheRealQueueThroughTheTrackedWorker() = runTest {
        val queue = RecordingQueueProvider()
        val lifecycle = FakeAppLifecycleProvider()
        val tracker = QueueWorkerHealthTracker(FixedDataLoomClock(DataLoomInstant(9_000L)))
        val dataLoom = builder(queue)
            .pipeline(SucceedingPipeline())
            .queueWorkerConfiguration(queueWorkerSpec())
            .queueWorkerHealthTracker(tracker)
            .lifecycleDrainConfiguration(
                DataLoomLifecycleDrainSpec(
                    lifecycleProvider = lifecycle,
                    consumerId = QueueConsumerId("drain-consumer"),
                    policy = LifecycleDrainPolicy(minimumInterval = SchedulingDelay.ZERO, maxEntriesPerDrain = 7),
                ),
            )
            .build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())

        collecting(dataLoom) {
            lifecycle.set(AppLifecycleState.BACKGROUND)
            runCurrent()

            val acquire = queue.acquireRequests.single()
            assertEquals(QueueConsumerId("drain-consumer"), acquire.consumerId)
            assertEquals(QueueLeaseId("drain-lease-1"), acquire.leaseId)
            assertEquals(7, acquire.maxEntries)
            assertEquals(9_000L, acquire.acquiredAt.epochMilliseconds)
            assertEquals(1, queue.completionRequests.size, "the acquired entry was processed and completed")
            // Recorded by the existing worker health tracker: the drain has no tracking of its own.
            val observed = tracker.snapshot()
            assertEquals(QueueWorkerRunOutcome.COMPLETED_PROCESSED, observed.lastRunOutcome)
            assertEquals(0, observed.runsInFlight)
        }
    }

    @Test
    fun aFailedDrainIsRecordedByTheWorkerTrackerAndTheCollectorKeepsRunning() = runTest {
        val queue = RecordingQueueProvider(failFirstAcquire = true)
        val lifecycle = FakeAppLifecycleProvider()
        val tracker = QueueWorkerHealthTracker(FixedDataLoomClock(DataLoomInstant(9_000L)))
        val dataLoom = builder(queue)
            .pipeline(SucceedingPipeline())
            .queueWorkerConfiguration(queueWorkerSpec())
            .queueWorkerHealthTracker(tracker)
            .lifecycleDrainConfiguration(
                DataLoomLifecycleDrainSpec(
                    lifecycleProvider = lifecycle,
                    consumerId = QueueConsumerId("drain-consumer"),
                    policy = LifecycleDrainPolicy(minimumInterval = SchedulingDelay.ZERO),
                ),
            )
            .build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())

        collecting(dataLoom) {
            lifecycle.set(AppLifecycleState.BACKGROUND)
            runCurrent()
            assertEquals(QueueWorkerRunOutcome.PROCESSING_FAILED, tracker.snapshot().lastRunOutcome)
            assertEquals(1, tracker.snapshot().consecutiveFailedRuns)

            lifecycle.set(AppLifecycleState.FOREGROUND)
            lifecycle.set(AppLifecycleState.BACKGROUND)
            runCurrent()
            assertEquals(2, queue.acquireRequests.size)
            assertEquals(QueueWorkerRunOutcome.COMPLETED_PROCESSED, tracker.snapshot().lastRunOutcome)
        }
    }

    private suspend fun TestScope.collecting(dataLoom: DataLoom, block: suspend () -> Unit) {
        val job = launch { dataLoom.lifecycleDrain!!.run() }
        runCurrent()
        try {
            block()
        } finally {
            job.cancelAndJoin()
        }
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private fun builder(queue: RecordingQueueProvider): DataLoomBuilder = DataLoomBuilder()
        .runtimeDependencies(runtimeDependencies())
        .providers(RecordingStorageProvider(), RecordingTransportProvider(), queue)
        .defaultProviderBindings(
            SynchronizationProviderBindings(
                storageProviderId = ProviderId("drain-storage"),
                transportProviderId = ProviderId("drain-transport"),
                queueProviderId = queue.descriptor.id,
            ),
        )

    private fun queueWorkerSpec(): DataLoomQueueWorkerSpec = DataLoomQueueWorkerSpec(
        workResolver = QueuedSynchronizationWorkResolver { entry ->
            QueuedSynchronizationWorkResolution.Resolved(
                QueuedSynchronizationWork(
                    request = entry.synchronizationRequest,
                    bindings = SynchronizationProviderBindings(
                        storageProviderId = ProviderId("drain-storage"),
                        transportProviderId = ProviderId("drain-transport"),
                    ),
                ),
            )
        },
        retryPolicy = object : RetryPolicy {
            override val id = RetryPolicyId("drain-no-retry")
            override fun evaluate(request: RetryEvaluationRequest): RetryDecision =
                RetryDecision.Stop(RetryStopReason.NON_RECOVERABLE)
        },
        retryOperation = RetryOperation("lifecycle-drain.test.operation"),
        configuration = QueueWorkerConfiguration(
            scheduleId = ScheduleId("drain-worker"),
            constraints = ScheduleConstraints(),
            existingSchedulePolicy = ExistingSchedulePolicy.REPLACE,
            continuationDelay = SchedulingDelay.ZERO,
            recoverExpiredLeasesBeforeProcessing = false,
        ),
    )

    private fun runtimeDependencies(): RuntimeDependencies {
        var leaseCounter = 0
        return RuntimeDependencies(
            clock = FixedDataLoomClock(DataLoomInstant(epochMilliseconds = 9_000L)),
            identifiers = RuntimeIdentifierGenerators(
                synchronizationEventIds = generator { SynchronizationEventId("drain-event") },
                queueEntryIds = generator { QueueEntryId("drain-generated-entry") },
                queueLeaseIds = generator { QueueLeaseId("drain-lease-${++leaseCounter}") },
                conflictIds = generator { ConflictId("drain-conflict") },
            ),
        )
    }

    private fun <T> generator(block: () -> T): IdentifierGenerator<T> =
        object : IdentifierGenerator<T> {
            override fun generate(): T = block()
        }

    private class FixedDataLoomClock(private val instant: DataLoomInstant) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
    }

    /** Always succeeds, so an acquired entry is driven to completion. */
    private class SucceedingPipeline : SynchronizationPipeline {
        override val direction: SynchronizationDirection = SynchronizationDirection.PUSH

        override suspend fun execute(context: SynchronizationExecutionContext): SynchronizationResult =
            SynchronizationResult.Succeeded(
                request = context.request,
                completedAt = DataLoomInstant(9_500L),
                summary = SynchronizationSummary(),
            )
    }

    /** Returns one real entry per successful acquire, or fails the first acquire when asked to. */
    private class RecordingQueueProvider(private val failFirstAcquire: Boolean = false) : QueueProvider {
        val acquireRequests = mutableListOf<QueueAcquireRequest>()
        val completionRequests = mutableListOf<QueueCompletionRequest>()

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("drain-queue"),
            name = ProviderName("Lifecycle Drain Test Queue"),
            type = ProviderType.QUEUE,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun enqueue(request: QueueEnqueueRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun acquire(request: QueueAcquireRequest): ProviderOperationResult<QueueAcquireResult> {
            acquireRequests.add(request)
            if (failFirstAcquire && acquireRequests.size == 1) {
                return ProviderOperationResult.Failure(LifecycleDrainTestError())
            }
            val entryRequest = SynchronizationRequest(
                workflowId = WorkflowId("drain-workflow"),
                sessionId = SynchronizationSessionId("drain-session"),
                direction = SynchronizationDirection.PUSH,
                mode = SynchronizationMode.DELTA,
                context = ExecutionContext(
                    executionId = ExecutionId("drain-execution"),
                    correlationId = CorrelationId("drain-correlation"),
                ),
            )
            val lease = QueueLease(
                id = request.leaseId,
                consumerId = request.consumerId,
                acquiredAt = request.acquiredAt,
                expiresAt = request.leaseExpiresAt,
            )
            val entry = QueueEntry(
                id = QueueEntryId("drain-real-entry-${acquireRequests.size}"),
                synchronizationRequest = entryRequest,
                state = QueueEntryState.LEASED,
                enqueuedAt = request.acquiredAt,
                availableAt = request.acquiredAt,
                lease = lease,
            )
            return ProviderOperationResult.Success(QueueAcquireResult.Entries(lease = lease, entries = listOf(entry)))
        }

        override suspend fun complete(request: QueueCompletionRequest): ProviderOperationResult<Unit> {
            completionRequests.add(request)
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun reschedule(request: QueueRescheduleRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(LifecycleDrainTestError())

        override suspend fun defer(request: QueueDeferralRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(LifecycleDrainTestError())

        override suspend fun fail(request: QueueFailureRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(LifecycleDrainTestError())

        override suspend fun cancel(request: QueueCancellationRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Failure(LifecycleDrainTestError())

        override suspend fun recoverExpiredLeases(
            request: ExpiredLeaseRecoveryRequest,
        ): ProviderOperationResult<ExpiredLeaseRecoveryResult> =
            ProviderOperationResult.Success(ExpiredLeaseRecoveryResult(recoveredEntries = 0))
    }

    private class RecordingStorageProvider : StorageProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("drain-storage"),
            name = ProviderName("Lifecycle Drain Storage"),
            type = ProviderType.STORAGE,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun readOutboundChanges(
            request: OutboundChangeReadRequest,
        ): ProviderOperationResult<OutboundChangeReadResult> =
            ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges)

        override suspend fun applyInboundChanges(request: InboundChangeApplyRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun acknowledgeOutboundChanges(
            request: OutboundChangeAcknowledgementRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun readCheckpoint(
            request: CheckpointReadRequest,
        ): ProviderOperationResult<SynchronizationCheckpoint?> = ProviderOperationResult.Success(null)

        override suspend fun writeCheckpoint(request: CheckpointWriteRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)
    }

    private class RecordingTransportProvider : TransportProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("drain-transport"),
            name = ProviderName("Lifecycle Drain Transport"),
            type = ProviderType.TRANSPORT,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun pushChanges(request: PushChangesRequest): ProviderOperationResult<ChangeSetAcknowledgement> =
            ProviderOperationResult.Failure(LifecycleDrainTestError())

        override suspend fun pullChanges(request: PullChangesRequest): ProviderOperationResult<PullChangesResult> =
            ProviderOperationResult.Success(PullChangesResult.NoChanges())
    }
}
