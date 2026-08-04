package io.dataloom.runtime.queue

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.IdentifierGenerator
import io.dataloom.api.identifier.QueueConsumerId
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.RetryPolicyId
import io.dataloom.api.identifier.SynchronizationEventId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.SynchronizationProviderBindings
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.queue.QueueLease
import io.dataloom.api.retry.RetryDecision
import io.dataloom.api.retry.RetryEvaluationRequest
import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.retry.RetryPolicy
import io.dataloom.api.retry.RetryStopReason
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.runtime.RuntimeIdentifierGenerators
import io.dataloom.api.strategy.BuiltInSynchronizationStrategy
import io.dataloom.api.strategy.PersistedStrategyDecision
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyConsistency
import io.dataloom.api.strategy.StrategyDataOrigin
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyDeferralReason
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyDurableContinuationPlan
import io.dataloom.api.strategy.StrategyExecutionPlan
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.strategy.StrategyProviderCapability
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.synchronization.SynchronizationSummary
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.core.provider.ProviderLifecycleCoordinator
import io.dataloom.core.provider.ProviderRegistry
import io.dataloom.core.provider.StrategyProviderResolver
import io.dataloom.core.provider.SynchronizationProviderResolver
import io.dataloom.runtime.execution.SynchronizationExecutionContext
import io.dataloom.runtime.execution.SynchronizationExecutionCoordinator
import io.dataloom.runtime.execution.SynchronizationPipeline
import io.dataloom.runtime.execution.SynchronizationPipelineRegistry
import io.dataloom.runtime.retry.SynchronizationRetryEvaluator
import io.dataloom.runtime.strategy.AcceptedStrategyPlanExecutionCoordinator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

class AcceptedStrategyQueuedExecutionRoutingTest {

    @Test
    fun planBearingWorkNeverFallsBackToLegacyCoordinatorWhenAcceptedCoordinatorMissing() = runTest {
        val fixture = fixture()
        val handler = handler(fixture, acceptedCoordinator = null)

        val outcome = handler.execute(entry())

        val failed = assertIs<QueueEntryExecutionOutcome.Failed>(outcome)
        assertEquals(
            "DL-Q-ACCEPTED-PLAN-COORDINATOR-NOT-CONFIGURED",
            failed.error.code.value,
        )
        assertEquals(0, fixture.pipeline.calls)
    }

    @Test
    fun configuredAcceptedCoordinatorOwnsPlanBearingQueueExecution() = runTest {
        val fixture = fixture()
        val accepted = AcceptedStrategyPlanExecutionCoordinator(
            lifecycleCoordinator = fixture.lifecycle,
            providerResolver = StrategyProviderResolver(fixture.registry),
            clock = fixture.dependencies.clock,
            runtimeDependencies = fixture.dependencies,
            pipelineRegistry = fixture.pipelineRegistry,
        )
        val handler = handler(fixture, acceptedCoordinator = accepted)

        val outcome = handler.execute(entry())

        val failed = assertIs<QueueEntryExecutionOutcome.Failed>(outcome)
        assertEquals("DL-Q-ACCEPTED-PLAN-REJECTED", failed.error.code.value)
        assertEquals(0, fixture.pipeline.calls)
    }

    private fun handler(
        fixture: Fixture,
        acceptedCoordinator: AcceptedStrategyPlanExecutionCoordinator?,
    ): QueuedSynchronizationExecutionHandler = QueuedSynchronizationExecutionHandler(
        workResolver = QueuedSynchronizationWorkResolver { queued ->
            QueuedSynchronizationWorkResolution.Resolved(
                QueuedSynchronizationWork(
                    request = queued.synchronizationRequest,
                    bindings = bindings(),
                    strategyDecision = queued.strategyDecision,
                    strategyPlan = queued.strategyPlan,
                ),
            )
        },
        executionCoordinator = fixture.legacyCoordinator,
        retryEvaluator = SynchronizationRetryEvaluator(
            retryPolicy = StopPolicy,
            clock = fixture.dependencies.clock,
        ),
        retryOperation = RetryOperation("queued.accepted-plan"),
        acceptedStrategyPlanCoordinator = acceptedCoordinator,
    )

    private fun fixture(): Fixture {
        val registry = ProviderRegistry(emptyList())
        val lifecycle = ProviderLifecycleCoordinator(
            registry = registry,
            context = ProviderInitializationContext(),
        )
        val pipeline = RecordingPipeline()
        val pipelineRegistry = SynchronizationPipelineRegistry(listOf(pipeline))
        val dependencies = runtimeDependencies()
        return Fixture(
            registry = registry,
            lifecycle = lifecycle,
            pipeline = pipeline,
            pipelineRegistry = pipelineRegistry,
            dependencies = dependencies,
            legacyCoordinator = SynchronizationExecutionCoordinator(
                lifecycleCoordinator = lifecycle,
                providerResolver = SynchronizationProviderResolver(registry),
                pipelineRegistry = pipelineRegistry,
                runtimeDependencies = dependencies,
            ),
        )
    }

    private fun entry(): QueueEntry = QueueEntry(
        id = QueueEntryId("entry-accepted"),
        synchronizationRequest = request(),
        state = QueueEntryState.LEASED,
        enqueuedAt = DataLoomInstant(1_000L),
        availableAt = DataLoomInstant(1_000L),
        lease = QueueLease(
            id = QueueLeaseId("lease-accepted"),
            consumerId = QueueConsumerId("consumer-accepted"),
            acquiredAt = DataLoomInstant(1_500L),
            expiresAt = DataLoomInstant(10_000L),
        ),
        strategyDecision = decision(),
        strategyPlan = plan(),
    )

    private fun request(): SynchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("workflow-accepted"),
        sessionId = SynchronizationSessionId("session-accepted"),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("execution-accepted"),
            correlationId = CorrelationId("correlation-accepted"),
        ),
    )

    private fun decision(): PersistedStrategyDecision = PersistedStrategyDecision(
        decisionId = StrategyDecisionId("decision-accepted"),
        planId = StrategyPlanId("plan-accepted"),
        requestedStrategy = BuiltInSynchronizationStrategy.ADAPTIVE,
        effectiveProfileId = StrategyProfileId("offline-profile"),
        effectiveStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
        configurationVersion = StrategyConfigurationVersion(1L),
        disposition = StrategyDisposition.DEFER,
    )

    private fun plan(): StrategyExecutionPlan = StrategyExecutionPlan(
        id = StrategyPlanId("plan-accepted"),
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
            operations = listOf(StrategyOperation.PUSH_REMOTE),
            requiredCapabilities = setOf(StrategyProviderCapability.TRANSPORT),
            dataOrigin = StrategyDataOrigin.NONE,
            consistency = StrategyConsistency.LOCAL_AUTHORITATIVE,
        ),
    )

    private fun bindings(): SynchronizationProviderBindings =
        SynchronizationProviderBindings(
            storageProviderId = ProviderId("storage-missing"),
            transportProviderId = ProviderId("transport-missing"),
        )

    private fun runtimeDependencies(): RuntimeDependencies = RuntimeDependencies(
        clock = FixedClock(DataLoomInstant(2_000L)),
        identifiers = RuntimeIdentifierGenerators(
            synchronizationEventIds = fixed(SynchronizationEventId("event-1")),
            queueEntryIds = fixed(QueueEntryId("entry-1")),
            queueLeaseIds = fixed(QueueLeaseId("lease-1")),
            conflictIds = fixed(ConflictId("conflict-1")),
        ),
    )

    private fun <T> fixed(value: T): IdentifierGenerator<T> =
        object : IdentifierGenerator<T> {
            override fun generate(): T = value
        }

    private object StopPolicy : RetryPolicy {
        override val id: RetryPolicyId = RetryPolicyId("stop")
        override fun evaluate(request: RetryEvaluationRequest): RetryDecision =
            RetryDecision.Stop(RetryStopReason.POLICY_REJECTED)
    }

    private class FixedClock(private val value: DataLoomInstant) : DataLoomClock {
        override fun now(): DataLoomInstant = value
    }

    private class RecordingPipeline : SynchronizationPipeline {
        override val direction: SynchronizationDirection = SynchronizationDirection.PUSH
        var calls: Int = 0

        override suspend fun execute(
            context: SynchronizationExecutionContext,
        ): SynchronizationResult {
            calls++
            return SynchronizationResult.Succeeded(
                request = context.request,
                completedAt = DataLoomInstant(3_000L),
                summary = SynchronizationSummary(),
            )
        }
    }

    private data class Fixture(
        val registry: ProviderRegistry,
        val lifecycle: ProviderLifecycleCoordinator,
        val pipeline: RecordingPipeline,
        val pipelineRegistry: SynchronizationPipelineRegistry,
        val dependencies: RuntimeDependencies,
        val legacyCoordinator: SynchronizationExecutionCoordinator,
    )
}
