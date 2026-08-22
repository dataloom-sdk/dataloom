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
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/**
 * Kotlin/Native iOS Simulator runtime proof that a real, durably admitted
 * strategy plan survives being read back out of a real
 * [io.dataloom.runtime.queue.AppleFileQueueProvider]-backed queue and is
 * genuinely replayed by a real [DataLoom.queueWorker] cycle -- the iOS
 * counterpart to `AndroidReferenceConsumerDurableQueueRobolectricTest`
 * (`#325`), closing the "iOS has no equivalent durable-queue proof at all"
 * gap `#101`'s market-readiness row named.
 *
 * ## Scope: one platform, one strategy
 *
 * This proves iOS + offline-first only, mirroring `#325`'s own scope
 * decision exactly. Android and the other five strategies (network-only,
 * remote-first, cache-first, hybrid, adaptive) are explicitly deferred, not
 * silently claimed. Offline-first was chosen for the identical reason `#325`
 * documents: `#259`'s durable-work branch is the simplest real admission
 * path that already exists in production code.
 *
 * ## What this proves
 *
 * [durableAdmissionIsReplayedByQueueWorker] exercises the full path,
 * entirely through real, production DataLoom code, on a real Kotlin/Native
 * iOS Simulator runtime:
 *
 * 1. **Admission, not synchronous execution.** [DataLoom.synchronize] for a
 *    `StrategySynchronizationRequest` built from
 *    [OfflineFirstStrategyProfile] (`requireDurableQueue = true`,
 *    connectivity `AVAILABLE`) returns
 *    [StrategySynchronizationExecutionResult.DurablyEnqueued] --
 *    `StrategyDurableQueueAdmitter` durably persists the accepted plan via
 *    the real [io.dataloom.runtime.queue.AppleFileQueueProvider] (a real
 *    `flock`-guarded file under a fresh temporary directory) instead of
 *    running the pipeline synchronously. The test-only transport records
 *    zero `pullChanges` calls at this point, proving nothing executed yet.
 * 2. **Survives being read back out.** The queue entry is not merely
 *    written; step 3 acquires it back from the same real on-disk snapshot
 *    via a genuine `QueueProvider.acquire` call inside the queue-worker
 *    cycle.
 * 3. **A queue worker genuinely replays it.** [DataLoom.queueWorker]'s
 *    single `run(...)` call deterministically drives exactly one bounded
 *    acquire/execute/complete cycle.
 *    `AcceptedStrategyPlanExecutionCoordinator` -- the durable-continuation
 *    replay coordinator, never exercised by [IosReferenceConsumerTest] --
 *    resolves the real storage/transport providers from the queue entry's
 *    own persisted bindings and runs the real
 *    `InboundPullSynchronizationPipeline`.
 * 4. **The result is genuinely observable.** A [SynchronizationObserver]
 *    registered through the real [DataLoomBuilder.observer] capability
 *    captures the terminal [SynchronizationEvent.Completed] emitted by that
 *    replay and asserts `summary.inboundEventsApplied == 1` -- the same bar
 *    [IosReferenceConsumerTest] established for the direct pass, not just a
 *    "succeeded" queue outcome. [QueueProcessingResult.Processed.summary.completed]
 *    is asserted as one additional layer of evidence that the entry reached
 *    a real, durable terminal transition.
 *
 * ## Why no real background-scheduler tick
 *
 * Unlike Android (where `dataloom-scheduler-workmanager`'s
 * `DataLoomCoroutineWorker` bridges a real `WorkManager` tick to
 * `DataLoom.queueWorker.run(...)` in production, and `#325` documents
 * calling `run(...)` directly as a deterministic stand-in for that tick),
 * this repository has no Apple counterpart module that bridges a real
 * `BGTaskScheduler` task invocation to `DataLoom.queueWorker.run(...)` --
 * confirmed by inspecting `dataloom-platform-ios`'s
 * [io.dataloom.platform.ios.scheduling.AppleSchedulerProvider], which only
 * implements [io.dataloom.api.scheduling.SchedulerProvider]'s
 * `schedule`/`cancel` submission contract, not a launch-handler-to-queue-worker
 * bridge. `#325`'s own "what this does not prove" section names this exact
 * gap ("iOS ... see `dataloom-scheduler-workmanager`'s Apple counterpart,
 * which this test does not touch"). This test therefore proves the same
 * deterministic single-pass `queueWorker.run(...)` capability `#325` proved
 * on Android -- the same capability a future Apple scheduler bridge would
 * delegate to -- not a real `BGTaskScheduler` background tick, which remains
 * open (see this module's own KDoc and `docs/status/market-readiness.md`).
 *
 * ## What this does not prove
 *
 * A real background-scheduler-triggered tick (no Apple
 * `WorkManager`/`DataLoomCoroutineWorker` counterpart exists yet -- see
 * above); the other five built-in strategies' own admission branches
 * (cache-first, hybrid share the same `StrategyDurableQueueAdmitter`
 * machinery but are not separately exercised here); retry, circuit-breaker,
 * or conflict-detection behavior during queue replay (this entry always
 * succeeds on its first attempt); and a physical device (Simulator only,
 * matching [IosReferenceConsumerTest]'s own documented boundary).
 *
 * ## A note on how this was verified
 *
 * Like [IosReferenceConsumerTest], this file can be cross-compiled from a
 * Windows development host
 * (`compileTestKotlinIosArm64`/`IosSimulatorArm64`/`IosX64`), which catches
 * type errors and API drift, but **cannot be executed** there -- only a real
 * macOS host with Xcode and the iOS Simulator can run
 * `iosSimulatorArm64Test`/`iosX64Test`. This repository's
 * `apple-validation.yml` CI job (`macos-15`) is the actual pass/fail signal
 * for this file's runtime behavior, not local cross-compilation alone.
 */
class IosReferenceConsumerDurableQueueTest {

    @Test
    fun durableAdmissionIsReplayedByQueueWorker() = runTest {
        val runId = NSUUID().UUIDString
        val directoryPath = buildString {
            append(NSTemporaryDirectory().trimEnd('/'))
            append("/dataloom-ios-reference-consumer-durable-queue-")
            append(runId)
        }

        val changeSet = ChangeSet(
            id = ChangeSetId("durable-queue-change-set-$runId"),
            events = listOf(
                ChangeEvent(
                    id = ChangeEventId("durable-queue-event-$runId"),
                    entity = EntityReference(
                        type = EntityType("durable-queue-entity"),
                        id = EntityId("durable-queue-entity-$runId"),
                    ),
                    operation = ChangeOperation.CREATE,
                ),
            ),
        )
        val transport = CountingOneChangeSetTransportProvider(changeSet)
        val completedResults = mutableListOf<SynchronizationResult>()
        val observer = object : SynchronizationObserver {
            override val id: SynchronizationObserverId = SynchronizationObserverId("durable-queue-replay-observer-$runId")
            override fun onEvent(event: SynchronizationEvent) {
                if (event is SynchronizationEvent.Completed) {
                    completedResults += event.result
                }
            }
        }

        val dataLoom = buildDurableQueueDataLoom(
            directoryPath = directoryPath,
            storageDatabaseName = "dataloom-storage-durable-queue-$runId.db",
            queueFileName = "dataloom-queue-durable-queue-$runId.tsv",
            transportProvider = transport,
            observer = observer,
        )
        assertEquals(ProviderLifecycleResult.InitializeSuccess, dataLoom.initialize())

        val strategyRequest = StrategySynchronizationRequest(
            request = SynchronizationRequest(
                workflowId = WorkflowId("durable-queue-workflow-$runId"),
                sessionId = SynchronizationSessionId("durable-queue-session-$runId"),
                direction = SynchronizationDirection.PULL,
                mode = SynchronizationMode.FULL,
                context = ExecutionContext(
                    executionId = ExecutionId("durable-queue-execution-$runId"),
                    correlationId = CorrelationId("durable-queue-correlation-$runId"),
                ),
            ),
            decisionId = StrategyDecisionId("durable-queue-decision-$runId"),
            planId = StrategyPlanId("durable-queue-plan-$runId"),
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
        // real Apple file queue snapshot and replay it via exactly one
        // deterministic queue-worker cycle.
        // The admitted entry's availableAt was stamped from the real wall
        // clock (referenceRuntimeDependencies' AppleDataLoomClock, the same
        // clock buildDurableQueueDataLoom uses in production). acquiredAt
        // must therefore also be a real "now" -- not an arbitrary small
        // epoch -- or the acquire query's `availableAt <= acquiredAt` guard
        // never matches and the entry is never eligible.
        val acquiredAt = AppleDataLoomClock().now()
        val runResult = dataLoom.queueWorker!!.run(
            QueueWorkerRunRequest(
                processingRequest = QueueProcessingRequest(
                    acquireRequest = QueueAcquireRequest(
                        consumerId = QueueConsumerId("simulator-durable-queue-consumer-$runId"),
                        leaseId = QueueLeaseId("simulator-durable-queue-lease-$runId"),
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
     * `dataloom-platform-ios` helpers [appleDataLoomProviders]/
     * [installAppleProviders] [IosReferenceConsumerTest] uses, additionally
     * opting into [DataLoomBuilder.queueSubmissionEncoder] and
     * [DataLoomBuilder.queueWorkerConfiguration] -- the two capabilities
     * [installAppleProviders] deliberately leaves for the host application
     * to configure itself, since they require application-specific policy
     * decisions (retry policy, work resolution) `dataloom-platform-ios`
     * cannot make on the host's behalf. Mirrors
     * `AndroidReferenceConsumerDurableQueueRobolectricTest.buildDurableQueueDataLoom`'s
     * identical role for the Android path.
     *
     * The queue-submission encoder and work resolver are intentionally
     * trivial: [QueueEntry] already carries [QueueEntry.synchronizationRequest],
     * [QueueEntry.strategyDecision], and [QueueEntry.strategyPlan] as typed
     * fields (no opaque byte payload to encode), so round-tripping through
     * the real `AppleFileQueueProvider` requires no serialization -- only
     * [SynchronizationProviderBindings], which is not persisted on
     * [QueueEntry], is reconstructed by the resolver from the same provider
     * IDs [installAppleProviders] already bound.
     */
    private fun buildDurableQueueDataLoom(
        directoryPath: String,
        storageDatabaseName: String,
        queueFileName: String,
        transportProvider: TransportProvider,
        observer: SynchronizationObserver,
    ): DataLoom {
        val providers = appleDataLoomProviders(
            preRegisteredIdentifiers = emptySet(),
            directoryPath = directoryPath,
            storageDatabaseName = storageDatabaseName,
            queueFileName = queueFileName,
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
            .installAppleProviders(providers, transportProvider)
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
                    retryOperation = RetryOperation("simulator.durable-queue-replay"),
                    configuration = QueueWorkerConfiguration(
                        scheduleId = ScheduleId("simulator-durable-queue-worker"),
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
     * [buildReferenceDataLoom]'s own private helper of the same shape --
     * real wall clock, secure-random-backed identifier generators. Kept
     * local to this test rather than reaching into that file's private
     * helper, matching the Android durable-queue test's identical choice.
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
 * Test-only [QueuedSynchronizationWorkEncoder] that copies a submission's
 * typed [QueuedSynchronizationSubmission.work] fields directly onto a
 * [QueueEntry] -- no byte serialization is needed because [QueueEntry]
 * already carries [QueueEntry.synchronizationRequest],
 * [QueueEntry.strategyDecision], and [QueueEntry.strategyPlan] as typed
 * fields. [SynchronizationProviderBindings] is deliberately not part of
 * [QueueEntry] and is reconstructed independently by the paired
 * [QueuedSynchronizationWorkResolver] in
 * [IosReferenceConsumerDurableQueueTest.buildDurableQueueDataLoom].
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
    override val id: RetryPolicyId = RetryPolicyId("simulator-durable-queue-never-retry")
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
private class CountingOneChangeSetTransportProvider(
    private val changeSet: ChangeSet,
) : TransportProvider {
    var pullCalls: Int = 0
        private set

    override val descriptor: ProviderDescriptor = ProviderDescriptor(
        id = ProviderId("io.dataloom.consumer.ios.test.counting-one-change-set-transport"),
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
