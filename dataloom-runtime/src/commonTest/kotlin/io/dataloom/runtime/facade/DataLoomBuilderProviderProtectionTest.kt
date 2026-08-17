package io.dataloom.runtime.facade

import io.dataloom.api.circuit.CircuitBreakerCompareAndSetRequest
import io.dataloom.api.circuit.CircuitBreakerCompareAndSetResult
import io.dataloom.api.circuit.CircuitBreakerLoadResult
import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.circuit.CircuitBreakerStateRecord
import io.dataloom.api.circuit.CircuitBreakerStateStore
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
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
import io.dataloom.api.provider.SynchronizationProviderBindings
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.runtime.RuntimeIdentifierGenerators
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.storage.InboundChangeApplyRequest
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
import io.dataloom.runtime.execution.SynchronizationExecutionRejectionReason
import io.dataloom.runtime.execution.SynchronizationExecutionResult
import io.dataloom.runtime.execution.SynchronizationPipeline
import io.dataloom.runtime.execution.protection.ProviderProtectionInvocation
import io.dataloom.runtime.retry.CircuitBreakerConfiguration
import io.dataloom.runtime.retry.StorageCircuitOperation
import io.dataloom.runtime.retry.StorageCircuitScopes
import io.dataloom.runtime.retry.TransportCircuitOperation
import io.dataloom.runtime.retry.TransportCircuitScopes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class DataLoomBuilderProviderProtectionTest {

    @Test
    fun `builder without provider protection exposes no protected capability`() {
        val fixture = fixture()
        val dataLoom = fixture.builder.build()

        assertNull(dataLoom.protectedSynchronization)
    }

    @Test
    fun `valid provider protection build is side effect free`() {
        val fixture = fixture()
        val dataLoom = fixture.builder
            .providerProtectionConfiguration(fixture.spec())
            .build()

        assertNotNull(dataLoom.protectedSynchronization)
        assertEquals(0, fixture.storage.initializeCalls)
        assertEquals(0, fixture.storage.healthCalls)
        assertEquals(0, fixture.transport.initializeCalls)
        assertEquals(0, fixture.transport.healthCalls)
        assertEquals(0, fixture.storageStore.loadCalls)
        assertEquals(0, fixture.storageStore.compareCalls)
        assertEquals(0, fixture.transportStore.loadCalls)
        assertEquals(0, fixture.transportStore.compareCalls)
        assertEquals(0, fixture.clock.readCalls)
    }

    @Test
    fun `invalid storage operation scope fails before provider store or clock access`() {
        val fixture = fixture()
        val valid = fixture.spec()
        val invalidStorage = DataLoomStorageProtectionSpec(
            circuitBreakerConfiguration = valid.storage.circuitBreakerConfiguration,
            circuitBreakerStateStore = valid.storage.circuitBreakerStateStore,
            scopes = valid.storage.scopes.copy(
                health = CircuitBreakerScope.providerOperation(
                    providerId = fixture.storage.descriptor.id,
                    operation = StorageCircuitOperation.CLOSE.retryOperation,
                ),
            ),
        )

        assertFailsWith<DataLoomBuildException> {
            fixture.builder.providerProtectionConfiguration(
                DataLoomProviderProtectionSpec(
                    storage = invalidStorage,
                    transport = valid.transport,
                ),
            ).build()
        }

        assertEquals(0, fixture.storage.initializeCalls)
        assertEquals(0, fixture.storage.healthCalls)
        assertEquals(0, fixture.transport.initializeCalls)
        assertEquals(0, fixture.transport.healthCalls)
        assertEquals(0, fixture.storageStore.loadCalls)
        assertEquals(0, fixture.transportStore.loadCalls)
        assertEquals(0, fixture.clock.readCalls)
    }

    @Test
    fun `protected synchronization before initialization preserves existing rejection`() = runTest {
        val fixture = fixture()
        val dataLoom = fixture.builder
            .providerProtectionConfiguration(fixture.spec())
            .build()

        val rejected = assertIs<ProviderProtectedSynchronizationExecutionResult.Rejected>(
            requireNotNull(dataLoom.protectedSynchronization).synchronize(request()),
        )

        assertEquals(
            SynchronizationExecutionRejectionReason.PROVIDERS_NOT_INITIALIZED,
            rejected.rejection.reason,
        )
        assertEquals(0, fixture.storage.healthCalls)
        assertEquals(0, fixture.transport.healthCalls)
        assertEquals(0, fixture.storageStore.loadCalls)
        assertEquals(0, fixture.transportStore.loadCalls)
    }

    @Test
    fun `initialized protected synchronization returns exact pipeline and provider evidence`() = runTest {
        val fixture = fixture()
        val dataLoom = fixture.builder
            .providerProtectionConfiguration(fixture.spec())
            .build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())
        val storageHealthCallsAfterInitialization = fixture.storage.healthCalls
        val transportHealthCallsAfterInitialization = fixture.transport.healthCalls

        val executed = assertIs<ProviderProtectedSynchronizationExecutionResult.Executed>(
            requireNotNull(dataLoom.protectedSynchronization).synchronize(request()),
        )

        assertIs<SynchronizationResult.Succeeded>(executed.result.synchronizationResult)
        assertEquals(2, executed.result.operationEvidence.size)
        assertEquals("storage.health", executed.result.operationEvidence[0].operation.value)
        assertEquals("transport.health", executed.result.operationEvidence[1].operation.value)
        assertTrue(
            executed.result.operationEvidence.all {
                it.invocation == ProviderProtectionInvocation.SUCCEEDED
            },
        )
        assertEquals(
            storageHealthCallsAfterInitialization + 1,
            fixture.storage.healthCalls,
        )
        assertEquals(
            transportHealthCallsAfterInitialization + 1,
            fixture.transport.healthCalls,
        )
        assertTrue(fixture.storageStore.loadCalls >= 1)
        assertTrue(fixture.transportStore.loadCalls >= 1)
    }

    @Test
    fun `historical direct synchronization remains unprotected and unchanged`() = runTest {
        val fixture = fixture()
        val dataLoom = fixture.builder
            .providerProtectionConfiguration(fixture.spec())
            .build()
        assertIs<ProviderLifecycleResult.InitializeSuccess>(dataLoom.initialize())
        val storageHealthCallsAfterInitialization = fixture.storage.healthCalls
        val transportHealthCallsAfterInitialization = fixture.transport.healthCalls

        val direct = assertIs<SynchronizationExecutionResult.Executed>(
            dataLoom.synchronize(request()),
        )

        assertIs<SynchronizationResult.Succeeded>(direct.result)
        assertEquals(
            storageHealthCallsAfterInitialization + 1,
            fixture.storage.healthCalls,
        )
        assertEquals(
            transportHealthCallsAfterInitialization + 1,
            fixture.transport.healthCalls,
        )
        assertEquals(0, fixture.storageStore.loadCalls)
        assertEquals(0, fixture.transportStore.loadCalls)
    }

    private data class Fixture(
        val builder: DataLoomBuilder,
        val storage: RecordingStorageProvider,
        val transport: RecordingTransportProvider,
        val storageStore: RecordingCircuitStore,
        val transportStore: RecordingCircuitStore,
        val clock: CountingClock,
    ) {
        fun spec(): DataLoomProviderProtectionSpec = DataLoomProviderProtectionSpec(
            storage = DataLoomStorageProtectionSpec(
                circuitBreakerConfiguration = configuration(),
                circuitBreakerStateStore = storageStore,
                scopes = storageScopes(storage.descriptor.id),
            ),
            transport = DataLoomTransportProtectionSpec(
                circuitBreakerConfiguration = configuration(),
                circuitBreakerStateStore = transportStore,
                scopes = transportScopes(transport.descriptor.id),
            ),
        )
    }

    private fun fixture(): Fixture {
        val storage = RecordingStorageProvider()
        val transport = RecordingTransportProvider()
        val storageStore = RecordingCircuitStore()
        val transportStore = RecordingCircuitStore()
        val clock = CountingClock(now)
        val bindings = SynchronizationProviderBindings(
            storageProviderId = storage.descriptor.id,
            transportProviderId = transport.descriptor.id,
        )
        val builder = DataLoomBuilder()
            .runtimeDependencies(runtimeDependencies(clock))
            .providers(storage, transport)
            .defaultProviderBindings(bindings)
            .pipeline(HealthCheckingPipeline())
        return Fixture(
            builder = builder,
            storage = storage,
            transport = transport,
            storageStore = storageStore,
            transportStore = transportStore,
            clock = clock,
        )
    }

    private class HealthCheckingPipeline : SynchronizationPipeline {
        override val direction: SynchronizationDirection = SynchronizationDirection.PUSH

        override suspend fun execute(
            context: SynchronizationExecutionContext,
        ): SynchronizationResult {
            when (val storage = context.providers.storageProvider.health()) {
                is ProviderOperationResult.Failure -> return failed(context, storage.error)
                is ProviderOperationResult.Success -> Unit
            }
            when (val transport = context.providers.transportProvider.health()) {
                is ProviderOperationResult.Failure -> return failed(context, transport.error)
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

    private class RecordingStorageProvider : StorageProvider {
        var initializeCalls: Int = 0
            private set
        var healthCalls: Int = 0
            private set

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("builder-protected-storage"),
            name = ProviderName("Builder Protected Storage"),
            type = ProviderType.STORAGE,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> {
            initializeCalls++
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun health(): ProviderOperationResult<ProviderHealth> {
            healthCalls++
            return ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))
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
    }

    private class RecordingTransportProvider : TransportProvider {
        var initializeCalls: Int = 0
            private set
        var healthCalls: Int = 0
            private set

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("builder-protected-transport"),
            name = ProviderName("Builder Protected Transport"),
            type = ProviderType.TRANSPORT,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> {
            initializeCalls++
            return ProviderOperationResult.Success(Unit)
        }

        override suspend fun health(): ProviderOperationResult<ProviderHealth> {
            healthCalls++
            return ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))
        }

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun pushChanges(
            request: PushChangesRequest,
        ): ProviderOperationResult<ChangeSetAcknowledgement> =
            ProviderOperationResult.Failure(error("PUSH_UNUSED"))

        override suspend fun pullChanges(
            request: PullChangesRequest,
        ): ProviderOperationResult<PullChangesResult> =
            ProviderOperationResult.Failure(error("PULL_UNUSED"))
    }

    private class RecordingCircuitStore : CircuitBreakerStateStore {
        private val records = mutableMapOf<CircuitBreakerScope, CircuitBreakerStateRecord>()
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

        fun request(): SynchronizationRequest = SynchronizationRequest(
            workflowId = WorkflowId("builder-provider-protection-workflow"),
            sessionId = SynchronizationSessionId("builder-provider-protection-session"),
            direction = SynchronizationDirection.PUSH,
            mode = SynchronizationMode.DELTA,
            context = ExecutionContext(
                executionId = ExecutionId("builder-provider-protection-execution"),
                correlationId = CorrelationId("builder-provider-protection-correlation"),
            ),
        )

        fun runtimeDependencies(clock: DataLoomClock): RuntimeDependencies =
            RuntimeDependencies(
                clock = clock,
                identifiers = RuntimeIdentifierGenerators(
                    synchronizationEventIds = generator {
                        SynchronizationEventId("builder-provider-protection-event")
                    },
                    queueEntryIds = generator {
                        QueueEntryId("builder-provider-protection-entry")
                    },
                    queueLeaseIds = generator {
                        QueueLeaseId("builder-provider-protection-lease")
                    },
                    conflictIds = generator {
                        ConflictId("builder-provider-protection-conflict")
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

        fun error(code: String): DataLoomError = TestError(ErrorCode(code))
    }

    private data class TestError(
        override val code: ErrorCode,
        override val category: ErrorCategory = ErrorCategory.CONFIGURATION,
        override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val message: String = "Builder provider protection test error.",
        override val cause: Throwable? = null,
    ) : DataLoomError
}
