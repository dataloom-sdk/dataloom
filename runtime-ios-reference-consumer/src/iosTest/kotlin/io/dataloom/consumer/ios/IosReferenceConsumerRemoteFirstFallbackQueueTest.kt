@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.consumer.ios

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
import io.dataloom.api.time.AppleDataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.platform.ios.AppleDataLoomProviders
import io.dataloom.platform.ios.appleDataLoomProviders
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
 * Kotlin/Native iOS Simulator runtime proof for **remote-first's own
 * non-empty-`fallbackOn` durable branch** -- the iOS counterpart to
 * `AndroidReferenceConsumerRemoteFirstFallbackQueueRobolectricTest` (`#101`
 * market-readiness row). It is the follow-up [IosReferenceConsumerRemoteFirstQueueTest]
 * named as not covered.
 *
 * ## A stale claim in [IosReferenceConsumerRemoteFirstQueueTest]'s KDoc,
 * corrected here
 *
 * That test's "What this does not prove" section says the `fallbackOn`-carrying
 * branch "requires `StrategyLocalFallbackProvider`, which
 * `SqlDelightStorageProvider` does not implement". Re-reading current source,
 * that is no longer true: `SqlDelightStorageProvider` is declared
 * `StorageProvider, StrategyLocalFallbackProvider, StrategyReconciliationProvider`
 * and implements `evaluateLocalFallback` as a bounded existence check over its
 * own checkpoint/inbound-applied tables (which [IosReferenceConsumerHybridReconcileQueueTest]
 * already relies on). The branch is therefore provable on iOS exactly as on
 * Android.
 *
 * ## Why the fallback here is reactive
 *
 * Re-verified against current source (`BuiltInSynchronizationStrategyEvaluator`,
 * `AcceptedStrategyPlanExecutionCoordinator`), identical `commonMain` logic to
 * the Android proof:
 *
 * - With connectivity `UNKNOWN` and `unknownConnectivityPolicy = DEFER`, the
 *   plan is `DEFER` with `[ENQUEUE_DURABLE_WORK]`; the durable continuation is
 *   `[READ_CHECKPOINT, PULL_REMOTE, PERSIST_REMOTE]` and, because `fallbackOn`
 *   is non-empty, carries a non-null `fallbackPlan` (`remoteFallbackPlan`
 *   returns null only for an empty `fallbackOn` or PUSH). A non-null
 *   `fallbackPlan` makes the replay require the storage provider to be a
 *   `StrategyLocalFallbackProvider`.
 * - The continuation does not serve local data up front (no `SERVE_LOCAL`).
 *   Local fallback runs only if the replay's real `PULL_REMOTE` fails with an
 *   outcome contained in `fallbackPlan.remoteOutcomes` (`handleRemoteFailure`).
 *   This test's transport therefore always fails `pullChanges` with a
 *   [io.dataloom.api.strategy.ClassifiedStrategyRemoteError] classified
 *   [StrategyRemoteOutcome.UNAVAILABLE], matching `fallbackOn = setOf(UNAVAILABLE)`.
 *
 * ## A confirmed property: the queue-level disposition is FAILED, not COMPLETED
 *
 * `StrategyQueueExecutionOutcomeMapper.map`'s `FallbackActivated` arm computes
 * `unresolvedErrors = errorsFrom(result.partialOutput)`; the failed
 * `PULL_REMOTE` is carried forward as `partialOutput` (`handleRemoteFailure`
 * -> `executeFallback`), so the list is non-empty and the entry is routed
 * through `evaluateRetry` even though local fallback data was served. With this
 * test's never-retry policy that resolves to `StopRetry` -> `failed(...)`
 * (`QueueFailureDisposition.FAILED`). This was read directly from the mapper,
 * not copied from the Android test's prose. Consequently the queue summary
 * (`failed == 1`, `completed == 0`) and the observer's `Completed` event (a
 * `Failed` result) cannot by themselves distinguish "fallback served" from
 * "fallback unavailable"; direct evidence that the real
 * `SqlDelightStorageProvider.evaluateLocalFallback` ran and reported
 * `Available` comes from [CountingLocalFallbackSqlDelightStorageProvider], a
 * fully delegating wrapper that forwards to the real, unmodified provider and
 * only additionally counts calls and records the result.
 *
 * ## What this proves
 *
 * [remoteFirstFallbackQueueWorkerReplay]:
 *
 * 1. [DataLoom.synchronize] returns
 *    [StrategySynchronizationExecutionResult.Deferred] with a non-null
 *    `queueEntryId`; zero transport calls and zero fallback calls so far.
 * 2. One `queueWorker.run(...)` acquires the entry back out of the real
 *    on-disk `AppleFileQueueProvider` snapshot and replays it through the real
 *    `AcceptedStrategyPlanExecutionCoordinator`.
 * 3. The real transport is attempted exactly once and fails.
 * 4. The real `SqlDelightStorageProvider.evaluateLocalFallback` is invoked
 *    exactly once and reports `Available` (STALE) because of a real checkpoint
 *    seeded beforehand through that same provider.
 * 5. The observer sees a `Failed` result with the simulated transport error,
 *    and the queue summary shows `failed == 1`, `completed == 0`.
 *
 * ## What this does not prove
 *
 * A scenario where `evaluateLocalFallback` reports `Unavailable` (a checkpoint
 * is always seeded); retry, circuit-breaker or conflict behavior beyond the
 * single never-retry stop; a real Apple background-scheduler tick
 * (`queueWorker.run(...)` is called directly); a physical device.
 *
 * ## A note on how this was verified
 *
 * This file can be cross-compiled from a Windows host
 * (`compileTestKotlinIosArm64`/`IosSimulatorArm64`/`IosX64`), which catches
 * type errors and API drift, but **cannot be executed** there. Its first real
 * execution is the `apple-validation.yml` CI job (`macos-15`).
 */
class IosReferenceConsumerRemoteFirstFallbackQueueTest {

    @Test
    fun remoteFirstFallbackQueueWorkerReplay() = runTest {
        val runId = NSUUID().UUIDString
        val directoryPath = buildString {
            append(NSTemporaryDirectory().trimEnd('/'))
            append("/dataloom-ios-reference-consumer-remote-first-fallback-queue-")
            append(runId)
        }

        val transport = FailingPullRemoteFirstFallbackTransportProvider()
        val completedResults = mutableListOf<SynchronizationResult>()
        val observer = object : SynchronizationObserver {
            override val id: SynchronizationObserverId =
                SynchronizationObserverId("durable-queue-remote-first-fallback-replay-observer-$runId")
            override fun onEvent(event: SynchronizationEvent) {
                if (event is SynchronizationEvent.Completed) {
                    completedResults += event.result
                }
            }
        }

        val providers = appleDataLoomProviders(
            preRegisteredIdentifiers = emptySet(),
            directoryPath = directoryPath,
            storageDatabaseName = "dataloom-storage-remote-first-fallback-queue-$runId.db",
            queueFileName = "dataloom-queue-remote-first-fallback-queue-$runId.tsv",
        )
        val fallbackStorage = CountingLocalFallbackSqlDelightStorageProvider(providers.storage)

        // The real SqlDelightStorageProvider.evaluateLocalFallback is a genuine
        // existence check over its own infrastructure tables. Seed one real
        // checkpoint through the SAME provider instance the decorator wraps
        // (before building/initializing DataLoom, matching the Android proof's
        // seed-then-initialize ordering) so the reactive fallback step reports
        // Available once the replay's remote attempt fails below.
        val seedResult = providers.storage.writeCheckpoint(
            CheckpointWriteRequest(
                request = seedRequest(runId),
                checkpoint = SynchronizationCheckpoint(
                    key = CheckpointKey("remote-first-fallback-seed-checkpoint-$runId"),
                    token = CheckpointToken("remote-first-fallback-seed-token-$runId"),
                ),
            ),
        )
        assertEquals(ProviderOperationResult.Success(Unit), seedResult)

        val dataLoom = buildDurableQueueDataLoom(
            providers = providers,
            storageProvider = fallbackStorage,
            transportProvider = transport,
            observer = observer,
        )
        assertEquals(ProviderLifecycleResult.InitializeSuccess, dataLoom.initialize())

        val strategyRequest = StrategySynchronizationRequest(
            request = SynchronizationRequest(
                workflowId = WorkflowId("durable-queue-remote-first-fallback-workflow-$runId"),
                sessionId = SynchronizationSessionId("durable-queue-remote-first-fallback-session-$runId"),
                direction = SynchronizationDirection.PULL,
                mode = SynchronizationMode.FULL,
                context = ExecutionContext(
                    executionId = ExecutionId("durable-queue-remote-first-fallback-execution-$runId"),
                    correlationId = CorrelationId("durable-queue-remote-first-fallback-correlation-$runId"),
                ),
            ),
            decisionId = StrategyDecisionId("durable-queue-remote-first-fallback-decision-$runId"),
            planId = StrategyPlanId("durable-queue-remote-first-fallback-plan-$runId"),
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
        // real on-disk AppleFileQueueProvider snapshot and replay it through
        // exactly one deterministic queue-worker cycle.
        val acquiredAt = AppleDataLoomClock().now()
        val runResult = dataLoom.queueWorker!!.run(
            QueueWorkerRunRequest(
                processingRequest = QueueProcessingRequest(
                    acquireRequest = QueueAcquireRequest(
                        consumerId = QueueConsumerId("simulator-durable-queue-remote-first-fallback-consumer-$runId"),
                        leaseId = QueueLeaseId("simulator-durable-queue-remote-first-fallback-lease-$runId"),
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

        // Step 5: the real, unmodified SqlDelightStorageProvider
        // .evaluateLocalFallback was invoked exactly once (through the fully
        // delegating counting wrapper) and reported Available because of the
        // real checkpoint seeded above.
        assertEquals(1, fallbackStorage.fallbackCalls)
        val fallbackResult =
            assertIs<StrategyLocalFallbackResult.Available>(fallbackStorage.lastFallbackResult)
        assertEquals(StrategyCacheState.STALE, fallbackResult.cacheState)

        // Step 6: the observer's Completed event carries the pipeline's raw,
        // pre-fallback-mapping result -- Failed with exactly the simulated
        // transport error.
        assertEquals(1, completedResults.size)
        val observedFailure = assertIs<SynchronizationResult.Failed>(completedResults.single())
        assertIs<SimulatedRemoteUnavailableError>(observedFailure.error)

        // Step 7: StrategyQueueExecutionOutcomeMapper's FallbackActivated arm
        // still routes this entry through retry evaluation because the original
        // PULL_REMOTE failure remains attached as unresolved evidence; the
        // never-retry policy stops it there, so the persisted disposition is
        // FAILED, not COMPLETED, even though local state was served in step 5.
        assertEquals(0, processingResult.summary.completed)
        assertEquals(1, processingResult.summary.failed)

        assertEquals(ProviderLifecycleResult.ShutdownSuccess, dataLoom.shutdown())
    }

    private fun seedRequest(runId: String): SynchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("durable-queue-remote-first-fallback-seed-workflow-$runId"),
        sessionId = SynchronizationSessionId("durable-queue-remote-first-fallback-seed-session-$runId"),
        direction = SynchronizationDirection.PULL,
        mode = SynchronizationMode.FULL,
        context = ExecutionContext(
            executionId = ExecutionId("durable-queue-remote-first-fallback-seed-execution-$runId"),
            correlationId = CorrelationId("durable-queue-remote-first-fallback-seed-correlation-$runId"),
        ),
    )

    /**
     * Assembles a real [DataLoom] instance from the same production
     * `dataloom-platform-ios` providers every other reference-consumer test
     * uses, except the storage role is registered as
     * [CountingLocalFallbackSqlDelightStorageProvider] (wrapping [providers]'
     * own real [SqlDelightStorageProvider]) instead of `providers.storage`
     * directly -- `installAppleProviders` always registers `providers.storage`
     * itself, and the provider registry rejects two providers sharing one
     * [ProviderId]. The decorator shares the real provider's own id, so every
     * binding below resolves to it exactly as `installAppleProviders` would.
     */
    private fun buildDurableQueueDataLoom(
        providers: AppleDataLoomProviders,
        storageProvider: CountingLocalFallbackSqlDelightStorageProvider,
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
                    retryOperation = RetryOperation("simulator.durable-queue-remote-first-fallback-replay"),
                    configuration = QueueWorkerConfiguration(
                        scheduleId = ScheduleId("simulator-durable-queue-remote-first-fallback-worker"),
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
     * generators. Kept local to this file, matching this repository's
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
 * Thin, fully delegating instrumentation wrapper around a real
 * [SqlDelightStorageProvider]. Every [StrategyLocalFallbackProvider] member
 * (which, being a `StorageProvider` subtype, includes every ordinary storage
 * member) is delegated unchanged to [delegate], except [evaluateLocalFallback],
 * which still forwards to [delegate]'s real database-backed implementation and
 * only additionally counts invocations and records the returned
 * [StrategyLocalFallbackResult]. It exists solely because remote-first's
 * reactive fallback leaves no other externally observable signal that the real
 * provider's own logic ran and reported `Available`.
 */
private class CountingLocalFallbackSqlDelightStorageProvider(
    private val delegate: SqlDelightStorageProvider,
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
 * [IosReferenceConsumerRemoteFirstQueueTest]'s own passthrough encoder -- no
 * byte serialization is needed because [QueueEntry] already carries the
 * request, strategy decision and strategy plan as typed fields.
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
    override val id: RetryPolicyId = RetryPolicyId("simulator-durable-queue-remote-first-fallback-never-retry")
    override fun evaluate(request: RetryEvaluationRequest): RetryDecision =
        RetryDecision.Stop(RetryStopReason.POLICY_REJECTED)
}

/**
 * Test-only error that classifies as [StrategyRemoteOutcome.UNAVAILABLE] via
 * [io.dataloom.api.strategy.ClassifiedStrategyRemoteError] -- matching this
 * test's `fallbackOn = setOf(UNAVAILABLE)` allowlist, so a genuine
 * `PULL_REMOTE` failure is routed into the reactive fallback path instead of a
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
 * Test-only [TransportProvider] whose [pullChanges] always fails with
 * [SimulatedRemoteUnavailableError] and counts calls -- proving durable
 * admission does not execute synchronously (zero calls after admission) and
 * that the queue-worker replay genuinely invokes the real pipeline's remote leg
 * (exactly one call after `run(...)`). Push fails deterministically if called.
 */
private class FailingPullRemoteFirstFallbackTransportProvider : TransportProvider {
    var pullCalls: Int = 0
        private set

    override val descriptor: ProviderDescriptor = ProviderDescriptor(
        id = ProviderId("io.dataloom.consumer.ios.test.failing-pull-remote-first-fallback-transport"),
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
