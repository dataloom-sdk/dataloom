package io.dataloom.runtime.strategy

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
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.StrategyProviderBindings
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.runtime.RuntimeIdentifierGenerators
import io.dataloom.api.strategy.CacheFirstStrategyProfile
import io.dataloom.api.strategy.HybridSource
import io.dataloom.api.strategy.HybridStrategyProfile
import io.dataloom.api.strategy.RemoteFirstStrategyProfile
import io.dataloom.api.strategy.StrategyCacheState
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyConnectivity
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.strategy.StrategyRuntimeEvidence
import io.dataloom.api.strategy.StrategySynchronizationRequest
import io.dataloom.api.strategy.SynchronizationStrategyProfile
import io.dataloom.api.strategy.UnknownConnectivityPolicy
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.core.provider.ProviderLifecycleCoordinator
import io.dataloom.core.provider.ProviderRegistry
import io.dataloom.core.provider.StrategyProviderResolver
import io.dataloom.runtime.execution.SynchronizationPipelineRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class NonAtomicStrategyDeferralSafetyTest {

    @Test
    fun cacheFirstOfflinePushDoesNotClaimUncommittedDurableDeferral() = runTest {
        assertDirectDeferralFailsClosed(
            direction = SynchronizationDirection.PUSH,
            profile = CacheFirstStrategyProfile(
                id = StrategyProfileId("cache-direct-deferral"),
                configurationVersion = StrategyConfigurationVersion(1),
                requireDurableRefresh = true,
            ),
            evidence = StrategyRuntimeEvidence(
                connectivity = StrategyConnectivity.UNAVAILABLE,
                cacheState = StrategyCacheState.NOT_EVALUATED,
            ),
            expectedOperations = listOf(
                StrategyOperation.READ_LOCAL,
                StrategyOperation.ENQUEUE_DURABLE_WORK,
            ),
        )
    }

    @Test
    fun remoteFirstUnknownConnectivityDoesNotClaimUncommittedQueueDeferral() = runTest {
        assertDirectDeferralFailsClosed(
            direction = SynchronizationDirection.PULL,
            profile = RemoteFirstStrategyProfile(
                id = StrategyProfileId("remote-direct-deferral"),
                configurationVersion = StrategyConfigurationVersion(1),
                unknownConnectivityPolicy = UnknownConnectivityPolicy.DEFER,
            ),
            evidence = StrategyRuntimeEvidence(
                connectivity = StrategyConnectivity.UNKNOWN,
                cacheState = StrategyCacheState.MISSING,
            ),
            expectedOperations = listOf(StrategyOperation.ENQUEUE_DURABLE_WORK),
        )
    }

    @Test
    fun hybridUnknownConnectivityDoesNotClaimUncommittedQueueDeferral() = runTest {
        assertDirectDeferralFailsClosed(
            direction = SynchronizationDirection.PULL,
            profile = HybridStrategyProfile(
                id = StrategyProfileId("hybrid-direct-deferral"),
                configurationVersion = StrategyConfigurationVersion(1),
                primarySource = HybridSource.REMOTE,
                fallbackSource = HybridSource.LOCAL,
                unknownConnectivityPolicy = UnknownConnectivityPolicy.DEFER,
            ),
            evidence = StrategyRuntimeEvidence(
                connectivity = StrategyConnectivity.UNKNOWN,
                cacheState = StrategyCacheState.MISSING,
            ),
            expectedOperations = listOf(StrategyOperation.ENQUEUE_DURABLE_WORK),
        )
    }

    private suspend fun assertDirectDeferralFailsClosed(
        direction: SynchronizationDirection,
        profile: SynchronizationStrategyProfile,
        evidence: StrategyRuntimeEvidence,
        expectedOperations: List<StrategyOperation>,
    ) {
        val fixture = fixture()
        val result = fixture.coordinator.execute(
            request = StrategySynchronizationRequest(
                request = SynchronizationRequest(
                    workflowId = WorkflowId("non-atomic-deferral-workflow"),
                    sessionId = SynchronizationSessionId("non-atomic-deferral-session"),
                    direction = direction,
                    mode = SynchronizationMode.DELTA,
                    context = ExecutionContext(
                        executionId = ExecutionId("non-atomic-deferral-execution"),
                        correlationId = CorrelationId("non-atomic-deferral-correlation"),
                    ),
                ),
                decisionId = StrategyDecisionId("non-atomic-deferral-decision"),
                planId = StrategyPlanId("non-atomic-deferral-plan"),
                profile = profile,
                evidence = evidence,
            ),
            bindings = StrategyProviderBindings(),
        )

        val rejected =
            assertIs<StrategySynchronizationExecutionResult.Rejected>(result)
        assertEquals(StrategyExecutionRejectionReason.UNSUPPORTED_PLAN, rejected.reason)
        assertEquals(StrategyDisposition.DEFER, rejected.evaluation.plan.disposition)
        assertEquals(expectedOperations, rejected.evaluation.plan.operations)
        assertTrue(
            StrategyOperation.ENQUEUE_DURABLE_WORK in rejected.evaluation.plan.operations,
        )
    }

    private suspend fun fixture(): Fixture {
        val registry = ProviderRegistry(emptyList())
        val lifecycle = ProviderLifecycleCoordinator(
            registry = registry,
            context = ProviderInitializationContext(),
        )
        lifecycle.initialize()
        val dependencies = runtimeDependencies()
        return Fixture(
            coordinator = StrategySynchronizationExecutionCoordinator(
                lifecycleCoordinator = lifecycle,
                evaluator = BuiltInSynchronizationStrategyEvaluator(),
                providerResolver = StrategyProviderResolver(registry),
                clock = dependencies.clock,
                runtimeDependencies = dependencies,
                pipelineRegistry = SynchronizationPipelineRegistry(emptyList()),
                lifecycleEventEmitter = null,
            ),
        )
    }

    private fun runtimeDependencies(): RuntimeDependencies =
        RuntimeDependencies(
            clock = FixedClock(DataLoomInstant(10_000L)),
            identifiers = RuntimeIdentifierGenerators(
                synchronizationEventIds =
                    fixedGenerator(SynchronizationEventId("non-atomic-deferral-event")),
                queueEntryIds =
                    fixedGenerator(QueueEntryId("non-atomic-deferral-entry")),
                queueLeaseIds =
                    fixedGenerator(QueueLeaseId("non-atomic-deferral-lease")),
                conflictIds =
                    fixedGenerator(ConflictId("non-atomic-deferral-conflict")),
            ),
        )

    private fun <T> fixedGenerator(value: T): IdentifierGenerator<T> =
        object : IdentifierGenerator<T> {
            override fun generate(): T = value
        }

    private data class Fixture(
        val coordinator: StrategySynchronizationExecutionCoordinator,
    )

    private class FixedClock(
        private val instant: DataLoomInstant,
    ) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
    }
}
