@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.consumer.ios

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
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/**
 * Kotlin/Native iOS Simulator runtime proof for **hybrid**'s own
 * explicit-fallback branch -- `HybridSource.LOCAL` selected as an explicit
 * fallback from a `REMOTE` primary, with `reconcileAfterFallback = true` (the
 * default) -- the iOS counterpart to
 * `AndroidReferenceConsumerHybridReconcileQueueRobolectricTest` (`#101`
 * market-readiness row, closing this row's own "hybrid's explicit-fallback
 * branch's iOS counterpart" follow-up).
 *
 * ## Investigation: re-verified directly against source, not trusted from
 * the Android test's own KDoc
 *
 * `BuiltInSynchronizationStrategyEvaluator.evaluateHybrid`/
 * `deriveDurableContinuation` are `commonMain` code -- identical on every
 * platform -- so the Android proof's branch analysis was re-read against the
 * current source in this session rather than assumed still accurate:
 *
 * - `evaluateHybrid`'s explicit-fallback branch (`HybridSource.LOCAL`
 *   selected as an explicit fallback from a `REMOTE` primary) adds
 *   `ENQUEUE_DURABLE_WORK` and `RECONCILE` together, unconditionally, in the
 *   same `also { }` block whenever `profile.reconcileAfterFallback` is
 *   `true` (the default) -- confirmed directly. Its immediate operations
 *   also carry `SERVE_LOCAL`, so this branch requires both
 *   `StrategyLocalFallbackProvider` (gated on `SERVE_LOCAL in
 *   continuation.operations || continuation.fallbackPlan != null`) and
 *   `StrategyReconciliationProvider` (gated on `RECONCILE in
 *   continuation.operations`) at replay
 *   (`AcceptedStrategyPlanExecutionCoordinator.validateReplayProviders`,
 *   confirmed directly).
 * - `SqlDelightStorageProvider` -- confirmed directly against its class
 *   declaration (`dataloom-storage-sqldelight`'s
 *   `SqlDelightStorageProvider.kt`) -- now implements `StorageProvider`,
 *   `StrategyLocalFallbackProvider`, **and** `StrategyReconciliationProvider`
 *   as of round 31's `StrategyLocalFallbackProvider`/
 *   `StrategyReconciliationProvider` implementation work (the same round
 *   that gave `RoomStorageProvider` the identical capability, which the
 *   Android proof this file mirrors exercises). Both reference storage
 *   providers therefore satisfy this branch's provider requirements
 *   identically now; only an iOS runtime proof of it was still missing.
 * - `HybridStrategyExecutor`'s own `serveLocal` (the synchronous half) and
 *   `AcceptedStrategyPlanExecutionCoordinator`'s own `finalizeReconciliation`
 *   (the durable half) are both `commonMain` code, run unmodified against
 *   whichever real `StorageProvider` is registered -- so exercising this
 *   branch on iOS requires no `commonMain` change, only a real
 *   `SqlDelightStorageProvider`-backed end-to-end proof, exactly mirroring
 *   how [IosReferenceConsumerRemoteFirstQueueTest]/
 *   [IosReferenceConsumerHybridQueueTest] already mirror their own Android
 *   counterparts.
 *
 * ## What this proves
 *
 * [hybridReconcileQueueWorkerReplay] exercises the full path, entirely
 * through real, production DataLoom code:
 *
 * 1. **Synchronous half.** With `primarySource = REMOTE` and connectivity
 *    `UNAVAILABLE`, `evaluateHybrid` selects `HybridSource.LOCAL` as an
 *    explicit fallback (`usedFallback = true`), producing `operations =
 *    [SERVE_LOCAL, ENQUEUE_DURABLE_WORK, RECONCILE]` and
 *    `StrategyDisposition.SERVE_AND_REFRESH`. `HybridStrategyExecutor`
 *    durably admits the continuation first (a real `queueEntryId`, zero
 *    transport calls), then genuinely calls
 *    `SqlDelightStorageProvider.evaluateLocalFallback` -- seeded beforehand
 *    with one real checkpoint through the same real provider instance so its
 *    own existence check reports
 *    [io.dataloom.api.strategy.StrategyLocalFallbackResult.Available] rather
 *    than failing the evaluator/provider consistency check -- and returns
 *    [StrategySynchronizationExecutionResult.ServedFromCache] carrying that
 *    `queueEntryId`.
 * 2. **Durable half.** The admitted continuation is exactly
 *    `remoteOperations(PULL, persistRemote = true) + RECONCILE` =
 *    `[READ_CHECKPOINT, PULL_REMOTE, PERSIST_REMOTE, RECONCILE]`. Reading the
 *    entry back out of the real on-disk `AppleFileQueueProvider` snapshot and
 *    replaying it through one deterministic `DataLoom.queueWorker.run(...)`
 *    cycle genuinely drives `AcceptedStrategyPlanExecutionCoordinator` (no
 *    longer rejected at `validateReplayProviders` since
 *    `SqlDelightStorageProvider` now satisfies both capability checks)
 *    through the real, registered `InboundPullSynchronizationPipeline`
 *    against real SQLDelight storage, then genuinely calls
 *    `SqlDelightStorageProvider.reconcileStrategy` -- which confirms the
 *    checkpoint `PERSIST_REMOTE` just wrote actually exists and reports
 *    `Applied` -- to a genuinely observed `SynchronizationResult.Succeeded`
 *    (`summary.inboundEventsApplied == 1`) via a real
 *    `SynchronizationObserver`, the same bar
 *    `#325`/`#334`/`#337`/`#368`/`#371` established for every other durable
 *    branch this suite proves.
 *
 * ## What this does not prove
 *
 * Hybrid's other durable branch (connectivity-`UNKNOWN`/`DEFER`, no
 * `SERVE_LOCAL`/`RECONCILE`) -- already proven on iOS by
 * [IosReferenceConsumerHybridQueueTest]; retry, circuit-breaker, or
 * conflict-detection behavior during queue replay (this entry always
 * succeeds on its first attempt); a real Apple background-scheduler-triggered
 * tick (this test calls `queueWorker.run(...)` directly, deterministically);
 * and a physical device (Simulator only).
 *
 * ## A note on how this was verified
 *
 * Like [IosReferenceConsumerDurableQueueTest],
 * [IosReferenceConsumerCacheFirstPullQueueTest],
 * [IosReferenceConsumerRemoteFirstQueueTest], and
 * [IosReferenceConsumerHybridQueueTest], this file can be cross-compiled from
 * a Windows development host
 * (`compileTestKotlinIosArm64`/`IosSimulatorArm64`/`IosX64`), which catches
 * type errors and API drift, but **cannot be executed** there -- only a real
 * macOS host with Xcode and the iOS Simulator can run
 * `iosSimulatorArm64Test`/`iosX64Test`. This repository's
 * `apple-validation.yml` CI job (`macos-15`) is the actual pass/fail signal
 * for this file's runtime behavior, not local cross-compilation alone.
 */
class IosReferenceConsumerHybridReconcileQueueTest {

    @Test
    fun hybridReconcileQueueWorkerReplay() = runTest {
        val runId = NSUUID().UUIDString
        val directoryPath = buildString {
            append(NSTemporaryDirectory().trimEnd('/'))
            append("/dataloom-ios-reference-consumer-hybrid-reconcile-queue-")
            append(runId)
        }

        val changeSet = ChangeSet(
            id = ChangeSetId("hybrid-reconcile-queue-change-set-$runId"),
            events = listOf(
                ChangeEvent(
                    id = ChangeEventId("hybrid-reconcile-queue-event-$runId"),
                    entity = EntityReference(
                        type = EntityType("hybrid-reconcile-queue-entity"),
                        id = EntityId("hybrid-reconcile-queue-entity-$runId"),
                    ),
                    operation = ChangeOperation.CREATE,
                ),
            ),
        )
        val transport = CountingOneChangeSetHybridReconciliationTransportProvider(changeSet)
        val completedResults = mutableListOf<SynchronizationResult>()
        val observer = object : SynchronizationObserver {
            override val id: SynchronizationObserverId =
                SynchronizationObserverId("durable-queue-hybrid-reconcile-replay-observer-$runId")
            override fun onEvent(event: SynchronizationEvent) {
                if (event is SynchronizationEvent.Completed) {
                    completedResults += event.result
                }
            }
        }

        val providers = appleDataLoomProviders(
            preRegisteredIdentifiers = emptySet(),
            directoryPath = directoryPath,
            storageDatabaseName = "dataloom-storage-hybrid-reconcile-queue-$runId.db",
            queueFileName = "dataloom-queue-hybrid-reconcile-queue-$runId.tsv",
        )

        // The real, unmodified SqlDelightStorageProvider's evaluateLocalFallback
        // is a genuine existence check over its own infrastructure tables -- it
        // is not seeded implicitly by evaluation evidence alone. Seed one real
        // checkpoint first (before building/initializing DataLoom, matching
        // AndroidReferenceConsumerHybridReconcileQueueRobolectricTest's own
        // established seed-then-initialize ordering) so this branch's
        // SERVE_LOCAL step (still genuinely exercised, not bypassed) reports
        // synchronized local state is actually Available, matching the
        // StrategyCacheState.STALE evidence supplied below.
        val seedResult = providers.storage.writeCheckpoint(
            CheckpointWriteRequest(
                request = seedRequest(runId),
                checkpoint = SynchronizationCheckpoint(
                    key = CheckpointKey("hybrid-reconcile-seed-checkpoint-$runId"),
                    token = CheckpointToken("hybrid-reconcile-seed-token-$runId"),
                ),
            ),
        )
        assertEquals(ProviderOperationResult.Success(Unit), seedResult)

        val dataLoom = buildDurableQueueDataLoom(
            providers = providers,
            transportProvider = transport,
            observer = observer,
        )
        assertEquals(ProviderLifecycleResult.InitializeSuccess, dataLoom.initialize())

        val strategyRequest = StrategySynchronizationRequest(
            request = SynchronizationRequest(
                workflowId = WorkflowId("durable-queue-hybrid-reconcile-workflow-$runId"),
                sessionId = SynchronizationSessionId("durable-queue-hybrid-reconcile-session-$runId"),
                direction = SynchronizationDirection.PULL,
                mode = SynchronizationMode.FULL,
                context = ExecutionContext(
                    executionId = ExecutionId("durable-queue-hybrid-reconcile-execution-$runId"),
                    correlationId = CorrelationId("durable-queue-hybrid-reconcile-correlation-$runId"),
                ),
            ),
            decisionId = StrategyDecisionId("durable-queue-hybrid-reconcile-decision-$runId"),
            planId = StrategyPlanId("durable-queue-hybrid-reconcile-plan-$runId"),
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
        // SqlDelightStorageProvider.evaluateLocalFallback, and the deferred
        // remote catch-up (carrying RECONCILE) is durably admitted alongside
        // it. Zero transport calls yet.
        val servedResult = dataLoom.synchronize(strategyRequest)
        val served = assertIs<StrategySynchronizationExecutionResult.ServedFromCache>(servedResult)
        assertEquals(StrategyCacheState.STALE, served.cacheState)
        val queueEntryId = assertNotNull(served.durableQueueEntryId)
        assertEquals(0, transport.pullCalls)
        assertEquals(0, completedResults.size)

        // Step 2 + 3: acquire the durably persisted continuation back out of
        // the real on-disk AppleFileQueueProvider snapshot and replay it
        // through exactly one deterministic queue-worker cycle -- now
        // genuinely reaching AcceptedStrategyPlanExecutionCoordinator's
        // RECONCILE step instead of being rejected at validateReplayProviders.
        val acquiredAt = AppleDataLoomClock().now()
        val runResult = dataLoom.queueWorker!!.run(
            QueueWorkerRunRequest(
                processingRequest = QueueProcessingRequest(
                    acquireRequest = QueueAcquireRequest(
                        consumerId = QueueConsumerId("simulator-durable-queue-hybrid-reconcile-consumer-$runId"),
                        leaseId = QueueLeaseId("simulator-durable-queue-hybrid-reconcile-lease-$runId"),
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
        // reconcileStrategy's own Applied confirmation (checkpoint now exists
        // after PERSIST_REMOTE) did not turn the result into a failure.
        assertEquals(1, transport.pullCalls)
        assertEquals(1, completedResults.size)
        val succeeded = assertIs<SynchronizationResult.Succeeded>(completedResults.single())
        assertEquals(1L, succeeded.summary.inboundEventsApplied)
        assertNotNull(queueEntryId)

        assertEquals(ProviderLifecycleResult.ShutdownSuccess, dataLoom.shutdown())
    }

    private fun seedRequest(runId: String): SynchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("durable-queue-hybrid-reconcile-seed-workflow-$runId"),
        sessionId = SynchronizationSessionId("durable-queue-hybrid-reconcile-seed-session-$runId"),
        direction = SynchronizationDirection.PULL,
        mode = SynchronizationMode.FULL,
        context = ExecutionContext(
            executionId = ExecutionId("durable-queue-hybrid-reconcile-seed-execution-$runId"),
            correlationId = CorrelationId("durable-queue-hybrid-reconcile-seed-correlation-$runId"),
        ),
    )

    /**
     * Assembles a real [DataLoom] instance from the exact same production
     * `dataloom-platform-ios` helpers [appleDataLoomProviders]/
     * [installAppleProviders] every other reference-consumer test uses,
     * additionally opting into [DataLoomBuilder.queueSubmissionEncoder] and
     * [DataLoomBuilder.queueWorkerConfiguration] -- see
     * [IosReferenceConsumerHybridQueueTest.buildDurableQueueDataLoom] for why
     * these two capabilities require explicit test-side wiring.
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
                    retryOperation = RetryOperation("simulator.durable-queue-hybrid-reconcile-replay"),
                    configuration = QueueWorkerConfiguration(
                        scheduleId = ScheduleId("simulator-durable-queue-hybrid-reconcile-worker"),
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
     * [IosReferenceConsumerHybridQueueTest]'s own private helper of the same
     * shape -- real wall clock, secure-random-backed identifier generators.
     * Kept local to this file rather than reaching into that file's private
     * helper, matching this repository's own established convention for
     * these reference-consumer test files.
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
 * [IosReferenceConsumerHybridQueueTest]'s own
 * `HybridPassthroughQueuedSynchronizationWorkEncoder` -- no byte
 * serialization is needed because [QueueEntry] already carries
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
    override val id: RetryPolicyId = RetryPolicyId("simulator-durable-queue-hybrid-reconcile-never-retry")
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
            "io.dataloom.consumer.ios.test.counting-one-change-set-hybrid-reconciliation-transport",
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
