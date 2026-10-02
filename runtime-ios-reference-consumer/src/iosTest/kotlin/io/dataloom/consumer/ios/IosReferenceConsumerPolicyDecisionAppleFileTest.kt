@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.consumer.ios

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
import io.dataloom.api.random.AppleDataLoomSecureRandom
import io.dataloom.api.random.DataLoomSecureRandom
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.runtime.RuntimeIdentifierGenerators
import io.dataloom.api.security.AppleDataLoomDigestCalculator
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
import io.dataloom.api.time.AppleDataLoomClock
import io.dataloom.api.time.AppleDataLoomMonotonicClock
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.runtime.facade.DataLoom
import io.dataloom.runtime.facade.DataLoomBuilder
import io.dataloom.runtime.facade.DataLoomStrategyAdmissionPolicySpec
import io.dataloom.runtime.state.AppleFileDurableDomainStores
import io.dataloom.runtime.strategy.StrategyExecutionRejectionReason
import io.dataloom.runtime.strategy.StrategySynchronizationExecutionResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/**
 * Kotlin/Native iOS Simulator runtime proof that
 * [DataLoomStrategyAdmissionPolicySpec]/`strategyAdmissionPolicyConfiguration`
 * genuinely adopts [io.dataloom.runtime.state.AppleFileDurableStateStore] as a
 * real [io.dataloom.api.policy.DurablePolicyDecisionLog] backing store on
 * iOS, through a real [DataLoomBuilder].
 *
 * Before this test, [AppleFileDurableDomainStores.policyDecisionStore]
 * (`#93`, 2026-09-28) existed and had its own store-level iOS proof, but
 * nothing drove it through [DataLoomBuilder] on iOS -- only
 * strategy-decision diagnostics
 * ([IosReferenceConsumerStrategyDiagnosticsAppleFileTest]) had that bar
 * before this file. This is the policy-decisions counterpart, following the
 * identical shape: a real [DataLoom.synchronize] call for a
 * [NetworkOnlyStrategyProfile] request through a real [DataLoomBuilder]
 * wired with `strategyAdmissionPolicyConfiguration(...)`, reading the
 * resulting [io.dataloom.api.policy.PolicyDecisionRecord] back out through a
 * *second*, independently constructed Apple-file store pointed at the same
 * on-disk file -- proving genuine persistence to a real file, not merely an
 * in-memory decorator.
 *
 * ## What this does not prove
 *
 * A real caller reading this history from a production operations surface;
 * concurrent-writer contention against a real second OS process (see
 * `docs/apple/process-termination-investigation.md`); or a live, per-request
 * [ConfigurationSnapshot] resolution (this spec takes one fixed snapshot --
 * see [DataLoomStrategyAdmissionPolicySpec]'s own KDoc for why).
 *
 * ## A note on how this was verified
 *
 * Like [IosReferenceConsumerStrategyDiagnosticsAppleFileTest], this file can
 * be cross-compiled from a Windows development host
 * (`compileTestKotlinIosArm64`/`IosSimulatorArm64`/`IosX64`), but **cannot
 * be executed** there -- only a real macOS host with Xcode and the iOS
 * Simulator can run `iosSimulatorArm64Test`/`iosX64Test`. This repository's
 * `apple-validation.yml` CI job (`macos-15`) is the actual pass/fail signal
 * for this file's runtime behavior, not local cross-compilation alone.
 */
class IosReferenceConsumerPolicyDecisionAppleFileTest {

    private val policySetId = PolicySetId("ios-admission-policy")

    @Test
    fun allowedDecisionIsDurablyRecordedToARealAppleFileStoreAndSurvivesRestart() = runTest {
        val runId = NSUUID().UUIDString
        val directoryPath = policyDecisionDirectoryPath(runId)
        val fileName = "dataloom-policy-decision-$runId.tsv"

        val transport = PolicyDecisionRecordingTransportProvider()
        val bindings = StrategyProviderBindings(transportProviderId = transport.descriptor.id)
        val dataLoom = policyDataLoom(
            transport = transport,
            bindings = bindings,
            check = AlwaysAllowCheck,
            store = policyDecisionStore(directoryPath, fileName),
        )
        assertEquals(ProviderLifecycleResult.InitializeSuccess, dataLoom.initialize())

        val request = networkOnlyRequest(runId)
        val result = dataLoom.synchronize(request, bindings)
        assertIs<StrategySynchronizationExecutionResult.Executed>(result)

        // A brand-new store instance, pointed at the same directory/file but
        // sharing no in-memory state with the one policyDataLoom used above.
        val restartedLog = io.dataloom.api.policy.DurablePolicyDecisionLog(policyDecisionStore(directoryPath, fileName))
        val scope = PolicyDecisionScope(policySetId = policySetId, executionId = request.request.context.executionId)
        val recorded = assertIs<ProviderOperationResult.Success<io.dataloom.api.policy.PolicyDecisionRecord?>>(
            restartedLog.current(scope),
        )
        assertEquals(PolicyCheckOutcome.Allow("always allow"), recorded.value?.decision?.outcome)

        assertEquals(ProviderLifecycleResult.ShutdownSuccess, dataLoom.shutdown())
    }

    @Test
    fun deniedDecisionIsDurablyRecordedToARealAppleFileStoreAndSurvivesRestart() = runTest {
        val runId = NSUUID().UUIDString
        val directoryPath = policyDecisionDirectoryPath(runId)
        val fileName = "dataloom-policy-decision-denied-$runId.tsv"

        val transport = PolicyDecisionRecordingTransportProvider()
        val bindings = StrategyProviderBindings(transportProviderId = transport.descriptor.id)
        val dataLoom = policyDataLoom(
            transport = transport,
            bindings = bindings,
            check = AlwaysDenyCheck,
            store = policyDecisionStore(directoryPath, fileName),
        )
        assertEquals(ProviderLifecycleResult.InitializeSuccess, dataLoom.initialize())

        val request = networkOnlyRequest(runId)
        val result = dataLoom.synchronize(request, bindings)
        val rejected = assertIs<StrategySynchronizationExecutionResult.Rejected>(result)
        assertEquals(StrategyExecutionRejectionReason.POLICY_DENIED, rejected.reason)

        val restartedLog = io.dataloom.api.policy.DurablePolicyDecisionLog(policyDecisionStore(directoryPath, fileName))
        val scope = PolicyDecisionScope(policySetId = policySetId, executionId = request.request.context.executionId)
        val recorded = assertIs<ProviderOperationResult.Success<io.dataloom.api.policy.PolicyDecisionRecord?>>(
            restartedLog.current(scope),
        )
        assertEquals(PolicyCheckOutcome.Deny("always deny"), recorded.value?.decision?.outcome)
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private fun policyDecisionDirectoryPath(runId: String): String = buildString {
        append(NSTemporaryDirectory().trimEnd('/'))
        append("/dataloom-ios-reference-consumer-policy-decision-")
        append(runId)
    }

    private fun policyDecisionStore(
        directoryPath: String,
        fileName: String,
    ) = AppleFileDurableDomainStores.policyDecisionStore(directoryPath = directoryPath, fileName = fileName)

    private fun policyDataLoom(
        transport: TransportProvider,
        bindings: StrategyProviderBindings,
        check: PolicyCheck,
        store: io.dataloom.api.state.DurableStateStore<PolicyDecisionScope, io.dataloom.api.policy.PolicyDecisionRecord>,
    ): DataLoom = DataLoomBuilder()
        .runtimeDependencies(policyRuntimeDependencies())
        .provider(transport)
        .defaultStrategyProviderBindings(bindings)
        .strategyAdmissionPolicyConfiguration(
            DataLoomStrategyAdmissionPolicySpec(
                policySet = PolicySet(id = policySetId, checks = listOf(check)),
                evaluator = PolicyEvaluator(AppleDataLoomMonotonicClock()),
                budget = PolicyEvaluationBudget(maxElapsedNanoseconds = 1_000_000_000L),
                configurationSnapshot = ConfigurationSnapshot.create(
                    version = 1L,
                    entries = emptyMap(),
                    digestCalculator = AppleDataLoomDigestCalculator(),
                ),
                decisionLogStore = store,
            ),
        )
        .build()

    private fun networkOnlyRequest(runId: String): StrategySynchronizationRequest = StrategySynchronizationRequest(
        request = SynchronizationRequest(
            workflowId = WorkflowId("policy-decision-workflow-$runId"),
            sessionId = SynchronizationSessionId("policy-decision-session-$runId"),
            direction = SynchronizationDirection.PULL,
            mode = SynchronizationMode.DELTA,
            context = ExecutionContext(
                executionId = ExecutionId("policy-decision-execution-$runId"),
                correlationId = CorrelationId("policy-decision-correlation-$runId"),
            ),
        ),
        decisionId = StrategyDecisionId("policy-decision-decision-$runId"),
        planId = StrategyPlanId("policy-decision-plan-$runId"),
        profile = NetworkOnlyStrategyProfile(
            id = StrategyProfileId("policy-decision-profile-$runId"),
            configurationVersion = StrategyConfigurationVersion(1L),
        ),
        evidence = StrategyRuntimeEvidence(
            connectivity = StrategyConnectivity.AVAILABLE,
            transportHealth = StrategyProviderHealth.HEALTHY,
        ),
        input = StrategyOperationInput.DirectTransport(),
    )

    private fun policyRuntimeDependencies(): RuntimeDependencies = RuntimeDependencies(
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

    private object AlwaysAllowCheck : PolicyCheck {
        override val id: PolicyCheckId = PolicyCheckId("ios-always-allow")
        override fun evaluate(input: PolicyEvaluationInput): PolicyCheckOutcome = PolicyCheckOutcome.Allow("always allow")
    }

    private object AlwaysDenyCheck : PolicyCheck {
        override val id: PolicyCheckId = PolicyCheckId("ios-always-deny")
        override fun evaluate(input: PolicyEvaluationInput): PolicyCheckOutcome = PolicyCheckOutcome.Deny("always deny")
    }
}

/**
 * Test-only [TransportProvider] that reports no remote changes and fails any
 * push attempt -- matches [NetworkOnlyStrategyProfile]'s pull-only usage in
 * [IosReferenceConsumerStrategyDiagnosticsAppleFileTest], duplicated here
 * (not reused) because that class is file-private to its own test file.
 */
private class PolicyDecisionRecordingTransportProvider : TransportProvider {
    override val descriptor: ProviderDescriptor = ProviderDescriptor(
        id = ProviderId("io.dataloom.consumer.ios.test.policy-decision-transport"),
        name = ProviderName("Policy Decision Test Transport"),
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
        error("PolicyDecisionRecordingTransportProvider does not support push.")

    override suspend fun pullChanges(
        request: PullChangesRequest,
    ): ProviderOperationResult<PullChangesResult> =
        ProviderOperationResult.Success(PullChangesResult.NoChanges())
}
