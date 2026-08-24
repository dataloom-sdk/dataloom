package io.dataloom.runtime.facade

import io.dataloom.api.configuration.ConfigurationSnapshot
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.IdentifierGenerator
import io.dataloom.api.identifier.PolicyCheckId
import io.dataloom.api.identifier.PolicySetId
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.SynchronizationEventId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.policy.PolicyCheck
import io.dataloom.api.policy.PolicyCheckOutcome
import io.dataloom.api.policy.PolicyDecisionRecord
import io.dataloom.api.policy.PolicyDecisionScope
import io.dataloom.api.policy.PolicyEvaluationBudget
import io.dataloom.api.policy.PolicyEvaluationInput
import io.dataloom.api.policy.PolicyEvaluator
import io.dataloom.api.policy.PolicySet
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
import io.dataloom.api.security.DataLoomDigest
import io.dataloom.api.security.DataLoomDigestCalculator
import io.dataloom.api.security.DigestAlgorithm
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateRecord
import io.dataloom.api.state.DurableStateStore
import io.dataloom.api.strategy.NetworkOnlyStrategyProfile
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyConnectivity
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyOperationInput
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.strategy.StrategyProviderHealth
import io.dataloom.api.strategy.StrategyRuntimeEvidence
import io.dataloom.api.strategy.StrategySynchronizationRequest
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.time.DataLoomMonotonicClock
import io.dataloom.api.time.DataLoomMonotonicReading
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.runtime.strategy.StrategyExecutionRejectionReason
import io.dataloom.runtime.strategy.StrategySynchronizationExecutionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/**
 * Proves [DataLoomStrategyAdmissionPolicySpec]/`strategyAdmissionPolicyConfiguration`
 * is a real, reachable caller of [PolicyEvaluator.evaluate] and
 * [io.dataloom.api.policy.DurablePolicyDecisionLog] --
 * [io.dataloom.runtime.strategy.StrategySynchronizationExecutionCoordinator]
 * evaluates and enforces the configured policy for real when configured, and
 * performs no policy evaluation at all when it is not (byte-for-byte the
 * same behavior as before this feature existed).
 */
class DataLoomBuilderStrategyAdmissionPolicyTest {

    @Test
    fun allowedRequestExecutesAndDecisionIsDurablyRecordedWhenConfigured() = runTest {
        val transport = RecordingTransportProvider()
        val bindings = StrategyProviderBindings(transportProviderId = transport.descriptor.id)
        val store = InMemoryPolicyDecisionStore()
        val policySet = PolicySet(
            id = PolicySetId("admission-policy"),
            checks = listOf(AlwaysAllowCheck),
        )
        val dataLoom = builder(transport, bindings)
            .strategyAdmissionPolicyConfiguration(
                DataLoomStrategyAdmissionPolicySpec(
                    policySet = policySet,
                    evaluator = PolicyEvaluator(FixedMonotonicClock()),
                    budget = PolicyEvaluationBudget(maxElapsedNanoseconds = 1_000_000_000L),
                    configurationSnapshot = emptySnapshot(),
                    decisionLogStore = store,
                ),
            )
            .build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())
        val request = networkOnlyRequest("allowed")

        val result = dataLoom.synchronize(request, bindings)

        assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        val recorded = store.recordedFor(
            PolicyDecisionScope(policySetId = policySet.id, executionId = request.request.context.executionId),
        )
        assertEquals(PolicyCheckOutcome.Allow("always allow"), recorded?.decision?.outcome)
    }

    @Test
    fun deniedRequestIsRejectedAndDecisionIsDurablyRecordedWhenConfigured() = runTest {
        val transport = RecordingTransportProvider()
        val bindings = StrategyProviderBindings(transportProviderId = transport.descriptor.id)
        val store = InMemoryPolicyDecisionStore()
        val policySet = PolicySet(
            id = PolicySetId("admission-policy"),
            checks = listOf(AlwaysDenyCheck),
        )
        val dataLoom = builder(transport, bindings)
            .strategyAdmissionPolicyConfiguration(
                DataLoomStrategyAdmissionPolicySpec(
                    policySet = policySet,
                    evaluator = PolicyEvaluator(FixedMonotonicClock()),
                    budget = PolicyEvaluationBudget(maxElapsedNanoseconds = 1_000_000_000L),
                    configurationSnapshot = emptySnapshot(),
                    decisionLogStore = store,
                ),
            )
            .build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())
        val request = networkOnlyRequest("denied")

        val result = dataLoom.synchronize(request, bindings)

        assertIs<StrategySynchronizationExecutionResult.Rejected>(result)
        assertEquals(StrategyExecutionRejectionReason.POLICY_DENIED, result.reason)
        assertEquals(0, transport.pullCallCount)
        val recorded = store.recordedFor(
            PolicyDecisionScope(policySetId = policySet.id, executionId = request.request.context.executionId),
        )
        assertEquals(PolicyCheckOutcome.Deny("always deny"), recorded?.decision?.outcome)
    }

    @Test
    fun deniedRequestIsRejectedWhenNoDecisionLogIsConfigured() = runTest {
        val transport = RecordingTransportProvider()
        val bindings = StrategyProviderBindings(transportProviderId = transport.descriptor.id)
        val policySet = PolicySet(
            id = PolicySetId("admission-policy"),
            checks = listOf(AlwaysDenyCheck),
        )
        val dataLoom = builder(transport, bindings)
            .strategyAdmissionPolicyConfiguration(
                DataLoomStrategyAdmissionPolicySpec(
                    policySet = policySet,
                    evaluator = PolicyEvaluator(FixedMonotonicClock()),
                    budget = PolicyEvaluationBudget(maxElapsedNanoseconds = 1_000_000_000L),
                    configurationSnapshot = emptySnapshot(),
                    // decisionLogStore intentionally omitted.
                ),
            )
            .build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())
        val request = networkOnlyRequest("denied-no-log")

        val result = dataLoom.synchronize(request, bindings)

        assertIs<StrategySynchronizationExecutionResult.Rejected>(result)
        assertEquals(StrategyExecutionRejectionReason.POLICY_DENIED, result.reason)
        assertEquals(0, transport.pullCallCount)
    }

    @Test
    fun requestExecutesAndNoPolicyIsEvaluatedWhenNotConfigured() = runTest {
        val transport = RecordingTransportProvider()
        val bindings = StrategyProviderBindings(transportProviderId = transport.descriptor.id)
        // Note: strategyAdmissionPolicyConfiguration is never called.
        val dataLoom = builder(transport, bindings).build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())
        val request = networkOnlyRequest("unconfigured")

        val result = dataLoom.synchronize(request, bindings)

        assertIs<StrategySynchronizationExecutionResult.Executed>(result)
        assertEquals(1, transport.pullCallCount)
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

    private fun networkOnlyRequest(suffix: String): StrategySynchronizationRequest = StrategySynchronizationRequest(
        request = synchronizationRequest(suffix, SynchronizationDirection.PULL),
        decisionId = StrategyDecisionId("admission-policy-$suffix-decision"),
        planId = StrategyPlanId("admission-policy-$suffix-plan"),
        profile = NetworkOnlyStrategyProfile(
            id = StrategyProfileId("admission-policy-$suffix-profile"),
            configurationVersion = StrategyConfigurationVersion(1L),
        ),
        evidence = StrategyRuntimeEvidence(
            connectivity = StrategyConnectivity.AVAILABLE,
            transportHealth = StrategyProviderHealth.HEALTHY,
        ),
        input = StrategyOperationInput.DirectTransport(),
    )

    private fun synchronizationRequest(
        suffix: String,
        direction: SynchronizationDirection,
    ): SynchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("admission-policy-$suffix-workflow"),
        sessionId = SynchronizationSessionId("admission-policy-$suffix-session"),
        direction = direction,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("admission-policy-$suffix-execution"),
            correlationId = CorrelationId("admission-policy-$suffix-correlation"),
        ),
    )

    private fun runtimeDependencies(): RuntimeDependencies = RuntimeDependencies(
        clock = FixedDataLoomClock(DataLoomInstant(epochMilliseconds = 9_000L)),
        identifiers = RuntimeIdentifierGenerators(
            synchronizationEventIds = generator { SynchronizationEventId("admission-policy-event") },
            queueEntryIds = generator { QueueEntryId("admission-policy-queue-entry") },
            queueLeaseIds = generator { QueueLeaseId("admission-policy-queue-lease") },
            conflictIds = generator { ConflictId("admission-policy-conflict") },
        ),
    )

    private fun emptySnapshot(): ConfigurationSnapshot = ConfigurationSnapshot.create(
        version = 1L,
        entries = emptyMap(),
        digestCalculator = FakeDataLoomDigestCalculator,
    )

    private fun <T> generator(block: () -> T): IdentifierGenerator<T> =
        object : IdentifierGenerator<T> {
            override fun generate(): T = block()
        }

    private class FixedDataLoomClock(private val instant: DataLoomInstant) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
    }

    /** Deterministic clock that never reports the budget as exhausted. */
    private class FixedMonotonicClock : DataLoomMonotonicClock {
        override fun mark(): DataLoomMonotonicReading = DataLoomMonotonicReading(0L)
    }

    /** Deterministic, non-cryptographic digest calculator -- only used to build snapshots. */
    private object FakeDataLoomDigestCalculator : DataLoomDigestCalculator {
        override fun digest(algorithm: DigestAlgorithm, input: ByteArray): DataLoomDigest {
            val length = when (algorithm) {
                DigestAlgorithm.SHA_256 -> 32
                DigestAlgorithm.SHA_512 -> 64
            }
            return DataLoomDigest(algorithm, ByteArray(length))
        }
    }

    private object AlwaysAllowCheck : PolicyCheck {
        override val id: PolicyCheckId = PolicyCheckId("always-allow")
        override fun evaluate(input: PolicyEvaluationInput): PolicyCheckOutcome =
            PolicyCheckOutcome.Allow("always allow")
    }

    private object AlwaysDenyCheck : PolicyCheck {
        override val id: PolicyCheckId = PolicyCheckId("always-deny")
        override fun evaluate(input: PolicyEvaluationInput): PolicyCheckOutcome =
            PolicyCheckOutcome.Deny("always deny")
    }

    private class RecordingTransportProvider(
        private val pullResult: ProviderOperationResult<PullChangesResult> =
            ProviderOperationResult.Success(PullChangesResult.NoChanges()),
    ) : TransportProvider {
        var pullCallCount: Int = 0
            private set

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("admission-policy-transport"),
            name = ProviderName("Admission Policy Transport"),
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
        ): ProviderOperationResult<PullChangesResult> {
            pullCallCount++
            return pullResult
        }
    }

    private class InMemoryPolicyDecisionStore : DurableStateStore<PolicyDecisionScope, PolicyDecisionRecord> {
        private val records = mutableMapOf<PolicyDecisionScope, DurableStateRecord<PolicyDecisionRecord>>()

        suspend fun recordedFor(scope: PolicyDecisionScope): PolicyDecisionRecord? = records[scope]?.state

        override suspend fun load(
            scope: PolicyDecisionScope,
        ): ProviderOperationResult<DurableStateLoadResult<PolicyDecisionRecord>> {
            val record = records[scope]
            return ProviderOperationResult.Success(
                if (record == null) DurableStateLoadResult.Missing else DurableStateLoadResult.Found(record),
            )
        }

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<PolicyDecisionScope, PolicyDecisionRecord>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<PolicyDecisionRecord>> {
            val current = records[request.scope]
            if (current?.version != request.expectedVersion) {
                return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Conflict(current))
            }
            val updated = DurableStateRecord(
                state = request.nextState,
                version = (current?.version ?: -1L) + 1L,
                schemaVersion = request.nextSchemaVersion,
            )
            records[request.scope] = updated
            return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Updated(updated))
        }
    }
}
