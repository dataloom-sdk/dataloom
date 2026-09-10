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
 * connectivity-`UNKNOWN`/`DEFER` durable branch -- the iOS counterpart to
 * `AndroidReferenceConsumerHybridQueueRobolectricTest` (`#101`
 * market-readiness row, closing this row's own "hybrid's iOS counterpart on
 * either branch remains unexercised" line for this one branch).
 *
 * ## Investigation: re-verified directly against source, not trusted from
 * the Android test's own KDoc
 *
 * `BuiltInSynchronizationStrategyEvaluator.evaluateHybrid` and
 * `deriveDurableContinuation` are `commonMain` code -- identical on every
 * platform -- so the Android proof's branch analysis was re-read against
 * the current source in this session rather than assumed still accurate:
 *
 * - `evaluateHybrid` produces `ENQUEUE_DURABLE_WORK` in exactly two places.
 *   The first -- `HybridSource.LOCAL` selected as an explicit fallback from
 *   a `REMOTE` primary -- adds `ENQUEUE_DURABLE_WORK` and `RECONCILE`
 *   together, unconditionally, in the same `also { }` block (confirmed
 *   directly: `if (usedFallback && profile.reconcileAfterFallback) { it +=
 *   ENQUEUE_DURABLE_WORK; it += RECONCILE }`). There is no field
 *   combination that reaches that branch's `ENQUEUE_DURABLE_WORK` without
 *   also getting `RECONCILE`, and `RECONCILE` in the durable continuation
 *   requires `StrategyReconciliationProvider`
 *   (`AcceptedStrategyPlanExecutionCoordinator.validateReplayProviders`),
 *   which `SqlDelightStorageProvider` does not implement -- so that branch
 *   stays genuinely blocked on both platforms, unchanged.
 * - The genuinely durable, unblocked branch is reached through the
 *   `unknownConnectivityResult` helper (the same one
 *   `evaluateRemoteFirst`/`evaluateNetworkOnly` use): when
 *   `request.evidence.connectivity` is `UNKNOWN` (or `NOT_EVALUATED`),
 *   `profile.primarySource == HybridSource.REMOTE`, and
 *   `profile.unknownConnectivityPolicy != ATTEMPT_REMOTE`. With
 *   `unknownConnectivityPolicy = DEFER` the plan is
 *   `StrategyDisposition.DEFER` with `operations = [ENQUEUE_DURABLE_WORK]`
 *   only -- no `SERVE_LOCAL`, no `RECONCILE`.
 * - `deriveDurableContinuation`'s `HybridStrategyProfile` arm is
 *   `remoteOperations(direction, persistRemote = profile.persistRemoteResult)`,
 *   appending `RECONCILE` **only if** `profile.reconcileAfterFallback` is
 *   `true`. With `reconcileAfterFallback = false` (not this profile's
 *   default) the continuation is exactly `remoteOperations(...)` -- for
 *   PULL with the default `persistRemoteResult = true`, `[READ_CHECKPOINT,
 *   PULL_REMOTE, PERSIST_REMOTE]`, byte-for-byte the same continuation
 *   shape `#325`/`#334`'s offline-first proofs (including iOS's own),
 *   `#337`/iOS's cache-first PULL proof, and
 *   `IosReferenceConsumerRemoteFirstQueueTest`'s remote-first DEFER proof
 *   already exercise.
 * - `deriveDurableContinuation`'s `continuationFallback` `when` block only
 *   ever produces a non-null `StrategyFallbackPlan` for
 *   `RemoteFirstStrategyProfile`; its `else` branch (which every
 *   `HybridStrategyProfile` falls into) is always `null` -- confirmed
 *   directly. So hybrid's durable continuation *never* carries a
 *   `fallbackPlan` under any field combination, and
 *   `validateReplayProviders` (whose `StrategyLocalFallbackProvider` check
 *   is gated on `continuation.fallbackPlan != null || SERVE_LOCAL in
 *   continuation.operations`, and whose `StrategyReconciliationProvider`
 *   check is gated on `RECONCILE in continuation.operations`) requires
 *   neither `StrategyLocalFallbackProvider` nor
 *   `StrategyReconciliationProvider` for this branch -- only
 *   `STORAGE`/`TRANSPORT`, which the real, unmodified
 *   `SqlDelightStorageProvider` (confirmed directly against its class
 *   declaration to implement only `StorageProvider`) and the test transport
 *   already satisfy.
 *
 * This is therefore the same commonMain planner branch the Android proof
 * exercises, re-verified against current source for this session rather
 * than trusted from that test's own prose, and proven here against a real
 * Kotlin/Native iOS Simulator runtime instead of left claimed only by
 * cross-platform code-sharing.
 *
 * ## What this proves
 *
 * [hybridDeferralReplayedByQueueWorker] exercises the full path, entirely
 * through real, production DataLoom code:
 *
 * 1. **Admission, not synchronous execution.** [DataLoom.synchronize] for a
 *    `StrategySynchronizationRequest` built from [HybridStrategyProfile]
 *    (`primarySource = REMOTE`, `fallbackSource = LOCAL`,
 *    `reconcileAfterFallback = false`, `unknownConnectivityPolicy = DEFER`,
 *    direction `PULL`, connectivity `UNKNOWN`) returns
 *    [StrategySynchronizationExecutionResult.Deferred] with a real, non-null
 *    `queueEntryId` -- `StrategyDurableQueueAdmitter` durably persists the
 *    accepted plan via the real `AppleFileQueueProvider` instead of running
 *    the pipeline synchronously. The test-only transport records zero
 *    `pullChanges` calls at this point.
 * 2. **Survives being read back out.** The queue entry is genuinely
 *    acquired back out of the same real on-disk `AppleFileQueueProvider`
 *    snapshot inside the queue-worker cycle, not merely held in memory.
 * 3. **A queue worker genuinely replays it.** [DataLoom.queueWorker]'s
 *    single `run(...)` call deterministically drives exactly one bounded
 *    acquire/execute/complete cycle; `AcceptedStrategyPlanExecutionCoordinator`
 *    resolves the real storage/transport providers from the queue entry's
 *    own persisted bindings and runs the real, registered
 *    `InboundPullSynchronizationPipeline` against real SQLDelight storage
 *    and the test transport.
 * 4. **The result is genuinely observable.** A [SynchronizationObserver]
 *    registered through the real [DataLoomBuilder.observer] capability
 *    captures the terminal [SynchronizationEvent.Completed] emitted by that
 *    replay and asserts `summary.inboundEventsApplied == 1` -- the same bar
 *    `#325`/`#334`/`#337` established.
 *    [QueueProcessingResult.Processed.summary.completed] is asserted as one
 *    additional layer of evidence that the entry reached a real, durable
 *    terminal transition.
 *
 * ## What this does not prove
 *
 * Hybrid's other durable branch (`HybridSource.LOCAL` selected as an
 * explicit fallback from `REMOTE`, `reconcileAfterFallback = true`, the
 * default) -- it always carries `RECONCILE` alongside `ENQUEUE_DURABLE_WORK`
 * with no way to separate them, requiring `StrategyReconciliationProvider`
 * (and, since its immediate operations also carry `SERVE_LOCAL`,
 * `StrategyLocalFallbackProvider` too), which `SqlDelightStorageProvider`
 * does not implement -- mirroring the Android proof's own finding;
 * network-only (cannot admit durable work at all) and adaptive (no branch
 * of its own); retry, circuit-breaker, or conflict-detection behavior
 * during queue replay (this entry always succeeds on its first attempt); a
 * real Apple background-scheduler-triggered tick (this test calls
 * `queueWorker.run(...)` directly, deterministically -- no
 * `dataloom-scheduler-workmanager` Apple counterpart module exists yet);
 * and a physical device (Simulator only).
 *
 * ## A note on how this was verified
 *
 * Like [IosReferenceConsumerDurableQueueTest],
 * [IosReferenceConsumerCacheFirstPullQueueTest], and
 * [IosReferenceConsumerRemoteFirstQueueTest], this file can be
 * cross-compiled from a Windows development host
 * (`compileTestKotlinIosArm64`/`IosSimulatorArm64`/`IosX64`), which catches
 * type errors and API drift, but **cannot be executed** there -- only a
 * real macOS host with Xcode and the iOS Simulator can run
 * `iosSimulatorArm64Test`/`iosX64Test`. This repository's
 * `apple-validation.yml` CI job (`macos-15`) is the actual pass/fail
 * signal for this file's runtime behavior, not local cross-compilation
 * alone.
 */
class IosReferenceConsumerHybridQueueTest {

    @Test
    fun hybridDeferralReplayedByQueueWorker() = runTest {
        val runId = NSUUID().UUIDString
        val directoryPath = buildString {
            append(NSTemporaryDirectory().trimEnd('/'))
            append("/dataloom-ios-reference-consumer-hybrid-queue-")
            append(runId)
        }

        val changeSet = ChangeSet(
            id = ChangeSetId("hybrid-queue-change-set-$runId"),
            events = listOf(
                ChangeEvent(
                    id = ChangeEventId("hybrid-queue-event-$runId"),
                    entity = EntityReference(
                        type = EntityType("hybrid-queue-entity"),
                        id = EntityId("hybrid-queue-entity-$runId"),
                    ),
                    operation = ChangeOperation.CREATE,
                ),
            ),
        )
        val transport = CountingOneChangeSetHybridTransportProvider(changeSet)
        val completedResults = mutableListOf<SynchronizationResult>()
        val observer = object : SynchronizationObserver {
            override val id: SynchronizationObserverId =
                SynchronizationObserverId("durable-queue-hybrid-replay-observer-$runId")
            override fun onEvent(event: SynchronizationEvent) {
                if (event is SynchronizationEvent.Completed) {
                    completedResults += event.result
                }
            }
        }

        val providers = appleDataLoomProviders(
            preRegisteredIdentifiers = emptySet(),
            directoryPath = directoryPath,
            storageDatabaseName = "dataloom-storage-hybrid-queue-$runId.db",
            queueFileName = "dataloom-queue-hybrid-queue-$runId.tsv",
        )

        val dataLoom = buildDurableQueueDataLoom(
            providers = providers,
            transportProvider = transport,
            observer = observer,
        )
        assertEquals(ProviderLifecycleResult.InitializeSuccess, dataLoom.initialize())

        val strategyRequest = StrategySynchronizationRequest(
            request = SynchronizationRequest(
                workflowId = WorkflowId("durable-queue-hybrid-workflow-$runId"),
                sessionId = SynchronizationSessionId("durable-queue-hybrid-session-$runId"),
                direction = SynchronizationDirection.PULL,
                mode = SynchronizationMode.FULL,
                context = ExecutionContext(
                    executionId = ExecutionId("durable-queue-hybrid-execution-$runId"),
                    correlationId = CorrelationId("durable-queue-hybrid-correlation-$runId"),
                ),
            ),
            decisionId = StrategyDecisionId("durable-queue-hybrid-decision-$runId"),
            planId = StrategyPlanId("durable-queue-hybrid-plan-$runId"),
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
        // real on-disk AppleFileQueueProvider snapshot and replay it via
        // exactly one deterministic queue-worker cycle.
        val acquiredAt = AppleDataLoomClock().now()
        val runResult = dataLoom.queueWorker!!.run(
            QueueWorkerRunRequest(
                processingRequest = QueueProcessingRequest(
                    acquireRequest = QueueAcquireRequest(
                        consumerId = QueueConsumerId("simulator-durable-queue-hybrid-consumer-$runId"),
                        leaseId = QueueLeaseId("simulator-durable-queue-hybrid-lease-$runId"),
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
     * `dataloom-platform-ios` helper [installAppleProviders]
     * [IosReferenceConsumerRemoteFirstQueueTest] uses, additionally opting
     * into [DataLoomBuilder.queueSubmissionEncoder] and
     * [DataLoomBuilder.queueWorkerConfiguration].
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
                    retryOperation = RetryOperation("simulator.durable-queue-hybrid-replay"),
                    configuration = QueueWorkerConfiguration(
                        scheduleId = ScheduleId("simulator-durable-queue-hybrid-worker"),
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
     * [IosReferenceConsumerRemoteFirstQueueTest]'s own private helper of the
     * same shape -- real wall clock, secure-random-backed identifier
     * generators. Kept local to this file rather than reaching into that
     * file's private helper, matching this repository's own established
     * convention for these reference-consumer test files.
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
 * [IosReferenceConsumerRemoteFirstQueueTest]'s own
 * `RemoteFirstPassthroughQueuedSynchronizationWorkEncoder` -- no byte
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
    override val id: RetryPolicyId = RetryPolicyId("simulator-durable-queue-hybrid-never-retry")
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
        id = ProviderId("io.dataloom.consumer.ios.test.counting-one-change-set-hybrid-transport"),
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
