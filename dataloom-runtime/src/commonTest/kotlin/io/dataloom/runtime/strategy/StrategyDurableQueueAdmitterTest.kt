package io.dataloom.runtime.strategy

import io.dataloom.api.connectivity.ConnectivityProvider
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.execution.StrategyProviderSet
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
import io.dataloom.api.queue.ExpiredLeaseRecoveryRequest
import io.dataloom.api.queue.ExpiredLeaseRecoveryResult
import io.dataloom.api.queue.QueueAcquireRequest
import io.dataloom.api.queue.QueueAcquireResult
import io.dataloom.api.queue.QueueCancellationRequest
import io.dataloom.api.queue.QueueCompletionRequest
import io.dataloom.api.queue.QueueDeferralRequest
import io.dataloom.api.queue.QueueEnqueueRequest
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.queue.QueueFailureRequest
import io.dataloom.api.queue.QueueProvider
import io.dataloom.api.queue.QueueRescheduleRequest
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.runtime.RuntimeIdentifierGenerators
import io.dataloom.api.scheduling.SchedulerProvider
import io.dataloom.api.storage.InboundChangeApplyRequest
import io.dataloom.api.storage.OutboundChangeReadRequest
import io.dataloom.api.storage.OutboundChangeReadResult
import io.dataloom.api.storage.StorageProvider
import io.dataloom.api.strategy.OfflineFirstStrategyProfile
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyConnectivity
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyOperationInput
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.strategy.StrategyRuntimeEvidence
import io.dataloom.api.strategy.StrategySynchronizationRequest
import io.dataloom.api.synchronization.CheckpointReadRequest
import io.dataloom.api.synchronization.CheckpointWriteRequest
import io.dataloom.api.synchronization.OutboundChangeAcknowledgementRequest
import io.dataloom.api.synchronization.SynchronizationCheckpoint
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.runtime.queue.QueuedSynchronizationWork
import io.dataloom.runtime.submission.QueuedSynchronizationSubmission
import io.dataloom.runtime.submission.QueuedSynchronizationWorkEncoder
import io.dataloom.runtime.submission.QueuedSynchronizationWorkEncodingResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

/**
 * Direct unit tests for [StrategyDurableQueueAdmitter] — the bridge between
 * [StrategyQueueAdmissionEvaluator] (pure, provider-free) and a real
 * [QueueProvider.enqueue] call.
 */
class StrategyDurableQueueAdmitterTest {

    private val now = DataLoomInstant(epochMilliseconds = 55_000L)
    private val clock = FixedDataLoomClock(now)
    private val evaluator = BuiltInSynchronizationStrategyEvaluator()
    private val runtimeDependencies = runtimeDependencies(clock)

    @Test
    fun noEncoderConfiguredReturnsNotConfiguredWithoutTouchingProviders() = runTest {
        val queue = FakeQueueProvider()
        val request = offlineFirstRequest()
        val outcome = admitter(encoder = null).admit(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(queue = queue),
        )
        assertIs<StrategyDurableQueueAdmissionOutcome.NotConfigured>(outcome)
        assertEquals(0, queue.enqueueCalls)
    }

    @Test
    fun admissionSucceedsAndCallsQueueProviderExactlyOnce() = runTest {
        val queue = FakeQueueProvider()
        val encoder = FakeEncoder()
        val request = offlineFirstRequest()
        val outcome = admitter(encoder).admit(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(queue = queue),
        )
        val admitted = assertIs<StrategyDurableQueueAdmissionOutcome.Admitted>(outcome)
        assertEquals(QueueEntryId("durable-admission-queue-entry"), admitted.queueEntryId)
        assertEquals(1, queue.enqueueCalls)
        val enqueued = queue.lastRequest
        assertEquals(QueueEntryId("durable-admission-queue-entry"), enqueued?.entry?.id)
        assertEquals(now, enqueued?.entry?.availableAt)
    }

    @Test
    fun rejectedPlanWithoutDurableContinuationIsRejected() = runTest {
        // PULL with connectivity available and no cache evidence never sets
        // ENQUEUE_DURABLE_WORK for offline-first -- reaching the evaluator's
        // own StrategyQueueAdmissionEvaluator.Rejected(MISSING_DURABLE_QUEUE_OPERATION) path.
        val queue = FakeQueueProvider()
        val encoder = FakeEncoder()
        val request = offlineFirstRequest(requireDurableQueue = false)
        val outcome = admitter(encoder).admit(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(queue = queue),
        )
        val rejected = assertIs<StrategyDurableQueueAdmissionOutcome.Rejected>(outcome)
        assertEquals(StrategyExecutionRejectionReason.UNSUPPORTED_PLAN, rejected.reason)
        assertEquals(0, queue.enqueueCalls)
    }

    @Test
    fun missingQueueProviderIsRejected() = runTest {
        val encoder = FakeEncoder()
        val request = offlineFirstRequest()
        val outcome = admitter(encoder).admit(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(queue = null),
        )
        val rejected = assertIs<StrategyDurableQueueAdmissionOutcome.Rejected>(outcome)
        assertEquals(
            StrategyExecutionRejectionReason.DURABLE_ADMISSION_PROVIDERS_INCOMPLETE,
            rejected.reason,
        )
    }

    @Test
    fun encoderRejectionIsSurfacedAsFailedWithoutCallingTheProvider() = runTest {
        val queue = FakeQueueProvider()
        val encoderError = testError("ENCODER_REJECTED")
        val encoder = FakeEncoder(rejection = encoderError)
        val request = offlineFirstRequest()
        val outcome = admitter(encoder).admit(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(queue = queue),
        )
        val failed = assertIs<StrategyDurableQueueAdmissionOutcome.Failed>(outcome)
        assertEquals(encoderError, failed.error)
        assertEquals(0, queue.enqueueCalls)
    }

    @Test
    fun queueProviderFailurePropagatesAsFailed() = runTest {
        val providerError = testError("ENQUEUE_UNAVAILABLE")
        val queue = FakeQueueProvider(enqueueResult = ProviderOperationResult.Failure(providerError))
        val encoder = FakeEncoder()
        val request = offlineFirstRequest()
        val outcome = admitter(encoder).admit(
            request = request,
            evaluation = evaluationFor(request),
            providers = providerSet(queue = queue),
        )
        val failed = assertIs<StrategyDurableQueueAdmissionOutcome.Failed>(outcome)
        assertEquals(providerError, failed.error)
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private fun admitter(encoder: QueuedSynchronizationWorkEncoder?): StrategyDurableQueueAdmitter =
        StrategyDurableQueueAdmitter(
            clock = clock,
            runtimeDependencies = runtimeDependencies,
            encoder = encoder,
        )

    private fun offlineFirstRequest(
        requireDurableQueue: Boolean = true,
    ): StrategySynchronizationRequest = StrategySynchronizationRequest(
        request = SynchronizationRequest(
            workflowId = WorkflowId("durable-admission-workflow"),
            sessionId = SynchronizationSessionId("durable-admission-session"),
            direction = SynchronizationDirection.PUSH,
            mode = SynchronizationMode.DELTA,
            context = ExecutionContext(
                executionId = ExecutionId("durable-admission-execution"),
                correlationId = CorrelationId("durable-admission-correlation"),
            ),
        ),
        decisionId = StrategyDecisionId("durable-admission-decision"),
        planId = StrategyPlanId("durable-admission-plan"),
        profile = OfflineFirstStrategyProfile(
            id = StrategyProfileId("durable-admission-profile"),
            configurationVersion = StrategyConfigurationVersion(1L),
            requireDurableQueue = requireDurableQueue,
            reconcileWhenOnline = false,
        ),
        evidence = StrategyRuntimeEvidence(connectivity = StrategyConnectivity.AVAILABLE),
        input = StrategyOperationInput.ProviderBacked,
    )

    private fun evaluationFor(request: StrategySynchronizationRequest) =
        evaluator.evaluate(request.evaluationRequest())

    private fun providerSet(queue: QueueProvider?): StrategyProviderSet = object : StrategyProviderSet {
        override val storageProvider: StorageProvider = FakeStorageProvider()
        override val transportProvider: TransportProvider = FakeTransportProvider()
        override val schedulerProvider: SchedulerProvider? = null
        override val connectivityProvider: ConnectivityProvider? = null
        override val queueProvider: QueueProvider? = queue
    }

    private fun testError(code: String): DataLoomError = TestAdmissionError(ErrorCode(code))

    private data class TestAdmissionError(
        override val code: ErrorCode,
        override val message: String = "test durable-admission failure",
        override val category: ErrorCategory = ErrorCategory.NETWORK,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val cause: Throwable? = null,
    ) : DataLoomError

    private class FixedDataLoomClock(private val instant: DataLoomInstant) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
    }

    private class FakeEncoder(
        private val rejection: DataLoomError? = null,
    ) : QueuedSynchronizationWorkEncoder {
        override fun encode(
            submission: QueuedSynchronizationSubmission,
        ): QueuedSynchronizationWorkEncodingResult {
            rejection?.let { return QueuedSynchronizationWorkEncodingResult.Rejected(it) }
            return QueuedSynchronizationWorkEncodingResult.Encoded(
                QueueEnqueueRequest(
                    entry = QueueEntry(
                        id = submission.queueEntryId,
                        synchronizationRequest = submission.work.request,
                        state = QueueEntryState.PENDING,
                        enqueuedAt = submission.availableAt,
                        availableAt = submission.availableAt,
                        workflowTimeoutState = submission.workflowTimeoutState,
                        strategyDecision = submission.work.strategyDecision,
                        strategyPlan = submission.work.strategyPlan,
                    ),
                ),
            )
        }
    }

    private class FakeQueueProvider(
        private val enqueueResult: ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit),
    ) : QueueProvider {
        var enqueueCalls: Int = 0
            private set
        var lastRequest: QueueEnqueueRequest? = null
            private set

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("durable-admission-queue"),
            name = ProviderName("Durable Admission Queue"),
            type = ProviderType.QUEUE,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun enqueue(request: QueueEnqueueRequest): ProviderOperationResult<Unit> {
            enqueueCalls++
            lastRequest = request
            return enqueueResult
        }

        override suspend fun acquire(
            request: QueueAcquireRequest,
        ): ProviderOperationResult<QueueAcquireResult> =
            ProviderOperationResult.Success(QueueAcquireResult.NoEntries)

        override suspend fun complete(request: QueueCompletionRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun reschedule(request: QueueRescheduleRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun defer(request: QueueDeferralRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun fail(request: QueueFailureRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun cancel(request: QueueCancellationRequest): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun recoverExpiredLeases(
            request: ExpiredLeaseRecoveryRequest,
        ): ProviderOperationResult<ExpiredLeaseRecoveryResult> =
            ProviderOperationResult.Success(ExpiredLeaseRecoveryResult(recoveredEntries = 0))
    }

    private class FakeTransportProvider : TransportProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("durable-admission-transport"),
            name = ProviderName("Durable Admission Transport"),
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
        ): ProviderOperationResult<io.dataloom.api.synchronization.ChangeSetAcknowledgement> =
            ProviderOperationResult.Failure(TestAdmissionError(ErrorCode("PUSH_UNUSED")))

        override suspend fun pullChanges(
            request: PullChangesRequest,
        ): ProviderOperationResult<PullChangesResult> =
            ProviderOperationResult.Failure(TestAdmissionError(ErrorCode("PULL_UNUSED")))
    }

    private class FakeStorageProvider : StorageProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("durable-admission-storage"),
            name = ProviderName("Durable Admission Storage"),
            type = ProviderType.STORAGE,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

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
        ): ProviderOperationResult<SynchronizationCheckpoint?> = ProviderOperationResult.Success(null)

        override suspend fun writeCheckpoint(
            request: CheckpointWriteRequest,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)
    }

    private companion object {
        fun runtimeDependencies(clock: DataLoomClock): RuntimeDependencies =
            RuntimeDependencies(
                clock = clock,
                identifiers = RuntimeIdentifierGenerators(
                    synchronizationEventIds = generator { SynchronizationEventId("durable-admission-event") },
                    queueEntryIds = generator { QueueEntryId("durable-admission-queue-entry") },
                    queueLeaseIds = generator { QueueLeaseId("durable-admission-queue-lease") },
                    conflictIds = generator { ConflictId("durable-admission-conflict") },
                ),
            )

        fun <T> generator(block: () -> T): IdentifierGenerator<T> =
            object : IdentifierGenerator<T> {
                override fun generate(): T = block()
            }
    }
}
