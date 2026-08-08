package io.dataloom.storage.sqldelight

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.ChangeSet
import io.dataloom.api.change.EntityReference
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ChangeSetId
import io.dataloom.api.identifier.CheckpointKey
import io.dataloom.api.identifier.CheckpointToken
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.ChangeOperation
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.payload.DataLoomPayload
import io.dataloom.api.payload.EntityVersion
import io.dataloom.api.payload.PayloadContentType
import io.dataloom.api.storage.InboundChangeApplyRequest
import io.dataloom.api.storage.OutboundChangeReadRequest
import io.dataloom.api.synchronization.ChangeAcknowledgementStatus
import io.dataloom.api.synchronization.ChangeEventAcknowledgement
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.synchronization.CheckpointReadRequest
import io.dataloom.api.synchronization.CheckpointWriteRequest
import io.dataloom.api.synchronization.OutboundChangeAcknowledgementRequest
import io.dataloom.api.synchronization.SynchronizationCheckpoint

internal fun sampleSynchronizationRequest(suffix: String): SynchronizationRequest = SynchronizationRequest(
    workflowId = WorkflowId("workflow-$suffix"),
    sessionId = SynchronizationSessionId("session-$suffix"),
    direction = SynchronizationDirection.BIDIRECTIONAL,
    mode = SynchronizationMode.DELTA,
    context = ExecutionContext(
        executionId = ExecutionId("execution-$suffix"),
        correlationId = CorrelationId("correlation-$suffix"),
    ),
)

internal fun sampleChangeSet(
    changeSetId: String,
    eventIds: List<String>,
    entityType: String = "invoice",
): ChangeSet = ChangeSet(
    id = ChangeSetId(changeSetId),
    events = eventIds.map { eventId ->
        ChangeEvent(
            id = ChangeEventId(eventId),
            entity = EntityReference(
                type = EntityType(entityType),
                id = EntityId("entity-$eventId"),
                version = EntityVersion("v1"),
            ),
            operation = ChangeOperation.UPDATE,
            payload = DataLoomPayload(
                contentType = PayloadContentType("application/json"),
                bytes = "{\"event\":\"$eventId\"}".encodeToByteArray(),
            ),
        )
    },
)

internal fun sampleOutboundReadRequest(
    suffix: String = "001",
    maxEvents: Int? = null,
): OutboundChangeReadRequest = OutboundChangeReadRequest(
    request = sampleSynchronizationRequest(suffix),
    maxEvents = maxEvents,
)

internal fun sampleInboundApplyRequest(
    suffix: String = "001",
    changeSet: ChangeSet = sampleChangeSet(changeSetId = "changes-$suffix", eventIds = listOf("event-$suffix")),
): InboundChangeApplyRequest = InboundChangeApplyRequest(
    request = sampleSynchronizationRequest(suffix),
    changeSet = changeSet,
)

internal fun sampleOutboundAcknowledgementRequest(
    suffix: String = "001",
    changeSetId: String,
    eventId: String,
    status: ChangeAcknowledgementStatus,
): OutboundChangeAcknowledgementRequest = OutboundChangeAcknowledgementRequest(
    request = sampleSynchronizationRequest(suffix),
    acknowledgement = ChangeSetAcknowledgement(
        changeSetId = ChangeSetId(changeSetId),
        events = listOf(
            ChangeEventAcknowledgement(
                eventId = ChangeEventId(eventId),
                status = status,
            ),
        ),
    ),
)

internal fun sampleCheckpointWriteRequest(
    suffix: String = "001",
    token: String = "token-$suffix",
): CheckpointWriteRequest = CheckpointWriteRequest(
    request = sampleSynchronizationRequest(suffix),
    checkpoint = SynchronizationCheckpoint(
        key = CheckpointKey("checkpoint-$suffix"),
        token = CheckpointToken(token),
    ),
)

internal fun sampleCheckpointReadRequest(
    suffix: String = "001",
): CheckpointReadRequest = CheckpointReadRequest(
    request = sampleSynchronizationRequest(suffix),
    key = CheckpointKey("checkpoint-$suffix"),
)

internal expect fun createTestSqlDelightStorageDatabase(): SqlDelightStorageDatabase
