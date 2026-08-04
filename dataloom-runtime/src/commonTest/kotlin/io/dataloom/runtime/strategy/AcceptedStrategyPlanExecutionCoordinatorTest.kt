package io.dataloom.runtime.strategy

import io.dataloom.api.change.ChangeSet
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
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
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.provider.StrategyProviderBindings
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.runtime.RuntimeIdentifierGenerators
import io.dataloom.api.storage.InboundChangeApplyRequest
import io.dataloom.api.storage.OutboundChangeReadRequest
import io.dataloom.api.storage.OutboundChangeReadResult
import io.dataloom.api.strategy.BuiltInSynchronizationStrategy
import io.dataloom.api.strategy.ClassifiedStrategyRemoteError
import io.dataloom.api.strategy.PersistedStrategyDecision
import io.dataloom.api.strategy.StrategyCacheState
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyConsistency
import io.dataloom.api.strategy.StrategyDataOrigin
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyDeferralReason
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyDurableContinuationPlan
import io.dataloom.api.strategy.StrategyExecutionPlan
import io.dataloom.api.strategy.StrategyFallbackPlan
import io.dataloom.api.strategy.StrategyLocalFallbackProvider
import io.dataloom.api.strategy.StrategyLocalFallbackRequest
import io.dataloom.api.strategy.StrategyLocalFallbackResult
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.strategy.StrategyProviderCapability
import io.dataloom.api.strategy.StrategyReconciliationProvider
import io.dataloom.api.strategy.StrategyReconciliationRequest
import io.dataloom.api.strategy.StrategyReconciliationResult
import io.dataloom.api.strategy.StrategyRemoteOutcome
import io.dataloom.api.strategy.StrategyTransportOutput
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.synchronization.ChangeAcknowledgementStatus
import io.dataloom.api.synchronization.ChangeEventAcknowledgement
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.synchronization.CheckpointReadRequest
import io.dataloom.api.synchronization.CheckpointWriteRequest
import io.dataloom.api.synchronization.OutboundChangeAcknowledgementRequest
import io.dataloom.api.synchronization.SynchronizationCheckpoint
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.synchronization.SynchronizationSkipReason
import io.dataloom.api.synchronization.SynchronizationSummary
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.core.provider.ProviderLifecycleCoordinator
import io.dataloom.core.provider.ProviderRegistry
import io.dataloom.core.provider.StrategyProviderResolver
import io.dataloom.runtime.execution.SynchronizationExecutionContext
import io.dataloom.runtime.execution.SynchronizationPipeline
import io.dataloom.runtime.execution.SynchronizationPipelineRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest

class AcceptedStrategyPlanExecutionCoordinatorTest {

    @Test
    fun mismatchedDecisionStopsBeforeProviderExecution() = runTest {
        val storage = RecordingStrategyStorage()
        val transport = RecordingTransport()
        val fixture = fixture(storage, transport)
        val result = fixture.coordinator.execute(
            request = request(SynchronizationDirection.PUSH),
            decision = decision(configurationVersion = 2L),
            acceptedPlan = offlinePlan(),
            bindings = bindings(storage, transport),
        )

        val rejected = assertIs<StrategySynchronizationExecutionResult.Rejected>(result)
        assertEquals(StrategyExecutionRejectionReason.ACCEPTED_PLAN_MISMATCH, rejected.reason)
        assertEquals(0, storage.reconcileCalls)
        assertEquals(0, transport.pushCalls)
        assertEquals(0, fixture.pushPipeline.calls)
    }

    @Test
    fun offlineContinuationUsesStoredOperationsAndReconcilesExactlyOnce() = runTest {
        val storage = RecordingStrategyStorage()
        val transport = RecordingTransport()
        val fixture = fixture(storage, transport)
        val acceptedPlan = offlinePlan()

        val result = fixture.coordinator.execute(
            request = request(SynchronizationDirection.PUSH),
            decision = decision(),
            acceptedPlan = acceptedPlan,
            bindings = bindings(storage, transport),
        )

        val executed = assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        assertIs<StrategyTransportOutput.ProviderBacked>(executed.output)
        assertEquals(listOf("strategy.accepted-plan-replay"), executed.evaluation.reasonCodes)
        assertEquals(1, fixture.pushPipeline.calls)
        assertEquals(1, storage.reconcileCalls)
        assertEquals(StrategyOperation.PUSH_REMOTE, storage.lastReconciliation?.completedOperations?.last())
    }

    @Test
    fun transportOnlyPullDoesNotResolveOrInvokeStorage() = runTest {
        val transport = RecordingTransport(
            pullResult = ProviderOperationResult.Success(PullChangesResult.NoChanges()),
        )
        val fixture = fixture(storage = null, transport = transport)
        val plan = remotePullPlan(fallback = null)

        val result = fixture.coordinator.execute(
            request = request(SynchronizationDirection.PULL),
            decision = decision(
                requested = BuiltInSynchronizationStrategy.REMOTE_FIRST,
                effective = BuiltInSynchronizationStrategy.REMOTE_FIRST,
                profileId = "remote-profile",
                disposition = StrategyDisposition.DEFER,
            ),
            acceptedPlan = plan,
            bindings = StrategyProviderBindings(
                transportProviderId = transport.descriptor.id,
            ),
        )

        assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        assertEquals(1, transport.pullCalls)
        assertEquals(0, fixture.pullPipeline.calls)
    }

    @Test
    fun typedFallbackUsesPersistedCacheStateNotCurrentEvidence() = runTest {
        val storage = RecordingStrategyStorage(
            fallbackResult = ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Available(StrategyCacheState.STALE),
            ),
        )
        val unavailable = ClassifiedError(StrategyRemoteOutcome.UNAVAILABLE)
        val transport = RecordingTransport(
            pullResult = ProviderOperationResult.Failure(unavailable),
        )
        val fixture = fixture(storage, transport)
        val plan = remotePullPlan(
            fallback = StrategyFallbackPlan(
                remoteOutcomes = setOf(StrategyRemoteOutcome.UNAVAILABLE),
                operations = listOf(StrategyOperation.SERVE_LOCAL),
                dataOrigin = StrategyDataOrigin.LOCAL,
            ),
        )
        val result = fixture.coordinator.execute(
            request = request(SynchronizationDirection.PULL),
            decision = decision(
                requested = BuiltInSynchronizationStrategy.REMOTE_FIRST,
                effective = BuiltInSynchronizationStrategy.REMOTE_FIRST,
                profileId = "remote-profile",
                disposition = StrategyDisposition.DEFER,
            ),
            acceptedPlan = plan,
            bindings = bindings(storage, transport),
        )

        val fallback = assertIs<StrategySynchronizationExecutionResult.FallbackActivated>(result)
        assertEquals(StrategyCacheState.STALE, fallback.cacheState)
        assertEquals(StrategyCacheState.STALE, storage.lastFallback?.evaluatedCacheState)
        assertSame(unavailable, fallback.primaryError)
    }

    @Test
    fun fallbackReconciliationRunsOnceWithoutClaimingFailedPullCompleted() = runTest {
        val storage = RecordingStrategyStorage(
            fallbackResult = ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Available(StrategyCacheState.STALE),
            ),
        )
        val unavailable = ClassifiedError(StrategyRemoteOutcome.UNAVAILABLE)
        val transport = RecordingTransport(
            pullResult = ProviderOperationResult.Failure(unavailable),
        )
        val fixture = fixture(storage, transport)
        val fallbackPlan = StrategyFallbackPlan(
            remoteOutcomes = setOf(StrategyRemoteOutcome.UNAVAILABLE),
            operations = listOf(StrategyOperation.SERVE_LOCAL),
            dataOrigin = StrategyDataOrigin.LOCAL,
        )

        val result = fixture.coordinator.execute(
            request = request(SynchronizationDirection.PULL),
            decision = decision(
                requested = BuiltInSynchronizationStrategy.REMOTE_FIRST,
                effective = BuiltInSynchronizationStrategy.REMOTE_FIRST,
                profileId = "remote-profile",
                disposition = StrategyDisposition.DEFER,
            ),
            acceptedPlan = remotePullPlan(
                fallback = fallbackPlan,
                reconcile = true,
            ),
            bindings = bindings(storage, transport),
        )

        val fallback = assertIs<StrategySynchronizationExecutionResult.FallbackActivated>(result)
        assertEquals(1, transport.pullCalls)
        assertEquals(1, storage.reconcileCalls)
        assertEquals(
            listOf(StrategyOperation.SERVE_LOCAL),
            storage.lastReconciliation?.completedOperations,
        )
        assertEquals(listOf(StrategyOperation.SERVE_LOCAL), fallback.completedOperations)
    }

    @Test
    fun missingReconciliationCapabilityFailsBeforeProviderExecution() = runTest {
        val storage = PlainStorage()
        val transport = RecordingTransport()
        val fixture = fixture(storage, transport)

        val result = fixture.coordinator.execute(
            request = request(SynchronizationDirection.PUSH),
            decision = decision(),
            acceptedPlan = offlinePlan(),
            bindings = bindings(storage, transport),
        )

        val rejected = assertIs<StrategySynchronizationExecutionResult.Rejected>(result)
        assertEquals(
            StrategyExecutionRejectionReason.RECONCILIATION_PROVIDER_NOT_CONFIGURED,
            rejected.reason,
        )
        assertEquals(0, fixture.pushPipeline.calls)
    }

    @Test
    fun extraReplayCapabilityRejectsBeforeProviderExecution() = runTest {
        val storage = RecordingStrategyStorage()
        val transport = RecordingTransport()
        val fixture = fixture(storage, transport)
        val plan = StrategyExecutionPlan(
            id = StrategyPlanId("plan-1"),
            requestedStrategy = BuiltInSynchronizationStrategy.REMOTE_FIRST,
            effectiveProfileId = StrategyProfileId("remote-profile"),
            effectiveStrategy = BuiltInSynchronizationStrategy.REMOTE_FIRST,
            configurationVersion = StrategyConfigurationVersion(1L),
            direction = SynchronizationDirection.PULL,
            mode = SynchronizationMode.DELTA,
            disposition = StrategyDisposition.DEFER,
            operations = listOf(StrategyOperation.ENQUEUE_DURABLE_WORK),
            requiredCapabilities = setOf(StrategyProviderCapability.QUEUE),
            dataOrigin = StrategyDataOrigin.NONE,
            consistency = StrategyConsistency.REMOTE_AUTHORITATIVE,
            deferralReason = StrategyDeferralReason.CONNECTIVITY_UNKNOWN,
            durableContinuation = StrategyDurableContinuationPlan(
                operations = listOf(StrategyOperation.PULL_REMOTE),
                requiredCapabilities = setOf(
                    StrategyProviderCapability.TRANSPORT,
                    StrategyProviderCapability.STORAGE,
                ),
                dataOrigin = StrategyDataOrigin.REMOTE,
                consistency = StrategyConsistency.REMOTE_AUTHORITATIVE,
            ),
        )

        val result = fixture.coordinator.execute(
            request = request(SynchronizationDirection.PULL),
            decision = decision(
                requested = BuiltInSynchronizationStrategy.REMOTE_FIRST,
                effective = BuiltInSynchronizationStrategy.REMOTE_FIRST,
                profileId = "remote-profile",
                disposition = StrategyDisposition.DEFER,
            ),
            acceptedPlan = plan,
            bindings = bindings(storage, transport),
        )

        val rejected = assertIs<StrategySynchronizationExecutionResult.Rejected>(result)
        assertEquals(StrategyExecutionRejectionReason.UNSUPPORTED_PLAN, rejected.reason)
        assertEquals(0, transport.pullCalls)
        assertEquals(0, fixture.pullPipeline.calls)
    }

    @Test
    fun localOnlyContinuationUsesPersistedCacheEvidenceWithoutTransport() = runTest {
        val storage = RecordingStrategyStorage(
            fallbackResult = ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Available(StrategyCacheState.STALE),
            ),
        )
        val transport = RecordingTransport()
        val fixture = fixture(storage, transport)
        val plan = StrategyExecutionPlan(
            id = StrategyPlanId("plan-1"),
            requestedStrategy = BuiltInSynchronizationStrategy.CACHE_FIRST,
            effectiveProfileId = StrategyProfileId("cache-profile"),
            effectiveStrategy = BuiltInSynchronizationStrategy.CACHE_FIRST,
            configurationVersion = StrategyConfigurationVersion(1L),
            direction = SynchronizationDirection.PULL,
            mode = SynchronizationMode.DELTA,
            disposition = StrategyDisposition.DEFER,
            operations = listOf(StrategyOperation.ENQUEUE_DURABLE_WORK),
            requiredCapabilities = setOf(StrategyProviderCapability.QUEUE),
            dataOrigin = StrategyDataOrigin.LOCAL,
            consistency = StrategyConsistency.EVENTUAL,
            deferralReason = StrategyDeferralReason.CONNECTIVITY_UNAVAILABLE,
            durableContinuation = StrategyDurableContinuationPlan(
                operations = listOf(StrategyOperation.SERVE_LOCAL),
                requiredCapabilities = setOf(StrategyProviderCapability.STORAGE),
                dataOrigin = StrategyDataOrigin.LOCAL,
                consistency = StrategyConsistency.EVENTUAL,
                evaluatedCacheState = StrategyCacheState.STALE,
            ),
        )

        val result = fixture.coordinator.execute(
            request = request(SynchronizationDirection.PULL),
            decision = decision(
                requested = BuiltInSynchronizationStrategy.CACHE_FIRST,
                effective = BuiltInSynchronizationStrategy.CACHE_FIRST,
                profileId = "cache-profile",
                disposition = StrategyDisposition.DEFER,
            ),
            acceptedPlan = plan,
            bindings = StrategyProviderBindings(
                storageProviderId = storage.descriptor.id,
            ),
        )

        val fallback = assertIs<StrategySynchronizationExecutionResult.FallbackActivated>(result)
        assertEquals(StrategyCacheState.STALE, fallback.cacheState)
        assertEquals(StrategyCacheState.STALE, storage.lastFallback?.evaluatedCacheState)
        assertEquals(0, transport.pullCalls)
        assertEquals(0, fixture.pullPipeline.calls)
    }

    @Test
    fun unsupportedReplaySequenceRejectsBeforeProviderExecution() = runTest {
        val storage = RecordingStrategyStorage()
        val transport = RecordingTransport()
        val fixture = fixture(storage, transport)
        val plan = StrategyExecutionPlan(
            id = StrategyPlanId("plan-1"),
            requestedStrategy = BuiltInSynchronizationStrategy.REMOTE_FIRST,
            effectiveProfileId = StrategyProfileId("remote-profile"),
            effectiveStrategy = BuiltInSynchronizationStrategy.REMOTE_FIRST,
            configurationVersion = StrategyConfigurationVersion(1L),
            direction = SynchronizationDirection.PULL,
            mode = SynchronizationMode.DELTA,
            disposition = StrategyDisposition.DEFER,
            operations = listOf(StrategyOperation.ENQUEUE_DURABLE_WORK),
            requiredCapabilities = setOf(StrategyProviderCapability.QUEUE),
            dataOrigin = StrategyDataOrigin.NONE,
            consistency = StrategyConsistency.REMOTE_AUTHORITATIVE,
            deferralReason = StrategyDeferralReason.CONNECTIVITY_UNKNOWN,
            durableContinuation = StrategyDurableContinuationPlan(
                operations = listOf(
                    StrategyOperation.READ_LOCAL,
                    StrategyOperation.PULL_REMOTE,
                ),
                requiredCapabilities = setOf(
                    StrategyProviderCapability.STORAGE,
                    StrategyProviderCapability.TRANSPORT,
                ),
                dataOrigin = StrategyDataOrigin.REMOTE,
                consistency = StrategyConsistency.REMOTE_AUTHORITATIVE,
            ),
        )

        val result = fixture.coordinator.execute(
            request = request(SynchronizationDirection.PULL),
            decision = decision(
                requested = BuiltInSynchronizationStrategy.REMOTE_FIRST,
                effective = BuiltInSynchronizationStrategy.REMOTE_FIRST,
                profileId = "remote-profile",
                disposition = StrategyDisposition.DEFER,
            ),
            acceptedPlan = plan,
            bindings = bindings(storage, transport),
        )

        val rejected = assertIs<StrategySynchronizationExecutionResult.Rejected>(result)
        assertEquals(StrategyExecutionRejectionReason.UNSUPPORTED_PLAN, rejected.reason)
        assertEquals(0, transport.pullCalls)
        assertEquals(0, fixture.pullPipeline.calls)
    }

    @Test
    fun localOnlyReconciliationExecutesExactlyOnce() = runTest {
        val storage = RecordingStrategyStorage(
            fallbackResult = ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Available(StrategyCacheState.STALE),
            ),
        )
        val transport = RecordingTransport()
        val fixture = fixture(storage, transport)
        val plan = StrategyExecutionPlan(
            id = StrategyPlanId("plan-1"),
            requestedStrategy = BuiltInSynchronizationStrategy.HYBRID,
            effectiveProfileId = StrategyProfileId("hybrid-profile"),
            effectiveStrategy = BuiltInSynchronizationStrategy.HYBRID,
            configurationVersion = StrategyConfigurationVersion(1L),
            direction = SynchronizationDirection.PULL,
            mode = SynchronizationMode.DELTA,
            disposition = StrategyDisposition.DEFER,
            operations = listOf(StrategyOperation.ENQUEUE_DURABLE_WORK),
            requiredCapabilities = setOf(StrategyProviderCapability.QUEUE),
            dataOrigin = StrategyDataOrigin.LOCAL,
            consistency = StrategyConsistency.READ_YOUR_WRITES,
            deferralReason = StrategyDeferralReason.CONNECTIVITY_UNAVAILABLE,
            durableContinuation = StrategyDurableContinuationPlan(
                operations = listOf(
                    StrategyOperation.SERVE_LOCAL,
                    StrategyOperation.RECONCILE,
                ),
                requiredCapabilities = setOf(
                    StrategyProviderCapability.STORAGE,
                    StrategyProviderCapability.CONFLICT_STATE,
                ),
                dataOrigin = StrategyDataOrigin.LOCAL,
                consistency = StrategyConsistency.READ_YOUR_WRITES,
                evaluatedCacheState = StrategyCacheState.STALE,
            ),
        )

        val result = fixture.coordinator.execute(
            request = request(SynchronizationDirection.PULL),
            decision = decision(
                requested = BuiltInSynchronizationStrategy.HYBRID,
                effective = BuiltInSynchronizationStrategy.HYBRID,
                profileId = "hybrid-profile",
                disposition = StrategyDisposition.DEFER,
            ),
            acceptedPlan = plan,
            bindings = StrategyProviderBindings(
                storageProviderId = storage.descriptor.id,
            ),
        )

        val fallback = assertIs<StrategySynchronizationExecutionResult.FallbackActivated>(result)
        assertEquals(StrategyCacheState.STALE, fallback.cacheState)
        assertEquals(1, storage.reconcileCalls)
        assertEquals(
            listOf(StrategyOperation.SERVE_LOCAL),
            storage.lastReconciliation?.completedOperations,
        )
        assertEquals(0, transport.pullCalls)
        assertEquals(0, fixture.pullPipeline.calls)
    }

    @Test
    fun skippedReplayWithoutProviderEffectDoesNotReconcile() = runTest {
        val storage = RecordingStrategyStorage()
        val transport = RecordingTransport()
        val fixture = fixture(storage, transport)
        fixture.pushPipeline.skipWithoutProviderCalls = true

        val result = fixture.coordinator.execute(
            request = request(SynchronizationDirection.PUSH),
            decision = decision(),
            acceptedPlan = offlinePlan(),
            bindings = bindings(storage, transport),
        )

        val executed = assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        val output = assertIs<StrategyTransportOutput.ProviderBacked>(executed.output)
        assertIs<SynchronizationResult.Skipped>(output.result)
        assertEquals(0, storage.reconcileCalls)
        assertEquals(0, transport.pushCalls)
    }

    private suspend fun fixture(
        storage: io.dataloom.api.storage.StorageProvider?,
        transport: TransportProvider,
    ): Fixture {
        val providers = listOfNotNull(storage, transport)
        val registry = ProviderRegistry(providers)
        val lifecycle = ProviderLifecycleCoordinator(
            registry,
            ProviderInitializationContext(),
        )
        lifecycle.initialize()
        val push = RecordingPipeline(SynchronizationDirection.PUSH)
        val pull = RecordingPipeline(SynchronizationDirection.PULL)
        val bidirectional = RecordingPipeline(SynchronizationDirection.BIDIRECTIONAL)
        val dependencies = runtimeDependencies()
        return Fixture(
            coordinator = AcceptedStrategyPlanExecutionCoordinator(
                lifecycleCoordinator = lifecycle,
                providerResolver = StrategyProviderResolver(registry),
                clock = dependencies.clock,
                runtimeDependencies = dependencies,
                pipelineRegistry = SynchronizationPipelineRegistry(
                    listOf(push, pull, bidirectional),
                ),
            ),
            pushPipeline = push,
            pullPipeline = pull,
        )
    }

    private fun offlinePlan(): StrategyExecutionPlan = StrategyExecutionPlan(
        id = StrategyPlanId("plan-1"),
        requestedStrategy = BuiltInSynchronizationStrategy.ADAPTIVE,
        effectiveProfileId = StrategyProfileId("offline-profile"),
        effectiveStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
        configurationVersion = StrategyConfigurationVersion(1L),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        disposition = StrategyDisposition.DEFER,
        operations = listOf(StrategyOperation.ENQUEUE_DURABLE_WORK),
        requiredCapabilities = setOf(StrategyProviderCapability.QUEUE),
        dataOrigin = StrategyDataOrigin.NONE,
        consistency = StrategyConsistency.LOCAL_AUTHORITATIVE,
        deferralReason = StrategyDeferralReason.CONNECTIVITY_UNAVAILABLE,
        durableContinuation = StrategyDurableContinuationPlan(
            operations = listOf(
                StrategyOperation.READ_LOCAL,
                StrategyOperation.PUSH_REMOTE,
                StrategyOperation.RECONCILE,
            ),
            requiredCapabilities = setOf(
                StrategyProviderCapability.STORAGE,
                StrategyProviderCapability.TRANSPORT,
                StrategyProviderCapability.CONFLICT_STATE,
            ),
            dataOrigin = StrategyDataOrigin.NONE,
            consistency = StrategyConsistency.LOCAL_AUTHORITATIVE,
        ),
    )

    private fun remotePullPlan(
        fallback: StrategyFallbackPlan?,
        reconcile: Boolean = false,
    ): StrategyExecutionPlan = StrategyExecutionPlan(
        id = StrategyPlanId("plan-1"),
        requestedStrategy = BuiltInSynchronizationStrategy.REMOTE_FIRST,
        effectiveProfileId = StrategyProfileId("remote-profile"),
        effectiveStrategy = BuiltInSynchronizationStrategy.REMOTE_FIRST,
        configurationVersion = StrategyConfigurationVersion(1L),
        direction = SynchronizationDirection.PULL,
        mode = SynchronizationMode.DELTA,
        disposition = StrategyDisposition.DEFER,
        operations = listOf(StrategyOperation.ENQUEUE_DURABLE_WORK),
        requiredCapabilities = setOf(StrategyProviderCapability.QUEUE),
        dataOrigin = StrategyDataOrigin.NONE,
        consistency = StrategyConsistency.REMOTE_AUTHORITATIVE,
        deferralReason = StrategyDeferralReason.CONNECTIVITY_UNKNOWN,
        durableContinuation = StrategyDurableContinuationPlan(
            operations = listOf(StrategyOperation.PULL_REMOTE) +
                if (reconcile) listOf(StrategyOperation.RECONCILE) else emptyList(),
            requiredCapabilities =
                setOf(StrategyProviderCapability.TRANSPORT) +
                    if (fallback != null || reconcile) {
                        setOf(StrategyProviderCapability.STORAGE)
                    } else {
                        emptySet()
                    } +
                    if (reconcile) {
                        setOf(StrategyProviderCapability.CONFLICT_STATE)
                    } else {
                        emptySet()
                    },
            dataOrigin = StrategyDataOrigin.REMOTE,
            consistency = StrategyConsistency.REMOTE_AUTHORITATIVE,
            evaluatedCacheState = StrategyCacheState.STALE,
            fallbackPlan = fallback,
        ),
    )

    private fun decision(
        configurationVersion: Long = 1L,
        requested: BuiltInSynchronizationStrategy = BuiltInSynchronizationStrategy.ADAPTIVE,
        effective: BuiltInSynchronizationStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
        profileId: String = "offline-profile",
        disposition: StrategyDisposition = StrategyDisposition.DEFER,
    ): PersistedStrategyDecision = PersistedStrategyDecision(
        decisionId = StrategyDecisionId("decision-1"),
        planId = StrategyPlanId("plan-1"),
        requestedStrategy = requested,
        effectiveProfileId = StrategyProfileId(profileId),
        effectiveStrategy = effective,
        configurationVersion = StrategyConfigurationVersion(configurationVersion),
        disposition = disposition,
    )

    private fun request(direction: SynchronizationDirection): SynchronizationRequest =
        SynchronizationRequest(
            workflowId = WorkflowId("workflow-1"),
            sessionId = SynchronizationSessionId("session-1"),
            direction = direction,
            mode = SynchronizationMode.DELTA,
            context = ExecutionContext(
                executionId = ExecutionId("execution-1"),
                correlationId = CorrelationId("correlation-1"),
            ),
        )

    private fun bindings(
        storage: io.dataloom.api.storage.StorageProvider,
        transport: TransportProvider,
    ): StrategyProviderBindings = StrategyProviderBindings(
        storageProviderId = storage.descriptor.id,
        transportProviderId = transport.descriptor.id,
    )

    private fun runtimeDependencies(): RuntimeDependencies {
        val clock = FixedClock(DataLoomInstant(9_000L))
        return RuntimeDependencies(
            clock = clock,
            identifiers = RuntimeIdentifierGenerators(
                synchronizationEventIds = fixedGenerator(SynchronizationEventId("event-1")),
                queueEntryIds = fixedGenerator(QueueEntryId("entry-1")),
                queueLeaseIds = fixedGenerator(QueueLeaseId("lease-1")),
                conflictIds = fixedGenerator(ConflictId("conflict-1")),
            ),
        )
    }

    private fun <T> fixedGenerator(value: T): IdentifierGenerator<T> =
        object : IdentifierGenerator<T> {
            override fun generate(): T = value
        }

    private data class Fixture(
        val coordinator: AcceptedStrategyPlanExecutionCoordinator,
        val pushPipeline: RecordingPipeline,
        val pullPipeline: RecordingPipeline,
    )

    private class RecordingPipeline(
        override val direction: SynchronizationDirection,
    ) : SynchronizationPipeline {
        var calls: Int = 0
        var skipWithoutProviderCalls: Boolean = false

        override suspend fun execute(
            context: SynchronizationExecutionContext,
        ): SynchronizationResult {
            calls++
            return if (skipWithoutProviderCalls) {
                SynchronizationResult.Skipped(
                    request = context.request,
                    completedAt = DataLoomInstant(8_000L),
                    summary = SynchronizationSummary(),
                    reason = SynchronizationSkipReason.NO_CHANGES,
                )
            } else {
                SynchronizationResult.Succeeded(
                    request = context.request,
                    completedAt = DataLoomInstant(8_000L),
                    summary = SynchronizationSummary(),
                )
            }
        }
    }

    private open class PlainStorage : io.dataloom.api.storage.StorageProvider {
        override val descriptor: ProviderDescriptor = descriptor("storage", ProviderType.STORAGE)
        override suspend fun initialize(context: ProviderInitializationContext) = success(Unit)
        override suspend fun health() = success(ProviderHealth(ProviderHealthStatus.HEALTHY))
        override suspend fun close() = success(Unit)
        override suspend fun readOutboundChanges(request: OutboundChangeReadRequest) =
            success(OutboundChangeReadResult.NoChanges)
        override suspend fun applyInboundChanges(request: InboundChangeApplyRequest) = success(Unit)
        override suspend fun acknowledgeOutboundChanges(
            request: OutboundChangeAcknowledgementRequest,
        ) = success(Unit)
        override suspend fun readCheckpoint(request: CheckpointReadRequest) =
            success<SynchronizationCheckpoint?>(null)
        override suspend fun writeCheckpoint(request: CheckpointWriteRequest) = success(Unit)
    }

    private class RecordingStrategyStorage(
        private val fallbackResult: ProviderOperationResult<StrategyLocalFallbackResult> =
            ProviderOperationResult.Success(
                StrategyLocalFallbackResult.Available(StrategyCacheState.FRESH),
            ),
    ) : PlainStorage(), StrategyLocalFallbackProvider, StrategyReconciliationProvider {
        var reconcileCalls: Int = 0
        var lastReconciliation: StrategyReconciliationRequest? = null
        var lastFallback: StrategyLocalFallbackRequest? = null

        override suspend fun evaluateLocalFallback(
            request: StrategyLocalFallbackRequest,
        ): ProviderOperationResult<StrategyLocalFallbackResult> {
            lastFallback = request
            return fallbackResult
        }

        override suspend fun reconcileStrategy(
            request: StrategyReconciliationRequest,
        ): ProviderOperationResult<StrategyReconciliationResult> {
            reconcileCalls++
            lastReconciliation = request
            return ProviderOperationResult.Success(StrategyReconciliationResult.Applied)
        }
    }

    private class RecordingTransport(
        private val pullResult: ProviderOperationResult<PullChangesResult> =
            ProviderOperationResult.Success(PullChangesResult.NoChanges()),
    ) : TransportProvider {
        override val descriptor: ProviderDescriptor = descriptor("transport", ProviderType.TRANSPORT)
        var pushCalls: Int = 0
        var pullCalls: Int = 0

        override suspend fun initialize(context: ProviderInitializationContext) = success(Unit)
        override suspend fun health() = success(ProviderHealth(ProviderHealthStatus.HEALTHY))
        override suspend fun close() = success(Unit)
        override suspend fun pushChanges(
            request: PushChangesRequest,
        ): ProviderOperationResult<ChangeSetAcknowledgement> {
            pushCalls++
            return ProviderOperationResult.Success(
                ChangeSetAcknowledgement(
                    changeSetId = request.changeSet.id,
                    events = listOf(
                        ChangeEventAcknowledgement(
                            eventId = ChangeEventId("event-ack"),
                            status = ChangeAcknowledgementStatus.ACCEPTED,
                        ),
                    ),
                ),
            )
        }
        override suspend fun pullChanges(
            request: PullChangesRequest,
        ): ProviderOperationResult<PullChangesResult> {
            pullCalls++
            return pullResult
        }
    }

    private data class ClassifiedError(
        override val remoteOutcome: StrategyRemoteOutcome,
        override val code: ErrorCode = ErrorCode("REMOTE_UNAVAILABLE"),
        override val category: ErrorCategory = ErrorCategory.NETWORK,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Remote unavailable.",
        override val cause: Throwable? = null,
    ) : ClassifiedStrategyRemoteError

    private class FixedClock(private val instant: DataLoomInstant) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
    }

    private companion object {
        fun descriptor(id: String, type: ProviderType): ProviderDescriptor = ProviderDescriptor(
            id = ProviderId(id),
            name = ProviderName(id),
            type = type,
            version = ProviderVersion("1.0.0"),
        )

        fun <T> success(value: T): ProviderOperationResult<T> =
            ProviderOperationResult.Success(value)
    }
}
