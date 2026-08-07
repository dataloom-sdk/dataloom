package io.dataloom.runtime.observation

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.event.EventOrderingScope
import io.dataloom.api.event.EventRetentionWindow
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.SynchronizationEventId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.TenantId
import io.dataloom.api.identifier.TraceId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.operational.OperationalEventCategory
import io.dataloom.api.operational.OperationalEventEnvelope
import io.dataloom.api.operational.OperationalEventId
import io.dataloom.api.operational.OperationalEventSource
import io.dataloom.api.operational.OperationalEventType
import io.dataloom.api.operational.OperationalPayloadDescriptor
import io.dataloom.api.operational.OperationalPayloadEncoding
import io.dataloom.api.operational.OperationalPayloadType
import io.dataloom.api.operational.OperationalSchemaVersion
import io.dataloom.api.security.DataClassification
import io.dataloom.api.security.RedactedAttributes
import io.dataloom.api.synchronization.SynchronizationEvent
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.synchronization.SynchronizationSummary
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant

internal fun testEnvelope(
    id: String,
    workflowId: String = "workflow-001",
    category: OperationalEventCategory = OperationalEventCategory.LIFECYCLE,
    correlationId: String = "correlation-001",
    causationId: String? = null,
    traceId: String? = "trace-001",
    tenantId: String? = "tenant-001",
    occurredAt: Long = 1_000L,
    schemaVersion: Int = 1,
    attributes: RedactedAttributes = RedactedAttributes.Empty,
): OperationalEventEnvelope = OperationalEventEnvelope(
    id = OperationalEventId(id),
    type = OperationalEventType("dataloom.test.event"),
    source = OperationalEventSource("dataloom.test.source"),
    category = category,
    schemaVersion = OperationalSchemaVersion(schemaVersion),
    occurredAt = DataLoomInstant(occurredAt),
    correlationId = CorrelationId(correlationId),
    causationId = causationId?.let(::OperationalEventId),
    traceId = traceId?.let(::TraceId),
    tenantId = tenantId?.let(::TenantId),
    workflowId = WorkflowId(workflowId),
    payload = OperationalPayloadDescriptor(
        type = OperationalPayloadType("dataloom.test.signal"),
        schemaVersion = OperationalSchemaVersion(schemaVersion),
        encoding = OperationalPayloadEncoding("application/vnd.dataloom.signal"),
        classification = DataClassification.INTERNAL,
        encodedSizeBytes = 0L,
    ),
    attributes = attributes,
)

internal fun testRetention(
    storedAt: Long = 1_000L,
    expiresAtExclusive: Long = 5_000L,
): EventRetentionWindow = EventRetentionWindow(
    storedAt = DataLoomInstant(storedAt),
    expiresAtExclusive = DataLoomInstant(expiresAtExclusive),
)

internal fun testScope(workflowId: String = "workflow-001"): EventOrderingScope =
    EventOrderingScope.Workflow(WorkflowId(workflowId))

internal fun testSynchronizationRequest(
    workflowId: String = "workflow-001",
): SynchronizationRequest = SynchronizationRequest(
    workflowId = WorkflowId(workflowId),
    sessionId = SynchronizationSessionId("session-001"),
    direction = SynchronizationDirection.PULL,
    mode = SynchronizationMode.DELTA,
    context = ExecutionContext(
        executionId = ExecutionId("execution-001"),
        correlationId = CorrelationId("correlation-001"),
        traceId = TraceId("trace-001"),
        tenantId = TenantId("tenant-001"),
    ),
)

internal fun testStartedEvent(
    workflowId: String = "workflow-001",
): SynchronizationEvent.Started = SynchronizationEvent.Started(
    id = SynchronizationEventId("legacy-event-001"),
    request = testSynchronizationRequest(workflowId),
    occurredAt = DataLoomInstant(1_000L),
)

internal fun testCompletedEvent(
    workflowId: String = "workflow-001",
): SynchronizationEvent.Completed {
    val request: SynchronizationRequest = testSynchronizationRequest(workflowId)
    val result = SynchronizationResult.Succeeded(
        request = request,
        completedAt = DataLoomInstant(1_000L),
        summary = SynchronizationSummary(),
    )
    return SynchronizationEvent.Completed(
        id = SynchronizationEventId("legacy-event-002"),
        request = request,
        occurredAt = DataLoomInstant(1_001L),
        result = result,
    )
}

internal class MutableTestClock(
    var current: DataLoomInstant,
) : DataLoomClock {
    override fun now(): DataLoomInstant = current
}
