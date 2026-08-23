package io.dataloom.consumer.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import io.dataloom.android.androidDataLoomProviders
import io.dataloom.android.installAndroidProviders
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
import io.dataloom.api.identifier.SynchronizationObserverId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
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
import io.dataloom.api.strategy.CacheFirstStrategyProfile
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
import io.dataloom.api.synchronization.SynchronizationSkipReason
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
 * Robolectric-backed runtime proof that a real, durably admitted **cache-first**
 * strategy plan survives being read back out of a real Room-backed queue and
 * is genuinely replayed by a real `DataLoomQueueWorker` cycle -- the second
 * built-in strategy `#101`'s market-readiness row named as sharing
 * `StrategyDurableQueueAdmitter` machinery with offline-first
 * ([AndroidReferenceConsumerDurableQueueRobolectricTest], `#325`) but
 * "unexercised at this admission-then-replay layer."
 *
 * ## Scope: one platform, one additional strategy
 *
 * This proves Android + cache-first only, and only its PUSH-direction
 * durable-deferral branch. iOS and the remaining built-in strategies
 * (network-only, remote-first, hybrid, adaptive) are explicitly deferred, not
 * silently claimed.
 *
 * ## Investigation: why cache-first's PUSH branch, not its documented PULL refresh branch
 *
 * `docs/api/cache-first-strategy-execution.md` documents cache-first's
 * durable-refresh branch as a PULL/BIDIRECTIONAL `SERVE_LOCAL` +
 * `ENQUEUE_DURABLE_WORK` shape (`requireDurableRefresh = true`, cache
 * `FRESH`/`STALE`). That branch is genuinely **not** exercisable against the
 * real production Android storage provider: `RoomStorageProvider` does not
 * implement `StrategyLocalFallbackProvider` (confirmed by reading
 * `dataloom-storage-room`'s `RoomStorageProvider.kt` -- it implements only
 * `StorageProvider`), and both `CacheFirstStrategyExecutor.serveLocal` and
 * `AcceptedStrategyPlanExecutionCoordinator.validateReplayProviders` reject
 * any plan whose operations include `SERVE_LOCAL` with
 * `LOCAL_FALLBACK_PROVIDER_NOT_CONFIGURED` when the resolved storage provider
 * doesn't implement that capability -- exactly the same real constraint
 * `#325`'s own KDoc already documented as its reason for avoiding
 * `SERVE_LOCAL` entirely.
 *
 * `BuiltInSynchronizationStrategyEvaluator.evaluateCacheFirstPush` reaches a
 * *second*, separate `ENQUEUE_DURABLE_WORK` branch that the docs page does
 * not mention: for `SynchronizationDirection.PUSH` with connectivity
 * `UNAVAILABLE` and `requireDurableRefresh = true` (the profile default), the
 * plan is `StrategyDisposition.DEFER` with `operations = [READ_LOCAL,
 * ENQUEUE_DURABLE_WORK]`, and `deriveDurableContinuation` freezes the
 * continuation as exactly `[READ_LOCAL, PUSH_REMOTE]` -- no `SERVE_LOCAL`,
 * no `RECONCILE`. This is `AcceptedStrategyPlanExecutionCoordinator`'s
 * supported PUSH replay shape (`READ_LOCAL, PUSH_REMOTE`), and
 * `validateReplayProviders` only requires `StrategyLocalFallbackProvider`
 * when `SERVE_LOCAL` or a `fallbackPlan` is present -- neither is true here.
 * It is therefore the **only** cache-first branch that reaches
 * `ENQUEUE_DURABLE_WORK` while staying compatible with the real, unmodified
 * `RoomStorageProvider` -- the cache-first analogue of `#325`'s own MISSING
 * -cache/no-`SERVE_LOCAL` choice for offline-first.
 *
 * (Hybrid's own durable branches were also investigated and are worse
 * candidates than cache-first for this same reason: hybrid's PULL/
 * BIDIRECTIONAL fallback branch requires `SERVE_LOCAL`, and hybrid's PUSH
 * fallback branch's continuation always ends in `RECONCILE`, which requires
 * `StrategyReconciliationProvider` -- another capability `RoomStorageProvider`
 * does not implement. No pivot to hybrid was necessary because cache-first's
 * PUSH branch is genuinely replayable today.)
 *
 * ## What this proves
 *
 * [cacheFirstPushDeferralReplayedByQueueWorker] exercises the full
 * path, entirely through real, production DataLoom code:
 *
 * 1. **Admission, not synchronous execution.** [DataLoom.synchronize] for a
 *    `StrategySynchronizationRequest` built from [CacheFirstStrategyProfile]
 *    (`requireDurableRefresh = true`, direction `PUSH`, connectivity
 *    `UNAVAILABLE`) returns [StrategySynchronizationExecutionResult.Deferred]
 *    with a real, non-null `queueEntryId` -- `StrategyDurableQueueAdmitter`
 *    durably persists the accepted plan via the real
 *    [io.dataloom.queue.room.RoomQueueProvider] instead of running the
 *    pipeline synchronously. The test-only transport records zero
 *    `pushChanges` calls at this point, proving nothing executed yet.
 * 2. **Survives being read back out.** The queue entry is not merely
 *    written; step 3 acquires it back from the same real Room database via a
 *    genuine `QueueProvider.acquire` call inside the queue-worker cycle.
 * 3. **A queue worker genuinely replays it.** [DataLoom.queueWorker]'s single
 *    `run(...)` call deterministically drives exactly one bounded
 *    acquire/execute/complete cycle. `AcceptedStrategyPlanExecutionCoordinator`
 *    resolves the real storage/transport providers from the queue entry's own
 *    persisted bindings and runs the real, registered
 *    `OutboundPushSynchronizationPipeline`, which genuinely calls
 *    `RoomStorageProvider.readOutboundChanges` against the real Room
 *    database.
 * 4. **The result is genuinely observable.** A [SynchronizationObserver]
 *    registered through the real [DataLoomBuilder.observer] capability
 *    captures the terminal [SynchronizationEvent.Completed] emitted by that
 *    replay. [QueueProcessingResult.Processed.summary.completed] is asserted
 *    as one additional layer of evidence that the entry reached a real,
 *    durable terminal transition.
 *
 * ## Why the terminal result is `Skipped(NO_CHANGES)`, not an applied/pushed count
 *
 * Unlike `#325`'s offline-first proof (where the pulled `ChangeSet` is
 * caller-supplied through the test `TransportProvider`, so
 * `inboundEventsApplied == 1` is a genuine, non-trivial assertion),
 * cache-first's only real-provider-compatible durable branch is
 * PUSH-direction, and outbound content is *read from local storage*, not
 * supplied by the transport. `StorageProvider` -- the contract
 * `RoomStorageProvider` implements -- exposes `readOutboundChanges` and
 * `acknowledgeOutboundChanges` but genuinely has **no public API to seed
 * local pending outbound changes**: `OutboundChangeDao.appendChangeSet` and
 * the DAO itself are `internal` to `dataloom-storage-room` and are not
 * reachable from this module, and no `StorageProvider`/`DataLoom` facade
 * method writes to the outbound table at all. This looks like a genuine,
 * previously-undocumented gap in the current storage-provider contract, not
 * a shortcut chosen for convenience -- flagged for follow-up rather than
 * silently worked around with reflection or an invented test-only bypass.
 *
 * Given that constraint, this test's real, honestly-observed terminal state
 * is [SynchronizationResult.Skipped] with
 * [SynchronizationSkipReason.NO_CHANGES] -- a real Room query against a real,
 * empty outbound table, correctly finding nothing to push. Every structural
 * step above (durable admission, real persistence, real read-back, a real
 * one-cycle queue-worker replay through the real production coordinator
 * chain, and a real terminal event observed through the same observer wiring
 * `#325`'s proof used) is still genuinely exercised; only the "was there
 * content to move" bar is necessarily weaker than `#325`'s, for the reason
 * documented above. `transport.pushCalls` staying `0` throughout is asserted
 * as confirmation that the pipeline made a real, correct decision from a real
 * (empty) storage read -- not a stub standing in for one.
 *
 * ## What this does not prove
 *
 * iOS; the remaining four built-in strategies' own admission branches
 * (network-only cannot admit durable work at all; remote-first, hybrid, and
 * adaptive remain unexercised at this layer); a genuine non-empty outbound
 * push payload flowing through real storage (see above); retry,
 * circuit-breaker, or conflict-detection behavior during queue replay (this
 * entry always succeeds on its first attempt); a real WorkManager-triggered
 * background tick (this test calls `queueWorker.run(...)` directly,
 * deterministically); and a real managed-device emulator (Robolectric only).
 */
@RunWith(RobolectricTestRunner::class)
class AndroidReferenceConsumerCacheFirstQueueRobolectricTest {

    @Test
    fun cacheFirstPushDeferralReplayedByQueueWorker() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)

        val transport = CountingPushTransportProvider()
        val completedResults = mutableListOf<SynchronizationResult>()
        val observer = object : SynchronizationObserver {
            override val id: SynchronizationObserverId = SynchronizationObserverId("durable-queue-cache-first-replay-observer")
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
                workflowId = WorkflowId("durable-queue-cache-first-workflow-1"),
                sessionId = SynchronizationSessionId("durable-queue-cache-first-session-1"),
                direction = SynchronizationDirection.PUSH,
                mode = SynchronizationMode.FULL,
                context = ExecutionContext(
                    executionId = ExecutionId("durable-queue-cache-first-execution-1"),
                    correlationId = CorrelationId("durable-queue-cache-first-correlation-1"),
                ),
            ),
            decisionId = StrategyDecisionId("durable-queue-cache-first-decision-1"),
            planId = StrategyPlanId("durable-queue-cache-first-plan-1"),
            profile = CacheFirstStrategyProfile(
                id = StrategyProfileId("durable-queue-cache-first-profile"),
                configurationVersion = StrategyConfigurationVersion(1L),
                requireDurableRefresh = true,
            ),
            evidence = StrategyRuntimeEvidence(
                connectivity = StrategyConnectivity.UNAVAILABLE,
                cacheState = StrategyCacheState.MISSING,
            ),
            input = StrategyOperationInput.ProviderBacked,
        )

        // Step 1: durable admission -- must NOT execute synchronously.
        val admissionResult = dataLoom.synchronize(strategyRequest)
        val deferred = assertIs<StrategySynchronizationExecutionResult.Deferred>(admissionResult)
        assertNotNull(deferred.queueEntryId)
        assertEquals(0, transport.pushCalls)
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
                        consumerId = QueueConsumerId("robolectric-durable-queue-cache-first-consumer"),
                        leaseId = QueueLeaseId("robolectric-durable-queue-cache-first-lease"),
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

        // Step 4: the replay genuinely reached a real terminal transition,
        // observed through the same production observer wiring #325's proof
        // used. See the class KDoc for why Skipped(NO_CHANGES) -- not an
        // applied/pushed count -- is the honest terminal state here.
        assertEquals(1, completedResults.size)
        val skipped = assertIs<SynchronizationResult.Skipped>(completedResults.single())
        assertEquals(SynchronizationSkipReason.NO_CHANGES, skipped.reason)
        assertEquals(0, transport.pushCalls)

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
        // A short (not full-UUID) suffix keeps the resulting database file
        // path well under Windows' MAX_PATH limit -- see #325's identical
        // comment for the same real, local, path-length constraint.
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
            .queueSubmissionEncoder(CacheFirstPassthroughQueuedSynchronizationWorkEncoder)
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
                    retryPolicy = CacheFirstNeverRetryPolicy,
                    retryOperation = RetryOperation("robolectric.durable-queue-cache-first-replay"),
                    configuration = QueueWorkerConfiguration(
                        scheduleId = ScheduleId("robolectric-durable-queue-cache-first-worker"),
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
     * generators. Kept local to this test rather than reaching into that
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
 * `CacheFirstPassthroughQueuedSynchronizationWorkEncoder` (`#325`) -- no byte
 * serialization is needed because [QueueEntry] already carries
 * [QueueEntry.synchronizationRequest], [QueueEntry.strategyDecision], and
 * [QueueEntry.strategyPlan] as typed fields.
 */
private object CacheFirstPassthroughQueuedSynchronizationWorkEncoder : QueuedSynchronizationWorkEncoder {
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
private object CacheFirstNeverRetryPolicy : RetryPolicy {
    override val id: RetryPolicyId = RetryPolicyId("robolectric-durable-queue-cache-first-never-retry")
    override fun evaluate(request: RetryEvaluationRequest): RetryDecision =
        RetryDecision.Stop(RetryStopReason.POLICY_REJECTED)
}

/**
 * Test-only [TransportProvider] that counts [pushChanges] calls and fails
 * deterministically if [pullChanges] is ever called -- this test exercises
 * the PUSH direction only. See the containing test class's KDoc for why
 * [pushChanges] is expected to be called zero times: the real
 * [io.dataloom.storage.room.RoomStorageProvider]'s outbound table is
 * genuinely empty (no public API exists to seed it), so the real
 * `OutboundPushSynchronizationPipeline` correctly never reaches the
 * transport at all.
 */
private class CountingPushTransportProvider : TransportProvider {
    var pushCalls: Int = 0
        private set

    override val descriptor: ProviderDescriptor = ProviderDescriptor(
        id = ProviderId("io.dataloom.consumer.android.test.counting-push-transport"),
        name = ProviderName("Counting Push Test Transport"),
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
    ): ProviderOperationResult<ChangeSetAcknowledgement> {
        pushCalls++
        error(
            "CountingPushTransportProvider.pushChanges should never be called: the real " +
                "RoomStorageProvider outbound table is empty for this test, so the real " +
                "OutboundPushSynchronizationPipeline should short-circuit to Skipped(NO_CHANGES) " +
                "before ever reaching the transport.",
        )
    }

    override suspend fun pullChanges(
        request: PullChangesRequest,
    ): ProviderOperationResult<PullChangesResult> =
        error("CountingPushTransportProvider does not support pull.")
}
