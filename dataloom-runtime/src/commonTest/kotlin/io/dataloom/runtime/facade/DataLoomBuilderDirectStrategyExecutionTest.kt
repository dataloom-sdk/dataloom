package io.dataloom.runtime.facade

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.IdentifierGenerator
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.SynchronizationEventId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
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
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.runtime.RuntimeIdentifierGenerators
import io.dataloom.api.strategy.NetworkOnlyStrategyProfile
import io.dataloom.api.strategy.OfflineFirstStrategyProfile
import io.dataloom.api.strategy.RemoteFirstStrategyProfile
import io.dataloom.api.strategy.StrategyCacheState
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyConnectivity
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyExecutionTrigger
import io.dataloom.api.strategy.StrategyOperationInput
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.strategy.StrategyProviderHealth
import io.dataloom.api.strategy.StrategyRuntimeEvidence
import io.dataloom.api.strategy.StrategySynchronizationRequest
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.runtime.strategy.StrategyExecutionRejectionReason
import io.dataloom.runtime.strategy.StrategySynchronizationExecutionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Direct tests for [io.dataloom.runtime.strategy.StrategySynchronizationExecutionCoordinator]'s
 * admission and rejection logic, exercised through the unprotected
 * `DataLoom.synchronize(request, bindings)` entry point.
 *
 * `StrategySynchronizationExecutionCoordinator` previously had no direct test
 * file — its dispatch to `NetworkOnlyStrategyExecutor`/`RemoteFirstStrategyExecutor`
 * is covered indirectly by [DataLoomBuilderProtectedStrategyTest] and the direct
 * executor test suites, but the coordinator's own admission gates
 * (`PROVIDERS_NOT_INITIALIZED`, strategy `REJECT`/`DEFER` disposition,
 * `INCOMPATIBLE_TRIGGER`, `INCOMPATIBLE_INPUT`, and
 * `PROVIDER_RESOLUTION_FAILED`) had no dedicated coverage anywhere.
 * `UNSUPPORTED_PLAN` was covered here too until `HybridStrategyExecutor`
 * shipped and closed off the last reachable plan shape that hit it — see
 * the removal note further down this file.
 */
class DataLoomBuilderDirectStrategyExecutionTest {

    @Test
    fun synchronizeBeforeInitializeIsRejected() = runTest {
        val transport = RecordingTransportProvider()
        val bindings = StrategyProviderBindings(transportProviderId = transport.descriptor.id)
        val dataLoom = builder(transport, bindings).build()

        val result = dataLoom.synchronize(networkOnlyRequest(), bindings)

        val rejected = assertIs<StrategySynchronizationExecutionResult.Rejected>(result)
        assertEquals(
            StrategyExecutionRejectionReason.PROVIDERS_NOT_INITIALIZED,
            rejected.reason,
        )
    }

    @Test
    fun strategyRejectedByPolicyIsRejected() = runTest {
        val transport = RecordingTransportProvider()
        val bindings = StrategyProviderBindings(transportProviderId = transport.descriptor.id)
        val dataLoom = builder(transport, bindings).build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())

        val request = StrategySynchronizationRequest(
            request = synchronizationRequest("remote-first-rejected", SynchronizationDirection.PULL),
            decisionId = StrategyDecisionId("direct-remote-first-decision"),
            planId = StrategyPlanId("direct-remote-first-plan"),
            profile = RemoteFirstStrategyProfile(
                id = StrategyProfileId("direct-remote-first-profile"),
                configurationVersion = StrategyConfigurationVersion(1L),
            ),
            evidence = StrategyRuntimeEvidence(
                connectivity = StrategyConnectivity.UNAVAILABLE,
                cacheState = StrategyCacheState.MISSING,
            ),
            input = StrategyOperationInput.ProviderBacked,
        )

        val result = dataLoom.synchronize(request, bindings)

        val rejected = assertIs<StrategySynchronizationExecutionResult.Rejected>(result)
        assertEquals(StrategyExecutionRejectionReason.STRATEGY_REJECTED, rejected.reason)
    }

    @Test
    fun deferredDispositionIsReturnedAsDeferred() = runTest {
        val transport = RecordingTransportProvider()
        val bindings = StrategyProviderBindings(transportProviderId = transport.descriptor.id)
        val dataLoom = builder(transport, bindings).build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())

        val request = StrategySynchronizationRequest(
            request = synchronizationRequest("offline-first-deferred", SynchronizationDirection.PULL),
            decisionId = StrategyDecisionId("direct-offline-first-decision"),
            planId = StrategyPlanId("direct-offline-first-plan"),
            profile = OfflineFirstStrategyProfile(
                id = StrategyProfileId("direct-offline-first-profile"),
                configurationVersion = StrategyConfigurationVersion(1L),
            ),
            evidence = StrategyRuntimeEvidence(connectivity = StrategyConnectivity.UNAVAILABLE),
            input = StrategyOperationInput.ProviderBacked,
        )

        val result = dataLoom.synchronize(request, bindings)

        assertIs<StrategySynchronizationExecutionResult.Deferred>(result)
    }

    @Test
    fun durableQueueTriggerIsIncompatibleWithDirectExecution() = runTest {
        val transport = RecordingTransportProvider()
        val bindings = StrategyProviderBindings(transportProviderId = transport.descriptor.id)
        val dataLoom = builder(transport, bindings).build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())

        val request = networkOnlyRequest(trigger = StrategyExecutionTrigger.DURABLE_QUEUE)

        val result = dataLoom.synchronize(request, bindings)

        val rejected = assertIs<StrategySynchronizationExecutionResult.Rejected>(result)
        assertEquals(StrategyExecutionRejectionReason.INCOMPATIBLE_TRIGGER, rejected.reason)
    }

    @Test
    fun wrongInputTypeForNetworkOnlyIsIncompatible() = runTest {
        val transport = RecordingTransportProvider()
        val bindings = StrategyProviderBindings(transportProviderId = transport.descriptor.id)
        val dataLoom = builder(transport, bindings).build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())

        val request = networkOnlyRequest(input = StrategyOperationInput.ProviderBacked)

        val result = dataLoom.synchronize(request, bindings)

        val rejected = assertIs<StrategySynchronizationExecutionResult.Rejected>(result)
        assertEquals(StrategyExecutionRejectionReason.INCOMPATIBLE_INPUT, rejected.reason)
    }

    // NOTE: `strategyWithoutABuiltInExecutorIsUnsupported` (asserting
    // UNSUPPORTED_PLAN for a strategy with no built-in executor) was removed
    // once HybridStrategyExecutor shipped. It previously used
    // HybridStrategyProfile — the last concrete strategy without an
    // executor at the time — and its own comment already noted "Adaptive
    // would work equally well here". That's no longer true: Adaptive always
    // resolves to one of the five now-supported concrete strategies (or a
    // REJECT-disposition plan that never reaches the coordinator's
    // effectiveStrategy dispatch at all), so there is no longer any plan
    // shape reachable through the public evaluator that hits this
    // coordinator's UNSUPPORTED_PLAN branch. See the "NOTE" comment on
    // StrategySynchronizationExecutionCoordinator's own dispatch for the
    // full explanation of why that branch is kept as defensive dead code.

    @Test
    fun unknownTransportBindingFailsProviderResolution() = runTest {
        val transport = RecordingTransportProvider()
        val realBindings = StrategyProviderBindings(transportProviderId = transport.descriptor.id)
        val dataLoom = builder(transport, realBindings).build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())

        val unknownBindings = StrategyProviderBindings(
            transportProviderId = ProviderId("unregistered-transport"),
        )

        val result = dataLoom.synchronize(networkOnlyRequest(), unknownBindings)

        val rejected = assertIs<StrategySynchronizationExecutionResult.Rejected>(result)
        assertEquals(StrategyExecutionRejectionReason.PROVIDER_RESOLUTION_FAILED, rejected.reason)
        assertTrue(rejected.bindingFailures.isNotEmpty())
    }

    // -------------------------------------------------------------------------
    // DEFER durable admission — previously a bare in-memory Deferred, now
    // real queue admission once a QueuedSynchronizationWorkEncoder is
    // configured on the builder (the same encoder DataLoom.queueSubmission
    // already uses for the manual submission path).
    // -------------------------------------------------------------------------

    @Test
    fun deferWithoutAnEncoderConfiguredStaysABareInMemorySignal() = runTest {
        val transport = RecordingTransportProvider()
        val storage = RecordingStorageProvider()
        val bindings = StrategyProviderBindings(
            storageProviderId = storage.descriptor.id,
            transportProviderId = transport.descriptor.id,
        )
        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(runtimeDependencies())
            .provider(transport)
            .provider(storage)
            .defaultStrategyProviderBindings(bindings)
            .build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())

        val result = dataLoom.synchronize(offlineFirstDeferredPushRequest(), bindings)

        val deferred = assertIs<StrategySynchronizationExecutionResult.Deferred>(result)
        assertEquals(null, deferred.queueEntryId)
    }

    @Test
    fun deferWithAnEncoderConfiguredIsDurablyAdmittedForReal() = runTest {
        val transport = RecordingTransportProvider()
        val storage = RecordingStorageProvider()
        val queue = RecordingQueueProvider()
        val bindings = StrategyProviderBindings(
            storageProviderId = storage.descriptor.id,
            transportProviderId = transport.descriptor.id,
            queueProviderId = queue.descriptor.id,
        )
        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(runtimeDependencies())
            .provider(transport)
            .provider(storage)
            .provider(queue)
            .defaultStrategyProviderBindings(bindings)
            .defaultProviderBindings(
                io.dataloom.api.provider.SynchronizationProviderBindings(
                    storageProviderId = storage.descriptor.id,
                    transportProviderId = transport.descriptor.id,
                    queueProviderId = queue.descriptor.id,
                ),
            )
            .queueSubmissionEncoder(RecordingEncoder())
            .build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())

        val result = dataLoom.synchronize(offlineFirstDeferredPushRequest(), bindings)

        val deferred = assertIs<StrategySynchronizationExecutionResult.Deferred>(result)
        assertEquals(QueueEntryId("direct-strategy-queue-entry"), deferred.queueEntryId)
        assertEquals(1, queue.enqueueCalls)
    }

    @Test
    fun deferWithAnEncoderConfiguredButProviderFailureIsARealFailureNotASilentDefer() = runTest {
        val transport = RecordingTransportProvider()
        val storage = RecordingStorageProvider()
        val providerError = object : io.dataloom.api.error.DataLoomError {
            override val code = io.dataloom.api.error.ErrorCode("ENQUEUE_UNAVAILABLE")
            override val category = io.dataloom.api.error.ErrorCategory.NETWORK
            override val severity = io.dataloom.api.error.ErrorSeverity.ERROR
            override val recoverability = io.dataloom.api.error.Recoverability.RECOVERABLE
            override val message = "test durable-admission provider failure"
            override val cause: Throwable? = null
        }
        val queue = RecordingQueueProvider(enqueueResult = ProviderOperationResult.Failure(providerError))
        val bindings = StrategyProviderBindings(
            storageProviderId = storage.descriptor.id,
            transportProviderId = transport.descriptor.id,
            queueProviderId = queue.descriptor.id,
        )
        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(runtimeDependencies())
            .provider(transport)
            .provider(storage)
            .provider(queue)
            .defaultStrategyProviderBindings(bindings)
            .defaultProviderBindings(
                io.dataloom.api.provider.SynchronizationProviderBindings(
                    storageProviderId = storage.descriptor.id,
                    transportProviderId = transport.descriptor.id,
                    queueProviderId = queue.descriptor.id,
                ),
            )
            .queueSubmissionEncoder(RecordingEncoder())
            .build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())

        val result = dataLoom.synchronize(offlineFirstDeferredPushRequest(), bindings)

        val failed = assertIs<StrategySynchronizationExecutionResult.Failed>(result)
        assertEquals(providerError, failed.error)
    }

    private fun offlineFirstDeferredPushRequest(): StrategySynchronizationRequest =
        StrategySynchronizationRequest(
            request = synchronizationRequest("offline-first-defer", SynchronizationDirection.PUSH),
            decisionId = StrategyDecisionId("direct-offline-first-defer-decision"),
            planId = StrategyPlanId("direct-offline-first-defer-plan"),
            profile = io.dataloom.api.strategy.OfflineFirstStrategyProfile(
                id = StrategyProfileId("direct-offline-first-defer-profile"),
                configurationVersion = StrategyConfigurationVersion(1L),
                requireDurableQueue = true,
            ),
            evidence = StrategyRuntimeEvidence(connectivity = StrategyConnectivity.UNAVAILABLE),
            input = StrategyOperationInput.ProviderBacked,
        )

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private fun builder(
        transport: RecordingTransportProvider,
        bindings: StrategyProviderBindings,
    ): DataLoomBuilder = DataLoomBuilder()
        .runtimeDependencies(runtimeDependencies())
        .provider(transport)
        .defaultStrategyProviderBindings(bindings)

    private fun networkOnlyRequest(
        trigger: StrategyExecutionTrigger = StrategyExecutionTrigger.DIRECT,
        input: StrategyOperationInput = StrategyOperationInput.DirectTransport(),
    ): StrategySynchronizationRequest = StrategySynchronizationRequest(
        request = synchronizationRequest("network-only-direct", SynchronizationDirection.PULL),
        decisionId = StrategyDecisionId("direct-network-only-decision"),
        planId = StrategyPlanId("direct-network-only-plan"),
        profile = NetworkOnlyStrategyProfile(
            id = StrategyProfileId("direct-network-only-profile"),
            configurationVersion = StrategyConfigurationVersion(1L),
        ),
        evidence = StrategyRuntimeEvidence(
            connectivity = StrategyConnectivity.AVAILABLE,
            transportHealth = StrategyProviderHealth.HEALTHY,
        ),
        trigger = trigger,
        input = input,
    )

    private fun synchronizationRequest(
        suffix: String,
        direction: SynchronizationDirection,
    ): SynchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("direct-strategy-$suffix-workflow"),
        sessionId = SynchronizationSessionId("direct-strategy-$suffix-session"),
        direction = direction,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("direct-strategy-$suffix-execution"),
            correlationId = CorrelationId("direct-strategy-$suffix-correlation"),
        ),
    )

    private fun runtimeDependencies(): RuntimeDependencies = RuntimeDependencies(
        clock = FixedDataLoomClock(DataLoomInstant(epochMilliseconds = 9_000L)),
        identifiers = RuntimeIdentifierGenerators(
            synchronizationEventIds = generator { SynchronizationEventId("direct-strategy-event") },
            queueEntryIds = generator { QueueEntryId("direct-strategy-queue-entry") },
            queueLeaseIds = generator { QueueLeaseId("direct-strategy-queue-lease") },
            conflictIds = generator { ConflictId("direct-strategy-conflict") },
        ),
    )

    private fun <T> generator(block: () -> T): IdentifierGenerator<T> =
        object : IdentifierGenerator<T> {
            override fun generate(): T = block()
        }

    private class FixedDataLoomClock(private val instant: DataLoomInstant) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
    }

    private class RecordingTransportProvider(
        private val pullResult: ProviderOperationResult<PullChangesResult> =
            ProviderOperationResult.Success(PullChangesResult.NoChanges()),
    ) : TransportProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("direct-strategy-transport"),
            name = ProviderName("Direct Strategy Transport"),
            type = ProviderType.TRANSPORT,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun pushChanges(
            request: PushChangesRequest,
        ): ProviderOperationResult<ChangeSetAcknowledgement> =
            ProviderOperationResult.Failure(
                object : io.dataloom.api.error.DataLoomError {
                    override val code = io.dataloom.api.error.ErrorCode("PUSH_UNUSED")
                    override val category = io.dataloom.api.error.ErrorCategory.NETWORK
                    override val severity = io.dataloom.api.error.ErrorSeverity.ERROR
                    override val recoverability = io.dataloom.api.error.Recoverability.RECOVERABLE
                    override val message = "Push not exercised in this test."
                    override val cause: Throwable? = null
                },
            )

        override suspend fun pullChanges(
            request: PullChangesRequest,
        ): ProviderOperationResult<PullChangesResult> = pullResult
    }

    private class RecordingStorageProvider : io.dataloom.api.storage.StorageProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("direct-strategy-storage"),
            name = ProviderName("Direct Strategy Storage"),
            type = ProviderType.STORAGE,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun readOutboundChanges(
            request: io.dataloom.api.storage.OutboundChangeReadRequest,
        ): ProviderOperationResult<io.dataloom.api.storage.OutboundChangeReadResult> =
            ProviderOperationResult.Success(io.dataloom.api.storage.OutboundChangeReadResult.NoChanges)

        override suspend fun applyInboundChanges(
            request: io.dataloom.api.storage.InboundChangeApplyRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun acknowledgeOutboundChanges(
            request: io.dataloom.api.synchronization.OutboundChangeAcknowledgementRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun readCheckpoint(
            request: io.dataloom.api.synchronization.CheckpointReadRequest,
        ): ProviderOperationResult<io.dataloom.api.synchronization.SynchronizationCheckpoint?> =
            ProviderOperationResult.Success(null)

        override suspend fun writeCheckpoint(
            request: io.dataloom.api.synchronization.CheckpointWriteRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)
    }

    private class RecordingQueueProvider(
        private val enqueueResult: ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit),
    ) : io.dataloom.api.queue.QueueProvider {
        var enqueueCalls: Int = 0
            private set

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("direct-strategy-queue"),
            name = ProviderName("Direct Strategy Queue"),
            type = ProviderType.QUEUE,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun enqueue(
            request: io.dataloom.api.queue.QueueEnqueueRequest,
        ): ProviderOperationResult<Unit> {
            enqueueCalls++
            return enqueueResult
        }

        override suspend fun acquire(
            request: io.dataloom.api.queue.QueueAcquireRequest,
        ): ProviderOperationResult<io.dataloom.api.queue.QueueAcquireResult> =
            ProviderOperationResult.Success(io.dataloom.api.queue.QueueAcquireResult.NoEntries)

        override suspend fun complete(
            request: io.dataloom.api.queue.QueueCompletionRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun reschedule(
            request: io.dataloom.api.queue.QueueRescheduleRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun defer(
            request: io.dataloom.api.queue.QueueDeferralRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun fail(
            request: io.dataloom.api.queue.QueueFailureRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun cancel(
            request: io.dataloom.api.queue.QueueCancellationRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun recoverExpiredLeases(
            request: io.dataloom.api.queue.ExpiredLeaseRecoveryRequest,
        ): ProviderOperationResult<io.dataloom.api.queue.ExpiredLeaseRecoveryResult> =
            ProviderOperationResult.Success(
                io.dataloom.api.queue.ExpiredLeaseRecoveryResult(recoveredEntries = 0),
            )
    }

    private class RecordingEncoder : io.dataloom.runtime.submission.QueuedSynchronizationWorkEncoder {
        override fun encode(
            submission: io.dataloom.runtime.submission.QueuedSynchronizationSubmission,
        ): io.dataloom.runtime.submission.QueuedSynchronizationWorkEncodingResult =
            io.dataloom.runtime.submission.QueuedSynchronizationWorkEncodingResult.Encoded(
                io.dataloom.api.queue.QueueEnqueueRequest(
                    entry = io.dataloom.api.queue.QueueEntry(
                        id = submission.queueEntryId,
                        synchronizationRequest = submission.work.request,
                        state = io.dataloom.api.queue.QueueEntryState.PENDING,
                        enqueuedAt = submission.availableAt,
                        availableAt = submission.availableAt,
                        workflowTimeoutState = submission.workflowTimeoutState,
                        strategyDecision = submission.work.strategyDecision,
                        strategyPlan = submission.work.strategyPlan,
                    ),
                ),
            )
    }
}
