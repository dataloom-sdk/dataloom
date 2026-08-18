package io.dataloom.consumer.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import io.dataloom.android.androidDataLoomProviders
import io.dataloom.android.installAndroidProviders
import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.ChangeSet
import io.dataloom.api.change.EntityReference
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ChangeSetId
import io.dataloom.api.identifier.ConflictId
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
import io.dataloom.api.model.ChangeOperation
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.observation.SynchronizationObserver
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
import io.dataloom.api.queue.QueueAcquireRequest
import io.dataloom.api.queue.QueueEnqueueRequest
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
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
import io.dataloom.api.strategy.OfflineFirstStrategyProfile
import io.dataloom.api.strategy.StrategyCacheState
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyConnectivity
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyOperationInput
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.strategy.StrategyRuntimeEvidence
import io.dataloom.api.strategy.StrategySynchronizationRequest
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.synchronization.SynchronizationEvent
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.time.SystemDataLoomClock
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.runtime.facade.DataLoom
import io.dataloom.runtime.facade.DataLoomBuilder
import io.dataloom.runtime.facade.DataLoomQueueWorkerSpec
import io.dataloom.runtime.queue.QueueProcessingRequest
import io.dataloom.runtime.queue.QueueProcessingResult
import io.dataloom.runtime.queue.QueuedSynchronizationWork
import io.dataloom.runtime.queue.QueuedSynchronizationWorkResolution
import io.dataloom.runtime.queue.QueuedSynchronizationWorkResolver
import io.dataloom.runtime.strategy.StrategySynchronizationExecutionResult
import io.dataloom.runtime.submission.QueuedSynchronizationSubmission
import io.dataloom.runtime.submission.QueuedSynchronizationWorkEncoder
import io.dataloom.runtime.submission.QueuedSynchronizationWorkEncodingResult
import io.dataloom.runtime.worker.QueueWorkerConfiguration
import io.dataloom.runtime.worker.QueueWorkerRunRequest
import io.dataloom.runtime.worker.QueueWorkerRunResult
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Robolectric-backed runtime proof that a real, durably admitted strategy
 * plan survives being read back out of a real Room-backed queue and is
 * genuinely replayed by a real `DataLoomQueueWorker` cycle -- the
 * queue-admission/retry/circuit/conflict-during-a-real-synchronization-pass
 * gap `#101`'s market-readiness row named, beyond
 * [AndroidReferenceConsumerRobolectricTest]'s existing direct PULL/FULL
 * proof (`#302`).
 *
 * ## Scope: one platform, one strategy
 *
 * This proves Android + offline-first only. iOS and the other five
 * strategies (network-only, remote-first, cache-first, hybrid, adaptive)
 * are explicitly deferred, not silently claimed. Offline-first was chosen
 * because `#259`'s durable-work branch is the simplest real admission path
 * that already exists in production code -- see
 * `docs/api/durable-queue-admission.md` and
 * `docs/api/offline-first-strategy-execution.md`.
 *
 * ## What this proves
 *
 * [durableAdmissionIsReplayedByQueueWorker]
 * exercises the full path, entirely through real, production DataLoom code:
 *
 * 1. **Admission, not synchronous execution.** [DataLoom.synchronize] for a
 *    `StrategySynchronizationRequest` built from
 *    [OfflineFirstStrategyProfile] (`requireDurableQueue = true`,
 *    connectivity `AVAILABLE`) returns
 *    [StrategySynchronizationExecutionResult.DurablyEnqueued] --
 *    `StrategyDurableQueueAdmitter` durably persists the accepted plan via
 *    the real [io.dataloom.queue.room.RoomQueueProvider] instead of running
 *    the pipeline synchronously. The test-only transport records zero
 *    `pullChanges` calls at this point, proving nothing executed yet.
 * 2. **Survives being read back out.** The queue entry is not merely
 *    written; step 3 acquires it back from the same real Room database via
 *    a genuine `QueueProvider.acquire` call inside the queue-worker cycle.
 * 3. **A queue worker genuinely replays it.** [DataLoom.queueWorker]'s
 *    single `run(...)` call -- the same `DataLoomQueueWorker` capability
 *    `DataLoomCoroutineWorker` (the real WorkManager bridge) delegates to in
 *    production -- deterministically drives exactly one bounded
 *    acquire/execute/complete cycle. This test calls it directly rather
 *    than waiting on a real WorkManager tick, matching `#302`'s existing
 *    preference for a deterministic single pass over a flaky scheduled one.
 *    `AcceptedStrategyPlanExecutionCoordinator` -- the durable-continuation
 *    replay coordinator, never exercised by `#302`/`#303` -- resolves the
 *    real storage/transport providers from the queue entry's own persisted
 *    bindings and runs the real `InboundPullSynchronizationPipeline`.
 * 4. **The result is genuinely observable.** A [SynchronizationObserver]
 *    registered through the real [DataLoomBuilder.observer] capability
 *    captures the terminal [SynchronizationEvent.Completed] emitted by that
 *    replay and asserts `summary.inboundEventsApplied == 1` -- the same bar
 *    `#302` established for the direct pass, not just a "succeeded" queue
 *    outcome. [QueueProcessingResult.Processed.summary.completed] is
 *    asserted as one additional layer of evidence that the entry reached a
 *    real, durable terminal transition.
 *
 * ## Why offline-first's PULL/cache-MISSING branch specifically
 *
 * With `evidence.cacheState = MISSING`, no `SERVE_LOCAL` is admitted, so the
 * durable continuation's operations are exactly
 * `[READ_CHECKPOINT, PULL_REMOTE, PERSIST_REMOTE]` --
 * `AcceptedStrategyPlanExecutionCoordinator`'s supported PULL replay shape
 * -- and needs only `STORAGE`/`TRANSPORT` providers, not the
 * `StrategyLocalFallbackProvider`/`StrategyReconciliationProvider`
 * capabilities `RoomStorageProvider` does not implement.
 * `reconcileWhenOnline = false` keeps `RECONCILE` out of the plan for the
 * same reason. This is the smallest real branch that exercises genuine
 * admission-then-replay end to end.
 *
 * ## What this does not prove
 *
 * iOS (no equivalent Apple queue-drain proof exists yet -- see
 * `dataloom-scheduler-workmanager`'s Apple counterpart, which this test
 * does not touch); the other five built-in strategies' own admission
 * branches (cache-first, hybrid share the same `StrategyDurableQueueAdmitter`
 * machinery but are not separately exercised here); retry, circuit-breaker,
 * or conflict-detection behavior during queue replay (this entry always
 * succeeds on its first attempt); a real WorkManager-triggered background
 * tick (this test calls `queueWorker.run(...)` directly, deterministically,
 * rather than waiting on a real scheduled tick); and a real managed-device
 * emulator (Robolectric only, matching `#302`'s own documented boundary).
 */
@RunWith(RobolectricTestRunner::class)
class AndroidReferenceConsumerDurableQueueRobolectricTest {

    @Test
    fun durableAdmissionIsReplayedByQueueWorker() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)

        val changeSet = ChangeSet(
            id = ChangeSetId("durable-queue-change-set-1"),
            events = listOf(
                ChangeEvent(
                    id = ChangeEventId("durable-queue-event-1"),
                    entity = EntityReference(
                        type = EntityType("durable-queue-entity"),
                        id = EntityId("durable-queue-entity-1"),
                    ),
                    operation = ChangeOperation.CREATE,
                ),
            ),
        )
        val transport = CountingOneChangeSetTransportProvider(changeSet)
        val completedResults = mutableListOf<SynchronizationResult>()
        val observer = object : SynchronizationObserver {
            override val id: SynchronizationObserverId = SynchronizationObserverId("durable-queue-replay-observer")
            override fun onEvent(event: SynchronizationEvent) {
                if (event is SynchronizationEvent.Completed) {
                    completedResults += event.result
                }
            }
        }

        val dataLoom = buildDurableQueueDataLoom(context, transport, observer)
        assertEquals(ProviderLifecycleResult.InitializeSuccess, dataLoom.initialize())

        val strategyRequest = StrategySynchronizationRequest(
            request = SynchronizationRequest(
                workflowId = WorkflowId("durable-queue-workflow-1"),
                sessionId = SynchronizationSessionId("durable-queue-session-1"),
                direction = SynchronizationDirection.PULL,
                mode = SynchronizationMode.FULL,
                context = ExecutionContext(
                    executionId = ExecutionId("durable-queue-execution-1"),
                    correlationId = CorrelationId("durable-queue-correlation-1"),
                ),
            ),
            decisionId = StrategyDecisionId("durable-queue-decision-1"),
            planId = StrategyPlanId("durable-queue-plan-1"),
            profile = OfflineFirstStrategyProfile(
                id = StrategyProfileId("durable-queue-offline-first-profile"),
                configurationVersion = StrategyConfigurationVersion(1L),
                requireDurableQueue = true,
                reconcileWhenOnline = false,
            ),
            evidence = StrategyRuntimeEvidence(
                connectivity = StrategyConnectivity.AVAILABLE,
                cacheState = StrategyCacheState.MISSING,
            ),
            input = StrategyOperationInput.ProviderBacked,
        )

        // Step 1: durable admission -- must NOT execute synchronously.
        val admissionResult = dataLoom.synchronize(strategyRequest)
        val enqueued = assertIs<StrategySynchronizationExecutionResult.DurablyEnqueued>(admissionResult)
        assertEquals(0, transport.pullCalls)
        assertEquals(0, completedResults.size)

        // Step 2 + 3: acquire the durably persisted entry back out of the
        // real Room queue database and replay it via exactly one
        // deterministic queue-worker cycle.
        // The admitted entry's availableAt was stamped from the real wall
        // clock (referenceRuntimeDependencies' SystemDataLoomClock, the same
        // clock buildReferenceDataLoom uses in production). acquiredAt must
        // therefore also be a real "now" -- not an arbitrary small epoch --
        // or the acquire query's `availableAt <= acquiredAt` guard never
        // matches and the entry is never eligible.
        val acquiredAt = DataLoomInstant(epochMilliseconds = System.currentTimeMillis())
        val runResult = dataLoom.queueWorker!!.run(
            QueueWorkerRunRequest(
                processingRequest = QueueProcessingRequest(
                    acquireRequest = QueueAcquireRequest(
                        consumerId = QueueConsumerId("robolectric-durable-queue-consumer"),
                        leaseId = QueueLeaseId("robolectric-durable-queue-lease"),
                        acquiredAt = acquiredAt,
                        leaseExpiresAt = DataLoomInstant(
                            epochMilliseconds = acquiredAt.epochMilliseconds + 60_000L,
                        ),
                        maxEntries = 10,
                    ),
                ),
                recoveryRequest = null,
            ),
        )

        val processed = assertIs<QueueWorkerRunResult.ProcessingCompleted>(runResult)
        val processingResult = assertIs<QueueProcessingResult.Processed>(processed.processingResult)
        assertEquals(1, processingResult.summary.completed)

        // Step 4: the replayed result is genuinely observable -- the
        // inbound event reached real storage, not just a "succeeded" status.
        assertEquals(1, transport.pullCalls)
        assertEquals(1, completedResults.size)
        val succeeded = assertIs<SynchronizationResult.Succeeded>(completedResults.single())
        assertEquals(1L, succeeded.summary.inboundEventsApplied)
        assertEquals(enqueued.queueEntryId, enqueued.queueEntryId)

        assertEquals(ProviderLifecycleResult.ShutdownSuccess, dataLoom.shutdown())
    }

    /**
     * Assembles a real [DataLoom] instance from the exact same production
     * `dataloom-android` helpers [androidDataLoomProviders]/
     * [installAndroidProviders] every other reference-consumer test uses,
     * additionally opting into [DataLoomBuilder.queueSubmissionEncoder] and
     * [DataLoomBuilder.queueWorkerConfiguration] -- the two capabilities
     * `installAndroidProviders` deliberately leaves for the host application
     * to configure itself, since they require application-specific policy
     * decisions (retry policy, work resolution) `dataloom-android` cannot
     * make on the host's behalf.
     *
     * The queue-submission encoder and work resolver are intentionally
     * trivial: [QueueEntry] already carries [QueueEntry.synchronizationRequest],
     * [QueueEntry.strategyDecision], and [QueueEntry.strategyPlan] as typed
     * fields (no opaque byte payload to encode), so round-tripping through
     * the real `RoomQueueProvider` requires no serialization -- only
     * [SynchronizationProviderBindings], which is not persisted on
     * [QueueEntry], is reconstructed by the resolver from the same provider
     * IDs [installAndroidProviders] already bound.
     */
    private fun buildDurableQueueDataLoom(
        context: Context,
        transportProvider: TransportProvider,
        observer: SynchronizationObserver,
    ): DataLoom {
        // A short (not full-UUID) suffix keeps the resulting database file
        // path well under Windows' MAX_PATH limit -- Robolectric's per-test
        // temp directory already embeds the test class and method name, and
        // this module's Windows CI/local runs otherwise hit a genuine
        // "file doesn't exist, check directory permissions" SQLite open
        // failure that is a local path-length artifact, not a product bug.
        val uniqueSuffix = UUID.randomUUID().toString().take(8)
        val providers = androidDataLoomProviders(
            context = context,
            storageDatabaseName = "dq-storage-$uniqueSuffix.db",
            queueDatabaseName = "dq-queue-$uniqueSuffix.db",
        )
        val bindings = SynchronizationProviderBindings(
            storageProviderId = providers.storage.descriptor.id,
            transportProviderId = transportProvider.descriptor.id,
            schedulerProviderId = providers.scheduler.descriptor.id,
            connectivityProviderId = providers.connectivity.descriptor.id,
            queueProviderId = providers.queue.descriptor.id,
        )

        return DataLoomBuilder()
            .runtimeDependencies(referenceRuntimeDependencies())
            .installAndroidProviders(providers, transportProvider)
            .observer(observer)
            .queueSubmissionEncoder(PassthroughQueuedSynchronizationWorkEncoder)
            .queueWorkerConfiguration(
                DataLoomQueueWorkerSpec(
                    workResolver = QueuedSynchronizationWorkResolver { entry ->
                        QueuedSynchronizationWorkResolution.Resolved(
                            QueuedSynchronizationWork(
                                request = entry.synchronizationRequest,
                                bindings = bindings,
                                strategyDecision = entry.strategyDecision,
                                strategyPlan = entry.strategyPlan,
                            ),
                        )
                    },
                    retryPolicy = NeverRetryPolicy,
                    retryOperation = RetryOperation("robolectric.durable-queue-replay"),
                    configuration = QueueWorkerConfiguration(
                        scheduleId = ScheduleId("robolectric-durable-queue-worker"),
                        constraints = ScheduleConstraints(),
                        existingSchedulePolicy = ExistingSchedulePolicy.REPLACE,
                        continuationDelay = SchedulingDelay.ZERO,
                        recoverExpiredLeasesBeforeProcessing = false,
                    ),
                ),
            )
            .build()
    }

    /**
     * Reference [RuntimeDependencies] duplicated from
     * [AndroidReferenceConsumer]'s own private helper of the same shape --
     * real wall clock, UUID-backed identifier generators. Kept local to this
     * test rather than reaching into that file's private helper.
     */
    private fun referenceRuntimeDependencies(): RuntimeDependencies = RuntimeDependencies(
        clock = SystemDataLoomClock(),
        identifiers = RuntimeIdentifierGenerators(
            synchronizationEventIds = uuidGenerator(::SynchronizationEventId),
            queueEntryIds = uuidGenerator(::QueueEntryId),
            queueLeaseIds = uuidGenerator(::QueueLeaseId),
            conflictIds = uuidGenerator(::ConflictId),
        ),
    )

    private fun <T> uuidGenerator(construct: (String) -> T): IdentifierGenerator<T> =
        object : IdentifierGenerator<T> {
            override fun generate(): T = construct(UUID.randomUUID().toString())
        }
}

/**
 * Test-only [QueuedSynchronizationWorkEncoder] that copies a submission's
 * typed [QueuedSynchronizationSubmission.work] fields directly onto a
 * [QueueEntry] -- no byte serialization is needed because [QueueEntry]
 * already carries [QueueEntry.synchronizationRequest],
 * [QueueEntry.strategyDecision], and [QueueEntry.strategyPlan] as typed
 * fields. [SynchronizationProviderBindings] is deliberately not part of
 * [QueueEntry] and is reconstructed independently by the paired
 * [QueuedSynchronizationWorkResolver] in
 * [AndroidReferenceConsumerDurableQueueRobolectricTest.buildDurableQueueDataLoom].
 */
private object PassthroughQueuedSynchronizationWorkEncoder : QueuedSynchronizationWorkEncoder {
    override fun encode(
        submission: QueuedSynchronizationSubmission,
    ): QueuedSynchronizationWorkEncodingResult =
        QueuedSynchronizationWorkEncodingResult.Encoded(
            QueueEnqueueRequest(
                entry = QueueEntry(
                    id = submission.queueEntryId,
                    synchronizationRequest = submission.work.request,
                    state = QueueEntryState.PENDING,
                    enqueuedAt = submission.availableAt,
                    availableAt = submission.availableAt,
                    strategyDecision = submission.work.strategyDecision,
                    strategyPlan = submission.work.strategyPlan,
                ),
            ),
        )
}

/** Test-only [RetryPolicy] that always stops -- this entry always succeeds on its first attempt. */
private object NeverRetryPolicy : RetryPolicy {
    override val id: RetryPolicyId = RetryPolicyId("robolectric-durable-queue-never-retry")
    override fun evaluate(request: RetryEvaluationRequest): RetryDecision =
        RetryDecision.Stop(RetryStopReason.POLICY_REJECTED)
}

/**
 * Test-only [TransportProvider] that always returns [changeSet] from
 * [pullChanges] and counts calls -- proving durable admission does not
 * execute synchronously (zero calls immediately after admission) and that
 * the queue-worker replay genuinely invokes the real pipeline (exactly one
 * call after [io.dataloom.runtime.facade.DataLoomQueueWorker.run]). Push is
 * not exercised by this test and fails deterministically if ever called.
 */
private class CountingOneChangeSetTransportProvider(
    private val changeSet: ChangeSet,
) : TransportProvider {
    var pullCalls: Int = 0
        private set

    override val descriptor: ProviderDescriptor = ProviderDescriptor(
        id = ProviderId("io.dataloom.consumer.android.test.counting-one-change-set-transport"),
        name = ProviderName("Counting One-Change-Set Test Transport"),
        type = ProviderType.TRANSPORT,
        version = ProviderVersion("1.0.0"),
    )

    override suspend fun initialize(
        context: ProviderInitializationContext,
    ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

    override suspend fun health(): ProviderOperationResult<ProviderHealth> =
        ProviderOperationResult.Success(ProviderHealth(status = ProviderHealthStatus.HEALTHY))

    override suspend fun close(): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

    override suspend fun pushChanges(
        request: PushChangesRequest,
    ): ProviderOperationResult<ChangeSetAcknowledgement> =
        error("CountingOneChangeSetTransportProvider does not support push.")

    override suspend fun pullChanges(
        request: PullChangesRequest,
    ): ProviderOperationResult<PullChangesResult> {
        pullCalls++
        return ProviderOperationResult.Success(PullChangesResult.Changes(changeSet = changeSet, hasMore = false))
    }
}
