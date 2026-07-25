package io.dataloom.testing

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.ChangeSet
import io.dataloom.api.change.EntityReference
import io.dataloom.api.conflict.ConflictDetectionRequest
import io.dataloom.api.conflict.ConflictResolutionRequest
import io.dataloom.api.conflict.ConflictType
import io.dataloom.api.conflict.SynchronizationConflict
import io.dataloom.api.connectivity.ConnectivityCheckRequest
import io.dataloom.api.connectivity.ConnectivitySnapshot
import io.dataloom.api.connectivity.ConnectivityStatus
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ChangeSetId
import io.dataloom.api.identifier.CheckpointKey
import io.dataloom.api.identifier.CheckpointToken
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.ConflictResolverId
import io.dataloom.api.identifier.ConflictDetectorId
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.QueueConsumerId
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.RetryPolicyId
import io.dataloom.api.identifier.ScheduleId
import io.dataloom.api.identifier.SynchronizationEventId
import io.dataloom.api.identifier.SynchronizationObserverId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.ChangeOperation
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.payload.EntityVersion
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.queue.QueueAcquireRequest
import io.dataloom.api.queue.QueueCancellationRequest
import io.dataloom.api.queue.QueueCompletionRequest
import io.dataloom.api.queue.QueueEnqueueRequest
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.queue.QueueFailureDisposition
import io.dataloom.api.queue.QueueFailureRequest
import io.dataloom.api.queue.QueueLease
import io.dataloom.api.queue.QueueRescheduleRequest
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.retry.RetryDecision
import io.dataloom.api.retry.RetryEvaluationRequest
import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.retry.RetryStopReason
import io.dataloom.api.scheduling.ScheduleCancellationRequest
import io.dataloom.api.scheduling.ScheduleReceipt
import io.dataloom.api.scheduling.ScheduleRequest
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.storage.InboundChangeApplyRequest
import io.dataloom.api.storage.OutboundChangeReadRequest
import io.dataloom.api.synchronization.ChangeAcknowledgementStatus
import io.dataloom.api.synchronization.ChangeEventAcknowledgement
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.synchronization.CheckpointReadRequest
import io.dataloom.api.synchronization.CheckpointWriteRequest
import io.dataloom.api.synchronization.OutboundChangeAcknowledgementRequest
import io.dataloom.api.synchronization.SynchronizationCheckpoint
import io.dataloom.api.synchronization.SynchronizationEvent
import io.dataloom.api.synchronization.SynchronizationPhase
import io.dataloom.api.synchronization.SynchronizationProgress
import io.dataloom.api.synchronization.SynchronizationProgressUnit
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.synchronization.SynchronizationSummary
import io.dataloom.api.time.DataLoomInstant
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

internal object PendingResult

internal fun <T> runSuspend(block: suspend () -> T): T {
    var rawResult: Any? = PendingResult
    var failure: Throwable? = null
    block.startCoroutine(
        object : Continuation<T> {
            override val context: CoroutineContext = EmptyCoroutineContext

            override fun resumeWith(result: Result<T>) {
                result.fold(
                    onSuccess = { value -> rawResult = value },
                    onFailure = { throwable -> failure = throwable },
                )
            }
        },
    )
    failure?.let { throw it }
    check(rawResult !== PendingResult) { "Suspend block did not complete synchronously in test." }
    @Suppress("UNCHECKED_CAST")
    return rawResult as T
}

internal data class FakeDataLoomError(
    override val code: ErrorCode = ErrorCode("DL-TEST"),
    override val category: ErrorCategory = ErrorCategory.PROVIDER,
    override val severity: ErrorSeverity = ErrorSeverity.ERROR,
    override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE,
    override val message: String = "Test failure.",
    override val cause: Throwable? = null,
) : DataLoomError

internal fun sampleExecutionContext(suffix: String = "001"): ExecutionContext = ExecutionContext(
    executionId = ExecutionId("exec-$suffix"),
    correlationId = CorrelationId("corr-$suffix"),
)

internal fun sampleSynchronizationRequest(
    suffix: String = "001",
    direction: SynchronizationDirection = SynchronizationDirection.BIDIRECTIONAL,
): SynchronizationRequest = SynchronizationRequest(
    workflowId = WorkflowId("workflow-$suffix"),
    sessionId = SynchronizationSessionId("session-$suffix"),
    direction = direction,
    mode = SynchronizationMode.DELTA,
    context = sampleExecutionContext(suffix),
)

internal fun sampleEntityReference(
    type: EntityType = EntityType("invoice"),
    id: EntityId = EntityId("entity-001"),
    version: EntityVersion? = null,
): EntityReference = EntityReference(type = type, id = id, version = version)

internal fun sampleChangeEvent(
    id: String = "event-001",
    entity: EntityReference = sampleEntityReference(version = EntityVersion("v1")),
    operation: ChangeOperation = ChangeOperation.UPDATE,
): ChangeEvent = ChangeEvent(
    id = ChangeEventId(id),
    entity = entity,
    operation = operation,
)

internal fun sampleChangeSet(id: String = "changes-001"): ChangeSet = ChangeSet(
    id = ChangeSetId(id),
    events = listOf(sampleChangeEvent()),
)

internal fun sampleAcknowledgement(changeSetId: String = "changes-001"): ChangeSetAcknowledgement =
    ChangeSetAcknowledgement(
        changeSetId = ChangeSetId(changeSetId),
        events = listOf(
            ChangeEventAcknowledgement(
                eventId = ChangeEventId("event-001"),
                status = ChangeAcknowledgementStatus.ACCEPTED,
            ),
        ),
    )

internal fun sampleCheckpoint(
    key: String = "checkpoint-001",
    token: String = "token-001",
): SynchronizationCheckpoint = SynchronizationCheckpoint(
    key = CheckpointKey(key),
    token = CheckpointToken(token),
)

internal fun sampleOutboundReadRequest(suffix: String = "001"): OutboundChangeReadRequest = OutboundChangeReadRequest(
    request = sampleSynchronizationRequest(suffix),
)

internal fun sampleInboundApplyRequest(suffix: String = "001"): InboundChangeApplyRequest = InboundChangeApplyRequest(
    request = sampleSynchronizationRequest(suffix),
    changeSet = sampleChangeSet("changes-$suffix"),
)

internal fun sampleOutboundAcknowledgementRequest(suffix: String = "001"): OutboundChangeAcknowledgementRequest =
    OutboundChangeAcknowledgementRequest(
        request = sampleSynchronizationRequest(suffix),
        acknowledgement = sampleAcknowledgement("changes-$suffix"),
    )

internal fun sampleCheckpointReadRequest(suffix: String = "001"): CheckpointReadRequest = CheckpointReadRequest(
    request = sampleSynchronizationRequest(suffix),
    key = CheckpointKey("checkpoint-$suffix"),
)

internal fun sampleCheckpointWriteRequest(suffix: String = "001"): CheckpointWriteRequest = CheckpointWriteRequest(
    request = sampleSynchronizationRequest(suffix),
    checkpoint = sampleCheckpoint(key = "checkpoint-$suffix", token = "token-$suffix"),
)

internal fun sampleProviderDescriptor(
    type: ProviderType = ProviderType.STORAGE,
    id: String = "provider-001",
): ProviderDescriptor = ProviderDescriptor(
    id = ProviderId(id),
    name = ProviderName("Provider $id"),
    type = type,
    version = ProviderVersion("1.0.0"),
)

internal fun sampleQueueEntry(
    id: String = "entry-001",
    state: QueueEntryState = QueueEntryState.PENDING,
    enqueuedAt: Long = 1_000L,
    availableAt: Long = enqueuedAt,
    retryAttempt: RetryAttempt? = null,
    lease: QueueLease? = null,
    lastError: DataLoomError? = null,
): QueueEntry = QueueEntry(
    id = QueueEntryId(id),
    synchronizationRequest = sampleSynchronizationRequest(id),
    state = state,
    enqueuedAt = DataLoomInstant(enqueuedAt),
    availableAt = DataLoomInstant(availableAt),
    retryAttempt = retryAttempt,
    lease = lease,
    lastError = lastError,
)

internal fun sampleLease(
    id: String = "lease-001",
    consumerId: String = "consumer-001",
    acquiredAt: Long = 10_000L,
    expiresAt: Long = 20_000L,
): QueueLease = QueueLease(
    id = QueueLeaseId(id),
    consumerId = QueueConsumerId(consumerId),
    acquiredAt = DataLoomInstant(acquiredAt),
    expiresAt = DataLoomInstant(expiresAt),
)

internal fun sampleQueueEnqueueRequest(id: String = "entry-001"): QueueEnqueueRequest =
    QueueEnqueueRequest(entry = sampleQueueEntry(id = id))

internal fun sampleQueueAcquireRequest(
    acquiredAt: Long = 20_000L,
    leaseExpiresAt: Long = 30_000L,
    maxEntries: Int = 10,
): QueueAcquireRequest = QueueAcquireRequest(
    consumerId = QueueConsumerId("consumer-001"),
    leaseId = QueueLeaseId("lease-001"),
    acquiredAt = DataLoomInstant(acquiredAt),
    leaseExpiresAt = DataLoomInstant(leaseExpiresAt),
    maxEntries = maxEntries,
)

internal fun sampleQueueCompletionRequest(
    entryId: String = "entry-001",
    leaseId: String = "lease-001",
): QueueCompletionRequest = QueueCompletionRequest(
    entryId = QueueEntryId(entryId),
    leaseId = QueueLeaseId(leaseId),
    completedAt = DataLoomInstant(40_000L),
)

internal fun sampleQueueRescheduleRequest(
    entryId: String = "entry-001",
    leaseId: String = "lease-001",
): QueueRescheduleRequest = QueueRescheduleRequest(
    entryId = QueueEntryId(entryId),
    leaseId = QueueLeaseId(leaseId),
    retryAttempt = RetryAttempt(2),
    availableAt = DataLoomInstant(50_000L),
    error = FakeDataLoomError(message = "Retry later."),
)

internal fun sampleQueueFailureRequest(
    entryId: String = "entry-001",
    leaseId: String = "lease-001",
    disposition: QueueFailureDisposition = QueueFailureDisposition.FAILED,
): QueueFailureRequest = QueueFailureRequest(
    entryId = QueueEntryId(entryId),
    leaseId = QueueLeaseId(leaseId),
    error = FakeDataLoomError(message = "Queue failure."),
    disposition = disposition,
)

internal fun sampleQueueCancellationRequest(entryId: String = "entry-001"): QueueCancellationRequest =
    QueueCancellationRequest(
        entryId = QueueEntryId(entryId),
        context = sampleExecutionContext("cancel-$entryId"),
    )

internal fun sampleScheduleRequest(id: String = "schedule-001"): ScheduleRequest = ScheduleRequest(
    id = ScheduleId(id),
    synchronizationRequest = sampleSynchronizationRequest(id),
)

internal fun sampleScheduleCancellationRequest(id: String = "schedule-001"): ScheduleCancellationRequest =
    ScheduleCancellationRequest(
        id = ScheduleId(id),
        context = sampleExecutionContext("schedule-$id"),
    )

internal fun sampleScheduleReceipt(id: String = "schedule-001"): ScheduleReceipt = ScheduleReceipt(
    id = ScheduleId(id),
)

internal fun sampleConnectivitySnapshot(
    status: ConnectivityStatus = ConnectivityStatus.AVAILABLE,
    isMetered: Boolean? = false,
): ConnectivitySnapshot = ConnectivitySnapshot(
    status = status,
    isMetered = isMetered,
)

internal fun sampleConnectivityCheckRequest(suffix: String = "001"): ConnectivityCheckRequest =
    ConnectivityCheckRequest(context = sampleExecutionContext("connectivity-$suffix"))

internal fun sampleRetryDecisionRetry(delayMillis: Long = 1_000L): RetryDecision =
    RetryDecision.Retry(delay = SchedulingDelay(delayMillis))

internal fun sampleRetryDecisionStop(reason: RetryStopReason = RetryStopReason.POLICY_REJECTED): RetryDecision =
    RetryDecision.Stop(reason = reason)

internal fun sampleRetryEvaluationRequest(suffix: String = "001"): RetryEvaluationRequest = RetryEvaluationRequest(
    synchronizationRequest = sampleSynchronizationRequest(suffix),
    operation = RetryOperation("transport.push"),
    error = FakeDataLoomError(message = "Retry me."),
    attempt = RetryAttempt(1),
    previousDelay = null,
    provider = sampleProviderDescriptor(ProviderType.TRANSPORT, id = "transport-$suffix"),
)

internal fun sampleConflict(
    conflictId: String = "conflict-001",
    entityId: String = "entity-001",
): SynchronizationConflict {
    val entity = sampleEntityReference(
        id = EntityId(entityId),
        version = EntityVersion("v1"),
    )
    return SynchronizationConflict(
        id = ConflictId(conflictId),
        type = ConflictType.CONCURRENT_CHANGE,
        entity = EntityReference(entity.type, entity.id),
        localChange = sampleChangeEvent(
            id = "$conflictId-local",
            entity = entity,
            operation = ChangeOperation.UPDATE,
        ),
        remoteChange = sampleChangeEvent(
            id = "$conflictId-remote",
            entity = entity.copy(version = EntityVersion("v2")),
            operation = ChangeOperation.UPDATE,
        ),
    )
}

internal fun sampleConflictDetectionRequest(suffix: String = "001"): ConflictDetectionRequest {
    val local = sampleChangeEvent(
        id = "local-$suffix",
        entity = sampleEntityReference(id = EntityId("entity-$suffix"), version = EntityVersion("v1")),
    )
    val remote = sampleChangeEvent(
        id = "remote-$suffix",
        entity = sampleEntityReference(id = EntityId("entity-$suffix"), version = EntityVersion("v2")),
    )
    return ConflictDetectionRequest(
        synchronizationRequest = sampleSynchronizationRequest(suffix),
        localChange = local,
        remoteChange = remote,
    )
}

internal fun sampleConflictResolutionRequest(suffix: String = "001"): ConflictResolutionRequest =
    ConflictResolutionRequest(
        synchronizationRequest = sampleSynchronizationRequest(suffix),
        conflict = sampleConflict(conflictId = "conflict-$suffix", entityId = "entity-$suffix"),
    )

internal fun sampleSummary(): SynchronizationSummary = SynchronizationSummary()

internal fun sampleSucceededResult(suffix: String = "001"): SynchronizationResult = SynchronizationResult.Succeeded(
    request = sampleSynchronizationRequest(suffix),
    completedAt = DataLoomInstant(100_000L),
    summary = sampleSummary(),
)

internal fun sampleStartedEvent(suffix: String = "001"): SynchronizationEvent.Started = SynchronizationEvent.Started(
    id = SynchronizationEventId("event-started-$suffix"),
    request = sampleSynchronizationRequest(suffix),
    occurredAt = DataLoomInstant(10_000L),
)

internal fun sampleProgressEvent(suffix: String = "001"): SynchronizationEvent.ProgressUpdated =
    SynchronizationEvent.ProgressUpdated(
        id = SynchronizationEventId("event-progress-$suffix"),
        request = sampleSynchronizationRequest(suffix),
        occurredAt = DataLoomInstant(20_000L),
        progress = SynchronizationProgress(
            phase = SynchronizationPhase.PUSHING,
            completed = 1L,
            total = 2L,
            unit = SynchronizationProgressUnit.EVENTS,
        ),
    )

internal fun sampleCompletedEvent(suffix: String = "001"): SynchronizationEvent.Completed {
    val request = sampleSynchronizationRequest(suffix)
    val result = SynchronizationResult.Succeeded(
        request = request,
        completedAt = DataLoomInstant(30_000L),
        summary = sampleSummary(),
    )
    return SynchronizationEvent.Completed(
        id = SynchronizationEventId("event-completed-$suffix"),
        request = request,
        occurredAt = DataLoomInstant(30_000L),
        result = result,
    )
}

internal fun retryPolicyId(value: String = "policy-001"): RetryPolicyId = RetryPolicyId(value)
internal fun conflictDetectorId(value: String = "detector-001"): ConflictDetectorId = ConflictDetectorId(value)
internal fun conflictResolverId(value: String = "resolver-001"): ConflictResolverId = ConflictResolverId(value)
internal fun observerId(value: String = "observer-001"): SynchronizationObserverId = SynchronizationObserverId(value)
