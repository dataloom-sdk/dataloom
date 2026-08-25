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
import io.dataloom.api.strategy.RemoteFirstStrategyProfile
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
 * Robolectric-backed runtime proof that a real, durably admitted **remote-first**
 * strategy plan survives being read back out of a real Room-backed queue and is
 * genuinely replayed by a real `DataLoomQueueWorker` cycle -- the third built-in
 * strategy `#101`'s market-readiness row named as sharing
 * `StrategyDurableQueueAdmitter` machinery with offline-first
 * ([AndroidReferenceConsumerDurableQueueRobolectricTest], `#325`) and cache-first
 * ([AndroidReferenceConsumerCacheFirstQueueRobolectricTest], `#337`/`#342`) but
 * "unexercised at this admission-then-replay layer."
 *
 * ## Scope: one platform, one additional strategy
 *
 * This proves Android + remote-first only, and only its connectivity-`UNKNOWN`
 * durable-defer branch. iOS and the remaining built-in strategies (network-only,
 * cache-first's PULL refresh branch, hybrid, adaptive) are explicitly deferred,
 * not silently claimed.
 *
 * ## Investigation: why remote-first's connectivity-UNKNOWN branch, not its
 * connectivity-UNAVAILABLE local-fallback branch
 *
 * `BuiltInSynchronizationStrategyEvaluator.evaluateRemoteFirst`'s
 * connectivity-`UNAVAILABLE` branch (`profile.fallbackOn` containing
 * `UNAVAILABLE` plus a fresh/stale cache) reaches `EXECUTE` with
 * `localFallbackOperations` -- `SERVE_LOCAL` for PULL -- immediately,
 * synchronously, with **no** `ENQUEUE_DURABLE_WORK` at all. It never durably
 * admits anything, so it cannot be this proof's branch.
 *
 * The genuinely durable branch is reached only through
 * `unknownConnectivityResult`: when `request.evidence.connectivity` is
 * `UNKNOWN` (or `NOT_EVALUATED`) and
 * `RemoteFirstStrategyProfile.unknownConnectivityPolicy = DEFER` (not the
 * default `ATTEMPT_REMOTE`), the plan is `StrategyDisposition.DEFER` with
 * `operations = [ENQUEUE_DURABLE_WORK]`, and `deriveDurableContinuation`
 * freezes the continuation for a `RemoteFirstStrategyProfile` as
 * `remoteOperations(direction, persistRemote = profile.persistRemoteResult)`
 * -- for PULL with the default `persistRemoteResult = true`, exactly
 * `[READ_CHECKPOINT, PULL_REMOTE, PERSIST_REMOTE]`. This is byte-for-byte the
 * same continuation shape `#325`'s offline-first proof already exercises, and
 * `AcceptedStrategyPlanExecutionCoordinator.validateReplayPlanStructure`
 * accepts it as one of its supported PULL sequences.
 *
 * Critically, `deriveDurableContinuation`'s own `continuationFallback` for a
 * `RemoteFirstStrategyProfile` is `remoteFallbackPlan(profile, direction)`,
 * which returns `null` whenever `profile.fallbackOn.isEmpty()` --
 * `StrategyDurableContinuationEvaluationTest.remoteFirstUnknownConnectivityFreezesTypedFallbackBranch`
 * (`dataloom-runtime`'s own commonTest,
 * `io.dataloom.runtime.strategy.StrategyDurableContinuationEvaluationTest`)
 * exercises the *other* shape, with
 * `fallbackOn = setOf(UNAVAILABLE)`, which freezes a non-null `fallbackPlan`
 * and therefore requires `SERVE_LOCAL`/`StrategyLocalFallbackProvider` at
 * replay -- exactly the real capability gap `RoomStorageProvider` does not
 * implement, and the same reason offline-first's own KDoc (`#325`) and
 * cache-first's own KDoc (`#337`) both avoided a `SERVE_LOCAL`-carrying
 * branch. This test instead uses the profile's default `fallbackOn =
 * emptySet()`, so `remoteFallbackPlan` returns `null`,
 * `continuation.fallbackPlan` is `null`, and
 * `AcceptedStrategyPlanExecutionCoordinator.validateReplayProviders` never
 * requires `StrategyLocalFallbackProvider` at all -- the remote-first analogue
 * of offline-first's own MISSING-cache/no-`SERVE_LOCAL` choice and
 * cache-first's own PUSH-branch choice. No `RECONCILE` is ever present either
 * (only offline-first's and hybrid's continuations can carry it), so
 * `StrategyReconciliationProvider` is never required. This is therefore the
 * **only** remote-first branch that reaches `ENQUEUE_DURABLE_WORK` while
 * staying compatible with the real, unmodified `RoomStorageProvider`.
 *
 * (Hybrid was not reconsidered here -- `#337`'s own KDoc already investigated
 * and rejected hybrid's durable branches for this same real-storage-provider
 * reason, and this test's job was only to determine remote-first's status.)
 *
 * ## What this proves
 *
 * [remoteFirstDeferralReplayedByQueueWorker] exercises the
 * full path, entirely through real, production DataLoom code:
 *
 * 1. **Admission, not synchronous execution.** [DataLoom.synchronize] for a
 *    `StrategySynchronizationRequest` built from [RemoteFirstStrategyProfile]
 *    (`unknownConnectivityPolicy = DEFER`, `fallbackOn` empty, direction
 *    `PULL`, connectivity `UNKNOWN`) returns
 *    [StrategySynchronizationExecutionResult.Deferred] with a real, non-null
 *    `queueEntryId` -- `StrategyDurableQueueAdmitter` durably persists the
 *    accepted plan via the real [io.dataloom.queue.room.RoomQueueProvider]
 *    instead of running the pipeline synchronously. The test-only transport
 *    records zero `pullChanges` calls at this point, proving nothing executed
 *    yet.
 * 2. **Survives being read back out.** The queue entry is not merely
 *    written; step 3 acquires it back from the same real Room database via a
 *    genuine `QueueProvider.acquire` call inside the queue-worker cycle.
 * 3. **A queue worker genuinely replays it.** [DataLoom.queueWorker]'s single
 *    `run(...)` call deterministically drives exactly one bounded
 *    acquire/execute/complete cycle. `AcceptedStrategyPlanExecutionCoordinator`
 *    resolves the real storage/transport providers from the queue entry's own
 *    persisted bindings and runs the real, registered
 *    `InboundPullSynchronizationPipeline` against the real Room database and
 *    the test transport.
 * 4. **The result is genuinely observable.** A [SynchronizationObserver]
 *    registered through the real [DataLoomBuilder.observer] capability
 *    captures the terminal [SynchronizationEvent.Completed] emitted by that
 *    replay and asserts `summary.inboundEventsApplied == 1` -- the same bar
 *    `#325`'s offline-first proof established.
 *    [QueueProcessingResult.Processed.summary.completed] is asserted as one
 *    additional layer of evidence that the entry reached a real, durable
 *    terminal transition.
 *
 * ## What this does not prove
 *
 * iOS (no equivalent Apple proof of this branch exists yet); remote-first's
 * own `fallbackOn`-carrying durable branch (requires
 * `StrategyLocalFallbackProvider`, which `RoomStorageProvider` does not
 * implement -- see the investigation above); the remaining three built-in
 * strategies' own admission branches not yet covered by this session
 * (network-only cannot admit durable work at all; cache-first's PULL refresh
 * branch and hybrid remain unexercised at this layer, both for the same
 * `SERVE_LOCAL`/`RECONCILE` capability-gap reasons); retry, circuit-breaker,
 * or conflict-detection behavior during queue replay (this entry always
 * succeeds on its first attempt); a real WorkManager-triggered background
 * tick (this test calls `queueWorker.run(...)` directly, deterministically);
 * and a real managed-device emulator (Robolectric only).
 */
@RunWith(RobolectricTestRunner::class)
class AndroidReferenceConsumerRemoteFirstQueueRobolectricTest {

    @Test
    fun remoteFirstDeferralReplayedByQueueWorker() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)

        val changeSet = ChangeSet(
            id = ChangeSetId("remote-first-queue-change-set-1"),
            events = listOf(
                ChangeEvent(
                    id = ChangeEventId("remote-first-queue-event-1"),
                    entity = EntityReference(
                        type = EntityType("remote-first-queue-entity"),
                        id = EntityId("remote-first-queue-entity-1"),
                    ),
                    operation = ChangeOperation.CREATE,
                ),
            ),
        )
        val transport = CountingOneChangeSetRemoteFirstTransportProvider(changeSet)
        val completedResults = mutableListOf<SynchronizationResult>()
        val observer = object : SynchronizationObserver {
            override val id: SynchronizationObserverId =
                SynchronizationObserverId("durable-queue-remote-first-replay-observer")
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
                workflowId = WorkflowId("durable-queue-remote-first-workflow-1"),
                sessionId = SynchronizationSessionId("durable-queue-remote-first-session-1"),
                direction = SynchronizationDirection.PULL,
                mode = SynchronizationMode.FULL,
                context = ExecutionContext(
                    executionId = ExecutionId("durable-queue-remote-first-execution-1"),
                    correlationId = CorrelationId("durable-queue-remote-first-correlation-1"),
                ),
            ),
            decisionId = StrategyDecisionId("durable-queue-remote-first-decision-1"),
            planId = StrategyPlanId("durable-queue-remote-first-plan-1"),
            profile = RemoteFirstStrategyProfile(
                id = StrategyProfileId("durable-queue-remote-first-profile"),
                configurationVersion = StrategyConfigurationVersion(1L),
                fallbackOn = emptySet(),
                persistRemoteResult = true,
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
                        consumerId = QueueConsumerId("robolectric-durable-queue-remote-first-consumer"),
                        leaseId = QueueLeaseId("robolectric-durable-queue-remote-first-lease"),
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
            storageDatabaseName = "dq-rf-storage-$uniqueSuffix.db",
            queueDatabaseName = "dq-rf-queue-$uniqueSuffix.db",
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
            .queueSubmissionEncoder(RemoteFirstPassthroughQueuedSynchronizationWorkEncoder)
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
                    retryPolicy = RemoteFirstNeverRetryPolicy,
                    retryOperation = RetryOperation("robolectric.durable-queue-remote-first-replay"),
                    configuration = QueueWorkerConfiguration(
                        scheduleId = ScheduleId("robolectric-durable-queue-remote-first-worker"),
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
private object RemoteFirstPassthroughQueuedSynchronizationWorkEncoder : QueuedSynchronizationWorkEncoder {
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
private object RemoteFirstNeverRetryPolicy : RetryPolicy {
    override val id: RetryPolicyId = RetryPolicyId("robolectric-durable-queue-remote-first-never-retry")
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
private class CountingOneChangeSetRemoteFirstTransportProvider(
    private val changeSet: ChangeSet,
) : TransportProvider {
    var pullCalls: Int = 0
        private set

    override val descriptor: ProviderDescriptor = ProviderDescriptor(
        id = ProviderId("io.dataloom.consumer.android.test.counting-one-change-set-remote-first-transport"),
        name = ProviderName("Counting One-Change-Set Remote-First Test Transport"),
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
        error("CountingOneChangeSetRemoteFirstTransportProvider does not support push.")

    override suspend fun pullChanges(
        request: PullChangesRequest,
    ): ProviderOperationResult<PullChangesResult> {
        pullCalls++
        return ProviderOperationResult.Success(PullChangesResult.Changes(changeSet = changeSet, hasMore = false))
    }
}
