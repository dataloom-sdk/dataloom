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
}
