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
import io.dataloom.runtime.strategy.StrategyExecutionRejectionReason
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
 * Kotlin/Native iOS Simulator runtime proof for cache-first's own
 * PULL-direction `SERVE_LOCAL`-refresh durable branch -- the iOS counterpart
 * to `AndroidReferenceConsumerCacheFirstPullQueueRobolectricTest` (`#101`
 * market-readiness row, closing this gate's own "iOS proof of the PULL
 * branch remains a follow-up" line).
 *
 * ## Investigation: the same branch, re-verified for iOS's real storage provider
 *
 * `BuiltInSynchronizationStrategyEvaluator.evaluateCacheFirst` is `commonMain`
 * code -- identical on every platform. For `SynchronizationDirection.PULL`
 * with `StrategyCacheState.STALE` and `staleCachePolicy =
 * SERVE_STALE_AND_REFRESH` (the profile default), `operations = [SERVE_LOCAL,
 * ENQUEUE_DURABLE_WORK, SCHEDULE_REFRESH]` with `requireDurableRefresh = true`
 * (also the profile default) -- a completely default `CacheFirstStrategyProfile`
 * reaches this branch with no field set away from its default, exactly as the
 * Android proof documents.
 *
 * `CacheFirstStrategyExecutor.serveLocal` rejects this branch synchronously
 * with `LOCAL_FALLBACK_PROVIDER_NOT_CONFIGURED` on any real storage provider
 * that implements only `StorageProvider`. Reading `dataloom-storage-sqldelight`'s
 * `SqlDelightStorageProvider` confirms the identical constraint holds on iOS:
 * it implements only `StorageProvider`, with no `StrategyLocalFallbackProvider`
 * conformance -- the same real gap `RoomStorageProvider` has on Android.
 *
 * But `BuiltInSynchronizationStrategyEvaluator.deriveDurableContinuation`'s
 * `CacheFirstStrategyProfile` arm for PULL/BIDIRECTIONAL never includes
 * `SERVE_LOCAL` -- it is exactly `remoteOperations(direction, persistRemote =
 * true)`, `[READ_CHECKPOINT, PULL_REMOTE, PERSIST_REMOTE]` for PULL --
 * byte-for-byte the same continuation shape `#325`/`#334`'s offline-first
 * proofs (including iOS's own) already exercise. `AcceptedStrategyPlanExecutionCoordinator
 * .validateReplayProviders` checks the *continuation*'s operations, not the
 * immediate plan's, so neither `StrategyLocalFallbackProvider` nor
 * `StrategyReconciliationProvider` is required to replay it -- only
 * `STORAGE`/`TRANSPORT`, which the real, unmodified `SqlDelightStorageProvider`/
 * test transport already satisfy. This is `commonMain` planner logic already
 * proven identical across platforms; this test exists to prove it against a
 * real Kotlin/Native iOS Simulator runtime rather than leave it claimed only
 * by inspection.
 *
 * Separately, `CacheFirstStrategyExecutor.execute` processes
 * `ENQUEUE_DURABLE_WORK` *before* it attempts `SERVE_LOCAL` -- the queue entry
 * is genuinely, durably persisted through the real `AppleFileQueueProvider`
 * before the synchronous rejection is ever returned to the caller.
 *
 * ## What this proves
 *
 * [cacheFirstPullAdmitsDurablyDespiteSyncRejection] exercises both halves
 * honestly, through real, unmodified production code, on a real Kotlin/Native
 * iOS Simulator runtime:
 *
 * 1. A real `DataLoom.synchronize` call for a completely-default
 *    `CacheFirstStrategyProfile` PULL request against a `STALE` cache returns
 *    a genuine `Rejected(LOCAL_FALLBACK_PROVIDER_NOT_CONFIGURED)` -- the
 *    synchronous half is confirmed blocked on iOS too, not assumed from the
 *    Android finding. Zero transport calls occur.
 * 2. Despite that top-level rejection, exactly one real queue entry was
 *    durably admitted underneath it, genuinely acquired back out of the real
 *    on-disk `AppleFileQueueProvider` snapshot by [DataLoom.queueWorker]'s
 *    `run(...)` (which acquires *any* pending entry, never by a caller-known
 *    identifier -- `Rejected` carries no `queueEntryId` field at all).
 * 3. `AcceptedStrategyPlanExecutionCoordinator` genuinely replays the
 *    persisted `durableContinuation` -- never `SERVE_LOCAL` -- a real
 *    `InboundPullSynchronizationPipeline` run against real SQLDelight
 *    storage, reaching a genuine `SynchronizationResult.Succeeded`
 *    (`summary.inboundEventsApplied == 1`), observed via a real
 *    `SynchronizationObserver`.
 *
 * The STALE+`SERVE_STALE_AND_REFRESH` branch exercised here and the
 * FRESH+`refreshOnFreshHit=true` branch share the exact same `operations`/
 * `deriveDurableContinuation` shape (only the cache-state guard differs), so
 * this proof covers both by inspection, mirroring the Android proof's own
 * scope decision; BIDIRECTIONAL shares the same continuation shape plus a
 * leading `READ_LOCAL`/`PUSH_REMOTE` pair, also covered by inspection.
 *
 * ## What this does not prove
 *
 * A public API path that actually returns this durably-admitted entry's
 * `queueEntryId` to the caller (none exists today -- the same real, narrow
 * gap the Android proof already surfaced and left out of scope);
 * retry/circuit-breaker/conflict-detection behavior during replay; a real
 * background-scheduler-triggered tick (no Apple `WorkManager` counterpart
 * exists yet); a physical device (Simulator only).
 *
 * ## A note on how this was verified
 *
 * Like [IosReferenceConsumerDurableQueueTest] and
 * [IosReferenceConsumerCacheFirstQueueTest], this file can be cross-compiled
 * from a Windows development host
 * (`compileTestKotlinIosArm64`/`IosSimulatorArm64`/`IosX64`), which catches
 * type errors and API drift, but **cannot be executed** there -- only a real
 * macOS host with Xcode and the iOS Simulator can run
 * `iosSimulatorArm64Test`/`iosX64Test`. This repository's
 * `apple-validation.yml` CI job (`macos-15`) is the actual pass/fail signal
 * for this file's runtime behavior, not local cross-compilation alone.
 */
class IosReferenceConsumerCacheFirstPullQueueTest {

    @Test
    fun cacheFirstPullAdmitsDurablyDespiteSyncRejection() = runTest {
        val runId = NSUUID().UUIDString
        val directoryPath = buildString {
            append(NSTemporaryDirectory().trimEnd('/'))
            append("/dataloom-ios-reference-consumer-cache-first-pull-queue-")
            append(runId)
        }

        val changeSet = ChangeSet(
            id = ChangeSetId("cache-first-pull-queue-change-set-$runId"),
            events = listOf(
                ChangeEvent(
                    id = ChangeEventId("cache-first-pull-queue-event-$runId"),
                    entity = EntityReference(
                        type = EntityType("cache-first-pull-queue-entity"),
                        id = EntityId("cache-first-pull-queue-entity-$runId"),
                    ),
                    operation = ChangeOperation.CREATE,
                ),
            ),
        )
        val transport = CountingOneChangeSetCacheFirstPullTransportProvider(changeSet)
        val completedResults = mutableListOf<SynchronizationResult>()
        val observer = object : SynchronizationObserver {
            override val id: SynchronizationObserverId =
                SynchronizationObserverId("durable-queue-cache-first-pull-replay-observer-$runId")
            override fun onEvent(event: SynchronizationEvent) {
                if (event is SynchronizationEvent.Completed) {
                    completedResults += event.result
                }
            }
        }

        val providers = appleDataLoomProviders(
            preRegisteredIdentifiers = emptySet(),
            directoryPath = directoryPath,
            storageDatabaseName = "dataloom-storage-cache-first-pull-queue-$runId.db",
            queueFileName = "dataloom-queue-cache-first-pull-queue-$runId.tsv",
        )

        val dataLoom = buildDurableQueueDataLoom(
            providers = providers,
            transportProvider = transport,
            observer = observer,
        )
        assertEquals(ProviderLifecycleResult.InitializeSuccess, dataLoom.initialize())

        // A completely default CacheFirstStrategyProfile: staleCachePolicy
        // defaults to SERVE_STALE_AND_REFRESH, requireDurableRefresh
        // defaults to true. No field is set away from its default to reach
        // this branch.
        val strategyRequest = StrategySynchronizationRequest(
            request = SynchronizationRequest(
                workflowId = WorkflowId("durable-queue-cache-first-pull-workflow-$runId"),
                sessionId = SynchronizationSessionId("durable-queue-cache-first-pull-session-$runId"),
                direction = SynchronizationDirection.PULL,
                mode = SynchronizationMode.FULL,
                context = ExecutionContext(
                    executionId = ExecutionId("durable-queue-cache-first-pull-execution-$runId"),
                    correlationId = CorrelationId("durable-queue-cache-first-pull-correlation-$runId"),
                ),
            ),
            decisionId = StrategyDecisionId("durable-queue-cache-first-pull-decision-$runId"),
            planId = StrategyPlanId("durable-queue-cache-first-pull-plan-$runId"),
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

        // Step 1: the synchronous half is genuinely, honestly blocked on iOS
        // too -- not Deferred with a discoverable queueEntryId, but a real
        // Rejected, mirroring the Android proof for the real SqlDelightStorageProvider.
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
        val acquiredAt = AppleDataLoomClock().now()
        val runResult = dataLoom.queueWorker!!.run(
            QueueWorkerRunRequest(
                processingRequest = QueueProcessingRequest(
                    acquireRequest = QueueAcquireRequest(
                        consumerId = QueueConsumerId("simulator-durable-queue-cache-first-pull-consumer-$runId"),
                        leaseId = QueueLeaseId("simulator-durable-queue-cache-first-pull-lease-$runId"),
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
     * `dataloom-platform-ios` helper [installAppleProviders]
     * [IosReferenceConsumerCacheFirstQueueTest.buildDurableQueueDataLoom]
     * uses, additionally opting into [DataLoomBuilder.queueSubmissionEncoder]
     * and [DataLoomBuilder.queueWorkerConfiguration].
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
                    retryOperation = RetryOperation("simulator.durable-queue-cache-first-pull-replay"),
                    configuration = QueueWorkerConfiguration(
                        scheduleId = ScheduleId("simulator-durable-queue-cache-first-pull-worker"),
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
     * [IosReferenceConsumerCacheFirstQueueTest]'s own private helper of the
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
 * [IosReferenceConsumerCacheFirstQueueTest]'s own
 * `CacheFirstPassthroughQueuedSynchronizationWorkEncoder` -- no byte
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
    override val id: RetryPolicyId = RetryPolicyId("simulator-durable-queue-cache-first-pull-never-retry")
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
private class CountingOneChangeSetCacheFirstPullTransportProvider(
    private val changeSet: ChangeSet,
) : TransportProvider {
    var pullCalls: Int = 0
        private set

    override val descriptor: ProviderDescriptor = ProviderDescriptor(
        id = ProviderId("io.dataloom.consumer.ios.test.counting-one-change-set-cache-first-pull-transport"),
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
        error("CountingOneChangeSetCacheFirstPullTransportProvider does not support push.")

    override suspend fun pullChanges(
        request: PullChangesRequest,
    ): ProviderOperationResult<PullChangesResult> {
        pullCalls++
        return ProviderOperationResult.Success(PullChangesResult.Changes(changeSet = changeSet, hasMore = false))
    }
}
