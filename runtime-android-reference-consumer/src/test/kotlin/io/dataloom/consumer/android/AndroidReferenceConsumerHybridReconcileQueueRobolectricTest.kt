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
import io.dataloom.api.identifier.CheckpointKey
import io.dataloom.api.identifier.CheckpointToken
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
import io.dataloom.api.strategy.HybridSource
import io.dataloom.api.strategy.HybridStrategyProfile
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
import io.dataloom.api.synchronization.CheckpointWriteRequest
import io.dataloom.api.synchronization.SynchronizationCheckpoint
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
import kotlin.test.assertNotNull

/**
 * Robolectric-backed runtime proof that hybrid's own explicit-fallback
 * branch — `HybridSource.LOCAL` selected as an explicit fallback from a
 * `REMOTE` primary, with `reconcileAfterFallback = true` (the default) — is
 * now genuinely exercisable end-to-end, closing the gap `#101`'s
 * market-readiness row and
 * [AndroidReferenceConsumerHybridQueueRobolectricTest]'s own KDoc both
 * named as blocked: that branch's `operations` and durable continuation
 * both always carry `RECONCILE` alongside `ENQUEUE_DURABLE_WORK`
 * (`BuiltInSynchronizationStrategyEvaluator.evaluateHybrid`/
 * `deriveDurableContinuation`), and its immediate operations also carry
 * `SERVE_LOCAL` — requiring both `StrategyLocalFallbackProvider` and
 * `StrategyReconciliationProvider`, neither of which the real
 * `RoomStorageProvider` implemented until now.
 *
 * ## What this proves
 *
 * With `RoomStorageProvider` now implementing both capability interfaces
 * (this round's change), the identical `HybridStrategyProfile` /
 * `StrategyRuntimeEvidence` combination
 * [AndroidReferenceConsumerHybridQueueRobolectricTest]'s own KDoc
 * documented as blocked genuinely completes end-to-end:
 *
 * 1. **Synchronous half.** With `primarySource = REMOTE` and connectivity
 *    `UNAVAILABLE`, `evaluateHybrid` selects `HybridSource.LOCAL` as an
 *    explicit fallback (`usedFallback = true`), producing
 *    `operations = [SERVE_LOCAL, ENQUEUE_DURABLE_WORK, RECONCILE]` and
 *    `StrategyDisposition.SERVE_AND_REFRESH`. `HybridStrategyExecutor`
 *    durably admits the continuation first (a real `queueEntryId`, zero
 *    transport calls), then genuinely calls
 *    `RoomStorageProvider.evaluateLocalFallback` — seeded beforehand with
 *    one real applied inbound change set so the provider's own existence
 *    check reports [io.dataloom.api.strategy.StrategyLocalFallbackResult
 *    .Available] rather than failing the evaluator/provider consistency
 *    check — and returns
 *    [StrategySynchronizationExecutionResult.ServedFromCache] carrying that
 *    `queueEntryId`.
 * 2. **Durable half.** The admitted continuation is exactly
 *    `remoteOperations(PULL, persistRemote = true) + RECONCILE` =
 *    `[READ_CHECKPOINT, PULL_REMOTE, PERSIST_REMOTE, RECONCILE]`. Reading
 *    the entry back out of the real Room-backed queue and replaying it
 *    through one deterministic `DataLoom.queueWorker.run(...)` cycle
 *    genuinely drives `AcceptedStrategyPlanExecutionCoordinator` (no longer
 *    rejected at `validateReplayProviders` since `RoomStorageProvider` now
 *    satisfies both capability checks) through the real, registered
 *    `InboundPullSynchronizationPipeline` against real Room storage, then
 *    genuinely calls `RoomStorageProvider.reconcileStrategy` — which
 *    confirms the checkpoint `PERSIST_REMOTE` just wrote actually exists
 *    and reports `Applied` — to a genuinely observed
 *    `SynchronizationResult.Succeeded` (`summary.inboundEventsApplied == 1`)
 *    via a real `SynchronizationObserver`, the same bar `#325`/`#368`/`#371`
 *    established for every other durable branch this suite proves.
 *
 * ## What this does not prove
 *
 * iOS (no equivalent Apple proof of this branch exists yet — the real
 * `SqlDelightStorageProvider` gained the identical capability this round
 * and is covered by its own `commonTest`/`jvmTest` coverage, but not by an
 * `IosReferenceConsumer*` end-to-end proof); retry, circuit-breaker, or
 * conflict-detection behavior during queue replay (this entry always
 * succeeds on its first attempt); a real WorkManager-triggered background
 * tick (this test calls `queueWorker.run(...)` directly, deterministically);
 * and a real managed-device emulator (Robolectric only).
 */
@RunWith(RobolectricTestRunner::class)
class AndroidReferenceConsumerHybridReconcileQueueRobolectricTest {

    @Test
    fun hybridReconcileQueueWorkerReplay() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)

        val changeSet = ChangeSet(
            id = ChangeSetId("hybrid-reconcile-queue-change-set-1"),
            events = listOf(
                ChangeEvent(
                    id = ChangeEventId("hybrid-reconcile-queue-event-1"),
                    entity = EntityReference(
                        type = EntityType("hybrid-reconcile-queue-entity"),
                        id = EntityId("hybrid-reconcile-queue-entity-1"),
                    ),
                    operation = ChangeOperation.CREATE,
                ),
            ),
        )
        val transport = CountingOneChangeSetHybridReconciliationTransportProvider(changeSet)
        val completedResults = mutableListOf<SynchronizationResult>()
        val observer = object : SynchronizationObserver {
            override val id: SynchronizationObserverId =
                SynchronizationObserverId("durable-queue-hybrid-reconcile-replay-observer")
            override fun onEvent(event: SynchronizationEvent) {
                if (event is SynchronizationEvent.Completed) {
                    completedResults += event.result
                }
            }
        }

        val providers = androidTestProviders(context)

        // The real, unmodified RoomStorageProvider's evaluateLocalFallback is a
        // genuine existence check over its own infrastructure tables -- it is
        // not seeded implicitly by evaluation evidence alone. Seed one real
        // checkpoint first (before building/initializing DataLoom, matching
        // AndroidReferenceConsumerCacheFirstQueueRobolectricTest's own
        // established seed-then-initialize ordering) so this branch's
        // SERVE_LOCAL step (still genuinely exercised, not bypassed) reports
        // synchronized local state is actually Available, matching the
        // StrategyCacheState.STALE evidence supplied below.
        val seedResult = providers.storage.writeCheckpoint(
            CheckpointWriteRequest(
                request = seedRequest(),
                checkpoint = SynchronizationCheckpoint(
                    key = CheckpointKey("hybrid-reconcile-seed-checkpoint"),
                    token = CheckpointToken("hybrid-reconcile-seed-token"),
                ),
            ),
        )
        assertEquals(ProviderOperationResult.Success(Unit), seedResult)

        val dataLoom = buildDurableQueueDataLoom(providers, transport, observer)
        assertEquals(ProviderLifecycleResult.InitializeSuccess, dataLoom.initialize())

        val strategyRequest = StrategySynchronizationRequest(
            request = SynchronizationRequest(
                workflowId = WorkflowId("durable-queue-hybrid-reconcile-workflow-1"),
                sessionId = SynchronizationSessionId("durable-queue-hybrid-reconcile-session-1"),
                direction = SynchronizationDirection.PULL,
                mode = SynchronizationMode.FULL,
                context = ExecutionContext(
                    executionId = ExecutionId("durable-queue-hybrid-reconcile-execution-1"),
                    correlationId = CorrelationId("durable-queue-hybrid-reconcile-correlation-1"),
                ),
            ),
            decisionId = StrategyDecisionId("durable-queue-hybrid-reconcile-decision-1"),
            planId = StrategyPlanId("durable-queue-hybrid-reconcile-plan-1"),
            profile = HybridStrategyProfile(
                id = StrategyProfileId("durable-queue-hybrid-reconcile-profile"),
                configurationVersion = StrategyConfigurationVersion(1L),
                primarySource = HybridSource.REMOTE,
                fallbackSource = HybridSource.LOCAL,
                persistRemoteResult = true,
                // reconcileAfterFallback defaults to true -- this is exactly
                // the branch that requires StrategyReconciliationProvider.
            ),
            evidence = StrategyRuntimeEvidence(
                connectivity = StrategyConnectivity.UNAVAILABLE,
                cacheState = StrategyCacheState.STALE,
            ),
            input = StrategyOperationInput.ProviderBacked,
        )

        // Step 1: the synchronous half -- SERVE_LOCAL succeeds via the real
        // RoomStorageProvider.evaluateLocalFallback, and the deferred remote
        // catch-up (carrying RECONCILE) is durably admitted alongside it.
        // Zero transport calls yet.
        val servedResult = dataLoom.synchronize(strategyRequest)
        val served = assertIs<StrategySynchronizationExecutionResult.ServedFromCache>(servedResult)
        assertEquals(StrategyCacheState.STALE, served.cacheState)
        val queueEntryId = assertNotNull(served.durableQueueEntryId)
        assertEquals(0, transport.pullCalls)
        assertEquals(0, completedResults.size)

        // Step 2 + 3: acquire the durably persisted continuation back out of
        // the real Room queue database and replay it through exactly one
        // deterministic queue-worker cycle -- now genuinely reaching
        // AcceptedStrategyPlanExecutionCoordinator's RECONCILE step instead
        // of being rejected at validateReplayProviders.
        val acquiredAt = DataLoomInstant(epochMilliseconds = System.currentTimeMillis())
        val runResult = dataLoom.queueWorker!!.run(
            QueueWorkerRunRequest(
                processingRequest = QueueProcessingRequest(
                    acquireRequest = QueueAcquireRequest(
                        consumerId = QueueConsumerId("robolectric-durable-queue-hybrid-reconcile-consumer"),
                        leaseId = QueueLeaseId("robolectric-durable-queue-hybrid-reconcile-lease"),
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

        // Step 4: the replayed result is genuinely observable, and
        // reconcileStrategy's own Applied confirmation (checkpoint now
        // exists after PERSIST_REMOTE) did not turn the result into a
        // failure.
        assertEquals(1, transport.pullCalls)
        assertEquals(1, completedResults.size)
        val succeeded = assertIs<SynchronizationResult.Succeeded>(completedResults.single())
        assertEquals(1L, succeeded.summary.inboundEventsApplied)
        assertNotNull(queueEntryId)

        assertEquals(ProviderLifecycleResult.ShutdownSuccess, dataLoom.shutdown())
    }

    private fun seedRequest(): SynchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("durable-queue-hybrid-reconcile-seed-workflow"),
        sessionId = SynchronizationSessionId("durable-queue-hybrid-reconcile-seed-session"),
        direction = SynchronizationDirection.PULL,
        mode = SynchronizationMode.FULL,
        context = ExecutionContext(
            executionId = ExecutionId("durable-queue-hybrid-reconcile-seed-execution"),
            correlationId = CorrelationId("durable-queue-hybrid-reconcile-seed-correlation"),
        ),
    )

    private fun androidTestProviders(context: Context) = androidDataLoomProviders(
        context = context,
        storageDatabaseName = "dq-hyr-storage-${UUID.randomUUID().toString().take(8)}.db",
        queueDatabaseName = "dq-hyr-queue-${UUID.randomUUID().toString().take(8)}.db",
    )

    /**
     * Assembles a real [DataLoom] instance from the exact same production
     * `dataloom-android` helpers [androidDataLoomProviders]/
     * [installAndroidProviders] every other reference-consumer test uses,
     * additionally opting into [DataLoomBuilder.queueSubmissionEncoder] and
     * [DataLoomBuilder.queueWorkerConfiguration] -- see
     * `AndroidReferenceConsumerDurableQueueRobolectricTest.buildDurableQueueDataLoom`
     * (`#325`) for why these two capabilities require explicit test-side
     * wiring.
     */
    private fun buildDurableQueueDataLoom(
        providers: io.dataloom.android.AndroidDataLoomProviders,
        transportProvider: TransportProvider,
        observer: SynchronizationObserver,
    ): DataLoom {
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
            .queueSubmissionEncoder(HybridReconciliationPassthroughQueuedSynchronizationWorkEncoder)
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
                    retryPolicy = HybridReconciliationNeverRetryPolicy,
                    retryOperation = RetryOperation("robolectric.durable-queue-hybrid-reconcile-replay"),
                    configuration = QueueWorkerConfiguration(
                        scheduleId = ScheduleId("robolectric-durable-queue-hybrid-reconcile-worker"),
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
     * `AndroidReferenceConsumerDurableQueueRobolectricTest`'s own private
     * helper of the same shape -- real wall clock, UUID-backed identifier
     * generators. Kept local to this file rather than reaching into that
     * file's private helper.
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
 * Test-only [QueuedSynchronizationWorkEncoder], identical in shape to
 * `AndroidReferenceConsumerHybridQueueRobolectricTest`'s own
 * `HybridPassthroughQueuedSynchronizationWorkEncoder` (`#325`/`#371`) -- no
 * byte serialization is needed because [QueueEntry] already carries
 * [QueueEntry.synchronizationRequest], [QueueEntry.strategyDecision], and
 * [QueueEntry.strategyPlan] as typed fields.
 */
private object HybridReconciliationPassthroughQueuedSynchronizationWorkEncoder : QueuedSynchronizationWorkEncoder {
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
private object HybridReconciliationNeverRetryPolicy : RetryPolicy {
    override val id: RetryPolicyId = RetryPolicyId("robolectric-durable-queue-hybrid-reconcile-never-retry")
    override fun evaluate(request: RetryEvaluationRequest): RetryDecision =
        RetryDecision.Stop(RetryStopReason.POLICY_REJECTED)
}

/**
 * Test-only [TransportProvider] that always returns [changeSet] from
 * [pullChanges] and counts calls -- proving durable admission does not
 * execute synchronously (zero calls immediately after admission) and that
 * the queue-worker replay genuinely invokes the real pipeline (exactly one
 * call after [DataLoom.queueWorker]'s `run(...)`). Push is not exercised by
 * this test and fails deterministically if ever called.
 */
private class CountingOneChangeSetHybridReconciliationTransportProvider(
    private val changeSet: ChangeSet,
) : TransportProvider {
    var pullCalls: Int = 0
        private set

    override val descriptor: ProviderDescriptor = ProviderDescriptor(
        id = ProviderId(
            "io.dataloom.consumer.android.test.counting-one-change-set-hybrid-reconciliation-transport",
        ),
        name = ProviderName("Counting One-Change-Set Hybrid Reconciliation Test Transport"),
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
        error("CountingOneChangeSetHybridReconciliationTransportProvider does not support push.")

    override suspend fun pullChanges(
        request: PullChangesRequest,
    ): ProviderOperationResult<PullChangesResult> {
        pullCalls++
        return ProviderOperationResult.Success(PullChangesResult.Changes(changeSet = changeSet, hasMore = false))
    }
}
