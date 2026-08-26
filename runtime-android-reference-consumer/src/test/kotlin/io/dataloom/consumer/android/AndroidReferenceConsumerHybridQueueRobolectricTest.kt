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
import io.dataloom.api.strategy.UnknownConnectivityPolicy
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
import kotlin.test.assertNotNull

/**
 * Robolectric-backed runtime proof that a real, durably admitted **hybrid**
 * strategy plan survives being read back out of a real Room-backed queue and
 * is genuinely replayed by a real `DataLoomQueueWorker` cycle -- closing the
 * gap `#101`'s market-readiness row named as "hybrid still shares the same
 * `StrategyDurableQueueAdmitter` machinery but remains genuinely blocked on
 * both platforms."
 *
 * ## Investigation: why hybrid's connectivity-UNKNOWN branch, not its
 * "explicit fallback from REMOTE" branch
 *
 * `BuiltInSynchronizationStrategyEvaluator.evaluateHybrid` produces
 * `ENQUEUE_DURABLE_WORK` in exactly two places, and this test's own
 * investigation (mirroring `#368`'s remote-first methodology precisely)
 * checked both:
 *
 * 1. **`HybridSource.LOCAL` selected as an explicit fallback from a `REMOTE`
 *    primary, `reconcileAfterFallback = true`.** `evaluateHybrid`'s own
 *    `operations` assembly adds `ENQUEUE_DURABLE_WORK` and `RECONCILE`
 *    together, unconditionally, in the same `also { }` block -- there is no
 *    way to reach this branch's `ENQUEUE_DURABLE_WORK` without also getting
 *    `RECONCILE`. `deriveDurableContinuation`'s `HybridStrategyProfile` arm
 *    mirrors that: it appends `RECONCILE` to the continuation whenever
 *    `profile.reconcileAfterFallback` is `true`. A `RECONCILE` continuation
 *    operation requires `StrategyReconciliationProvider`
 *    (`AcceptedStrategyPlanExecutionCoordinator.validateReplayProviders`),
 *    which the real `RoomStorageProvider` does not implement. This is the
 *    branch `docs/api/hybrid-strategy-execution.md` and the prior
 *    market-readiness investigation already correctly documented as blocked
 *    -- confirmed accurate, not stale, for this specific branch.
 *
 * 2. **Connectivity `UNKNOWN`/`NOT_EVALUATED` with `primarySource =
 *    HybridSource.REMOTE` and `unknownConnectivityPolicy != ATTEMPT_REMOTE`.**
 *    `evaluateHybrid` routes this through the exact same
 *    `unknownConnectivityResult` helper `evaluateRemoteFirst`/
 *    `evaluateNetworkOnly` already use. With `unknownConnectivityPolicy =
 *    DEFER`, the plan is `StrategyDisposition.DEFER` with `operations =
 *    [ENQUEUE_DURABLE_WORK]` only -- no `SERVE_LOCAL`, no `RECONCILE`.
 *    `deriveDurableContinuation`'s `HybridStrategyProfile` arm then freezes
 *    the continuation as `remoteOperations(direction, persistRemote =
 *    profile.persistRemoteResult)`, appending `RECONCILE` **only if**
 *    `profile.reconcileAfterFallback` is `true`. With
 *    `reconcileAfterFallback = false` (not this profile's default), the
 *    continuation is exactly `remoteOperations(...)` -- for PULL with the
 *    default `persistRemoteResult = true`, `[READ_CHECKPOINT, PULL_REMOTE,
 *    PERSIST_REMOTE]`, byte-for-byte the same shape `#325`'s offline-first
 *    and `#368`'s remote-first proofs already exercise. Separately,
 *    `deriveDurableContinuation`'s `continuationFallback` `when` block only
 *    ever produces a non-null `StrategyFallbackPlan` for
 *    `RemoteFirstStrategyProfile` -- its `else` branch (which `Hybrid`
 *    profiles fall into) is always `null`, so hybrid's durable continuation
 *    *never* carries a `fallbackPlan` under any field combination, unlike
 *    remote-first. `AcceptedStrategyPlanExecutionCoordinator
 *    .validateReplayProviders` therefore requires neither
 *    `StrategyLocalFallbackProvider` nor `StrategyReconciliationProvider` for
 *    this branch -- only `STORAGE`/`TRANSPORT`, which the real, unmodified
 *    `RoomStorageProvider`/test transport already satisfy.
 *
 * This second branch was never previously considered: it does not appear in
 * `docs/api/hybrid-strategy-execution.md`'s branch table (which only
 * documents the `REMOTE`/`LOCAL`-selection branches, the transport-free
 * PUSH branch, and the explicit-fallback-with-reconciliation branch), and
 * `#368`'s own remote-first KDoc explicitly deferred re-investigating hybrid
 * ("Hybrid was not reconsidered here"). It is structurally identical to the
 * gap `#368` found in remote-first: an existing investigation checked the
 * strategy's "typed fallback" branch and correctly found it blocked, but
 * never checked the strategy's separate connectivity-unknown `DEFER` branch,
 * which turns out to be the one exercisable path.
 *
 * ## What this proves
 *
 * [hybridDeferralReplayedByQueueWorker] exercises the same four-step real,
 * production path `#325`/`#337`/`#368` established: durable admission with
 * zero transport calls and a real `queueEntryId` (never synchronous
 * execution); the entry surviving a real read-back out of the Room-backed
 * queue; one deterministic `DataLoom.queueWorker.run(...)` cycle genuinely
 * driving `AcceptedStrategyPlanExecutionCoordinator` and the real,
 * registered `InboundPullSynchronizationPipeline` against real Room storage;
 * and a genuinely observed `SynchronizationResult.Succeeded`
 * (`summary.inboundEventsApplied == 1`) via a real `SynchronizationObserver`.
 *
 * ## What this does not prove
 *
 * iOS (no equivalent Apple proof of this branch exists yet); hybrid's other
 * durable branch (`reconcileAfterFallback = true`'s explicit-fallback path),
 * which remains genuinely blocked on `StrategyReconciliationProvider` for
 * the reasons documented above; network-only (cannot admit durable work at
 * all) and adaptive (no branch of its own); retry, circuit-breaker, or
 * conflict-detection behavior during queue replay (this entry always
 * succeeds on its first attempt); a real WorkManager-triggered background
 * tick (this test calls `queueWorker.run(...)` directly, deterministically);
 * and a real managed-device emulator (Robolectric only).
 */
@RunWith(RobolectricTestRunner::class)
class AndroidReferenceConsumerHybridQueueRobolectricTest {

    @Test
    fun hybridDeferralReplayedByQueueWorker() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)

        val changeSet = ChangeSet(
            id = ChangeSetId("hybrid-queue-change-set-1"),
            events = listOf(
                ChangeEvent(
                    id = ChangeEventId("hybrid-queue-event-1"),
                    entity = EntityReference(
                        type = EntityType("hybrid-queue-entity"),
                        id = EntityId("hybrid-queue-entity-1"),
                    ),
                    operation = ChangeOperation.CREATE,
                ),
            ),
        )
        val transport = CountingOneChangeSetHybridTransportProvider(changeSet)
        val completedResults = mutableListOf<SynchronizationResult>()
        val observer = object : SynchronizationObserver {
            override val id: SynchronizationObserverId =
                SynchronizationObserverId("durable-queue-hybrid-replay-observer")
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
                workflowId = WorkflowId("durable-queue-hybrid-workflow-1"),
                sessionId = SynchronizationSessionId("durable-queue-hybrid-session-1"),
                direction = SynchronizationDirection.PULL,
                mode = SynchronizationMode.FULL,
                context = ExecutionContext(
                    executionId = ExecutionId("durable-queue-hybrid-execution-1"),
                    correlationId = CorrelationId("durable-queue-hybrid-correlation-1"),
                ),
            ),
            decisionId = StrategyDecisionId("durable-queue-hybrid-decision-1"),
            planId = StrategyPlanId("durable-queue-hybrid-plan-1"),
            profile = HybridStrategyProfile(
                id = StrategyProfileId("durable-queue-hybrid-profile"),
                configurationVersion = StrategyConfigurationVersion(1L),
                primarySource = HybridSource.REMOTE,
                fallbackSource = HybridSource.LOCAL,
                persistRemoteResult = true,
                reconcileAfterFallback = false,
                unknownConnectivityPolicy = UnknownConnectivityPolicy.DEFER,
            ),
            evidence = StrategyRuntimeEvidence(
                connectivity = StrategyConnectivity.UNKNOWN,
                cacheState = StrategyCacheState.MISSING,
            ),
            input = StrategyOperationInput.ProviderBacked,
        )

        // Step 1: durable admission -- must NOT execute synchronously.
        val admissionResult = dataLoom.synchronize(strategyRequest)
        val deferred = assertIs<StrategySynchronizationExecutionResult.Deferred>(admissionResult)
        assertNotNull(deferred.queueEntryId)
        assertEquals(0, transport.pullCalls)
        assertEquals(0, completedResults.size)

        // Step 2 + 3: acquire the durably persisted entry back out of the
        // real Room queue database and replay it via exactly one
        // deterministic queue-worker cycle. See
        // AndroidReferenceConsumerDurableQueueRobolectricTest for why
        // acquiredAt must be a real wall-clock "now".
        val acquiredAt = DataLoomInstant(epochMilliseconds = System.currentTimeMillis())
        val runResult = dataLoom.queueWorker!!.run(
            QueueWorkerRunRequest(
                processingRequest = QueueProcessingRequest(
                    acquireRequest = QueueAcquireRequest(
                        consumerId = QueueConsumerId("robolectric-durable-queue-hybrid-consumer"),
                        leaseId = QueueLeaseId("robolectric-durable-queue-hybrid-lease"),
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
        // inbound event reached real storage, not just a "succeeded" queue
        // outcome.
        assertEquals(1, transport.pullCalls)
        assertEquals(1, completedResults.size)
        val succeeded = assertIs<SynchronizationResult.Succeeded>(completedResults.single())
        assertEquals(1L, succeeded.summary.inboundEventsApplied)

        assertEquals(ProviderLifecycleResult.ShutdownSuccess, dataLoom.shutdown())
    }

    /**
     * Assembles a real [DataLoom] instance from the exact same production
     * `dataloom-android` helpers [androidDataLoomProviders]/
     * [installAndroidProviders] every other reference-consumer test uses,
     * additionally opting into [DataLoomBuilder.queueSubmissionEncoder] and
     * [DataLoomBuilder.queueWorkerConfiguration] -- see
     * [AndroidReferenceConsumerDurableQueueRobolectricTest.buildDurableQueueDataLoom]
     * (`#325`) for why these two capabilities require explicit test-side
     * wiring.
     */
    private fun buildDurableQueueDataLoom(
        context: Context,
        transportProvider: TransportProvider,
        observer: SynchronizationObserver,
    ): DataLoom {
        // See AndroidReferenceConsumerDurableQueueRobolectricTest for why a
        // short (not full-UUID) suffix is used for the database file names.
        val uniqueSuffix = UUID.randomUUID().toString().take(8)
        val providers = androidDataLoomProviders(
            context = context,
            storageDatabaseName = "dq-hy-storage-$uniqueSuffix.db",
            queueDatabaseName = "dq-hy-queue-$uniqueSuffix.db",
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
            .queueSubmissionEncoder(HybridPassthroughQueuedSynchronizationWorkEncoder)
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
                    retryPolicy = HybridNeverRetryPolicy,
                    retryOperation = RetryOperation("robolectric.durable-queue-hybrid-replay"),
                    configuration = QueueWorkerConfiguration(
                        scheduleId = ScheduleId("robolectric-durable-queue-hybrid-worker"),
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
     * [AndroidReferenceConsumerDurableQueueRobolectricTest]'s own private
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
 * `AndroidReferenceConsumerDurableQueueRobolectricTest`'s own
 * `PassthroughQueuedSynchronizationWorkEncoder` (`#325`) -- no byte
 * serialization is needed because [QueueEntry] already carries
 * [QueueEntry.synchronizationRequest], [QueueEntry.strategyDecision], and
 * [QueueEntry.strategyPlan] as typed fields.
 */
private object HybridPassthroughQueuedSynchronizationWorkEncoder : QueuedSynchronizationWorkEncoder {
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
private object HybridNeverRetryPolicy : RetryPolicy {
    override val id: RetryPolicyId = RetryPolicyId("robolectric-durable-queue-hybrid-never-retry")
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
private class CountingOneChangeSetHybridTransportProvider(
    private val changeSet: ChangeSet,
) : TransportProvider {
    var pullCalls: Int = 0
        private set

    override val descriptor: ProviderDescriptor = ProviderDescriptor(
        id = ProviderId("io.dataloom.consumer.android.test.counting-one-change-set-hybrid-transport"),
        name = ProviderName("Counting One-Change-Set Hybrid Test Transport"),
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
        error("CountingOneChangeSetHybridTransportProvider does not support push.")

    override suspend fun pullChanges(
        request: PullChangesRequest,
    ): ProviderOperationResult<PullChangesResult> {
        pullCalls++
        return ProviderOperationResult.Success(PullChangesResult.Changes(changeSet = changeSet, hasMore = false))
    }
}
