package io.dataloom.consumer.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
import io.dataloom.api.identifier.QueueConsumerId
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
import io.dataloom.api.provider.SynchronizationProviderBindings
import io.dataloom.api.queue.QueueAcquireRequest
import io.dataloom.api.queue.QueueEnqueueRequest
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.runtime.RuntimeIdentifierGenerators
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.queue.room.DataLoomDatabaseBuilder
import io.dataloom.queue.room.RoomCircuitBreakerStateStore
import io.dataloom.runtime.facade.DataLoomBuilder
import io.dataloom.runtime.facade.DataLoomProviderProtectionSpec
import io.dataloom.runtime.facade.DataLoomStorageProtectionSpec
import io.dataloom.runtime.facade.DataLoomTransportProtectionSpec
import io.dataloom.runtime.queue.DurableQueueExecutionProcessor
import io.dataloom.runtime.queue.ProviderProtectedQueuedSynchronizationExecutionHandler
import io.dataloom.runtime.queue.QueueEntryExecutionHandler
import io.dataloom.runtime.queue.QueueProcessingRequest
import io.dataloom.runtime.queue.QueueProcessingResult
import io.dataloom.runtime.queue.QueuedSynchronizationWork
import io.dataloom.runtime.queue.QueuedSynchronizationWorkResolution
import io.dataloom.runtime.queue.QueuedSynchronizationWorkResolver
import io.dataloom.runtime.retry.CircuitBreakerConfiguration
import io.dataloom.runtime.retry.RetryBackoffStrategy
import io.dataloom.runtime.retry.RetryBudgetConfiguration
import io.dataloom.runtime.retry.StandardRetryPolicy
import io.dataloom.runtime.retry.StorageCircuitOperation
import io.dataloom.runtime.retry.StorageCircuitScopes
import io.dataloom.runtime.retry.SynchronizationRetryEvaluator
import io.dataloom.runtime.retry.TransportCircuitOperation
import io.dataloom.runtime.retry.TransportCircuitScopes
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric-backed runtime proof that the composed queue-worker -> retry-
 * reschedule -> circuit-breaker loop genuinely works over the real, on-disk
 * Android Room queue and circuit-breaker stores -- the gap
 * `docs/status/dl-040-qualification-matrix.md` section 4.3 row C2 names as
 * UNPROVEN: "both provider-flow tests
 * ([AndroidReferenceConsumerRetryCircuitQualificationInstrumentedTest],
 * [io.dataloom.consumer.ios.IosReferenceConsumerRetryCircuitQualificationTest])
 * call the evaluator directly" instead of driving it through a real acquire
 * -> execute -> reschedule -> acquire cycle.
 *
 * ## Why this needed its own assembly, not [DataLoomBuilder.queueWorkerConfiguration]
 *
 * `DataLoomBuilder.queueWorkerConfiguration`/`circuitQueueWorkerConfiguration`
 * both hard-wire `QueuedSynchronizationExecutionHandler`, which executes
 * queued work through the *unprotected* `SynchronizationExecutionCoordinator`
 * -- `providerProtectionConfiguration`'s circuit-breaker/timeout wrapping
 * (`TransportCircuitProtectionRuntime`) is applied only to the separate
 * `DataLoom.protectedSynchronization` facade used by direct calls (confirmed
 * by reading `DataLoomBuilder.kt`'s `buildQueueWorker`/`buildCircuitQueueWorker`:
 * both receive the same plain `executionCoordinator` built in step 8, never
 * `protectedSynchronization`). The one production component that *does* run
 * queued work through the circuit-protected facade,
 * [ProviderProtectedQueuedSynchronizationExecutionHandler], exists and is
 * unit-tested with fakes
 * (`ProviderProtectedQueuedSynchronizationExecutionHandlerTest`) but is not
 * yet wired into any `DataLoomBuilder` queue-worker path -- see
 * `docs/audits/DL-040-provider-protected-queued-execution-checkpoint.md`,
 * "Remaining DL-040 work": "circuit-aware queue processor and worker adoption
 * of per-entry provider evidence".
 *
 * This test assembles that exact missing composition itself, entirely from
 * real, non-test production classes:
 * [DataLoom.protectedSynchronization] (real `CircuitBreakerExecutionGate`
 * over a real [RoomCircuitBreakerStateStore]), a real
 * [ProviderProtectedQueuedSynchronizationExecutionHandler], and the real
 * [DurableQueueExecutionProcessor] driving the real
 * `io.dataloom.queue.room.RoomQueueProvider` acquire/reschedule/complete
 * cycle. No production code is changed; this proves the pieces genuinely
 * compose today, using a one-line adapter
 * (`QueueEntryExecutionHandler { entry -> protectedHandler.execute(entry).outcome }`)
 * that only bridges the two already-identical
 * [io.dataloom.runtime.queue.QueueEntryExecutionOutcome] result shapes.
 *
 * ## Scenario
 *
 * A deterministic [MutableClock] and a transport that fails its first two
 * `pullChanges` calls then always succeeds drive five queue-worker cycles
 * (`failureThreshold = 2`, `openDuration = 1_000ms`, exponential backoff
 * `100ms` doubling to a `1_000ms` cap, no jitter):
 *
 * 1. `t=1000`: first genuine transport failure. Below threshold, circuit
 *    stays CLOSED. The real [SynchronizationRetryEvaluator] reschedules with
 *    a 100ms backoff.
 * 2. `t=1100`: second genuine transport failure trips the real circuit
 *    breaker (recorded in the real Room `circuit_breaker_states` table).
 *    Rescheduled again (200ms backoff).
 * 3. `t=1300`, `t=1700`: still inside the circuit's open window
 *    (`openUntil = 2100`). The real gate rejects each attempt *before* the
 *    transport is invoked again (call count stays at two); the rejection
 *    still flows through the real retry evaluator, so the entry keeps being
 *    genuinely rescheduled (400ms, then 800ms backoff) while the breaker
 *    stays open.
 * 4. `t=2500`: past the open deadline. The real gate grants the half-open
 *    probe, the transport succeeds for the third genuine time, the circuit
 *    closes, and the entry completes.
 *
 * A fresh, independent [RoomCircuitBreakerStateStore] connection confirms the
 * circuit genuinely closed on disk, not just in the coordinator's return
 * value used to drive the next cycle.
 *
 * ## What this does not prove
 *
 * KMP Android and KMP iOS (no KMP-shaped consumer exists yet -- see the
 * qualification matrix's own `†` note); a real OS process kill/relaunch of
 * the composed loop (a separate, already-covered proof --
 * `AndroidProcessTerminationRetryBudgetInstrumentedTest`/
 * `AndroidProcessTerminationCircuitBreakerInstrumentedTest`); cross-process
 * queue-lease contention; half-open probe lease contention (a separate,
 * already-covered proof, `AndroidCircuitBreakerProbeContentionInstrumentedTest`);
 * and a real Gradle Managed Device emulator run (Robolectric simulates the
 * Android framework on the JVM -- a real and useful signal, but not
 * identical to on-device timing).
 */
@RunWith(RobolectricTestRunner::class)
class ComposedQueueCircuitRobolectricTest {

    // Short class/method/database names are deliberate: Robolectric's
    // per-test temp directory embeds the test class and method name, and
    // this module's Windows local/CI runs otherwise hit a genuine
    // "file doesn't exist, check directory permissions" SQLite open failure
    // that is a local path-length (MAX_PATH) artifact, not a product bug --
    // the same boundary AndroidReferenceConsumerDurableQueueRobolectricTest's
    // own KDoc documents for the identical reason.
    @Test
    fun retryReschedulesThenCircuitRecovers() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)

        val suffix = UUID.randomUUID().toString().take(8)
        val clock = MutableClock(1_000L)
        val transport = FailTwiceThenSucceedTransportProvider()

        val providers = androidDataLoomProviders(
            context = context,
            storageDatabaseName = "cq-storage-$suffix.db",
            queueDatabaseName = "cq-queue-$suffix.db",
        )
        val circuitDatabaseName = "cq-circuit-$suffix.db"
        val circuitDatabase = DataLoomDatabaseBuilder.build(context, circuitDatabaseName)
        val circuitStore = RoomCircuitBreakerStateStore(circuitDatabase)
        val circuitBreakerConfiguration = CircuitBreakerConfiguration(
            failureThreshold = 2,
            failureWindow = SchedulingDelay(5_000L),
            openDuration = SchedulingDelay(1_000L),
            halfOpenProbeLeaseDuration = SchedulingDelay(500L),
        )
        val transportScope = CircuitBreakerScope.providerOperation(
            transport.descriptor.id,
            TransportCircuitOperation.PULL_CHANGES.retryOperation,
        )

        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(
                RuntimeDependencies(clock = clock, identifiers = referenceIdentifierGenerators()),
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
        assertEquals(ProviderLifecycleResult.InitializeSuccess, dataLoom.initialize())

        val bindings = SynchronizationProviderBindings(
            storageProviderId = providers.storage.descriptor.id,
            transportProviderId = transport.descriptor.id,
            schedulerProviderId = providers.scheduler.descriptor.id,
            connectivityProviderId = providers.connectivity.descriptor.id,
            queueProviderId = providers.queue.descriptor.id,
        )
        val retryOperation = RetryOperation("composed-queue-circuit.pull")
        val request = SynchronizationRequest(
            workflowId = WorkflowId("composed-queue-circuit-workflow-$suffix"),
            sessionId = SynchronizationSessionId("composed-queue-circuit-session-$suffix"),
            direction = SynchronizationDirection.PULL,
            mode = SynchronizationMode.FULL,
            context = ExecutionContext(
                executionId = ExecutionId("composed-queue-circuit-execution-$suffix"),
                correlationId = CorrelationId("composed-queue-circuit-correlation-$suffix"),
            ),
        )

        val enqueued = providers.queue.enqueue(
            QueueEnqueueRequest(
                entry = QueueEntry(
                    id = QueueEntryId("composed-queue-circuit-entry-$suffix"),
                    synchronizationRequest = request,
                    state = QueueEntryState.PENDING,
                    enqueuedAt = clock.now(),
                    availableAt = clock.now(),
                ),
            ),
        )
        check(enqueued is ProviderOperationResult.Success<Unit>) {
            "Enqueue failed: ${(enqueued as? ProviderOperationResult.Failure)?.error}"
        }

        val retryEvaluator = SynchronizationRetryEvaluator(
            retryPolicy = StandardRetryPolicy(
                id = RetryPolicyId("composed-queue-circuit-retry-policy"),
                strategy = RetryBackoffStrategy.Exponential(
                    initialDelay = SchedulingDelay(100L),
                    multiplier = 2,
                    maximumDelay = SchedulingDelay(1_000L),
                ),
                maximumAttempts = 5,
            ),
            clock = clock,
            budgetConfiguration = RetryBudgetConfiguration(
                maximumCumulativeDelay = SchedulingDelay(60_000L),
            ),
        )
        val resolver = QueuedSynchronizationWorkResolver { entry ->
            QueuedSynchronizationWorkResolution.Resolved(
                QueuedSynchronizationWork(request = entry.synchronizationRequest, bindings = bindings),
            )
        }
        // The exact missing composition (see class KDoc): a real, protected
        // per-entry execution handler feeding the real durable queue processor.
        val protectedHandler = ProviderProtectedQueuedSynchronizationExecutionHandler(
            workResolver = resolver,
            protectedSynchronization = requireNotNull(dataLoom.protectedSynchronization),
            retryEvaluator = retryEvaluator,
            retryOperation = retryOperation,
        )
        val processor = DurableQueueExecutionProcessor(
            queueProvider = providers.queue,
            executionHandler = QueueEntryExecutionHandler { entry -> protectedHandler.execute(entry).outcome },
        )

        suspend fun runCycle(acquiredAtMillis: Long): QueueProcessingResult.Processed {
            val result = processor.process(
                QueueProcessingRequest(
                    acquireRequest = QueueAcquireRequest(
                        consumerId = QueueConsumerId("composed-queue-circuit-consumer"),
                        leaseId = QueueLeaseId("composed-queue-circuit-lease-$acquiredAtMillis"),
                        acquiredAt = DataLoomInstant(acquiredAtMillis),
                        leaseExpiresAt = DataLoomInstant(acquiredAtMillis + 60_000L),
                        maxEntries = 10,
                    ),
                ),
            )
            return assertIs<QueueProcessingResult.Processed>(result)
        }

        // Cycle 1 @ t=1000: first genuine transport failure. Below the
        // failure threshold, so the real circuit stays CLOSED. Rescheduled
        // with the real evaluator's exponential backoff (attempt 1 => 100ms).
        clock.nowMillis = 1_000L
        val cycle1 = runCycle(clock.nowMillis)
        assertEquals(1, cycle1.summary.rescheduled)
        assertEquals(1, transport.pullCalls)
        assertEquals(DataLoomInstant(1_100L), cycle1.earliestRescheduledAt)

        // Cycle 2 @ t=1100: second genuine transport failure trips the real
        // circuit breaker, persisted to the real Room circuit-breaker store.
        // Rescheduled again (attempt 2 => 200ms).
        clock.nowMillis = 1_100L
        val cycle2 = runCycle(clock.nowMillis)
        assertEquals(1, cycle2.summary.rescheduled)
        assertEquals(2, transport.pullCalls)
        assertEquals(DataLoomInstant(1_300L), cycle2.earliestRescheduledAt)
        assertCircuitPhase(context, circuitDatabaseName, transportScope, CircuitBreakerPhase.OPEN)

        // Cycle 3 @ t=1300: still inside the circuit's open window
        // (openUntil = 2100). The real gate rejects before the transport is
        // ever invoked again; the rejection still flows through the real
        // retry evaluator (attempt 3 => 400ms), so the entry keeps being
        // genuinely rescheduled while the breaker stays open.
        clock.nowMillis = 1_300L
        val cycle3 = runCycle(clock.nowMillis)
        assertEquals(1, cycle3.summary.rescheduled)
        assertEquals(2, transport.pullCalls)
        assertEquals(DataLoomInstant(1_700L), cycle3.earliestRescheduledAt)

        // Cycle 4 @ t=1700: still open. Rejected again (attempt 4 => 800ms),
        // whose resulting availability (2500) lands past the open deadline --
        // exactly the "recovers once the breaker's window passes and backoff
        // is exhausted" moment this loop must produce on its own.
        clock.nowMillis = 1_700L
        val cycle4 = runCycle(clock.nowMillis)
        assertEquals(1, cycle4.summary.rescheduled)
        assertEquals(2, transport.pullCalls)
        assertEquals(DataLoomInstant(2_500L), cycle4.earliestRescheduledAt)

        // Cycle 5 @ t=2500: past the circuit's open deadline. The real gate
        // grants the half-open probe, the transport succeeds for the third
        // genuine time, the circuit closes, and the entry completes.
        clock.nowMillis = 2_500L
        val cycle5 = runCycle(clock.nowMillis)
        assertEquals(1, cycle5.summary.completed)
        assertEquals(3, transport.pullCalls)

        // Independent verification: a fresh Room connection confirms the
        // circuit genuinely closed on disk.
        assertCircuitPhase(context, circuitDatabaseName, transportScope, CircuitBreakerPhase.CLOSED)

        assertEquals(ProviderLifecycleResult.ShutdownSuccess, dataLoom.shutdown())
        circuitDatabase.close()
        context.deleteDatabase(circuitDatabaseName)
    }

    private suspend fun assertCircuitPhase(
        context: Context,
        circuitDatabaseName: String,
        scope: CircuitBreakerScope,
        expected: CircuitBreakerPhase,
    ) {
        val freshDatabase = DataLoomDatabaseBuilder.build(context, circuitDatabaseName)
        try {
            val loaded = assertIs<ProviderOperationResult.Success<CircuitBreakerLoadResult>>(
                RoomCircuitBreakerStateStore(freshDatabase).load(scope),
            )
            val found = assertIs<CircuitBreakerLoadResult.Found>(loaded.value)
            assertEquals(expected, found.record.state.phase)
        } finally {
            freshDatabase.close()
        }
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

    private data class InjectedNetworkFailure(
        override val code: ErrorCode = ErrorCode("COMPOSED_QUEUE_CIRCUIT_NETWORK_FAILURE"),
        override val category: ErrorCategory = ErrorCategory.NETWORK,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Sanitized injected transport failure for the composed queue/circuit test.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    /**
     * Test-only [TransportProvider] whose [pullChanges] fails its first two
     * calls (opening the real circuit) and succeeds on every call after
     * that (the eventual half-open probe and recovery).
     */
    private class FailTwiceThenSucceedTransportProvider : TransportProvider {
        var pullCalls: Int = 0
            private set

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("io.dataloom.consumer.android.test.composed-queue-circuit-transport"),
            name = ProviderName("Composed Queue/Circuit Fail-Twice-Then-Succeed Test Transport"),
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
            error("FailTwiceThenSucceedTransportProvider does not support push.")

        override suspend fun pullChanges(
            request: PullChangesRequest,
        ): ProviderOperationResult<PullChangesResult> {
            pullCalls += 1
            return if (pullCalls <= 2) {
                ProviderOperationResult.Failure(InjectedNetworkFailure())
            } else {
                ProviderOperationResult.Success(
                    PullChangesResult.Changes(
                        changeSet = ChangeSet(
                            id = ChangeSetId("composed-queue-circuit-change-set-$pullCalls"),
                            events = listOf(
                                ChangeEvent(
                                    id = ChangeEventId("composed-queue-circuit-event-$pullCalls"),
                                    entity = EntityReference(
                                        type = EntityType("composed-queue-circuit-entity"),
                                        id = EntityId("composed-queue-circuit-entity-$pullCalls"),
                                    ),
                                    operation = ChangeOperation.CREATE,
                                ),
                            ),
                        ),
                        hasMore = false,
                    ),
                )
            }
        }
    }
}
