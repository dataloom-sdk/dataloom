package io.dataloom.consumer.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import io.dataloom.android.androidDataLoomProviders
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.error.safeDiagnosticString
import io.dataloom.api.identifier.CheckpointKey
import io.dataloom.api.identifier.CheckpointToken
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
import io.dataloom.api.provider.StrategyProviderBindings
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
import io.dataloom.api.strategy.StrategyLocalFallbackProvider
import io.dataloom.api.strategy.StrategyLocalFallbackRequest
import io.dataloom.api.strategy.StrategyLocalFallbackResult
import io.dataloom.api.strategy.StrategyOperationInput
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.strategy.StrategyRemoteOutcome
import io.dataloom.api.strategy.StrategyRuntimeEvidence
import io.dataloom.api.strategy.StrategySynchronizationRequest
import io.dataloom.api.strategy.UnknownConnectivityPolicy
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
import io.dataloom.storage.room.RoomStorageProvider
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Robolectric-backed runtime proof for **remote-first's own non-empty-
 * `fallbackOn` durable branch** -- the follow-up
 * [AndroidReferenceConsumerRemoteFirstQueueRobolectricTest]'s own KDoc named
 * explicitly as not yet attempted: "a dedicated admission-then-replay proof
 * of *this specific* remote-first branch remains a follow-up, not silently
 * claimed."
 *
 * ## Investigation: re-verified directly against current source
 *
 * `BuiltInSynchronizationStrategyEvaluator.evaluateRemoteFirst` has exactly
 * two places a non-empty `fallbackOn` matters, confirmed by re-reading the
 * method body directly this round:
 *
 * 1. Its connectivity-`UNAVAILABLE` branch (`profile.fallbackOn` containing
 *    `UNAVAILABLE` plus available local data) reaches `EXECUTE` with
 *    `localFallbackOperations(direction)` -- for PULL, exactly
 *    `[SERVE_LOCAL]` -- **synchronously, with no `ENQUEUE_DURABLE_WORK` at
 *    all**. `deriveDurableContinuation` only ever produces a non-null
 *    continuation when `ENQUEUE_DURABLE_WORK` is present in the immediate
 *    operations, so this branch can never be a durable admission-then-replay
 *    proof, confirmed directly, not merely assumed from
 *    [AndroidReferenceConsumerRemoteFirstQueueRobolectricTest]'s own prose.
 * 2. The genuinely durable branch requires reaching `unknownConnectivityResult`
 *    with `policy = DEFER` (connectivity `UNKNOWN`/`NOT_EVALUATED`), which
 *    returns `StrategyDisposition.DEFER` with `operations =
 *    [ENQUEUE_DURABLE_WORK]` only. `deriveDurableContinuation`'s
 *    `RemoteFirstStrategyProfile` arm is
 *    `remoteOperations(direction, persistRemote = profile.persistRemoteResult)`
 *    -- for PULL with the default `persistRemoteResult = true`, exactly
 *    `[READ_CHECKPOINT, PULL_REMOTE, PERSIST_REMOTE]` -- and its
 *    `continuationFallback` is `remoteFallbackPlan(profile, direction)`,
 *    which is non-null precisely when `profile.fallbackOn` is non-empty
 *    (confirmed directly in `remoteFallbackPlan`'s own body: `if
 *    (profile.fallbackOn.isEmpty() || direction == PUSH) return null`). This
 *    is the one and only shape that carries a real `fallbackPlan` on a
 *    `RemoteFirstStrategyProfile`'s durable continuation.
 *
 * Critically, `AcceptedStrategyPlanExecutionCoordinator`'s own
 * `AcceptedStrategyContinuationExecutor.execute` never inspects
 * `continuation.fallbackPlan` unless `SERVE_LOCAL` is already present in
 * `continuation.operations` (it is not, here) **or** a genuine
 * `PULL_REMOTE` failure occurs during replay whose classified
 * [io.dataloom.api.strategy.StrategyRemoteOutcome] is contained in
 * `fallbackPlan.remoteOutcomes` (confirmed directly in
 * `handleRemoteFailure`'s own body). Unlike hybrid's explicit-fallback
 * branch (`SERVE_LOCAL` chosen synchronously by the evaluator, exercised by
 * [AndroidReferenceConsumerHybridReconcileQueueRobolectricTest]), remote-first's
 * fallback is **reactive**: the only way to genuinely exercise
 * `RoomStorageProvider.evaluateLocalFallback` for this branch is to let the
 * durable continuation's real `PULL_REMOTE` attempt genuinely fail with an
 * outcome this profile's own `fallbackOn` allowlist covers. This test's
 * transport therefore always fails `pullChanges` with a
 * [io.dataloom.api.strategy.ClassifiedStrategyRemoteError] whose
 * `remoteOutcome` is [StrategyRemoteOutcome.UNAVAILABLE], matching
 * `fallbackOn = setOf(UNAVAILABLE)`.
 *
 * ## A confirmed, deliberate consequence: this replay's own terminal queue
 * disposition is FAILED, not COMPLETED
 *
 * `StrategyQueueExecutionOutcomeMapper.map`'s own
 * `StrategySynchronizationExecutionResult.FallbackActivated` arm computes
 * `unresolvedErrors = errorsFrom(result.partialOutput)` -- and because a
 * genuine `PULL_REMOTE` failure necessarily carries that failure forward as
 * `partialOutput` (confirmed directly in
 * `AcceptedStrategyContinuationExecutor.handleRemoteFailure`/`executeFallback`),
 * that list is never empty for this branch, so the entry is always routed
 * through `evaluateRetry` even when local fallback data was genuinely found.
 * With this test's own never-retry policy, `evaluateRetry` resolves to
 * `StopRetry` and the entry is persisted `FAILED`, carrying the *original
 * simulated transport error* -- not a distinct "fallback unavailable" error.
 * This is a real, confirmed property of the production mapper (remote-first
 * still wants a future attempt at fresh remote data even after serving local
 * fallback data once), not a testing artifact -- and it means the queue
 * summary and the [SynchronizationObserver]'s own `Completed` event cannot,
 * by themselves, distinguish "fallback genuinely served" from "fallback
 * genuinely unavailable": both terminate identically at that layer. Direct
 * evidence that `RoomStorageProvider.evaluateLocalFallback` was genuinely
 * invoked and genuinely reported
 * [StrategyLocalFallbackResult.Available] therefore comes from
 * [CountingLocalFallbackRoomStorageProvider] below -- a thin, fully
 * delegating instrumentation wrapper around the real, unmodified
 * `RoomStorageProvider` (every method except [StrategyLocalFallbackProvider
 * .evaluateLocalFallback] is delegated unchanged via Kotlin interface
 * delegation; the delegated method itself forwards to the real provider's
 * own database-backed existence check and only additionally counts calls
 * and records the returned result) -- the same real production logic
 * [AndroidReferenceConsumerHybridReconcileQueueRobolectricTest] already
 * exercises for hybrid's own branch, observed here instead of merely relied
 * upon, because remote-first's own reactive fallback path leaves no other
 * externally observable signal.
 *
 * ## What this proves
 *
 * [remoteFirstFallbackQueueWorkerReplay] exercises the full path, entirely
 * through real, production DataLoom code:
 *
 * 1. **Admission, not synchronous execution.** [DataLoom.synchronize] for a
 *    `StrategySynchronizationRequest` built from [RemoteFirstStrategyProfile]
 *    (`fallbackOn = setOf(UNAVAILABLE)`, `unknownConnectivityPolicy = DEFER`,
 *    direction `PULL`, connectivity `UNKNOWN`) returns
 *    [StrategySynchronizationExecutionResult.Deferred] with a real, non-null
 *    `queueEntryId`. Zero transport calls and zero fallback-provider calls
 *    occur at this point.
 * 2. **Survives being read back out and is genuinely replayed.**
 *    [DataLoom.queueWorker]'s single `run(...)` call acquires the entry back
 *    out of the real Room-backed queue and drives exactly one
 *    `AcceptedStrategyPlanExecutionCoordinator` cycle against the real,
 *    registered `InboundPullSynchronizationPipeline` and real Room storage.
 * 3. **The real transport is genuinely attempted and genuinely fails.**
 *    `transport.pullCalls == 1` after replay -- the durable continuation's
 *    `PULL_REMOTE` step genuinely ran, it did not short-circuit.
 * 4. **The real `RoomStorageProvider.evaluateLocalFallback` is genuinely
 *    invoked, exactly once, and genuinely reports `Available`** because of a
 *    real checkpoint seeded beforehand through that same real provider --
 *    `AcceptedStrategyPlanExecutionCoordinator`'s reactive fallback path,
 *    gated on `validateReplayProviders`'s `StrategyLocalFallbackProvider`
 *    check, genuinely completes end-to-end for this branch.
 * 5. **The observer and the queue summary are genuinely observable**, and
 *    this file's own KDoc above explains precisely why they show `Failed`/
 *    `failed == 1` rather than `Succeeded`/`completed == 1` even though
 *    local fallback data was genuinely served -- a confirmed property of
 *    `StrategyQueueExecutionOutcomeMapper`, not an unexplained result.
 *
 * ## What this does not prove
 *
 * iOS (no equivalent Apple proof of this branch exists yet); a scenario
 * where `RoomStorageProvider.evaluateLocalFallback` genuinely reports
 * `Unavailable` (this test always seeds a checkpoint first); retry,
 * circuit-breaker, or conflict-detection behavior beyond the single
 * never-retry stop this test exercises; a real WorkManager-triggered
 * background tick (this test calls `queueWorker.run(...)` directly,
 * deterministically); and a real managed-device emulator (Robolectric
 * only).
 */
@RunWith(RobolectricTestRunner::class)
class AndroidReferenceConsumerRemoteFirstFallbackQueueRobolectricTest {

    @Test
    fun remoteFirstFallbackQueueWorkerReplay() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)

        val transport = FailingPullRemoteFirstFallbackTransportProvider()
        val completedResults = mutableListOf<SynchronizationResult>()
        val observer = object : SynchronizationObserver {
            override val id: SynchronizationObserverId =
                SynchronizationObserverId("durable-queue-remote-first-fallback-replay-observer")
            override fun onEvent(event: SynchronizationEvent) {
                if (event is SynchronizationEvent.Completed) {
                    completedResults += event.result
                }
            }
        }

        val androidProviders = androidDataLoomProviders(
            context = context,
            storageDatabaseName = "dq-rff-storage-${UUID.randomUUID().toString().take(8)}.db",
            queueDatabaseName = "dq-rff-queue-${UUID.randomUUID().toString().take(8)}.db",
        )
        val fallbackStorage = CountingLocalFallbackRoomStorageProvider(androidProviders.storage)

        // The real, unmodified RoomStorageProvider's evaluateLocalFallback is a
        // genuine existence check over its own infrastructure tables. Seed one
        // real checkpoint through the SAME real provider instance the decorator
        // wraps (before building/initializing DataLoom, matching
        // AndroidReferenceConsumerHybridReconcileQueueRobolectricTest's own
        // established seed-then-initialize ordering) so this branch's reactive
        // fallback step genuinely reports Available once the durable replay's
        // remote attempt fails below.
        val seedResult = androidProviders.storage.writeCheckpoint(
            CheckpointWriteRequest(
                request = seedRequest(),
                checkpoint = SynchronizationCheckpoint(
                    key = CheckpointKey("remote-first-fallback-seed-checkpoint"),
                    token = CheckpointToken("remote-first-fallback-seed-token"),
                ),
            ),
        )
        assertEquals(ProviderOperationResult.Success(Unit), seedResult)

        val dataLoom = buildDurableQueueDataLoom(androidProviders, fallbackStorage, transport, observer)
        assertEquals(ProviderLifecycleResult.InitializeSuccess, dataLoom.initialize())

        val strategyRequest = StrategySynchronizationRequest(
            request = SynchronizationRequest(
                workflowId = WorkflowId("durable-queue-remote-first-fallback-workflow-1"),
                sessionId = SynchronizationSessionId("durable-queue-remote-first-fallback-session-1"),
                direction = SynchronizationDirection.PULL,
                mode = SynchronizationMode.FULL,
                context = ExecutionContext(
                    executionId = ExecutionId("durable-queue-remote-first-fallback-execution-1"),
                    correlationId = CorrelationId("durable-queue-remote-first-fallback-correlation-1"),
                ),
            ),
            decisionId = StrategyDecisionId("durable-queue-remote-first-fallback-decision-1"),
            planId = StrategyPlanId("durable-queue-remote-first-fallback-plan-1"),
            profile = RemoteFirstStrategyProfile(
                id = StrategyProfileId("durable-queue-remote-first-fallback-profile"),
                configurationVersion = StrategyConfigurationVersion(1L),
                fallbackOn = setOf(StrategyRemoteOutcome.UNAVAILABLE),
                persistRemoteResult = true,
                unknownConnectivityPolicy = UnknownConnectivityPolicy.DEFER,
            ),
            evidence = StrategyRuntimeEvidence(
                connectivity = StrategyConnectivity.UNKNOWN,
                cacheState = StrategyCacheState.STALE,
            ),
            input = StrategyOperationInput.ProviderBacked,
        )

        // Step 1: durable admission only -- ENQUEUE_DURABLE_WORK, no SERVE_LOCAL
        // yet, no remote attempt yet.
        val admissionResult = dataLoom.synchronize(strategyRequest)
        val deferred = assertIs<StrategySynchronizationExecutionResult.Deferred>(admissionResult)
        assertNotNull(deferred.queueEntryId)
        assertEquals(0, transport.pullCalls)
        assertEquals(0, fallbackStorage.fallbackCalls)
        assertEquals(0, completedResults.size)

        // Step 2 + 3: acquire the durably persisted continuation back out of the
        // real Room queue database and replay it through exactly one
        // deterministic queue-worker cycle.
        val acquiredAt = DataLoomInstant(epochMilliseconds = System.currentTimeMillis())
        val runResult = dataLoom.queueWorker!!.run(
            QueueWorkerRunRequest(
                processingRequest = QueueProcessingRequest(
                    acquireRequest = QueueAcquireRequest(
                        consumerId = QueueConsumerId("robolectric-durable-queue-remote-first-fallback-consumer"),
                        leaseId = QueueLeaseId("robolectric-durable-queue-remote-first-fallback-lease"),
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

        // Step 4: the real transport genuinely attempted PULL_REMOTE exactly
        // once and genuinely failed with an outcome classified UNAVAILABLE.
        assertEquals(1, transport.pullCalls)

        // Step 5: the real, unmodified RoomStorageProvider.evaluateLocalFallback
        // was genuinely invoked exactly once (through the fully delegating
        // counting wrapper below) and genuinely reported Available, because of
        // the real checkpoint seeded above -- not a stub, not bypassed.
        assertEquals(1, fallbackStorage.fallbackCalls)
        val fallbackResult =
            assertIs<StrategyLocalFallbackResult.Available>(fallbackStorage.lastFallbackResult)
        assertEquals(StrategyCacheState.STALE, fallbackResult.cacheState)

        // Step 6: the observer's own Completed event carries the pipeline's raw,
        // pre-fallback-mapping SynchronizationResult -- genuinely Failed with
        // exactly the simulated transport error, proving the pipeline really
        // attempted and really failed the remote leg. See this file's own KDoc
        // for why this is a real, confirmed AcceptedStrategyPlanExecutionCoordinator
        // property, not a test limitation.
        assertEquals(1, completedResults.size)
        val observedFailure = assertIs<SynchronizationResult.Failed>(completedResults.single())
        assertIs<SimulatedRemoteUnavailableError>(observedFailure.error)

        // Step 7: StrategyQueueExecutionOutcomeMapper's own FallbackActivated
        // arm still routes this entry through retry evaluation because the
        // original PULL_REMOTE failure remains attached as unresolved evidence
        // -- and this test's own never-retry policy stops it there, so the
        // entry's persisted disposition is FAILED, not COMPLETED, even though
        // local state was genuinely served in step 5.
        assertEquals(0, processingResult.summary.completed)
        assertEquals(1, processingResult.summary.failed)

        assertEquals(ProviderLifecycleResult.ShutdownSuccess, dataLoom.shutdown())
    }

    private fun seedRequest(): SynchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("durable-queue-remote-first-fallback-seed-workflow"),
        sessionId = SynchronizationSessionId("durable-queue-remote-first-fallback-seed-session"),
        direction = SynchronizationDirection.PULL,
        mode = SynchronizationMode.FULL,
        context = ExecutionContext(
            executionId = ExecutionId("durable-queue-remote-first-fallback-seed-execution"),
            correlationId = CorrelationId("durable-queue-remote-first-fallback-seed-correlation"),
        ),
    )

    /**
     * Assembles a real [DataLoom] instance from the same production
     * `dataloom-android` providers every other reference-consumer test uses,
     * except the storage role is registered as [CountingLocalFallbackRoomStorageProvider]
     * (wrapping [providers]' own real [RoomStorageProvider] instance) instead
     * of the raw `AndroidDataLoomProviders.storage` field directly -- this is
     * why `io.dataloom.android.installAndroidProviders` is not used here (it
     * always registers `providers.storage` itself, and a
     * [io.dataloom.core.provider.ProviderRegistry] rejects two providers
     * sharing one [ProviderId] at `build()`). The decorator shares the real
     * provider's own [ProviderId], so every binding below resolves to it
     * exactly as [io.dataloom.android.installAndroidProviders] would resolve
     * the real provider directly.
     */
    private fun buildDurableQueueDataLoom(
        providers: io.dataloom.android.AndroidDataLoomProviders,
        storageProvider: CountingLocalFallbackRoomStorageProvider,
        transportProvider: TransportProvider,
        observer: SynchronizationObserver,
    ): DataLoom {
        val storageId = storageProvider.descriptor.id
        val transportId = transportProvider.descriptor.id
        val schedulerId = providers.scheduler.descriptor.id
        val connectivityId = providers.connectivity.descriptor.id
        val queueId = providers.queue.descriptor.id

        val bindings = SynchronizationProviderBindings(
            storageProviderId = storageId,
            transportProviderId = transportId,
            schedulerProviderId = schedulerId,
            connectivityProviderId = connectivityId,
            queueProviderId = queueId,
        )

        return DataLoomBuilder()
            .runtimeDependencies(referenceRuntimeDependencies())
            .provider(providers.connectivity)
            .provider(storageProvider)
            .provider(providers.queue)
            .provider(providers.scheduler)
            .provider(transportProvider)
            .defaultProviderBindings(bindings)
            .defaultStrategyProviderBindings(
                StrategyProviderBindings(
                    storageProviderId = storageId,
                    transportProviderId = transportId,
                    schedulerProviderId = schedulerId,
                    connectivityProviderId = connectivityId,
                    queueProviderId = queueId,
                ),
            )
            .observer(observer)
            .queueSubmissionEncoder(RemoteFirstFallbackPassthroughQueuedSynchronizationWorkEncoder)
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
                    retryPolicy = RemoteFirstFallbackNeverRetryPolicy,
                    retryOperation = RetryOperation("robolectric.durable-queue-remote-first-fallback-replay"),
                    configuration = QueueWorkerConfiguration(
                        scheduleId = ScheduleId("robolectric-durable-queue-remote-first-fallback-worker"),
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
 * Thin, fully delegating instrumentation wrapper around a real
 * [RoomStorageProvider] instance.
 *
 * Every [StrategyLocalFallbackProvider] member (which, being a
 * [io.dataloom.api.storage.StorageProvider] subtype, includes every ordinary
 * storage member too) is delegated unchanged to [delegate] via Kotlin
 * interface delegation, **except** [evaluateLocalFallback] itself, which
 * still forwards to [delegate]'s own real, unmodified database-backed
 * implementation and only additionally counts invocations and records the
 * returned [StrategyLocalFallbackResult]. This is not a reimplementation of
 * fallback evaluation -- it exists solely because remote-first's own
 * reactive fallback path (see this file's enclosing class KDoc) leaves no
 * other externally observable signal that the real provider's own logic ran
 * and genuinely reported `Available`.
 */
private class CountingLocalFallbackRoomStorageProvider(
    private val delegate: RoomStorageProvider,
) : StrategyLocalFallbackProvider by delegate {
    var fallbackCalls: Int = 0
        private set
    var lastFallbackResult: StrategyLocalFallbackResult? = null
        private set

    override suspend fun evaluateLocalFallback(
        request: StrategyLocalFallbackRequest,
    ): ProviderOperationResult<StrategyLocalFallbackResult> {
        fallbackCalls++
        val result = delegate.evaluateLocalFallback(request)
        if (result is ProviderOperationResult.Success) {
            lastFallbackResult = result.value
        }
        return result
    }
}

/**
 * Test-only [QueuedSynchronizationWorkEncoder], identical in shape to
 * `AndroidReferenceConsumerRemoteFirstQueueRobolectricTest`'s own
 * `RemoteFirstPassthroughQueuedSynchronizationWorkEncoder` -- no byte
 * serialization is needed because [QueueEntry] already carries
 * [QueueEntry.synchronizationRequest], [QueueEntry.strategyDecision], and
 * [QueueEntry.strategyPlan] as typed fields.
 */
private object RemoteFirstFallbackPassthroughQueuedSynchronizationWorkEncoder : QueuedSynchronizationWorkEncoder {
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

/** Test-only [RetryPolicy] that always stops -- this entry never gets a second attempt. */
private object RemoteFirstFallbackNeverRetryPolicy : RetryPolicy {
    override val id: RetryPolicyId = RetryPolicyId("robolectric-durable-queue-remote-first-fallback-never-retry")
    override fun evaluate(request: RetryEvaluationRequest): RetryDecision =
        RetryDecision.Stop(RetryStopReason.POLICY_REJECTED)
}

/**
 * Test-only [DataLoomError][io.dataloom.api.error.DataLoomError] that
 * classifies as [StrategyRemoteOutcome.UNAVAILABLE] via
 * [io.dataloom.api.strategy.ClassifiedStrategyRemoteError] -- matching this
 * test's own `fallbackOn = setOf(UNAVAILABLE)` allowlist exactly, so
 * `StrategyRemoteOutcomeClassifier.classify` (confirmed directly) routes a
 * genuine `PULL_REMOTE` failure into the reactive fallback path instead of a
 * plain terminal failure.
 */
private data class SimulatedRemoteUnavailableError(
    override val remoteOutcome: StrategyRemoteOutcome = StrategyRemoteOutcome.UNAVAILABLE,
    override val code: ErrorCode = ErrorCode("TEST-REMOTE-FIRST-FALLBACK-SIMULATED-UNAVAILABLE"),
    override val category: ErrorCategory = ErrorCategory.NETWORK,
    override val severity: ErrorSeverity = ErrorSeverity.ERROR,
    override val recoverability: Recoverability = Recoverability.RECOVERABLE,
    override val message: String = "Simulated remote pull failure for remote-first fallback proof.",
    override val cause: Throwable? = null,
) : io.dataloom.api.strategy.ClassifiedStrategyRemoteError {
    override fun toString(): String = safeDiagnosticString()
}

/**
 * Test-only [TransportProvider] whose [pullChanges] always deterministically
 * fails with [SimulatedRemoteUnavailableError] and counts calls -- proving
 * durable admission does not execute synchronously (zero calls immediately
 * after admission) and that the queue-worker replay genuinely invokes the
 * real pipeline's remote leg (exactly one call after
 * [DataLoom.queueWorker]'s `run(...)`), which is what genuinely triggers this
 * branch's reactive local-fallback path. Push is not exercised by this test
 * and fails deterministically if ever called.
 */
private class FailingPullRemoteFirstFallbackTransportProvider : TransportProvider {
    var pullCalls: Int = 0
        private set

    override val descriptor: ProviderDescriptor = ProviderDescriptor(
        id = ProviderId("io.dataloom.consumer.android.test.failing-pull-remote-first-fallback-transport"),
        name = ProviderName("Failing-Pull Remote-First Fallback Test Transport"),
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
        error("FailingPullRemoteFirstFallbackTransportProvider does not support push.")

    override suspend fun pullChanges(
        request: PullChangesRequest,
    ): ProviderOperationResult<PullChangesResult> {
        pullCalls++
        return ProviderOperationResult.Failure(SimulatedRemoteUnavailableError())
    }
}
