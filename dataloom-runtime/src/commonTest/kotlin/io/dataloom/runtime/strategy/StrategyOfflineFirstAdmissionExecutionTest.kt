package io.dataloom.runtime.strategy

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
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.provider.StrategyProviderBindings
import io.dataloom.api.queue.ExpiredLeaseRecoveryRequest
import io.dataloom.api.queue.ExpiredLeaseRecoveryResult
import io.dataloom.api.queue.QueueAcquireRequest
import io.dataloom.api.queue.QueueAcquireResult
import io.dataloom.api.queue.QueueCancellationRequest
import io.dataloom.api.queue.QueueCompletionRequest
import io.dataloom.api.queue.QueueDeferralRequest
import io.dataloom.api.queue.QueueEnqueueRequest
import io.dataloom.api.queue.QueueFailureRequest
import io.dataloom.api.queue.QueueProvider
import io.dataloom.api.queue.QueueRescheduleRequest
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.runtime.RuntimeIdentifierGenerators
import io.dataloom.api.storage.InboundChangeApplyRequest
import io.dataloom.api.storage.OutboundChangeReadRequest
import io.dataloom.api.storage.OutboundChangeReadResult
import io.dataloom.api.strategy.OfflineFirstStrategyProfile
import io.dataloom.api.strategy.StrategyCacheState
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyConnectivity
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyOfflineFirstAdmissionProvider
import io.dataloom.api.strategy.StrategyOfflineFirstAdmissionRequest
import io.dataloom.api.strategy.StrategyOfflineFirstAdmissionResult
import io.dataloom.api.strategy.StrategyOperationInput
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.strategy.StrategyProviderHealth
import io.dataloom.api.strategy.StrategyRuntimeEvidence
import io.dataloom.api.strategy.StrategySynchronizationRequest
import io.dataloom.api.synchronization.CheckpointReadRequest
import io.dataloom.api.synchronization.CheckpointWriteRequest
import io.dataloom.api.synchronization.OutboundChangeAcknowledgementRequest
import io.dataloom.api.synchronization.SynchronizationCheckpoint
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.core.provider.ProviderLifecycleCoordinator
import io.dataloom.core.provider.ProviderRegistry
import io.dataloom.core.provider.StrategyProviderResolver
import io.dataloom.runtime.execution.SynchronizationPipelineRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest

class StrategyOfflineFirstAdmissionExecutionTest {

    @Test
    fun acceptedAdmissionReturnsDurableDeferredEvidence() = runTest {
        val input = admissionInput()
        val storage = RecordingAtomicAdmissionStorage(
            ProviderOperationResult.Success(
                StrategyOfflineFirstAdmissionResult.Accepted(
                    queueEntryId = input.queueEntryId,
                    idempotencyKey = input.idempotencyKey,
                ),
            ),
        )
        val fixture = fixture(storage)

        val result = fixture.coordinator.execute(
            request = request(input),
            bindings = fixture.bindings,
        )

        val deferred = assertIs<StrategySynchronizationExecutionResult.Deferred>(result)
        assertEquals(
            StrategyOfflineFirstAdmissionDisposition.ACCEPTED,
            deferred.admissionDisposition,
        )
        assertEquals(1, storage.admissionCalls)
        assertEquals(input.queueEntryId, storage.lastRequest?.queueEntryId)
        assertEquals(input.idempotencyKey, storage.lastRequest?.idempotencyKey)
        assertEquals(0, fixture.queue.operationCalls)
    }

    @Test
    fun duplicateAdmissionReturnsAlreadyAcceptedEvidence() = runTest {
        val input = admissionInput()
        val storage = RecordingAtomicAdmissionStorage(
            ProviderOperationResult.Success(
                StrategyOfflineFirstAdmissionResult.AlreadyAccepted(
                    queueEntryId = input.queueEntryId,
                    idempotencyKey = input.idempotencyKey,
                ),
            ),
        )
        val fixture = fixture(storage)

        val result = fixture.coordinator.execute(
            request = request(input),
            bindings = fixture.bindings,
        )

        val deferred = assertIs<StrategySynchronizationExecutionResult.Deferred>(result)
        assertEquals(
            StrategyOfflineFirstAdmissionDisposition.ALREADY_ACCEPTED,
            deferred.admissionDisposition,
        )
        assertEquals(1, storage.admissionCalls)
        assertEquals(0, fixture.queue.operationCalls)
    }

    @Test
    fun providerFailureDoesNotReportAcceptanceOrTransportAttempt() = runTest {
        val failure = AdmissionFailure()
        val storage = RecordingAtomicAdmissionStorage(
            ProviderOperationResult.Failure(failure),
        )
        val fixture = fixture(storage)

        val result = fixture.coordinator.execute(
            request = request(admissionInput()),
            bindings = fixture.bindings,
        )

        val failed = assertIs<StrategySynchronizationExecutionResult.Failed>(result)
        assertSame(failure, failed.error)
        assertEquals(false, failed.transportAttempted)
        assertEquals(1, storage.admissionCalls)
        assertEquals(0, fixture.queue.operationCalls)
    }

    @Test
    fun mismatchedProviderIdentityFailsClosed() = runTest {
        val input = admissionInput()
        val storage = RecordingAtomicAdmissionStorage(
            ProviderOperationResult.Success(
                StrategyOfflineFirstAdmissionResult.Accepted(
                    queueEntryId = QueueEntryId("different-entry"),
                    idempotencyKey = input.idempotencyKey,
                ),
            ),
        )
        val fixture = fixture(storage)

        val result = fixture.coordinator.execute(
            request = request(input),
            bindings = fixture.bindings,
        )

        val failed = assertIs<StrategySynchronizationExecutionResult.Failed>(result)
        assertEquals(
            ErrorCode("STRATEGY_OFFLINE_FIRST_ADMISSION_IDENTITY_MISMATCH"),
            failed.error.code,
        )
        assertEquals(Recoverability.NON_RECOVERABLE, failed.error.recoverability)
        assertEquals(false, failed.transportAttempted)
        assertEquals(0, fixture.queue.operationCalls)
    }

    @Test
    fun missingAdmissionInputRejectsBeforeProviderInvocation() = runTest {
        val storage = RecordingAtomicAdmissionStorage(
            ProviderOperationResult.Success(
                StrategyOfflineFirstAdmissionResult.Accepted(
                    queueEntryId = QueueEntryId("entry-1"),
                    idempotencyKey = "intent-1",
                ),
            ),
        )
        val fixture = fixture(storage)

        val result = fixture.coordinator.execute(
            request = request(StrategyOperationInput.ProviderBacked),
            bindings = fixture.bindings,
        )

        val rejected = assertIs<StrategySynchronizationExecutionResult.Rejected>(result)
        assertEquals(StrategyExecutionRejectionReason.INCOMPATIBLE_INPUT, rejected.reason)
        assertEquals(0, storage.admissionCalls)
        assertEquals(0, fixture.queue.operationCalls)
    }

    @Test
    fun uninitializedRuntimeRejectsBeforeAdmission() = runTest {
        val storage = RecordingAtomicAdmissionStorage(
            ProviderOperationResult.Success(
                StrategyOfflineFirstAdmissionResult.Accepted(
                    queueEntryId = QueueEntryId("entry-1"),
                    idempotencyKey = "intent-1",
                ),
            ),
        )
        val fixture = fixture(storage, initialize = false)

        val result = fixture.coordinator.execute(
            request = request(admissionInput()),
            bindings = fixture.bindings,
        )

        val rejected = assertIs<StrategySynchronizationExecutionResult.Rejected>(result)
        assertEquals(
            StrategyExecutionRejectionReason.PROVIDERS_NOT_INITIALIZED,
            rejected.reason,
        )
        assertEquals(0, storage.admissionCalls)
        assertEquals(0, fixture.queue.operationCalls)
    }

    @Test
    fun onlineOfflineFirstExecutionRemainsFailClosedInThisSlice() = runTest {
        val storage = RecordingAtomicAdmissionStorage(
            ProviderOperationResult.Success(
                StrategyOfflineFirstAdmissionResult.Accepted(
                    queueEntryId = QueueEntryId("entry-1"),
                    idempotencyKey = "intent-1",
                ),
            ),
        )
        val fixture = fixture(storage)

        val result = fixture.coordinator.execute(
            request = request(
                input = admissionInput(),
                connectivity = StrategyConnectivity.AVAILABLE,
            ),
            bindings = fixture.bindings,
        )

        val rejected = assertIs<StrategySynchronizationExecutionResult.Rejected>(result)
        assertEquals(StrategyExecutionRejectionReason.UNSUPPORTED_PLAN, rejected.reason)
        assertEquals(0, storage.admissionCalls)
        assertEquals(0, fixture.queue.operationCalls)
    }

    private suspend fun fixture(
        storage: RecordingAtomicAdmissionStorage,
        initialize: Boolean = true,
    ): Fixture {
        val queue = RecordingQueueProvider()
        val registry = ProviderRegistry(listOf(storage, queue))
        val lifecycle = ProviderLifecycleCoordinator(
            registry = registry,
            context = ProviderInitializationContext(),
        )
        if (initialize) {
            lifecycle.initialize()
        }
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
            queue = queue,
            bindings = StrategyProviderBindings(
                storageProviderId = storage.descriptor.id,
                queueProviderId = queue.descriptor.id,
            ),
        )
    }

    private fun request(
        input: StrategyOperationInput,
        connectivity: StrategyConnectivity = StrategyConnectivity.UNAVAILABLE,
    ): StrategySynchronizationRequest =
        StrategySynchronizationRequest(
            request = SynchronizationRequest(
                workflowId = WorkflowId("workflow-1"),
                sessionId = SynchronizationSessionId("session-1"),
                direction = SynchronizationDirection.PUSH,
                mode = SynchronizationMode.DELTA,
                context = ExecutionContext(
                    executionId = ExecutionId("execution-1"),
                    correlationId = CorrelationId("correlation-1"),
                ),
            ),
            decisionId = StrategyDecisionId("decision-1"),
            planId = StrategyPlanId("plan-1"),
            profile = OfflineFirstStrategyProfile(
                id = StrategyProfileId("offline-profile"),
                configurationVersion = StrategyConfigurationVersion(1),
            ),
            evidence = StrategyRuntimeEvidence(
                connectivity = connectivity,
                cacheState = StrategyCacheState.NOT_EVALUATED,
                storageHealth = StrategyProviderHealth.HEALTHY,
                queueHealth = StrategyProviderHealth.HEALTHY,
                hasPendingLocalChanges = true,
                isBackgroundExecutionAvailable = true,
            ),
            input = input,
        )

    private fun admissionInput(): StrategyOperationInput.OfflineFirstAdmission =
        StrategyOperationInput.OfflineFirstAdmission(
            queueEntryId = QueueEntryId("entry-1"),
            idempotencyKey = "intent-1",
        )

    private fun runtimeDependencies(): RuntimeDependencies =
        RuntimeDependencies(
            clock = FixedClock(DataLoomInstant(9_000L)),
            identifiers = RuntimeIdentifierGenerators(
                synchronizationEventIds = fixedGenerator(SynchronizationEventId("event-1")),
                queueEntryIds = fixedGenerator(QueueEntryId("generated-entry")),
                queueLeaseIds = fixedGenerator(QueueLeaseId("lease-1")),
                conflictIds = fixedGenerator(ConflictId("conflict-1")),
            ),
        )

    private fun <T> fixedGenerator(value: T): IdentifierGenerator<T> =
        object : IdentifierGenerator<T> {
            override fun generate(): T = value
        }

    private data class Fixture(
        val coordinator: StrategySynchronizationExecutionCoordinator,
        val queue: RecordingQueueProvider,
        val bindings: StrategyProviderBindings,
    )

    private open class PlainStorage : io.dataloom.api.storage.StorageProvider {
        override val descriptor: ProviderDescriptor =
            descriptor("storage", ProviderType.STORAGE)

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> = success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> = success(Unit)

        override suspend fun readOutboundChanges(
            request: OutboundChangeReadRequest,
        ): ProviderOperationResult<OutboundChangeReadResult> = unexpected()

        override suspend fun applyInboundChanges(
            request: InboundChangeApplyRequest,
        ): ProviderOperationResult<Unit> = unexpected()

        override suspend fun acknowledgeOutboundChanges(
            request: OutboundChangeAcknowledgementRequest,
        ): ProviderOperationResult<Unit> = unexpected()

        override suspend fun readCheckpoint(
            request: CheckpointReadRequest,
        ): ProviderOperationResult<SynchronizationCheckpoint?> = unexpected()

        override suspend fun writeCheckpoint(
            request: CheckpointWriteRequest,
        ): ProviderOperationResult<Unit> = unexpected()
    }

    private class RecordingAtomicAdmissionStorage(
        private val result: ProviderOperationResult<StrategyOfflineFirstAdmissionResult>,
    ) : PlainStorage(), StrategyOfflineFirstAdmissionProvider {
        var admissionCalls: Int = 0
        var lastRequest: StrategyOfflineFirstAdmissionRequest? = null

        override suspend fun admitLocalIntentAndOutbox(
            request: StrategyOfflineFirstAdmissionRequest,
        ): ProviderOperationResult<StrategyOfflineFirstAdmissionResult> {
            admissionCalls++
            lastRequest = request
            return result
        }
    }

    private class RecordingQueueProvider : QueueProvider {
        override val descriptor: ProviderDescriptor =
            descriptor("queue", ProviderType.QUEUE)

        var operationCalls: Int = 0

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> = success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> = success(Unit)

        override suspend fun enqueue(
            request: QueueEnqueueRequest,
        ): ProviderOperationResult<Unit> = unexpectedQueueCall()

        override suspend fun acquire(
            request: QueueAcquireRequest,
        ): ProviderOperationResult<QueueAcquireResult> = unexpectedQueueCall()

        override suspend fun complete(
            request: QueueCompletionRequest,
        ): ProviderOperationResult<Unit> = unexpectedQueueCall()

        override suspend fun reschedule(
            request: QueueRescheduleRequest,
        ): ProviderOperationResult<Unit> = unexpectedQueueCall()

        override suspend fun defer(
            request: QueueDeferralRequest,
        ): ProviderOperationResult<Unit> = unexpectedQueueCall()

        override suspend fun fail(
            request: QueueFailureRequest,
        ): ProviderOperationResult<Unit> = unexpectedQueueCall()

        override suspend fun cancel(
            request: QueueCancellationRequest,
        ): ProviderOperationResult<Unit> = unexpectedQueueCall()

        override suspend fun recoverExpiredLeases(
            request: ExpiredLeaseRecoveryRequest,
        ): ProviderOperationResult<ExpiredLeaseRecoveryResult> = unexpectedQueueCall()

        private fun <T> unexpectedQueueCall(): T {
            operationCalls++
            error("QueueProvider operations must not run during atomic admission.")
        }
    }

    private data class AdmissionFailure(
        override val code: ErrorCode = ErrorCode("OFFLINE_ADMISSION_FAILED"),
        override val category: ErrorCategory = ErrorCategory.STORAGE,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Atomic admission failed.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private class FixedClock(private val instant: DataLoomInstant) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
    }

    private companion object {
        fun descriptor(id: String, type: ProviderType): ProviderDescriptor =
            ProviderDescriptor(
                id = ProviderId(id),
                name = ProviderName(id),
                type = type,
                version = ProviderVersion("1.0.0"),
            )

        fun <T> success(value: T): ProviderOperationResult<T> =
            ProviderOperationResult.Success(value)

        fun <T> unexpected(): T =
            error("Storage operations must not run during atomic admission.")
    }
}
