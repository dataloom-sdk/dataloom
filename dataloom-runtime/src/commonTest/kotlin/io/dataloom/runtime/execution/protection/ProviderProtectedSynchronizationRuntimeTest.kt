package io.dataloom.runtime.execution.protection

import io.dataloom.api.circuit.CircuitBreakerCompareAndSetRequest
import io.dataloom.api.circuit.CircuitBreakerCompareAndSetResult
import io.dataloom.api.circuit.CircuitBreakerLoadResult
import io.dataloom.api.circuit.CircuitBreakerPhase
import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.circuit.CircuitBreakerState
import io.dataloom.api.circuit.CircuitBreakerStateRecord
import io.dataloom.api.circuit.CircuitBreakerStateStore
import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.EntityReference
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.execution.SynchronizationProviderSet
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.IdentifierGenerator
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
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
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.runtime.RuntimeIdentifierGenerators
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.storage.InboundChangeApplyRequest
import io.dataloom.api.storage.LocalConflictCandidateReadRequest
import io.dataloom.api.storage.LocalConflictCandidateReadResult
import io.dataloom.api.storage.OutboundChangeReadRequest
import io.dataloom.api.storage.OutboundChangeReadResult
import io.dataloom.api.storage.StorageProvider
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.synchronization.CheckpointReadRequest
import io.dataloom.api.synchronization.CheckpointWriteRequest
import io.dataloom.api.synchronization.OutboundChangeAcknowledgementRequest
import io.dataloom.api.synchronization.SynchronizationCheckpoint
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.synchronization.SynchronizationSummary
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.runtime.execution.SynchronizationExecutionContext
import io.dataloom.runtime.execution.SynchronizationPipeline
import io.dataloom.runtime.retry.CircuitBreakerConfiguration
import io.dataloom.runtime.retry.CircuitBreakerRecordResult
import io.dataloom.runtime.retry.StorageCircuitOperation
import io.dataloom.runtime.retry.StorageCircuitProtectionRuntime
import io.dataloom.runtime.retry.StorageCircuitScopes
import io.dataloom.runtime.retry.TransportCircuitOperation
import io.dataloom.runtime.retry.TransportCircuitProtectionRuntime
import io.dataloom.runtime.retry.TransportCircuitScopes
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ProviderProtectedSynchronizationRuntimeTest {

    @Test
    fun `existing pipeline executes through protected providers and returns ordered evidence`() =
        runTest {
            val storage = RecordingStorageProvider()
            val transport = RecordingTransportProvider()
            val storageStore = RecordingCircuitStore()
            val transportStore = RecordingCircuitStore()
            val context = executionContext(storage, transport)

            val protected = ProviderProtectedSynchronizationRuntime.execute(
                context = context,
                pipeline = HealthCheckingPipeline(),
                storageOperations = protectedStorage(storage, storageStore),
                transportOperations = protectedTransport(transport, transportStore),
            )

            assertIs<SynchronizationResult.Succeeded>(protected.synchronizationResult)
            assertEquals(2, protected.operationEvidence.size)
            assertEquals("storage.health", protected.operationEvidence[0].operation.value)
            assertEquals("transport.health", protected.operationEvidence[1].operation.value)
            assertTrue(protected.operationEvidence.all { it.providerExecuted })
            assertTrue(protected.operationEvidence.all { it.providerSucceeded })
            assertTrue(protected.operationEvidence.all { it.circuitRecordingAccepted })
            assertEquals(1, storage.healthCalls)
            assertEquals(1, transport.healthCalls)
        }

    @Test
    fun `open storage circuit stops pipeline before provider invocation`() = runTest {
        val storage = RecordingStorageProvider()
        val transport = RecordingTransportProvider()
        val storageHealthScope = storageScopes(storage.descriptor.id).health
        val storageStore = RecordingCircuitStore(
            initialRecords = mapOf(storageHealthScope to openRecord(storageHealthScope)),
        )

        val protected = ProviderProtectedSynchronizationRuntime.execute(
            context = executionContext(storage, transport),
            pipeline = HealthCheckingPipeline(),
            storageOperations = protectedStorage(storage, storageStore),
            transportOperations = protectedTransport(transport, RecordingCircuitStore()),
        )

        val failed = assertIs<SynchronizationResult.Failed>(protected.synchronizationResult)
        assertEquals("PROVIDER_CIRCUIT_OPEN", failed.error.code.value)
        val evidence = protected.operationEvidence.single()
        assertEquals(ProviderProtectionInvocation.NOT_EXECUTED, evidence.invocation)
        assertEquals(
            ProviderProtectionPreExecutionReason.CIRCUIT_REJECTED,
            evidence.preExecutionReason,
        )
        assertFalse(evidence.providerExecuted)
        assertEquals(0, storage.healthCalls)
        assertEquals(0, transport.healthCalls)
    }

    @Test
    fun `provider success with failed later circuit write is fail closed and remains visible`() =
        runTest {
            val storage = RecordingStorageProvider()
            val transport = RecordingTransportProvider()
            val storageHealthScope = storageScopes(storage.descriptor.id).health
            val storeError = error("CIRCUIT_STORE_WRITE_FAILED", ErrorCategory.STORAGE)
            val storageStore = RecordingCircuitStore(
                initialRecords = mapOf(
                    storageHealthScope to closedFailureRecord(storageHealthScope),
                ),
                compareError = storeError,
            )

            val protected = ProviderProtectedSynchronizationRuntime.execute(
                context = executionContext(storage, transport),
                pipeline = HealthCheckingPipeline(),
                storageOperations = protectedStorage(storage, storageStore),
                transportOperations = protectedTransport(transport, RecordingCircuitStore()),
            )

            val failed = assertIs<SynchronizationResult.Failed>(protected.synchronizationResult)
            assertEquals(
                "PROVIDER_CIRCUIT_RECORDING_UNCONFIRMED",
                failed.error.code.value,
            )
            assertEquals(Recoverability.UNKNOWN, failed.error.recoverability)
            val evidence = protected.operationEvidence.single()
            assertTrue(evidence.providerExecuted)
            assertTrue(evidence.providerSucceeded)
            assertIs<CircuitBreakerRecordResult.PersistenceFailure>(evidence.recordResult)
            assertFalse(evidence.circuitRecordingAccepted)
            assertEquals(1, storage.healthCalls)
            assertEquals(0, transport.healthCalls)
        }

    @Test
    fun `canonical provider failure is preserved when circuit recording is accepted`() = runTest {
        val providerError = error(
            code = "STORAGE_HEALTH_UNAVAILABLE",
            category = ErrorCategory.STORAGE,
            recoverability = Recoverability.RECOVERABLE,
        )
        val storage = RecordingStorageProvider(
            healthResult = ProviderOperationResult.Failure(providerError),
        )
        val transport = RecordingTransportProvider()

        val protected = ProviderProtectedSynchronizationRuntime.execute(
            context = executionContext(storage, transport),
            pipeline = HealthCheckingPipeline(),
            storageOperations = protectedStorage(storage, RecordingCircuitStore()),
            transportOperations = protectedTransport(transport, RecordingCircuitStore()),
        )

        val failed = assertIs<SynchronizationResult.Failed>(protected.synchronizationResult)
        assertSame(providerError, failed.error)
        val evidence = protected.operationEvidence.single()
        assertEquals(ProviderProtectionInvocation.CIRCUIT_FAILURE, evidence.invocation)
        assertSame(providerError, evidence.error)
        assertTrue(evidence.providerExecuted)
        assertTrue(evidence.circuitRecordingAccepted)
        assertEquals(0, transport.healthCalls)
    }

    @Test
    fun `protected provider mismatch fails before pipeline store provider or clock access`() =
        runTest {
            val contextStorage = RecordingStorageProvider(
                providerId = ProviderId("context-storage"),
            )
            val protectedStorage = RecordingStorageProvider(
                providerId = ProviderId("different-storage"),
            )
            val transport = RecordingTransportProvider()
            val storageStore = RecordingCircuitStore()
            val transportStore = RecordingCircuitStore()
            val clock = CountingClock(now)
            val pipeline = HealthCheckingPipeline()

            assertFailsWith<IllegalArgumentException> {
                ProviderProtectedSynchronizationRuntime.execute(
                    context = executionContext(contextStorage, transport, clock),
                    pipeline = pipeline,
                    storageOperations = StorageCircuitProtectionRuntime.create(
                        storageProvider = protectedStorage,
                        clock = clock,
                        circuitBreakerConfiguration = configuration(),
                        circuitBreakerStateStore = storageStore,
                        scopes = storageScopes(protectedStorage.descriptor.id),
                    ),
                    transportOperations = TransportCircuitProtectionRuntime.create(
                        transportProvider = transport,
                        clock = clock,
                        circuitBreakerConfiguration = configuration(),
                        circuitBreakerStateStore = transportStore,
                        scopes = transportScopes(transport.descriptor.id),
                    ),
                )
            }

            assertEquals(0, pipeline.executeCalls)
            assertEquals(0, storageStore.loadCalls)
            assertEquals(0, transportStore.loadCalls)
            assertEquals(0, protectedStorage.healthCalls)
            assertEquals(0, transport.healthCalls)
            assertEquals(0, clock.readCalls)
        }

    @Test
    fun `caller cancellation propagates without being converted into pipeline evidence`() = runTest {
        val storage = RecordingStorageProvider(cancelHealth = true)
        val transport = RecordingTransportProvider()

        val cancellation = assertFailsWith<CancellationException> {
            ProviderProtectedSynchronizationRuntime.execute(
                context = executionContext(storage, transport),
                pipeline = HealthCheckingPipeline(),
                storageOperations = protectedStorage(storage, RecordingCircuitStore()),
                transportOperations = protectedTransport(transport, RecordingCircuitStore()),
            )
        }

        assertEquals("storage health cancelled", cancellation.message)
        assertEquals(1, storage.healthCalls)
        assertEquals(0, transport.healthCalls)
    }

    @Test
    fun `diagnostic strings exclude canonical error messages and provider values`() = runTest {
        val providerError = TestError(
            code = ErrorCode("SENSITIVE_FAILURE"),
            category = ErrorCategory.STORAGE,
            recoverability = Recoverability.RECOVERABLE,
            message = "Authorization: Bearer secret-token",
        )
        val storage = RecordingStorageProvider(
            healthResult = ProviderOperationResult.Failure(providerError),
        )
        val transport = RecordingTransportProvider()

        val protected = ProviderProtectedSynchronizationRuntime.execute(
            context = executionContext(storage, transport),
            pipeline = HealthCheckingPipeline(),
            storageOperations = protectedStorage(storage, RecordingCircuitStore()),
            transportOperations = protectedTransport(transport, RecordingCircuitStore()),
        )

        val resultText = protected.toString()
        val evidenceText = protected.operationEvidence.single().toString()
        assertFalse(resultText.contains("secret-token"))
        assertFalse(evidenceText.contains("secret-token"))
        assertFalse(evidenceText.contains("Authorization"))
        assertTrue(evidenceText.contains("SENSITIVE_FAILURE"))
    }

    @Test
    fun `open circuit rejects readLocalConflictCandidate the same way as other protected storage operations`() =
        runTest {
            val storage = RecordingStorageProvider()
            val conflictScope = storageScopes(storage.descriptor.id).readLocalConflictCandidate
            val storageStore = RecordingCircuitStore(
                initialRecords = mapOf(conflictScope to openRecord(conflictScope)),
            )
            val bridge = ProviderProtectionStorageBridge(
                protectedOperations = protectedStorage(storage, storageStore),
                evidenceCollector = ProviderProtectionEvidenceCollector(),
            )

            val result = bridge.readLocalConflictCandidate(
                LocalConflictCandidateReadRequest(
                    request = synchronizationRequest(),
                    entity = EntityReference(
                        type = EntityType("Order"),
                        id = EntityId("entity-1"),
                    ),
                ),
            )

            val failure = assertIs<ProviderOperationResult.Failure>(result)
            assertEquals("PROVIDER_CIRCUIT_OPEN", failure.error.code.value)
            assertEquals(0, storage.readLocalConflictCandidateCalls)
        }

    @Test
    fun `closed circuit forwards the real local conflict candidate result unchanged`() = runTest {
        val localChange = ChangeEvent(
            id = ChangeEventId("local-conflict-candidate"),
            entity = EntityReference(type = EntityType("Order"), id = EntityId("entity-1")),
            operation = ChangeOperation.UPDATE,
        )
        val storage = RecordingStorageProvider(
            conflictCandidateResult = ProviderOperationResult.Success(
                LocalConflictCandidateReadResult.Found(localChange),
            ),
        )
        val bridge = ProviderProtectionStorageBridge(
            protectedOperations = protectedStorage(storage, RecordingCircuitStore()),
            evidenceCollector = ProviderProtectionEvidenceCollector(),
        )

        val result = bridge.readLocalConflictCandidate(
            LocalConflictCandidateReadRequest(
                request = synchronizationRequest(),
                entity = EntityReference(type = EntityType("Order"), id = EntityId("entity-1")),
            ),
        )

        val success = assertIs<ProviderOperationResult.Success<LocalConflictCandidateReadResult>>(
            result,
        )
        val found = assertIs<LocalConflictCandidateReadResult.Found>(success.value)
        assertSame(localChange, found.localChange)
        assertEquals(1, storage.readLocalConflictCandidateCalls)
    }

    private fun protectedStorage(
        provider: StorageProvider,
        store: CircuitBreakerStateStore,
    ) = StorageCircuitProtectionRuntime.create(
        storageProvider = provider,
        clock = FixedClock(now),
        circuitBreakerConfiguration = configuration(),
        circuitBreakerStateStore = store,
        scopes = storageScopes(provider.descriptor.id),
    )

    private fun protectedTransport(
        provider: TransportProvider,
        store: CircuitBreakerStateStore,
    ) = TransportCircuitProtectionRuntime.create(
        transportProvider = provider,
        clock = FixedClock(now),
        circuitBreakerConfiguration = configuration(),
        circuitBreakerStateStore = store,
        scopes = transportScopes(provider.descriptor.id),
    )

    private class HealthCheckingPipeline : SynchronizationPipeline {
        override val direction: SynchronizationDirection = SynchronizationDirection.PUSH
        var executeCalls: Int = 0
            private set

        override suspend fun execute(
            context: SynchronizationExecutionContext,
        ): SynchronizationResult {
            executeCalls++
            when (val storageHealth = context.providers.storageProvider.health()) {
                is ProviderOperationResult.Failure -> return failed(context, storageHealth.error)
                is ProviderOperationResult.Success -> Unit
            }
            when (val transportHealth = context.providers.transportProvider.health()) {
                is ProviderOperationResult.Failure -> return failed(context, transportHealth.error)
                is ProviderOperationResult.Success -> Unit
            }
            return SynchronizationResult.Succeeded(
                request = context.request,
                completedAt = context.runtimeDependencies.clock.now(),
                summary = SynchronizationSummary(),
            )
        }

        private fun failed(
            context: SynchronizationExecutionContext,
            error: DataLoomError,
        ): SynchronizationResult = SynchronizationResult.Failed(
            request = context.request,
            completedAt = context.runtimeDependencies.clock.now(),
            summary = SynchronizationSummary(),
            error = error,
        )
    }

    private class TestProviderSet(
        override val storageProvider: StorageProvider,
        override val transportProvider: TransportProvider,
    ) : SynchronizationProviderSet {
        override val schedulerProvider = null
        override val connectivityProvider = null
        override val queueProvider = null
    }

    private class RecordingStorageProvider(
        providerId: ProviderId = ProviderId("protected-storage"),
        private val healthResult: ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY)),
        private val cancelHealth: Boolean = false,
        private val conflictCandidateResult:
            ProviderOperationResult<LocalConflictCandidateReadResult> =
            ProviderOperationResult.Success(LocalConflictCandidateReadResult.NotFound),
    ) : StorageProvider {
        var healthCalls: Int = 0
            private set
        var readLocalConflictCandidateCalls: Int = 0
            private set

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = providerId,
            name = ProviderName("Protected Storage"),
            type = ProviderType.STORAGE,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> {
            healthCalls++
            if (cancelHealth) throw CancellationException("storage health cancelled")
            return healthResult
        }

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun readOutboundChanges(
            request: OutboundChangeReadRequest,
        ): ProviderOperationResult<OutboundChangeReadResult> =
            ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges)

        override suspend fun applyInboundChanges(
            request: InboundChangeApplyRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun acknowledgeOutboundChanges(
            request: OutboundChangeAcknowledgementRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun readCheckpoint(
            request: CheckpointReadRequest,
        ): ProviderOperationResult<SynchronizationCheckpoint?> =
            ProviderOperationResult.Success(null)

        override suspend fun writeCheckpoint(
            request: CheckpointWriteRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun readLocalConflictCandidate(
            request: LocalConflictCandidateReadRequest,
        ): ProviderOperationResult<LocalConflictCandidateReadResult> {
            readLocalConflictCandidateCalls++
            return conflictCandidateResult
        }
    }

    private class RecordingTransportProvider(
        providerId: ProviderId = ProviderId("protected-transport"),
    ) : TransportProvider {
        var healthCalls: Int = 0
            private set

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = providerId,
            name = ProviderName("Protected Transport"),
            type = ProviderType.TRANSPORT,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> {
            healthCalls++
            return ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))
        }

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun pushChanges(
            request: PushChangesRequest,
        ): ProviderOperationResult<ChangeSetAcknowledgement> =
            ProviderOperationResult.Failure(error("PUSH_UNUSED", ErrorCategory.CONFIGURATION))

        override suspend fun pullChanges(
            request: PullChangesRequest,
        ): ProviderOperationResult<PullChangesResult> =
            ProviderOperationResult.Failure(error("PULL_UNUSED", ErrorCategory.CONFIGURATION))
    }

    private class RecordingCircuitStore(
        initialRecords: Map<CircuitBreakerScope, CircuitBreakerStateRecord> = emptyMap(),
        private val compareError: DataLoomError? = null,
    ) : CircuitBreakerStateStore {
        private val records = initialRecords.toMutableMap()
        var loadCalls: Int = 0
            private set
        var compareCalls: Int = 0
            private set

        override suspend fun load(
            scope: CircuitBreakerScope,
        ): ProviderOperationResult<CircuitBreakerLoadResult> {
            loadCalls++
            return ProviderOperationResult.Success(
                records[scope]?.let(CircuitBreakerLoadResult::Found)
                    ?: CircuitBreakerLoadResult.Missing,
            )
        }

        override suspend fun compareAndSet(
            request: CircuitBreakerCompareAndSetRequest,
        ): ProviderOperationResult<CircuitBreakerCompareAndSetResult> {
            compareCalls++
            compareError?.let { return ProviderOperationResult.Failure(it) }
            val current = records[request.scope]
            if (current?.version != request.expectedVersion) {
                return ProviderOperationResult.Success(
                    CircuitBreakerCompareAndSetResult.Conflict(current),
                )
            }
            val updated = CircuitBreakerStateRecord(
                state = request.nextState,
                version = (current?.version ?: -1L) + 1L,
            )
            records[request.scope] = updated
            return ProviderOperationResult.Success(
                CircuitBreakerCompareAndSetResult.Updated(updated),
            )
        }
    }

    private class FixedClock(
        private val instant: DataLoomInstant,
    ) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
    }

    private class CountingClock(
        private val instant: DataLoomInstant,
    ) : DataLoomClock {
        var readCalls: Int = 0
            private set

        override fun now(): DataLoomInstant {
            readCalls++
            return instant
        }
    }

    private companion object {
        val now = DataLoomInstant(1_000L)

        fun executionContext(
            storage: StorageProvider,
            transport: TransportProvider,
            clock: DataLoomClock = FixedClock(now),
        ): SynchronizationExecutionContext = SynchronizationExecutionContext(
            request = synchronizationRequest(),
            providers = TestProviderSet(storage, transport),
            runtimeDependencies = runtimeDependencies(clock),
        )

        fun synchronizationRequest(): SynchronizationRequest = SynchronizationRequest(
            workflowId = WorkflowId("protected-pipeline-workflow"),
            sessionId = SynchronizationSessionId("protected-pipeline-session"),
            direction = SynchronizationDirection.PUSH,
            mode = SynchronizationMode.DELTA,
            context = ExecutionContext(
                executionId = ExecutionId("protected-pipeline-execution"),
                correlationId = CorrelationId("protected-pipeline-correlation"),
            ),
        )

        fun runtimeDependencies(clock: DataLoomClock): RuntimeDependencies =
            RuntimeDependencies(
                clock = clock,
                identifiers = RuntimeIdentifierGenerators(
                    synchronizationEventIds = generator {
                        SynchronizationEventId("protected-pipeline-event")
                    },
                    queueEntryIds = generator {
                        QueueEntryId("protected-pipeline-entry")
                    },
                    queueLeaseIds = generator {
                        QueueLeaseId("protected-pipeline-lease")
                    },
                    conflictIds = generator {
                        ConflictId("protected-pipeline-conflict")
                    },
                ),
            )

        fun <T> generator(block: () -> T): IdentifierGenerator<T> =
            object : IdentifierGenerator<T> {
                override fun generate(): T = block()
            }

        fun configuration(): CircuitBreakerConfiguration = CircuitBreakerConfiguration(
            failureThreshold = 2,
            failureWindow = SchedulingDelay(1_000L),
            openDuration = SchedulingDelay(10_000L),
        )

        fun storageScopes(providerId: ProviderId): StorageCircuitScopes =
            StorageCircuitScopes(
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
                readCheckpoint = scope(
                    providerId,
                    StorageCircuitOperation.READ_CHECKPOINT.retryOperation,
                ),
                writeCheckpoint = scope(
                    providerId,
                    StorageCircuitOperation.WRITE_CHECKPOINT.retryOperation,
                ),
                readLocalConflictCandidate = scope(
                    providerId,
                    StorageCircuitOperation.READ_LOCAL_CONFLICT_CANDIDATE.retryOperation,
                ),
            )

        fun transportScopes(providerId: ProviderId): TransportCircuitScopes =
            TransportCircuitScopes(
                initialization = scope(providerId, TransportCircuitOperation.INITIALIZE.retryOperation),
                health = scope(providerId, TransportCircuitOperation.HEALTH.retryOperation),
                close = scope(providerId, TransportCircuitOperation.CLOSE.retryOperation),
                pushChanges = scope(
                    providerId,
                    TransportCircuitOperation.PUSH_CHANGES.retryOperation,
                ),
                pullChanges = scope(
                    providerId,
                    TransportCircuitOperation.PULL_CHANGES.retryOperation,
                ),
            )

        fun scope(
            providerId: ProviderId,
            operation: io.dataloom.api.retry.RetryOperation,
        ): CircuitBreakerScope = CircuitBreakerScope.providerOperation(
            providerId = providerId,
            operation = operation,
        )

        fun openRecord(scope: CircuitBreakerScope): CircuitBreakerStateRecord =
            CircuitBreakerStateRecord(
                state = CircuitBreakerState(
                    scope = scope,
                    phase = CircuitBreakerPhase.OPEN,
                    consecutiveFailures = 0,
                    failureWindowStartedAt = null,
                    openUntil = DataLoomInstant(10_000L),
                    probeGeneration = 0L,
                    probeInFlight = false,
                    updatedAt = now,
                ),
                version = 0L,
            )

        fun closedFailureRecord(scope: CircuitBreakerScope): CircuitBreakerStateRecord =
            CircuitBreakerStateRecord(
                state = CircuitBreakerState(
                    scope = scope,
                    phase = CircuitBreakerPhase.CLOSED,
                    consecutiveFailures = 1,
                    failureWindowStartedAt = now,
                    openUntil = null,
                    probeGeneration = 0L,
                    probeInFlight = false,
                    updatedAt = now,
                ),
                version = 0L,
            )

        fun error(
            code: String,
            category: ErrorCategory,
            recoverability: Recoverability = Recoverability.RECOVERABLE,
        ): DataLoomError = TestError(
            code = ErrorCode(code),
            category = category,
            recoverability = recoverability,
        )
    }

    private data class TestError(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val recoverability: Recoverability,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val message: String = "Protected pipeline test error.",
        override val cause: Throwable? = null,
    ) : DataLoomError
}
