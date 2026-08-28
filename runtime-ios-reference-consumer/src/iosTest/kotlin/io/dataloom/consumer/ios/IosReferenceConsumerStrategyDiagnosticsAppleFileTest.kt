@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.consumer.ios

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
import io.dataloom.api.random.AppleDataLoomSecureRandom
import io.dataloom.api.random.DataLoomSecureRandom
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.runtime.RuntimeIdentifierGenerators
import io.dataloom.api.strategy.DurableStrategyDecisionEventLog
import io.dataloom.api.strategy.NetworkOnlyStrategyProfile
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyConnectivity
import io.dataloom.api.strategy.StrategyDecisionEvent
import io.dataloom.api.strategy.StrategyDecisionEventCodec
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyDecisionOutcomeKind
import io.dataloom.api.strategy.StrategyOperationInput
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.strategy.StrategyProviderHealth
import io.dataloom.api.strategy.StrategyRuntimeEvidence
import io.dataloom.api.strategy.StrategySynchronizationRequest
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.time.AppleDataLoomClock
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.runtime.facade.DataLoom
import io.dataloom.runtime.facade.DataLoomBuilder
import io.dataloom.runtime.facade.DataLoomStrategyDiagnosticsSpec
import io.dataloom.runtime.state.AppleFileDurableStateStore
import io.dataloom.runtime.strategy.StrategyExecutionRejectionReason
import io.dataloom.runtime.strategy.StrategySynchronizationExecutionResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/**
 * Kotlin/Native iOS Simulator runtime proof that
 * [DataLoomStrategyDiagnosticsSpec]/`strategyDiagnosticsConfiguration` genuinely
 * adopts [AppleFileDurableStateStore] as a real [io.dataloom.api.strategy.DurableStrategyDecisionEventLog]
 * backing store -- the first real domain adoption of that store anywhere in
 * this repository. Before this test, [AppleFileDurableStateStore] existed
 * (`#307`, `docs/status/market-readiness.md`'s `#93` row) and was verified in
 * isolation by its own unit test, but every durable-state domain this
 * repository has adopted so far (`DurableConfigurationHistory`,
 * `DurablePolicyDecisionLog`, `DurableUnresolvedConflictLog`,
 * `DurableResolvedConflictDecisionLog`, `DurableAssetManifestHistory`,
 * `DurableStrategyDecisionEventLog`, `DurableStrategyDecisionOutcomeHistory`)
 * only ever used `RoomDurableStateStore` -- the explicitly named gap this test
 * closes for the strategy-decision-diagnostics domain specifically, chosen
 * because [DataLoomStrategyDiagnosticsSpec.store] is a plain, non-nullable,
 * caller-supplied [io.dataloom.api.state.DurableStateStore] with no other
 * design decision attached (unlike [io.dataloom.runtime.facade.DataLoomStrategyAdmissionPolicySpec],
 * which also needs a [io.dataloom.api.policy.PolicySet]/evaluator/budget/
 * configuration snapshot this slice does not need to invent).
 *
 * This is also a genuine `#101` (DL-039A) platform-parity proof: it is the
 * iOS/Apple counterpart to whichever Android test first proves a
 * `RoomDurableStateStore`-backed durable-state domain surviving a real
 * restart through `DataLoomBuilder` -- except here the backing store is
 * Apple's own file-based implementation, not Room.
 *
 * ## What this proves
 *
 * [executedNetworkOnlyDecisionSurvivesARealAppleFileStoreRestart] drives a
 * real [DataLoom.synchronize] call for a [NetworkOnlyStrategyProfile] request
 * through a real [DataLoomBuilder] wired with
 * `strategyDiagnosticsConfiguration(DataLoomStrategyDiagnosticsSpec(store = AppleFileDurableStateStore(...)))`,
 * then reads the resulting [StrategyDecisionEvent] back out through a
 * *second*, independently constructed [AppleFileDurableStateStore] instance
 * pointed at the same on-disk file -- proving genuine persistence to a real
 * file (`flock`-guarded, atomically renamed, exactly as
 * [AppleFileDurableStateStoreTest] proves for the store in isolation), not
 * merely an in-memory decorator, on a real Kotlin/Native iOS Simulator
 * runtime. [rejectedRequestIsAlsoDurablyRecordedToTheRealAppleFileStore]
 * proves the coordinator's documented "records a terminal result including
 * early admission rejections" behavior reaches the real file store too, not
 * only the successful path. [nothingIsRecordedWhenDiagnosticsAreNotConfigured]
 * proves the opt-in default -- an unconfigured [DataLoomBuilder] never
 * constructs or touches the file -- against a real, disk-backed `Missing`
 * load result, not an in-memory fake.
 *
 * ## What this does not prove
 *
 * A real caller reading this history from a production operations surface
 * (this repository has no such surface for any durable-state domain yet);
 * concurrent-writer contention against a real second OS process (Apple has
 * no `android:process`-equivalent mechanism -- see
 * `docs/apple/process-termination-investigation.md`); or adoption of
 * [AppleFileDurableStateStore] by any other durable-state domain
 * (`DurablePolicyDecisionLog`, the conflict logs, `DurableAssetManifestHistory`,
 * and `DurableStrategyDecisionOutcomeHistory` all remain Room-only in
 * production wiring after this change).
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
class IosReferenceConsumerStrategyDiagnosticsAppleFileTest {

    @Test
    fun executedNetworkOnlyDecisionSurvivesARealAppleFileStoreRestart() = runTest {
        val runId = NSUUID().UUIDString
        val directoryPath = strategyDiagnosticsDirectoryPath(runId)
        val fileName = "dataloom-strategy-diagnostics-$runId.tsv"

        val transport = RecordingTransportProvider()
        val bindings = StrategyProviderBindings(transportProviderId = transport.descriptor.id)
        val dataLoom = strategyDiagnosticsDataLoom(
            transport = transport,
            bindings = bindings,
            store = strategyDecisionEventStore(directoryPath, fileName),
        )
        assertEquals(ProviderLifecycleResult.InitializeSuccess, dataLoom.initialize())

        val request = networkOnlyRequest(runId)
        val result = dataLoom.synchronize(request, bindings)
        assertIs<StrategySynchronizationExecutionResult.Executed>(result)

        // A brand-new store instance, pointed at the same directory/file but
        // sharing no in-memory state whatsoever with the one
        // strategyDiagnosticsConfiguration used above -- the same "process
        // restart" bar AppleFileDurableStateStoreTest and
        // AppleFileCircuitBreakerStateStoreTest already establish for this
        // store family.
        val restartedLog = DurableStrategyDecisionEventLog(strategyDecisionEventStore(directoryPath, fileName))
        val recorded = assertIs<ProviderOperationResult.Success<StrategyDecisionEvent?>>(
            restartedLog.current(request.decisionId),
        )
        assertEquals(StrategyDecisionOutcomeKind.EXECUTED, recorded.value?.outcomeKind)
        assertEquals(request.planId, recorded.value?.planId)

        assertEquals(ProviderLifecycleResult.ShutdownSuccess, dataLoom.shutdown())
    }

    @Test
    fun rejectedRequestIsAlsoDurablyRecordedToTheRealAppleFileStore() = runTest {
        val runId = NSUUID().UUIDString
        val directoryPath = strategyDiagnosticsDirectoryPath(runId)
        val fileName = "dataloom-strategy-diagnostics-rejected-$runId.tsv"

        val transport = RecordingTransportProvider()
        val bindings = StrategyProviderBindings(transportProviderId = transport.descriptor.id)
        val dataLoom = strategyDiagnosticsDataLoom(
            transport = transport,
            bindings = bindings,
            store = strategyDecisionEventStore(directoryPath, fileName),
        )
        // Deliberately never initialized -- every request is rejected with
        // PROVIDERS_NOT_INITIALIZED before any provider is resolved.
        val request = networkOnlyRequest(runId)

        val result = dataLoom.synchronize(request, bindings)
        val rejected = assertIs<StrategySynchronizationExecutionResult.Rejected>(result)
        assertEquals(StrategyExecutionRejectionReason.PROVIDERS_NOT_INITIALIZED, rejected.reason)

        val restartedLog = DurableStrategyDecisionEventLog(strategyDecisionEventStore(directoryPath, fileName))
        val recorded = assertIs<ProviderOperationResult.Success<StrategyDecisionEvent?>>(
            restartedLog.current(request.decisionId),
        )
        assertEquals(StrategyDecisionOutcomeKind.REJECTED, recorded.value?.outcomeKind)
        assertEquals("PROVIDERS_NOT_INITIALIZED", recorded.value?.outcomeDetail)
    }

    @Test
    fun nothingIsRecordedWhenDiagnosticsAreNotConfigured() = runTest {
        val runId = NSUUID().UUIDString
        val directoryPath = strategyDiagnosticsDirectoryPath(runId)
        val fileName = "dataloom-strategy-diagnostics-unconfigured-$runId.tsv"

        val transport = RecordingTransportProvider()
        val bindings = StrategyProviderBindings(transportProviderId = transport.descriptor.id)
        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(strategyDiagnosticsRuntimeDependencies())
            .provider(transport)
            .defaultStrategyProviderBindings(bindings)
            // Note: strategyDiagnosticsConfiguration is never called.
            .build()
        assertEquals(ProviderLifecycleResult.InitializeSuccess, dataLoom.initialize())

        val request = networkOnlyRequest(runId)
        val result = dataLoom.synchronize(request, bindings)
        assertIs<StrategySynchronizationExecutionResult.Executed>(result)

        // A real, disk-backed store pointed at the exact path an enabled
        // spec would have used still reports Missing -- the file was never
        // created, not merely "this in-memory fake was never asked".
        val log = DurableStrategyDecisionEventLog(strategyDecisionEventStore(directoryPath, fileName))
        val recorded = assertIs<ProviderOperationResult.Success<StrategyDecisionEvent?>>(
            log.current(request.decisionId),
        )
        assertNull(recorded.value)

        assertEquals(ProviderLifecycleResult.ShutdownSuccess, dataLoom.shutdown())
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private fun strategyDiagnosticsDirectoryPath(runId: String): String = buildString {
        append(NSTemporaryDirectory().trimEnd('/'))
        append("/dataloom-ios-reference-consumer-strategy-diagnostics-")
        append(runId)
    }

    private fun strategyDecisionEventStore(
        directoryPath: String,
        fileName: String,
    ): AppleFileDurableStateStore<StrategyDecisionId, StrategyDecisionEvent> = AppleFileDurableStateStore(
        directoryPath = directoryPath,
        fileName = fileName,
        scopeKeyEncoder = DurableStrategyDecisionEventLog.KeyEncoder,
        codec = StrategyDecisionEventCodec(),
    )

    private fun strategyDiagnosticsDataLoom(
        transport: TransportProvider,
        bindings: StrategyProviderBindings,
        store: AppleFileDurableStateStore<StrategyDecisionId, StrategyDecisionEvent>,
    ): DataLoom = DataLoomBuilder()
        .runtimeDependencies(strategyDiagnosticsRuntimeDependencies())
        .provider(transport)
        .defaultStrategyProviderBindings(bindings)
        .strategyDiagnosticsConfiguration(DataLoomStrategyDiagnosticsSpec(store = store))
        .build()

    private fun networkOnlyRequest(runId: String): StrategySynchronizationRequest = StrategySynchronizationRequest(
        request = SynchronizationRequest(
            workflowId = WorkflowId("strategy-diagnostics-workflow-$runId"),
            sessionId = SynchronizationSessionId("strategy-diagnostics-session-$runId"),
            direction = SynchronizationDirection.PULL,
            mode = SynchronizationMode.DELTA,
            context = ExecutionContext(
                executionId = ExecutionId("strategy-diagnostics-execution-$runId"),
                correlationId = CorrelationId("strategy-diagnostics-correlation-$runId"),
            ),
        ),
        decisionId = StrategyDecisionId("strategy-diagnostics-decision-$runId"),
        planId = StrategyPlanId("strategy-diagnostics-plan-$runId"),
        profile = NetworkOnlyStrategyProfile(
            id = StrategyProfileId("strategy-diagnostics-profile-$runId"),
            configurationVersion = StrategyConfigurationVersion(1L),
        ),
        evidence = StrategyRuntimeEvidence(
            connectivity = StrategyConnectivity.AVAILABLE,
            transportHealth = StrategyProviderHealth.HEALTHY,
        ),
        input = StrategyOperationInput.DirectTransport(),
    )

    /**
     * Real wall clock and secure-random-backed identifier generators,
     * matching every other test in this module.
     */
    private fun strategyDiagnosticsRuntimeDependencies(): RuntimeDependencies = RuntimeDependencies(
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
 * Test-only [TransportProvider] that reports no remote changes and fails any
 * push attempt -- [NetworkOnlyStrategyProfile] only ever pulls in this test's
 * scenarios, and this fixture only needs to prove the strategy engine reaches
 * a terminal [StrategySynchronizationExecutionResult.Executed] result, not to
 * exercise transport content itself (already covered by
 * [IosReferenceConsumerTest]).
 */
private class RecordingTransportProvider : TransportProvider {
    override val descriptor: ProviderDescriptor = ProviderDescriptor(
        id = ProviderId("io.dataloom.consumer.ios.test.strategy-diagnostics-transport"),
        name = ProviderName("Strategy Diagnostics Test Transport"),
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
        error("RecordingTransportProvider does not support push.")

    override suspend fun pullChanges(
        request: PullChangesRequest,
    ): ProviderOperationResult<PullChangesResult> =
        ProviderOperationResult.Success(PullChangesResult.NoChanges())
}
