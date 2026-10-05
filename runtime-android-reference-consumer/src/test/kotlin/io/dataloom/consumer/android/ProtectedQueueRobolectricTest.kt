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
import io.dataloom.api.identifier.ScheduleId
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
import io.dataloom.api.scheduling.ExistingSchedulePolicy
import io.dataloom.api.scheduling.ScheduleConstraints
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.time.SystemDataLoomClock
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.queue.room.DataLoomDatabaseBuilder
import io.dataloom.queue.room.RoomCircuitBreakerStateStore
import io.dataloom.runtime.facade.DataLoom
import io.dataloom.runtime.facade.DataLoomBuilder
import io.dataloom.runtime.facade.DataLoomProviderProtectionSpec
import io.dataloom.runtime.facade.DataLoomQueueWorkerSpec
import io.dataloom.runtime.facade.DataLoomStorageProtectionSpec
import io.dataloom.runtime.facade.DataLoomTransportProtectionSpec
import io.dataloom.runtime.queue.QueueProcessingRequest
import io.dataloom.runtime.queue.QueueProcessingResult
import io.dataloom.runtime.queue.QueuedSynchronizationWork
import io.dataloom.runtime.queue.QueuedSynchronizationWorkResolution
import io.dataloom.runtime.queue.QueuedSynchronizationWorkResolver
import io.dataloom.runtime.retry.CircuitBreakerConfiguration
import io.dataloom.runtime.retry.RetryBackoffStrategy
import io.dataloom.runtime.retry.StandardRetryPolicy
import io.dataloom.runtime.retry.StorageCircuitOperation
import io.dataloom.runtime.retry.StorageCircuitScopes
import io.dataloom.runtime.retry.TransportCircuitOperation
import io.dataloom.runtime.retry.TransportCircuitScopes
import io.dataloom.runtime.worker.QueueWorkerConfiguration
import io.dataloom.runtime.worker.QueueWorkerRunRequest
import io.dataloom.runtime.worker.QueueWorkerRunResult
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric proof, through a real [DataLoomBuilder] with
 * `providerProtectionConfiguration` plus `queueWorkerConfiguration` over the
 * real Room queue and the real Room circuit-breaker store, that queued replay
 * is circuit-protected: a failing transport opens the circuit, a later queued
 * entry is rejected by the gate before reaching the transport (it is
 * rescheduled, not failed), and after the circuit's open duration really
 * elapses (real wall clock, `runBlocking`, never virtual time) the probe
 * reaches the transport, the circuit closes on disk, and both entries
 * complete.
 *
 * Contrast `ComposedQueueCircuitRobolectricTest`, which had to hand-assemble
 * the protected handler and processor because the builder's queue worker did
 * not use the protected facade; this test uses only the builder.
 *
 * Not proven here: a real WorkManager tick, a process kill between attempts,
 * iOS, and an emulator run (Robolectric only).
 */
@RunWith(RobolectricTestRunner::class)
class ProtectedQueueRobolectricTest {

    // Short names on purpose (Windows MAX_PATH; see
    // AndroidReferenceConsumerDurableQueueRobolectricTest).
    @Test
    fun circuitGatesQueuedReplay() = runBlocking<Unit> {
        val context: Context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)

        val suffix = UUID.randomUUID().toString().take(8)
        val transport = FailTwiceTransport()
        val providers = androidDataLoomProviders(
            context = context,
            storageDatabaseName = "pq-storage-$suffix.db",
            queueDatabaseName = "pq-queue-$suffix.db",
        )
        val circuitDatabaseName = "pq-circuit-$suffix.db"
        val circuitDatabase = DataLoomDatabaseBuilder.build(context, circuitDatabaseName)
        val circuitStore = RoomCircuitBreakerStateStore(circuitDatabase)
        val circuitConfiguration = CircuitBreakerConfiguration(
            failureThreshold = 2,
            failureWindow = SchedulingDelay(600_000L),
            openDuration = SchedulingDelay(OPEN_MILLIS),
            halfOpenProbeLeaseDuration = SchedulingDelay(500L),
        )
        val bindings = SynchronizationProviderBindings(
            storageProviderId = providers.storage.descriptor.id,
            transportProviderId = transport.descriptor.id,
            schedulerProviderId = providers.scheduler.descriptor.id,
            connectivityProviderId = providers.connectivity.descriptor.id,
            queueProviderId = providers.queue.descriptor.id,
        )
        val transportScope = CircuitBreakerScope.providerOperation(
            transport.descriptor.id,
            TransportCircuitOperation.PULL_CHANGES.retryOperation,
        )

        val dataLoom = DataLoomBuilder()
            .runtimeDependencies(
                RuntimeDependencies(
                    clock = SystemDataLoomClock(),
                    identifiers = RuntimeIdentifierGenerators(
                        synchronizationEventIds = uuidGenerator(::SynchronizationEventId),
                        queueEntryIds = uuidGenerator(::QueueEntryId),
                        queueLeaseIds = uuidGenerator(::QueueLeaseId),
                        conflictIds = uuidGenerator(::ConflictId),
                    ),
                ),
            )
            .installAndroidProviders(providers, transport)
            .providerProtectionConfiguration(
                DataLoomProviderProtectionSpec(
                    storage = DataLoomStorageProtectionSpec(
                        circuitBreakerConfiguration = circuitConfiguration,
                        circuitBreakerStateStore = circuitStore,
                        scopes = storageScopes(providers.storage.descriptor.id),
                    ),
                    transport = DataLoomTransportProtectionSpec(
                        circuitBreakerConfiguration = circuitConfiguration,
                        circuitBreakerStateStore = circuitStore,
                        scopes = transportScopes(transport.descriptor.id),
                    ),
                ),
            )
            .queueWorkerConfiguration(
                DataLoomQueueWorkerSpec(
                    workResolver = QueuedSynchronizationWorkResolver { entry ->
                        QueuedSynchronizationWorkResolution.Resolved(
                            QueuedSynchronizationWork(entry.synchronizationRequest, bindings),
                        )
                    },
                    retryPolicy = StandardRetryPolicy(
                        id = RetryPolicyId("protected-queue-policy"),
                        strategy = RetryBackoffStrategy.Immediate,
                        maximumAttempts = 10,
                    ),
                    retryOperation = RetryOperation("protected-queue.pull"),
                    configuration = QueueWorkerConfiguration(
                        scheduleId = ScheduleId("protected-queue-worker"),
                        constraints = ScheduleConstraints(),
                        existingSchedulePolicy = ExistingSchedulePolicy.REPLACE,
                        continuationDelay = SchedulingDelay.ZERO,
                        recoverExpiredLeasesBeforeProcessing = false,
                    ),
                ),
            )
            .build()
        assertEquals(ProviderLifecycleResult.InitializeSuccess, dataLoom.initialize())

        enqueue(providers.queue, "pq-e1-$suffix")

        // Cycle 1: first failure, below the threshold: rescheduled, circuit CLOSED.
        val c1 = cycle(dataLoom, "c1")
        assertEquals(1, c1.rescheduled)
        assertEquals(1, transport.pullCalls)

        // Cycle 2: e1's second failure opens the circuit; the later entry e2 is
        // rejected by the gate before the transport (call count stays at 2).
        enqueue(providers.queue, "pq-e2-$suffix")
        val c2 = cycle(dataLoom, "c2")
        val openedAt = System.currentTimeMillis()
        assertEquals(2, c2.rescheduled)
        assertEquals(0, c2.failed)
        assertEquals(2, transport.pullCalls)
        assertCircuitPhase(context, circuitDatabaseName, transportScope, CircuitBreakerPhase.OPEN)

        // Cycle 3: still inside the open window, both rejected before the transport.
        val c3 = cycle(dataLoom, "c3")
        assertTrue(System.currentTimeMillis() < openedAt + OPEN_MILLIS, "cycle 3 must be inside the open window")
        assertEquals(2, c3.rescheduled)
        assertEquals(2, transport.pullCalls)

        // Cycle 4: after the real open duration the probe succeeds and both complete.
        delay(OPEN_MILLIS + 250L)
        val c4 = cycle(dataLoom, "c4")
        assertEquals(2, c4.completed)
        assertEquals(4, transport.pullCalls)
        assertCircuitPhase(context, circuitDatabaseName, transportScope, CircuitBreakerPhase.CLOSED)

        assertEquals(ProviderLifecycleResult.ShutdownSuccess, dataLoom.shutdown())
        circuitDatabase.close()
        context.deleteDatabase(circuitDatabaseName)
    }

    private data class Cycle(val completed: Int, val rescheduled: Int, val failed: Int)

    private suspend fun cycle(dataLoom: DataLoom, tag: String): Cycle {
        val acquiredAt = System.currentTimeMillis()
        val run = assertIs<QueueWorkerRunResult.ProcessingCompleted>(
            checkNotNull(dataLoom.queueWorker).run(
                QueueWorkerRunRequest(
                    processingRequest = QueueProcessingRequest(
                        acquireRequest = QueueAcquireRequest(
                            consumerId = QueueConsumerId("protected-queue-consumer"),
                            leaseId = QueueLeaseId("protected-queue-lease-$tag"),
                            acquiredAt = DataLoomInstant(acquiredAt),
                            leaseExpiresAt = DataLoomInstant(acquiredAt + 60_000L),
                            maxEntries = 10,
                        ),
                    ),
                    recoveryRequest = null,
                ),
            ),
        )
        val processed = assertIs<QueueProcessingResult.Processed>(run.processingResult)
        return Cycle(processed.summary.completed, processed.summary.rescheduled, processed.summary.failed)
    }

    private suspend fun enqueue(queue: io.dataloom.api.queue.QueueProvider, id: String) {
        val now = DataLoomInstant(System.currentTimeMillis())
        val result = queue.enqueue(
            QueueEnqueueRequest(
                entry = QueueEntry(
                    id = QueueEntryId(id),
                    synchronizationRequest = SynchronizationRequest(
                        workflowId = WorkflowId("wf-$id"),
                        sessionId = SynchronizationSessionId("session-$id"),
                        direction = SynchronizationDirection.PULL,
                        mode = SynchronizationMode.FULL,
                        context = ExecutionContext(
                            executionId = ExecutionId("execution-$id"),
                            correlationId = CorrelationId("correlation-$id"),
                        ),
                    ),
                    state = QueueEntryState.PENDING,
                    enqueuedAt = now,
                    availableAt = now,
                ),
            ),
        )
        check(result is ProviderOperationResult.Success<Unit>) { "Enqueue failed: $result" }
    }

    private suspend fun assertCircuitPhase(
        context: Context,
        databaseName: String,
        scope: CircuitBreakerScope,
        expected: CircuitBreakerPhase,
    ) {
        val fresh = DataLoomDatabaseBuilder.build(context, databaseName)
        try {
            val loaded = assertIs<ProviderOperationResult.Success<CircuitBreakerLoadResult>>(
                RoomCircuitBreakerStateStore(fresh).load(scope),
            )
            val found = assertIs<CircuitBreakerLoadResult.Found>(loaded.value)
            assertEquals(expected, found.record.state.phase)
        } finally {
            fresh.close()
        }
    }

    private fun <T> uuidGenerator(construct: (String) -> T): IdentifierGenerator<T> =
        object : IdentifierGenerator<T> {
            override fun generate(): T = construct(UUID.randomUUID().toString())
        }

    private fun storageScopes(providerId: ProviderId): StorageCircuitScopes {
        fun scope(operation: StorageCircuitOperation) =
            CircuitBreakerScope.providerOperation(providerId, operation.retryOperation)
        return StorageCircuitScopes(
            initialization = scope(StorageCircuitOperation.INITIALIZE),
            health = scope(StorageCircuitOperation.HEALTH),
            close = scope(StorageCircuitOperation.CLOSE),
            readOutboundChanges = scope(StorageCircuitOperation.READ_OUTBOUND_CHANGES),
            applyInboundChanges = scope(StorageCircuitOperation.APPLY_INBOUND_CHANGES),
            acknowledgeOutboundChanges = scope(StorageCircuitOperation.ACKNOWLEDGE_OUTBOUND_CHANGES),
            readCheckpoint = scope(StorageCircuitOperation.READ_CHECKPOINT),
            writeCheckpoint = scope(StorageCircuitOperation.WRITE_CHECKPOINT),
            readLocalConflictCandidate = scope(StorageCircuitOperation.READ_LOCAL_CONFLICT_CANDIDATE),
        )
    }

    private fun transportScopes(providerId: ProviderId): TransportCircuitScopes {
        fun scope(operation: TransportCircuitOperation) =
            CircuitBreakerScope.providerOperation(providerId, operation.retryOperation)
        return TransportCircuitScopes(
            initialization = scope(TransportCircuitOperation.INITIALIZE),
            health = scope(TransportCircuitOperation.HEALTH),
            close = scope(TransportCircuitOperation.CLOSE),
            pushChanges = scope(TransportCircuitOperation.PUSH_CHANGES),
            pullChanges = scope(TransportCircuitOperation.PULL_CHANGES),
        )
    }

    private data class InjectedNetworkFailure(
        override val code: ErrorCode = ErrorCode("PROTECTED_QUEUE_NETWORK_FAILURE"),
        override val category: ErrorCategory = ErrorCategory.NETWORK,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Sanitized injected transport failure for the protected queue test.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    /** Test-only transport: its first two pulls fail, every later pull succeeds. */
    private class FailTwiceTransport : TransportProvider {
        var pullCalls: Int = 0
            private set

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("io.dataloom.consumer.android.test.protected-queue-transport"),
            name = ProviderName("Protected Queue Fail-Twice Test Transport"),
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
            error("FailTwiceTransport does not support push.")

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
                            id = ChangeSetId("protected-queue-change-set-$pullCalls"),
                            events = listOf(
                                ChangeEvent(
                                    id = ChangeEventId("protected-queue-event-$pullCalls"),
                                    entity = EntityReference(
                                        type = EntityType("protected-queue-entity"),
                                        id = EntityId("protected-queue-entity-$pullCalls"),
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

    private companion object {
        const val OPEN_MILLIS = 1_500L
    }
}
