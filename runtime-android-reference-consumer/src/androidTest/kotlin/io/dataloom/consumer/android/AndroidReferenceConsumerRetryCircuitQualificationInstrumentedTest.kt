package io.dataloom.consumer.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.WorkManagerTestInitHelper
import io.dataloom.android.androidDataLoomProviders
import io.dataloom.android.installAndroidProviders
import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.ChangeSet
import io.dataloom.api.change.EntityReference
import io.dataloom.api.circuit.CircuitBreakerLoadResult
import io.dataloom.api.circuit.CircuitBreakerPhase
import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ChangeSetId
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.IdentifierGenerator
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.RetryPolicyId
import io.dataloom.api.identifier.SynchronizationEventId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.ChangeOperation
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
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.runtime.RuntimeIdentifierGenerators
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.queue.room.DataLoomDatabaseBuilder
import io.dataloom.queue.room.RoomCircuitBreakerStateStore
import io.dataloom.runtime.execution.protection.ProviderProtectedSynchronizationResult
import io.dataloom.runtime.execution.protection.ProviderProtectionInvocation
import io.dataloom.runtime.execution.protection.ProviderProtectionOperationEvidence
import io.dataloom.runtime.execution.protection.ProviderProtectionPreExecutionReason
import io.dataloom.runtime.facade.DataLoom
import io.dataloom.runtime.facade.DataLoomBuilder
import io.dataloom.runtime.facade.DataLoomProviderProtectionSpec
import io.dataloom.runtime.facade.DataLoomStorageProtectionSpec
import io.dataloom.runtime.facade.DataLoomTransportProtectionSpec
import io.dataloom.runtime.facade.ProviderProtectedSynchronizationExecutionResult
import io.dataloom.runtime.retry.CircuitBreakerConfiguration
import io.dataloom.runtime.retry.CircuitBreakerRecordResult
import io.dataloom.runtime.retry.CircuitBreakerRejectionReason
import io.dataloom.runtime.retry.RetryBackoffStrategy
import io.dataloom.runtime.retry.RetryJitterStrategy
import io.dataloom.runtime.retry.RetryRandomRequest
import io.dataloom.runtime.retry.RetryRandomSource
import io.dataloom.runtime.retry.StandardRetryPolicy
import io.dataloom.runtime.retry.StorageCircuitOperation
import io.dataloom.runtime.retry.StorageCircuitScopes
import io.dataloom.runtime.retry.SynchronizationRetryEvaluation
import io.dataloom.runtime.retry.SynchronizationRetryEvaluator
import io.dataloom.runtime.retry.TransportCircuitOperation
import io.dataloom.runtime.retry.TransportCircuitScopes
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gradle Managed Device runtime proof that Book 2 AC-FUNC-004 (backoff +
 * jitter, circuit opens, attempts are rejected, half-open probe occurs, and
 * normal operation recovers) holds through the real, composed native
 * Android *provider flow* -- [DataLoomBuilder.providerProtectionConfiguration]
 * wired to the real [RoomCircuitBreakerStateStore] and driven by real
 * [DataLoom.protectedSynchronization] calls against the real
 * `InboundPullSynchronizationPipeline` -- closing the native-Android third
 * of the "complete AC-FUNC-004 provider flow through native Android, KMP
 * Android, and KMP iOS" gap `#94`'s market-readiness row names as still
 * pending.
 *
 * ## Why this is an instrumented test, not a Robolectric one
 *
 * This was first attempted as a Robolectric unit test
 * (`AndroidReferenceConsumerRobolectricTest`'s own sibling class shape).
 * That attempt failed at runtime with `CIRCUIT_ROOM_DATABASE_FAILURE` on
 * the very first [RoomCircuitBreakerStateStore] call -- reproduced in
 * isolation (a bare `store.load(scope)` against a freshly built database,
 * with no [DataLoomBuilder] or circuit-coordinator machinery involved at
 * all) confirms [RoomCircuitBreakerStateStore]'s `circuit_breaker_states`
 * table genuinely does not work under Robolectric's SQLite shadow in this
 * repository's current dependency versions, even though `RoomQueueProvider`
 * and `RoomStorageProvider` (same `DataLoomRoomDatabase`, different tables)
 * both already work fine there. This matches the existing pattern in this
 * repository exactly: every other `RoomCircuitBreakerStateStore`-touching
 * test (`RoomCircuitBreakerStateStoreInstrumentedTest`,
 * `RoomRetryCircuitFunctionalQualificationInstrumentedTest`,
 * `AndroidProcessTerminationCircuitBreakerInstrumentedTest`,
 * `AndroidCircuitBreakerProbeContentionInstrumentedTest`) is already
 * `androidTest`-only -- none is Robolectric. This test follows that same
 * established boundary rather than fighting it.
 *
 * ## What this compares against, and what it adds
 *
 * `RetryCircuitFunctionalQualificationTest` (`dataloom-runtime` commonTest)
 * and `RoomRetryCircuitFunctionalQualificationInstrumentedTest`
 * (`dataloom-queue-room` androidTest) already prove this exact AC-FUNC-004
 * sequence -- two failures open the circuit, a rejected attempt during the
 * open window never reaches the provider, one runtime wins the half-open
 * probe lease while a competing one is rejected as `PROBE_IN_FLIGHT`, and
 * the successful probe closes the circuit -- but both do so by driving
 * `CircuitBreakerExecutionGate`/`CircuitBreakerTransportOperationAdapter`
 * directly. Neither goes through [DataLoomBuilder], a real composed
 * provider stack, or the real `InboundPullSynchronizationPipeline`. The
 * Android checkpoint's own "Remaining Android acceptance work" section
 * names exactly this as open (see
 * `docs/audits/DL-040-ac-func-004-android-room-qualification.md`: "run the
 * full retry scheduling and transport-provider reference flow on both
 * native Android and KMP Android consumer paths").
 *
 * This test closes that native-Android gap: it drives the identical
 * deterministic scenario (failure threshold 2, 5s failure window, 1s open
 * duration, 500ms half-open probe lease; exponential 100ms/x2/max 1000ms
 * backoff with full jitter and a deterministic 40ms/75ms random sequence --
 * the exact same numbers `RetryCircuitFunctionalQualificationTest` uses)
 * entirely through [DataLoom.protectedSynchronization], with the real
 * `dataloom-android` providers ([androidDataLoomProviders]/
 * [installAndroidProviders]) composed by [DataLoomBuilder], and the real
 * on-disk [RoomCircuitBreakerStateStore] backing transport circuit
 * protection. "Backoff + jitter" is proven by driving the same production
 * [SynchronizationRetryEvaluator]/[StandardRetryPolicy] components between
 * real [DataLoom.protectedSynchronization] calls, exactly as the common
 * qualification test does with the raw adapter.
 *
 * ## Restart evidence, not OS process-kill evidence
 *
 * The "restarted"/"competing" runtime is a second, independently
 * constructed [RoomCircuitBreakerStateStore] over a second
 * [DataLoomDatabaseBuilder] connection to the *same* on-disk circuit
 * database file -- genuine multi-connection persistence evidence, the same
 * bar `RoomRetryCircuitFunctionalQualificationInstrumentedTest` itself
 * already established for the raw adapter. It is not a genuine OS process
 * kill/relaunch; that is the separate, already-investigated, still-blocked
 * half of `#94`'s "Still pending" clause (see
 * `docs/apple/process-termination-investigation.md`, and
 * `AndroidProcessTerminationCircuitBreakerInstrumentedTest`/
 * `AndroidProcessTerminationRetryBudgetInstrumentedTest` for the genuine
 * cross-process proof this repository already has for Android).
 *
 * ## What this does not prove
 *
 * KMP Android (an explicit KMP-aware Android target is confirmed blocked --
 * see `docs/android/kmp-android-target-blocker.md` -- so no KMP-Android-
 * specific instrumented test can exist yet; native Android's `jvm()`-variant
 * consumption, proven here, is the only Android consumption path that
 * exists), KMP iOS (a separate counterpart test exists for that platform --
 * see `IosReferenceConsumerRetryCircuitQualificationTest`), a physical
 * device (Gradle Managed Device AVD only, matching every other managed-
 * device reference-consumer test's own documented boundary), durable
 * retry-budget state (this test exercises the circuit-breaker store only,
 * mirroring the qualification checkpoints' own scope split), and the
 * queue-worker durable-replay path (`#101`'s own row already separately
 * names "retry, circuit-breaker ... behavior during queue replay" as its
 * own, distinct open item; this test proves the direct/protected-
 * synchronization path, not that one).
 *
 * ## A note on how this was verified
 *
 * This file compiles from a Windows development host
 * (`compileDebugAndroidTestKotlin`), but actually **running** it requires a
 * Gradle Managed Device, which itself requires KVM (Linux-only) -- this
 * repository's `android-validation.yml` CI job enables KVM on the
 * `ubuntu-latest` runner specifically for this purpose, the same setup
 * `AndroidReferenceConsumerInstrumentedTest` and every other managed-device
 * test in this repository already rely on. Local Windows verification is
 * limited to confirming this file compiles; the real pass/fail signal is
 * this PR/commit's own `pixel2Api35DebugAndroidTest` CI run.
 */
@RunWith(AndroidJUnit4::class)
class AndroidReferenceConsumerRetryCircuitQualificationInstrumentedTest {

    @Test
    fun acFunc004BackoffCircuitOpenRejectionProbeAndRecoverySurviveRealProviderFlow() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)

        val suffix = UUID.randomUUID().toString().take(8)
        val clock = MutableClock(1_000L)
        val failure = InjectedNetworkFailure()
        val transport = FaultInjectingPullTransportProvider(failure)
        val circuitBreakerConfiguration = CircuitBreakerConfiguration(
            failureThreshold = 2,
            failureWindow = SchedulingDelay(5_000L),
            openDuration = SchedulingDelay(1_000L),
            halfOpenProbeLeaseDuration = SchedulingDelay(500L),
        )
        val circuitDatabaseName = "ac-func-004-circuit-$suffix.db"
        val transportScope = CircuitBreakerScope.providerOperation(
            transport.descriptor.id,
            TransportCircuitOperation.PULL_CHANGES.retryOperation,
        )

        val primary = buildRetryCircuitDataLoom(
            context = context,
            clock = clock,
            transport = transport,
            circuitBreakerConfiguration = circuitBreakerConfiguration,
            circuitStore = RoomCircuitBreakerStateStore(
                DataLoomDatabaseBuilder.build(context, circuitDatabaseName),
            ),
            storageDatabaseName = "ac-func-004-storage-primary-$suffix.db",
            queueDatabaseName = "ac-func-004-queue-primary-$suffix.db",
        )
        assertEquals(ProviderLifecycleResult.InitializeSuccess, primary.initialize())

        val random = SequenceRetryRandomSource(40L, 75L)
        val retryOperation = TransportCircuitOperation.PULL_CHANGES.retryOperation
        val retryEvaluator = SynchronizationRetryEvaluator(
            retryPolicy = StandardRetryPolicy(
                id = RetryPolicyId("android-ac-func-004"),
                strategy = RetryBackoffStrategy.Exponential(
                    initialDelay = SchedulingDelay(100L),
                    multiplier = 2,
                    maximumDelay = SchedulingDelay(1_000L),
                ),
                maximumAttempts = 4,
                jitterStrategy = RetryJitterStrategy.Full,
                randomSource = random,
            ),
            clock = clock,
        )

        val request = SynchronizationRequest(
            workflowId = WorkflowId("android-ac-func-004-workflow-$suffix"),
            sessionId = SynchronizationSessionId("android-ac-func-004-session-$suffix"),
            direction = SynchronizationDirection.PULL,
            mode = SynchronizationMode.FULL,
            context = ExecutionContext(
                executionId = ExecutionId("android-ac-func-004-execution-$suffix"),
                correlationId = CorrelationId("android-ac-func-004-correlation-$suffix"),
            ),
        )

        // Attempt 1 @ t=1000 -- first recoverable failure, circuit stays closed.
        val first = assertIs<ProviderProtectedSynchronizationExecutionResult.Executed>(
            primary.protectedSynchronization!!.synchronize(request),
        )
        val firstFailed = assertIs<SynchronizationResult.Failed>(first.result.synchronizationResult)
        assertEquals(
            ProviderProtectionInvocation.CIRCUIT_FAILURE,
            pullEvidence(first.result, retryOperation).invocation,
        )
        assertEquals(1, transport.pullCalls)

        val firstRetry = assertIs<SynchronizationRetryEvaluation.ShouldRetry>(
            retryEvaluator.evaluate(
                result = firstFailed,
                retryAttempt = RetryAttempt(1),
                retryOperation = retryOperation,
            ),
        )
        assertEquals(SchedulingDelay(40L), firstRetry.selectedDelay)
        assertEquals(DataLoomInstant(1_040L), firstRetry.availableAt)

        // Attempt 2 @ t=1040 -- second failure opens the circuit durably.
        clock.nowMillis = firstRetry.availableAt.epochMilliseconds
        val second = assertIs<ProviderProtectedSynchronizationExecutionResult.Executed>(
            primary.protectedSynchronization!!.synchronize(request),
        )
        val secondFailed = assertIs<SynchronizationResult.Failed>(second.result.synchronizationResult)
        val secondEvidence = pullEvidence(second.result, retryOperation)
        assertEquals(ProviderProtectionInvocation.CIRCUIT_FAILURE, secondEvidence.invocation)
        val opened = assertIs<CircuitBreakerRecordResult.Recorded>(secondEvidence.recordResult)
        assertEquals(CircuitBreakerPhase.OPEN, opened.record.state.phase)
        assertEquals(DataLoomInstant(2_040L), opened.record.state.openUntil)
        assertEquals(2, transport.pullCalls)

        val secondRetry = assertIs<SynchronizationRetryEvaluation.ShouldRetry>(
            retryEvaluator.evaluate(
                result = secondFailed,
                retryAttempt = RetryAttempt(2),
                retryOperation = retryOperation,
            ),
        )
        assertEquals(SchedulingDelay(75L), secondRetry.selectedDelay)
        assertEquals(DataLoomInstant(1_115L), secondRetry.availableAt)
        assertEquals(listOf(100L, 200L), random.maximums)

        // Attempt 3 @ t=1115 -- still inside the open window: rejected
        // before the real transport is invoked at all.
        clock.nowMillis = secondRetry.availableAt.epochMilliseconds
        val third = assertIs<ProviderProtectedSynchronizationExecutionResult.Executed>(
            primary.protectedSynchronization!!.synchronize(request),
        )
        val thirdEvidence = pullEvidence(third.result, retryOperation)
        assertEquals(ProviderProtectionInvocation.NOT_EXECUTED, thirdEvidence.invocation)
        assertEquals(
            ProviderProtectionPreExecutionReason.CIRCUIT_REJECTED,
            thirdEvidence.preExecutionReason,
        )
        assertEquals(CircuitBreakerRejectionReason.OPEN, thirdEvidence.rejectionReason)
        assertEquals(DataLoomInstant(2_040L), thirdEvidence.retryAt)
        assertEquals(2, transport.pullCalls)

        // At the exact open deadline, a second, independently constructed
        // Room connection to the same on-disk circuit database contends for
        // the single half-open probe lease.
        clock.nowMillis = 2_040L
        val competing = buildRetryCircuitDataLoom(
            context = context,
            clock = clock,
            transport = transport,
            circuitBreakerConfiguration = circuitBreakerConfiguration,
            circuitStore = RoomCircuitBreakerStateStore(
                DataLoomDatabaseBuilder.build(context, circuitDatabaseName),
            ),
            storageDatabaseName = "ac-func-004-storage-competing-$suffix.db",
            queueDatabaseName = "ac-func-004-queue-competing-$suffix.db",
        )
        assertEquals(ProviderLifecycleResult.InitializeSuccess, competing.initialize())

        val probe = async { primary.protectedSynchronization!!.synchronize(request) }
        transport.probeStarted.await()

        val competingAttempt = assertIs<ProviderProtectedSynchronizationExecutionResult.Executed>(
            competing.protectedSynchronization!!.synchronize(request),
        )
        val competingEvidence = pullEvidence(competingAttempt.result, retryOperation)
        assertEquals(ProviderProtectionInvocation.NOT_EXECUTED, competingEvidence.invocation)
        assertEquals(
            CircuitBreakerRejectionReason.PROBE_IN_FLIGHT,
            competingEvidence.rejectionReason,
        )
        assertEquals(DataLoomInstant(2_540L), competingEvidence.retryAt)
        assertEquals(3, transport.pullCalls)

        transport.releaseProbe.complete(Unit)
        val probeResult = assertIs<ProviderProtectedSynchronizationExecutionResult.Executed>(
            probe.await(),
        )
        val probeEvidence = pullEvidence(probeResult.result, retryOperation)
        assertEquals(ProviderProtectionInvocation.SUCCEEDED, probeEvidence.invocation)
        val closed = assertIs<CircuitBreakerRecordResult.Recorded>(probeEvidence.recordResult)
        assertEquals(CircuitBreakerPhase.CLOSED, closed.record.state.phase)
        assertEquals(1L, closed.record.state.probeGeneration)
        assertIs<SynchronizationResult.Succeeded>(probeResult.result.synchronizationResult)

        // Normal operation recovers -- the next attempt, from the competing
        // runtime, executes and succeeds without any further rejection.
        clock.nowMillis += 1L
        val normal = assertIs<ProviderProtectedSynchronizationExecutionResult.Executed>(
            competing.protectedSynchronization!!.synchronize(request),
        )
        val normalEvidence = pullEvidence(normal.result, retryOperation)
        assertEquals(ProviderProtectionInvocation.SUCCEEDED, normalEvidence.invocation)
        assertIs<SynchronizationResult.Succeeded>(normal.result.synchronizationResult)
        assertEquals(4, transport.pullCalls)

        // A third, fresh Room connection reads back the exact recovered state.
        val verification = RoomCircuitBreakerStateStore(
            DataLoomDatabaseBuilder.build(context, circuitDatabaseName),
        )
        val persisted = assertIs<ProviderOperationResult.Success<CircuitBreakerLoadResult>>(
            verification.load(transportScope),
        )
        val found = assertIs<CircuitBreakerLoadResult.Found>(persisted.value)
        assertEquals(CircuitBreakerPhase.CLOSED, found.record.state.phase)
        assertEquals(1L, found.record.state.probeGeneration)

        assertEquals(ProviderLifecycleResult.ShutdownSuccess, primary.shutdown())
        assertEquals(ProviderLifecycleResult.ShutdownSuccess, competing.shutdown())

        context.deleteDatabase(circuitDatabaseName)
    }

    private fun pullEvidence(
        result: ProviderProtectedSynchronizationResult,
        retryOperation: io.dataloom.api.retry.RetryOperation,
    ): ProviderProtectionOperationEvidence =
        result.operationEvidence.single { it.operation == retryOperation }

    private fun buildRetryCircuitDataLoom(
        context: Context,
        clock: DataLoomClock,
        transport: TransportProvider,
        circuitBreakerConfiguration: CircuitBreakerConfiguration,
        circuitStore: RoomCircuitBreakerStateStore,
        storageDatabaseName: String,
        queueDatabaseName: String,
    ): DataLoom {
        val providers = androidDataLoomProviders(
            context = context,
            storageDatabaseName = storageDatabaseName,
            queueDatabaseName = queueDatabaseName,
        )

        return DataLoomBuilder()
            .runtimeDependencies(
                RuntimeDependencies(
                    clock = clock,
                    identifiers = referenceIdentifierGenerators(),
                ),
            )
            .installAndroidProviders(providers, transport)
            .providerProtectionConfiguration(
                DataLoomProviderProtectionSpec(
                    storage = DataLoomStorageProtectionSpec(
                        circuitBreakerConfiguration = circuitBreakerConfiguration,
                        circuitBreakerStateStore = circuitStore,
                        scopes = storageScopes(providers.storage.descriptor.id),
                    ),
                    transport = DataLoomTransportProtectionSpec(
                        circuitBreakerConfiguration = circuitBreakerConfiguration,
                        circuitBreakerStateStore = circuitStore,
                        scopes = transportScopes(transport.descriptor.id),
                    ),
                ),
            )
            .build()
    }

    private fun referenceIdentifierGenerators(): RuntimeIdentifierGenerators = RuntimeIdentifierGenerators(
        synchronizationEventIds = uuidGenerator(::SynchronizationEventId),
        queueEntryIds = uuidGenerator(::QueueEntryId),
        queueLeaseIds = uuidGenerator(::QueueLeaseId),
        conflictIds = uuidGenerator(::ConflictId),
    )

    private fun <T> uuidGenerator(construct: (String) -> T): IdentifierGenerator<T> =
        object : IdentifierGenerator<T> {
            override fun generate(): T = construct(UUID.randomUUID().toString())
        }

    private fun storageScopes(providerId: ProviderId): StorageCircuitScopes = StorageCircuitScopes(
        initialization = scope(providerId, StorageCircuitOperation.INITIALIZE.retryOperation),
        health = scope(providerId, StorageCircuitOperation.HEALTH.retryOperation),
        close = scope(providerId, StorageCircuitOperation.CLOSE.retryOperation),
        readOutboundChanges = scope(
            providerId,
            StorageCircuitOperation.READ_OUTBOUND_CHANGES.retryOperation,
        ),
        applyInboundChanges = scope(
            providerId,
            StorageCircuitOperation.APPLY_INBOUND_CHANGES.retryOperation,
        ),
        acknowledgeOutboundChanges = scope(
            providerId,
            StorageCircuitOperation.ACKNOWLEDGE_OUTBOUND_CHANGES.retryOperation,
        ),
        readCheckpoint = scope(providerId, StorageCircuitOperation.READ_CHECKPOINT.retryOperation),
        writeCheckpoint = scope(providerId, StorageCircuitOperation.WRITE_CHECKPOINT.retryOperation),
        readLocalConflictCandidate = scope(
            providerId,
            StorageCircuitOperation.READ_LOCAL_CONFLICT_CANDIDATE.retryOperation,
        ),
    )

    private fun transportScopes(providerId: ProviderId): TransportCircuitScopes = TransportCircuitScopes(
        initialization = scope(providerId, TransportCircuitOperation.INITIALIZE.retryOperation),
        health = scope(providerId, TransportCircuitOperation.HEALTH.retryOperation),
        close = scope(providerId, TransportCircuitOperation.CLOSE.retryOperation),
        pushChanges = scope(providerId, TransportCircuitOperation.PUSH_CHANGES.retryOperation),
        pullChanges = scope(providerId, TransportCircuitOperation.PULL_CHANGES.retryOperation),
    )

    private fun scope(
        providerId: ProviderId,
        operation: io.dataloom.api.retry.RetryOperation,
    ): CircuitBreakerScope = CircuitBreakerScope.providerOperation(providerId, operation)

    private class MutableClock(
        var nowMillis: Long,
    ) : DataLoomClock {
        override fun now(): DataLoomInstant = DataLoomInstant(nowMillis)
    }

    private class SequenceRetryRandomSource(
        vararg values: Long,
    ) : RetryRandomSource {
        private val remaining = values.toMutableList()
        val maximums = mutableListOf<Long>()

        override fun sample(request: RetryRandomRequest): Long {
            maximums += request.maximumInclusive
            return remaining.removeAt(0)
        }
    }

    private data class InjectedNetworkFailure(
        override val code: ErrorCode = ErrorCode("AC_FUNC_004_ANDROID_PROVIDER_FLOW_NETWORK_FAILURE"),
        override val category: ErrorCategory = ErrorCategory.NETWORK,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Sanitized injected transport failure for the AC-FUNC-004 provider-flow proof.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    /**
     * Test-only [TransportProvider] whose [pullChanges] fails the first two
     * calls (opening the circuit), blocks the third call until
     * [releaseProbe] completes (the half-open probe), and succeeds on every
     * subsequent call. [descriptor] is registered on two independent
     * [DataLoomBuilder] instances in this test so both real, protected
     * synchronization flows genuinely share one transport invocation count.
     */
    private class FaultInjectingPullTransportProvider(
        private val failure: DataLoomError,
    ) : TransportProvider {
        var pullCalls: Int = 0
            private set
        val probeStarted = CompletableDeferred<Unit>()
        val releaseProbe = CompletableDeferred<Unit>()

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("io.dataloom.consumer.android.test.ac-func-004-provider-flow-transport"),
            name = ProviderName("AC-FUNC-004 Provider-Flow Fault-Injecting Test Transport"),
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
            error("FaultInjectingPullTransportProvider does not support push.")

        override suspend fun pullChanges(
            request: PullChangesRequest,
        ): ProviderOperationResult<PullChangesResult> {
            pullCalls += 1
            return when (pullCalls) {
                1, 2 -> ProviderOperationResult.Failure(failure)
                3 -> {
                    probeStarted.complete(Unit)
                    releaseProbe.await()
                    ProviderOperationResult.Success(
                        PullChangesResult.Changes(changeSet = changeSet("probe"), hasMore = false),
                    )
                }
                else -> ProviderOperationResult.Success(
                    PullChangesResult.Changes(changeSet = changeSet("recovered"), hasMore = false),
                )
            }
        }

        private fun changeSet(label: String): ChangeSet = ChangeSet(
            id = ChangeSetId("ac-func-004-provider-flow-change-set-$label"),
            events = listOf(
                ChangeEvent(
                    id = ChangeEventId("ac-func-004-provider-flow-event-$label"),
                    entity = EntityReference(
                        type = EntityType("ac-func-004-provider-flow-entity"),
                        id = EntityId("ac-func-004-provider-flow-entity-$label"),
                    ),
                    operation = ChangeOperation.CREATE,
                ),
            ),
        )
    }
}
