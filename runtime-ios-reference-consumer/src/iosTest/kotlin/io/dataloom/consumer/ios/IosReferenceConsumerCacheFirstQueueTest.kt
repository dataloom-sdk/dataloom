@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.consumer.ios

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
import io.dataloom.api.random.AppleDataLoomSecureRandom
import io.dataloom.api.random.DataLoomSecureRandom
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
import io.dataloom.api.storage.OutboundChangeReadRequest
import io.dataloom.api.storage.OutboundChangeReadResult
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
import io.dataloom.api.synchronization.ChangeAcknowledgementStatus
import io.dataloom.api.synchronization.ChangeEventAcknowledgement
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.synchronization.SynchronizationEvent
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.time.AppleDataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.platform.ios.appleDataLoomProviders
import io.dataloom.platform.ios.installAppleProviders
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
import io.dataloom.storage.sqldelight.SqlDelightStorageProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/**
 * Kotlin/Native iOS Simulator runtime proof that a real, durably admitted
 * **cache-first** strategy plan survives being read back out of a real
 * [io.dataloom.runtime.queue.AppleFileQueueProvider]-backed queue and is
 * genuinely replayed by a real [DataLoom.queueWorker] cycle -- the iOS
 * counterpart to `AndroidReferenceConsumerCacheFirstQueueRobolectricTest`
 * (`#337`), closing the `#101` market-readiness row's "cache-first is now
 * proven on Android only (not iOS, and only its PUSH-direction branch)" gap
 * for iOS.
 *
 * ## Scope: one platform, one additional strategy
 *
 * This proves iOS + cache-first only, and only its PUSH-direction durable-
 * deferral branch, mirroring `#337`'s own scope decision exactly. The
 * remaining built-in strategies (network-only, remote-first, hybrid,
 * adaptive) are explicitly deferred, not silently claimed.
 *
 * ## Investigation: same PUSH/DEFER branch as `#337`, for the same reason
 *
 * `BuiltInSynchronizationStrategyEvaluator.evaluateCacheFirstPush` is
 * `commonMain` code -- identical on every platform. For
 * `SynchronizationDirection.PUSH` with connectivity `UNAVAILABLE` and
 * `requireDurableRefresh = true` (the profile default), the plan is
 * `StrategyDisposition.DEFER` with `operations = [READ_LOCAL,
 * ENQUEUE_DURABLE_WORK]`, and the frozen durable continuation is exactly
 * `[READ_LOCAL, PUSH_REMOTE]` -- `AcceptedStrategyPlanExecutionCoordinator`'s
 * supported PUSH replay shape. `validateReplayProviders` only requires
 * `StrategyLocalFallbackProvider` when `SERVE_LOCAL` or a `fallbackPlan` is
 * present, and only requires `StrategyReconciliationProvider` when
 * `RECONCILE` is present -- neither is true for this branch.
 *
 * `#337`'s own investigation found that `RoomStorageProvider` (Android's real
 * storage provider) implements only `StorageProvider`, not
 * `StrategyLocalFallbackProvider` or `StrategyReconciliationProvider`, ruling
 * out cache-first's documented PULL/BIDIRECTIONAL `SERVE_LOCAL` refresh
 * branch and hybrid's `RECONCILE`-terminated fallback branch as real-
 * provider-compatible. Reading `dataloom-storage-sqldelight`'s
 * [SqlDelightStorageProvider] confirms the identical constraint holds for
 * iOS's real storage provider: it also implements only `StorageProvider`
 * (`class SqlDelightStorageProvider ... : StorageProvider`), with no
 * `StrategyLocalFallbackProvider`/`StrategyReconciliationProvider`
 * conformance. The PUSH/DEFER branch this test exercises is therefore the
 * same, only real-provider-compatible cache-first durable branch on iOS as
 * it is on Android.
 *
 * ## A genuine platform difference: iOS can seed real outbound content
 *
 * `#337`'s Android proof terminates in `SynchronizationResult.Skipped(NO_CHANGES)`
 * because `RoomStorageProvider`'s outbound-seeding DAO
 * (`OutboundChangeDao.appendChangeSet`) is `internal` to `dataloom-storage-room`
 * -- there is genuinely no public `StorageProvider`/`DataLoom` facade API to
 * seed local pending outbound changes on Android.
 *
 * Reading [SqlDelightStorageProvider] shows this is **not** true of iOS's
 * real storage provider: it exposes a `public suspend fun
 * persistOutboundChanges(changeSet: ChangeSet)` method with no `internal`
 * visibility barrier. This is a real, existing asymmetry between the two
 * reference storage providers, not a shortcut invented for this test --
 * [persistOutboundChanges] is already exercised by
 * `SqlDelightStorageProviderTest` in its own module. Because this capability
 * genuinely exists on the real iOS storage provider, this test seeds one
 * real outbound [ChangeEvent] through it before admission, so the terminal
 * state proven here is a genuine [SynchronizationResult.Succeeded] with one
 * real event accepted by the (test-only) transport -- not merely
 * `Skipped(NO_CHANGES)`. This is intentionally a *stronger* proof than
 * `#337`'s on the one point where the real platforms actually differ; it
 * does not paper over or contradict `#337`'s own honest finding for Android,
 * which remains accurate for Android.
 *
 * ## What this proves
 *
 * [cacheFirstPushDeferralReplayedByQueueWorker] exercises the full path,
 * entirely through real, production DataLoom code, on a real Kotlin/Native
 * iOS Simulator runtime:
 *
 * 1. **A real outbound change is seeded first.** [SqlDelightStorageProvider.persistOutboundChanges]
 *    writes one real [ChangeEvent] into the same real SQLite database
 *    [DataLoom.synchronize] and the later queue-worker replay both read
 *    from.
 * 2. **Admission, not synchronous execution.** [DataLoom.synchronize] for a
 *    `StrategySynchronizationRequest` built from [CacheFirstStrategyProfile]
 *    (`requireDurableRefresh = true`, direction `PUSH`, connectivity
 *    `UNAVAILABLE`) returns [StrategySynchronizationExecutionResult.Deferred]
 *    with a real, non-null `queueEntryId` -- `StrategyDurableQueueAdmitter`
 *    durably persists the accepted plan via the real
 *    [io.dataloom.runtime.queue.AppleFileQueueProvider] instead of running
 *    the pipeline synchronously. The test-only transport records zero
 *    `pushChanges` calls at this point, proving nothing executed yet.
 * 3. **Survives being read back out.** The queue entry is not merely
 *    written; step 4 acquires it back from the same real on-disk snapshot
 *    via a genuine `QueueProvider.acquire` call inside the queue-worker
 *    cycle.
 * 4. **A queue worker genuinely replays it.** [DataLoom.queueWorker]'s single
 *    `run(...)` call deterministically drives exactly one bounded
 *    acquire/execute/complete cycle. `AcceptedStrategyPlanExecutionCoordinator`
 *    resolves the real storage/transport providers from the queue entry's own
 *    persisted bindings and runs the real, registered
 *    `OutboundPushSynchronizationPipeline`, which genuinely calls
 *    [SqlDelightStorageProvider.readOutboundChanges] against the real SQLite
 *    database, genuinely pushes the seeded event through the test transport,
 *    and genuinely calls `acknowledgeOutboundChanges`, which -- per
 *    [SqlDelightStorageProvider]'s own documented `ACCEPTED` behavior --
 *    deletes the row.
 * 5. **The result is genuinely observable.** A [SynchronizationObserver]
 *    registered through the real [DataLoomBuilder.observer] capability
 *    captures the terminal [SynchronizationEvent.Completed] emitted by that
 *    replay: [SynchronizationResult.Succeeded] with
 *    `summary.outboundEventsAccepted == 1`.
 *    [QueueProcessingResult.Processed.summary.completed] is asserted as one
 *    additional layer of evidence that the entry reached a real, durable
 *    terminal transition.
 * 6. **The outbound row is genuinely gone afterward.** A direct
 *    [SqlDelightStorageProvider.readOutboundChanges] call after the replay
 *    returns [OutboundChangeReadResult.NoChanges], confirming the
 *    acknowledgement was not just returned but actually persisted back to
 *    real storage.
 *
 * ## Why no real background-scheduler tick
 *
 * Same reason `IosReferenceConsumerDurableQueueTest` documents: this
 * repository has no Apple counterpart module that bridges a real
 * `BGTaskScheduler` task invocation to `DataLoom.queueWorker.run(...)`. This
 * test proves the same deterministic single-pass `queueWorker.run(...)`
 * capability, not a real background tick.
 *
 * ## What this does not prove
 *
 * Android (see `#337` for that platform's own proof); the remaining four
 * built-in strategies' own admission branches (network-only cannot admit
 * durable work at all; remote-first, hybrid, and adaptive remain unexercised
 * at this layer); a real background-scheduler-triggered tick (no Apple
 * `WorkManager` counterpart exists yet); retry, circuit-breaker, or
 * conflict-detection behavior during queue replay (this entry always
 * succeeds on its first attempt); and a physical device (Simulator only).
 *
 * ## A note on how this was verified
 *
 * Like [IosReferenceConsumerDurableQueueTest], this file can be
 * cross-compiled from a Windows development host
 * (`compileTestKotlinIosArm64`/`IosSimulatorArm64`/`IosX64`), which catches
 * type errors and API drift, but **cannot be executed** there -- only a real
 * macOS host with Xcode and the iOS Simulator can run
 * `iosSimulatorArm64Test`/`iosX64Test`. This repository's
 * `apple-validation.yml` CI job (`macos-15`) is the actual pass/fail signal
 * for this file's runtime behavior, not local cross-compilation alone.
 */
class IosReferenceConsumerCacheFirstQueueTest {

    @Test
    fun cacheFirstPushDeferralReplayedByQueueWorker() = runTest {
        val runId = NSUUID().UUIDString
        val directoryPath = buildString {
            append(NSTemporaryDirectory().trimEnd('/'))
            append("/dataloom-ios-reference-consumer-cache-first-queue-")
            append(runId)
        }

        val transport = CountingAcceptingPushTransportProvider()
        val completedResults = mutableListOf<SynchronizationResult>()
        val observer = object : SynchronizationObserver {
            override val id: SynchronizationObserverId =
                SynchronizationObserverId("durable-queue-cache-first-replay-observer-$runId")
            override fun onEvent(event: SynchronizationEvent) {
                if (event is SynchronizationEvent.Completed) {
                    completedResults += event.result
                }
            }
        }

        val providers = appleDataLoomProviders(
            preRegisteredIdentifiers = emptySet(),
            directoryPath = directoryPath,
            storageDatabaseName = "dataloom-storage-cache-first-queue-$runId.db",
            queueFileName = "dataloom-queue-cache-first-queue-$runId.tsv",
        )
        val storage: SqlDelightStorageProvider = providers.storage

        val seededEventId = ChangeEventId("cache-first-queue-event-$runId")
        val seededChangeSet = ChangeSet(
            id = ChangeSetId("cache-first-queue-change-set-$runId"),
            events = listOf(
                ChangeEvent(
                    id = seededEventId,
                    entity = EntityReference(
                        type = EntityType("cache-first-queue-entity"),
                        id = EntityId("cache-first-queue-entity-$runId"),
                    ),
                    operation = ChangeOperation.CREATE,
                ),
            ),
        )

        // Step 1: seed one real outbound change directly through the real
        // SQLDelight storage provider -- see the class KDoc for why this is
        // a genuine capability on iOS's real storage provider that Android's
        // does not expose.
        val seedResult = storage.persistOutboundChanges(seededChangeSet)
        assertIs<ProviderOperationResult.Success<Unit>>(seedResult)

        val dataLoom = buildDurableQueueDataLoom(
            providers = providers,
            transportProvider = transport,
            observer = observer,
        )
        assertEquals(ProviderLifecycleResult.InitializeSuccess, dataLoom.initialize())

        val strategyRequest = StrategySynchronizationRequest(
            request = SynchronizationRequest(
                workflowId = WorkflowId("durable-queue-cache-first-workflow-$runId"),
                sessionId = SynchronizationSessionId("durable-queue-cache-first-session-$runId"),
                direction = SynchronizationDirection.PUSH,
                mode = SynchronizationMode.FULL,
                context = ExecutionContext(
                    executionId = ExecutionId("durable-queue-cache-first-execution-$runId"),
                    correlationId = CorrelationId("durable-queue-cache-first-correlation-$runId"),
                ),
            ),
            decisionId = StrategyDecisionId("durable-queue-cache-first-decision-$runId"),
            planId = StrategyPlanId("durable-queue-cache-first-plan-$runId"),
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

        // Step 2: durable admission -- must NOT execute synchronously.
        val admissionResult = dataLoom.synchronize(strategyRequest)
        val deferred = assertIs<StrategySynchronizationExecutionResult.Deferred>(admissionResult)
        assertNotNull(deferred.queueEntryId)
        assertEquals(0, transport.pushCalls)
        assertEquals(0, completedResults.size)

        // Step 3 + 4: acquire the durably persisted entry back out of the
        // real Apple file queue snapshot and replay it via exactly one
        // deterministic queue-worker cycle. acquiredAt must be a real
        // wall-clock "now" -- see IosReferenceConsumerDurableQueueTest's
        // identical comment for why.
        val acquiredAt = AppleDataLoomClock().now()
        val runResult = dataLoom.queueWorker!!.run(
            QueueWorkerRunRequest(
                processingRequest = QueueProcessingRequest(
                    acquireRequest = QueueAcquireRequest(
                        consumerId = QueueConsumerId("simulator-durable-queue-cache-first-consumer-$runId"),
                        leaseId = QueueLeaseId("simulator-durable-queue-cache-first-lease-$runId"),
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

        // Step 5: the replay genuinely reached a real terminal transition,
        // with the seeded content genuinely flowing through the transport --
        // see the class KDoc for why Succeeded (not Skipped(NO_CHANGES)) is
        // the honest terminal state here.
        assertEquals(1, completedResults.size)
        val succeeded = assertIs<SynchronizationResult.Succeeded>(completedResults.single())
        assertEquals(1L, succeeded.summary.outboundEventsAccepted)
        assertEquals(1L, succeeded.summary.outboundEventsRead)
        assertEquals(1, transport.pushCalls)
        assertEquals(seededEventId, transport.lastPushedEventId)

        // Step 6: the acknowledged row is genuinely gone from real storage
        // afterward -- one more layer of evidence beyond the observed event.
        val postReplayRead = storage.readOutboundChanges(
            OutboundChangeReadRequest(request = strategyRequest.request),
        )
        val postReplayResult = assertIs<ProviderOperationResult.Success<OutboundChangeReadResult>>(postReplayRead)
        assertIs<OutboundChangeReadResult.NoChanges>(postReplayResult.value)

        assertEquals(ProviderLifecycleResult.ShutdownSuccess, dataLoom.shutdown())
    }

    /**
     * Assembles a real [DataLoom] instance from the exact same production
     * `dataloom-platform-ios` helper [installAppleProviders]
     * [IosReferenceConsumerTest] uses, additionally opting into
     * [DataLoomBuilder.queueSubmissionEncoder] and
     * [DataLoomBuilder.queueWorkerConfiguration]. Mirrors
     * [IosReferenceConsumerDurableQueueTest.buildDurableQueueDataLoom]'s
     * identical role, adapted to accept an already-constructed
     * [io.dataloom.platform.ios.AppleDataLoomProviders] so the calling test
     * can seed real outbound content through [SqlDelightStorageProvider]
     * before [DataLoom] is built.
     */
    private fun buildDurableQueueDataLoom(
        providers: io.dataloom.platform.ios.AppleDataLoomProviders,
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
            .installAppleProviders(providers, transportProvider)
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
                    retryOperation = RetryOperation("simulator.durable-queue-cache-first-replay"),
                    configuration = QueueWorkerConfiguration(
                        scheduleId = ScheduleId("simulator-durable-queue-cache-first-worker"),
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
     * [IosReferenceConsumerDurableQueueTest]'s own private helper of the
     * same shape -- real wall clock, secure-random-backed identifier
     * generators. Kept local to this file rather than reaching into that
     * file's private helper, matching the Android cache-first test's
     * identical choice.
     */
    private fun referenceRuntimeDependencies(): RuntimeDependencies = RuntimeDependencies(
        clock = AppleDataLoomClock(),
        identifiers = RuntimeIdentifierGenerators(
            synchronizationEventIds = randomHexIdGenerator(::SynchronizationEventId),
            queueEntryIds = randomHexIdGenerator(::QueueEntryId),
            queueLeaseIds = randomHexIdGenerator(::QueueLeaseId),
            conflictIds = randomHexIdGenerator(::ConflictId),
        ),
    )

    private val secureRandom: DataLoomSecureRandom = AppleDataLoomSecureRandom()

    private fun <T> randomHexIdGenerator(construct: (String) -> T): IdentifierGenerator<T> =
        object : IdentifierGenerator<T> {
            override fun generate(): T = construct(secureRandom.nextBytes(16).toHexString())
        }
}

/**
 * Test-only [QueuedSynchronizationWorkEncoder], identical in shape to
 * [PassthroughQueuedSynchronizationWorkEncoder] in
 * `IosReferenceConsumerDurableQueueTest.kt` -- no byte serialization is
 * needed because [QueueEntry] already carries
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
    override val id: RetryPolicyId = RetryPolicyId("simulator-durable-queue-cache-first-never-retry")
    override fun evaluate(request: RetryEvaluationRequest): RetryDecision =
        RetryDecision.Stop(RetryStopReason.POLICY_REJECTED)
}

/**
 * Test-only [TransportProvider] that counts [pushChanges] calls, records the
 * last pushed event's identifier, and accepts every event it is asked to
 * push -- proving durable admission does not execute synchronously (zero
 * calls immediately after admission) and that the queue-worker replay
 * genuinely invokes the real pipeline with the real seeded content (exactly
 * one call, carrying the seeded event, after [DataLoom.queueWorker]'s
 * `run(...)`). Pull is not exercised by this test and fails deterministically
 * if ever called.
 */
private class CountingAcceptingPushTransportProvider : TransportProvider {
    var pushCalls: Int = 0
        private set

    var lastPushedEventId: ChangeEventId? = null
        private set

    override val descriptor: ProviderDescriptor = ProviderDescriptor(
        id = ProviderId("io.dataloom.consumer.ios.test.counting-accepting-push-transport"),
        name = ProviderName("Counting Accepting Push Test Transport"),
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
        val changeSet = request.changeSet
        lastPushedEventId = changeSet.events.lastOrNull()?.id
        return ProviderOperationResult.Success(
            ChangeSetAcknowledgement(
                changeSetId = changeSet.id,
                events = changeSet.events.map { event ->
                    ChangeEventAcknowledgement(
                        eventId = event.id,
                        status = ChangeAcknowledgementStatus.ACCEPTED,
                    )
                },
            ),
        )
    }

    override suspend fun pullChanges(
        request: PullChangesRequest,
    ): ProviderOperationResult<PullChangesResult> =
        error("CountingAcceptingPushTransportProvider does not support pull.")
}
