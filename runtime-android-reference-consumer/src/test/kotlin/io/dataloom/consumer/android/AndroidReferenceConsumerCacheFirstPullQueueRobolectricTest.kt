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
import io.dataloom.runtime.strategy.StrategyExecutionRejectionReason
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
 * Robolectric-backed runtime proof that cache-first's own PULL-direction
 * `SERVE_LOCAL`-refresh durable branch -- the one branch `#101`'s
 * market-readiness row (and `#337`'s own KDoc) documented as "genuinely not
 * exercisable against the real, unmodified `RoomStorageProvider`" -- is, once
 * checked branch-by-branch exactly as `#368`/`#371` re-checked remote-first
 * and hybrid, only *half* blocked: the branch's synchronous half is
 * genuinely blocked (confirmed here, not just repeated), but its durable
 * continuation is genuinely replayable and was never actually exercised.
 *
 * ## Investigation: re-verifying, not trusting, the prior "not exercisable" claim
 *
 * `BuiltInSynchronizationStrategyEvaluator.evaluateCacheFirst` reaches
 * `ENQUEUE_DURABLE_WORK` for PULL/BIDIRECTIONAL in exactly two places, both
 * structurally identical:
 *
 * 1. `StrategyCacheState.FRESH` with `profile.refreshOnFreshHit = true` --
 *    `operations = [SERVE_LOCAL] + refreshOperations(requireDurableRefresh)`.
 * 2. `StrategyCacheState.STALE` with `profile.staleCachePolicy =
 *    SERVE_STALE_AND_REFRESH` (**the profile default**) -- same
 *    `operations` shape.
 *
 * With `requireDurableRefresh = true` (**also the profile default**),
 * `refreshOperations` contributes `[ENQUEUE_DURABLE_WORK, SCHEDULE_REFRESH]`,
 * so both branches produce `operations = [SERVE_LOCAL, ENQUEUE_DURABLE_WORK,
 * SCHEDULE_REFRESH]` with disposition `SERVE_AND_REFRESH`. This test uses the
 * STALE branch with an otherwise **completely default** `CacheFirstStrategyProfile`
 * -- no field needs to be set away from its default to reach it, unlike the
 * FRESH variant which needs `refreshOnFreshHit = true` explicitly.
 *
 * `#337`'s KDoc correctly observed that `CacheFirstStrategyExecutor.serveLocal`
 * rejects this branch with `LOCAL_FALLBACK_PROVIDER_NOT_CONFIGURED`, since
 * `RoomStorageProvider` implements only `StorageProvider`, not
 * `StrategyLocalFallbackProvider`. That part is re-confirmed here, for real,
 * not just repeated -- [cacheFirstPullRefreshRejectsSynchronouslyButStillAdmitsDurably]
 * asserts the genuine `Rejected(LOCAL_FALLBACK_PROVIDER_NOT_CONFIGURED)`
 * outcome from a real `DataLoom.synchronize` call.
 *
 * But `#337`'s KDoc also stated that `AcceptedStrategyPlanExecutionCoordinator
 * .validateReplayProviders` "rejects any plan whose operations include
 * `SERVE_LOCAL`" -- conflating the *immediate* plan's `operations` (which do
 * include `SERVE_LOCAL`) with the *durable continuation*'s `operations`
 * (which do not). Reading `BuiltInSynchronizationStrategyEvaluator
 * .deriveDurableContinuation`'s `CacheFirstStrategyProfile` arm for
 * PULL/BIDIRECTIONAL precisely:
 *
 * ```
 * is CacheFirstStrategyProfile -> when (request.direction) {
 *     SynchronizationDirection.PUSH -> listOf(READ_LOCAL, PUSH_REMOTE)
 *     SynchronizationDirection.PULL,
 *     SynchronizationDirection.BIDIRECTIONAL,
 *     -> remoteOperations(request.direction, persistRemote = true)
 * }
 * ```
 *
 * For PULL this is `[READ_CHECKPOINT, PULL_REMOTE, PERSIST_REMOTE]` --
 * `SERVE_LOCAL` never appears in the continuation at all. The durable
 * refresh is designed to *replace* the synchronous local serve with a
 * genuine remote pull-and-persist, not to repeat it later. This is
 * byte-for-byte the same continuation shape `#325` (offline-first), `#368`
 * (remote-first), and `#371` (hybrid) already proved replayable.
 * `validateReplayProviders` (in `AcceptedStrategyPlanExecutionCoordinator`)
 * checks `continuation.fallbackPlan`/`continuation.operations` -- not the
 * original plan's `operations` -- and `deriveDurableContinuation`'s
 * `continuationFallback` `when` block only ever produces a non-null
 * `StrategyFallbackPlan` for `RemoteFirstStrategyProfile`, so cache-first's
 * continuation never carries one either. Neither `StrategyLocalFallbackProvider`
 * nor `StrategyReconciliationProvider` is required to replay this branch's
 * continuation -- only `STORAGE`/`TRANSPORT`, which the real, unmodified
 * `RoomStorageProvider`/test transport already satisfy.
 *
 * Separately, `CacheFirstStrategyExecutor.execute` processes
 * `ENQUEUE_DURABLE_WORK` *before* it attempts `SERVE_LOCAL`: it calls
 * `StrategyDurableQueueAdmitter.admit` first (a real, unconditional write to
 * the real `RoomQueueProvider` -- `StrategyQueueAdmissionEvaluator.evaluate`
 * never inspects `SERVE_LOCAL` or checks for `StrategyLocalFallbackProvider`,
 * only disposition/`ENQUEUE_DURABLE_WORK`/`QUEUE` capability/durable
 * continuation presence), and only *then* attempts `serveLocal`, which fails
 * and returns `Rejected` -- a result type with no `queueEntryId` field, so
 * the caller has no way to discover the entry's identifier from the return
 * value. The entry is nonetheless genuinely, durably persisted before that
 * failure.
 *
 * ## What this proves
 *
 * [cacheFirstPullRefreshRejectsSynchronouslyButStillAdmitsDurably] exercises
 * both halves of this branch honestly, through real, unmodified production
 * code:
 *
 * 1. A real `DataLoom.synchronize` call for a completely-default
 *    `CacheFirstStrategyProfile` PULL request against a `STALE` cache
 *    returns a genuine `Rejected(LOCAL_FALLBACK_PROVIDER_NOT_CONFIGURED)` --
 *    the synchronous half is confirmed, not assumed, still blocked. Zero
 *    transport calls occur.
 * 2. Despite that top-level rejection, exactly one real queue entry was
 *    durably admitted underneath it: [DataLoom.queueWorker]'s `run(...)`
 *    (which acquires *any* pending entries via a real `QueueProvider.acquire`
 *    call -- never by a caller-supplied identifier, so the discarded
 *    `queueEntryId` is not actually needed to find it) genuinely acquires
 *    that orphaned entry and completes it.
 * 3. `AcceptedStrategyPlanExecutionCoordinator` genuinely replays the
 *    persisted `durableContinuation` -- not `SERVE_LOCAL` at all, a real
 *    `InboundPullSynchronizationPipeline` run against real Room storage --
 *    reaching a genuine `SynchronizationResult.Succeeded`
 *    (`summary.inboundEventsApplied == 1`), observed via a real
 *    `SynchronizationObserver`, exactly the bar `#325`/`#368`/`#371`
 *    established for their own PULL continuations.
 *
 * The STALE+`SERVE_STALE_AND_REFRESH` branch exercised here and the
 * FRESH+`refreshOnFreshHit=true` branch share the exact same `operations`/
 * `deriveDurableContinuation` shape (only the cache-state guard differs), so
 * this proof covers both by inspection; BIDIRECTIONAL shares the same
 * continuation shape (`remoteOperations(BIDIRECTIONAL, persistRemote =
 * true)`) plus a leading `READ_LOCAL`/`PUSH_REMOTE` pair, also covered by
 * inspection rather than a separate test.
 *
 * ## What this does not prove
 *
 * A public API path that actually returns this durably-admitted entry's
 * `queueEntryId` to the caller (none exists today -- `Rejected` has no such
 * field; this is a real, narrow gap this investigation surfaced but is out
 * of scope to fix here); iOS; BIDIRECTIONAL directly (covered by inspection
 * above); retry/circuit-breaker/conflict-detection behavior during replay;
 * a real WorkManager-triggered background tick; a real managed-device
 * emulator (Robolectric only).
 */
@RunWith(RobolectricTestRunner::class)
class AndroidReferenceConsumerCacheFirstPullQueueRobolectricTest {

    @Test
    fun cacheFirstPullRefreshRejectsSynchronouslyButStillAdmitsDurably() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)

        val changeSet = ChangeSet(
            id = ChangeSetId("cache-first-pull-queue-change-set-1"),
            events = listOf(
                ChangeEvent(
                    id = ChangeEventId("cache-first-pull-queue-event-1"),
                    entity = EntityReference(
                        type = EntityType("cache-first-pull-queue-entity"),
                        id = EntityId("cache-first-pull-queue-entity-1"),
                    ),
                    operation = ChangeOperation.CREATE,
                ),
            ),
        )
        val transport = CountingOneChangeSetCacheFirstTransportProvider(changeSet)
        val completedResults = mutableListOf<SynchronizationResult>()
        val observer = object : SynchronizationObserver {
            override val id: SynchronizationObserverId =
                SynchronizationObserverId("durable-queue-cache-first-pull-replay-observer")
            override fun onEvent(event: SynchronizationEvent) {
                if (event is SynchronizationEvent.Completed) {
                    completedResults += event.result
                }
            }
        }

        val dataLoom = buildDurableQueueDataLoom(context, transport, observer)
        assertEquals(ProviderLifecycleResult.InitializeSuccess, dataLoom.initialize())

        // A completely default CacheFirstStrategyProfile: staleCachePolicy
        // defaults to SERVE_STALE_AND_REFRESH, requireDurableRefresh
        // defaults to true. No field is set away from its default to reach
        // this branch.
        val strategyRequest = StrategySynchronizationRequest(
            request = SynchronizationRequest(
                workflowId = WorkflowId("durable-queue-cache-first-pull-workflow-1"),
                sessionId = SynchronizationSessionId("durable-queue-cache-first-pull-session-1"),
                direction = SynchronizationDirection.PULL,
                mode = SynchronizationMode.FULL,
                context = ExecutionContext(
                    executionId = ExecutionId("durable-queue-cache-first-pull-execution-1"),
                    correlationId = CorrelationId("durable-queue-cache-first-pull-correlation-1"),
                ),
            ),
            decisionId = StrategyDecisionId("durable-queue-cache-first-pull-decision-1"),
            planId = StrategyPlanId("durable-queue-cache-first-pull-plan-1"),
            profile = CacheFirstStrategyProfile(
                id = StrategyProfileId("durable-queue-cache-first-pull-profile"),
                configurationVersion = StrategyConfigurationVersion(1L),
            ),
            evidence = StrategyRuntimeEvidence(
                connectivity = StrategyConnectivity.AVAILABLE,
                cacheState = StrategyCacheState.STALE,
            ),
            input = StrategyOperationInput.ProviderBacked,
        )

        // Step 1: the synchronous half is genuinely, honestly blocked -- not
        // Deferred/DurablyEnqueued with a discoverable queueEntryId, but a
        // real Rejected, confirming (not merely repeating) #337's finding.
        val admissionResult = dataLoom.synchronize(strategyRequest)
        val rejected = assertIs<StrategySynchronizationExecutionResult.Rejected>(admissionResult)
        assertEquals(
            StrategyExecutionRejectionReason.LOCAL_FALLBACK_PROVIDER_NOT_CONFIGURED,
            rejected.reason,
        )
        assertEquals(0, transport.pullCalls)
        assertEquals(0, completedResults.size)

        // Step 2 + 3: despite that top-level Rejected, one real queue entry
        // was durably admitted underneath it before the SERVE_LOCAL failure
        // -- CacheFirstStrategyExecutor.execute admits ENQUEUE_DURABLE_WORK
        // first, then attempts SERVE_LOCAL. queueWorker.run acquires ANY
        // pending entries (never by a caller-known identifier), so the
        // discarded queueEntryId is not needed to find it.
        val acquiredAt = DataLoomInstant(epochMilliseconds = System.currentTimeMillis())
        val runResult = dataLoom.queueWorker!!.run(
            QueueWorkerRunRequest(
                processingRequest = QueueProcessingRequest(
                    acquireRequest = QueueAcquireRequest(
                        consumerId = QueueConsumerId("robolectric-durable-queue-cache-first-pull-consumer"),
                        leaseId = QueueLeaseId("robolectric-durable-queue-cache-first-pull-lease"),
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

        // Step 4: the replay genuinely reached real storage via the real
        // durable continuation (never SERVE_LOCAL) -- a genuine
        // SynchronizationResult.Succeeded, not a placeholder.
        assertEquals(1, transport.pullCalls)
        assertEquals(1, completedResults.size)
        val succeeded = assertIs<SynchronizationResult.Succeeded>(completedResults.single())
        assertEquals(1L, succeeded.summary.inboundEventsApplied)

        assertEquals(ProviderLifecycleResult.ShutdownSuccess, dataLoom.shutdown())
    }

    /**
     * Assembles a real [DataLoom] instance from the exact same production
     * `dataloom-android` helpers every other reference-consumer test uses,
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
        val uniqueSuffix = UUID.randomUUID().toString().take(8)
        val providers = androidDataLoomProviders(
            context = context,
            storageDatabaseName = "dq-cfp-storage-$uniqueSuffix.db",
            queueDatabaseName = "dq-cfp-queue-$uniqueSuffix.db",
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
            .queueSubmissionEncoder(CacheFirstPullPassthroughQueuedSynchronizationWorkEncoder)
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
                    retryPolicy = CacheFirstPullNeverRetryPolicy,
                    retryOperation = RetryOperation("robolectric.durable-queue-cache-first-pull-replay"),
                    configuration = QueueWorkerConfiguration(
                        scheduleId = ScheduleId("robolectric-durable-queue-cache-first-pull-worker"),
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
private object CacheFirstPullPassthroughQueuedSynchronizationWorkEncoder : QueuedSynchronizationWorkEncoder {
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
private object CacheFirstPullNeverRetryPolicy : RetryPolicy {
    override val id: RetryPolicyId = RetryPolicyId("robolectric-durable-queue-cache-first-pull-never-retry")
    override fun evaluate(request: RetryEvaluationRequest): RetryDecision =
        RetryDecision.Stop(RetryStopReason.POLICY_REJECTED)
}

/**
 * Test-only [TransportProvider] that always returns [changeSet] from
 * [pullChanges] and counts calls -- proving the synchronous top-level
 * rejection genuinely never touched transport (zero calls immediately after
 * the `Rejected` result) and that the queue-worker replay genuinely invokes
 * the real pipeline (exactly one call after [DataLoom.queueWorker]'s
 * `run(...)`). Push is not exercised by this test and fails deterministically
 * if ever called.
 */
private class CountingOneChangeSetCacheFirstTransportProvider(
    private val changeSet: ChangeSet,
) : TransportProvider {
    var pullCalls: Int = 0
        private set

    override val descriptor: ProviderDescriptor = ProviderDescriptor(
        id = ProviderId("io.dataloom.consumer.android.test.counting-one-change-set-cache-first-pull-transport"),
        name = ProviderName("Counting One-Change-Set Cache-First Pull Test Transport"),
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
        error("CountingOneChangeSetCacheFirstTransportProvider does not support push.")

    override suspend fun pullChanges(
        request: PullChangesRequest,
    ): ProviderOperationResult<PullChangesResult> {
        pullCalls++
        return ProviderOperationResult.Success(PullChangesResult.Changes(changeSet = changeSet, hasMore = false))
    }
}
