package io.dataloom.runtime.observation.operational

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.EntityReference
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.conflict.ConflictType
import io.dataloom.api.conflict.SynchronizationConflict
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.SynchronizationEventId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.TenantId
import io.dataloom.api.identifier.TraceId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.ChangeOperation
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.operational.OperationalEventCategory
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.synchronization.SynchronizationEvent
import io.dataloom.api.synchronization.SynchronizationPhase
import io.dataloom.api.synchronization.SynchronizationProgress
import io.dataloom.api.synchronization.SynchronizationProgressUnit
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.synchronization.SynchronizationSkipReason
import io.dataloom.api.synchronization.SynchronizationSummary
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves [SynchronizationOperationalEventBridge] maps every
 * [SynchronizationEvent] subtype to a sane [io.dataloom.api.operational.OperationalEventEnvelope],
 * and that field classification never leaks a raw sensitive value.
 */
class SynchronizationOperationalEventBridgeTest {

    private data class FakeError(
        override val code: ErrorCode = ErrorCode("DL-FAKE"),
        override val category: ErrorCategory = ErrorCategory.NETWORK,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "raw-sensitive-message-should-never-appear",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private fun request(
        sessionIdValue: String = "session-value-should-be-masked",
        tenantId: TenantId? = TenantId("tenant-001"),
        traceId: TraceId? = TraceId("trace-001"),
    ): SynchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("workflow-001"),
        sessionId = SynchronizationSessionId(sessionIdValue),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("execution-001"),
            correlationId = CorrelationId("correlation-001"),
            traceId = traceId,
            tenantId = tenantId,
        ),
    )

    private fun startedEvent(
        idValue: String = "sync-event-1",
        req: SynchronizationRequest = request(),
    ): SynchronizationEvent.Started = SynchronizationEvent.Started(
        id = SynchronizationEventId(idValue),
        request = req,
        occurredAt = DataLoomInstant(5_000L),
    )

    // -------------------------------------------------------------------------
    // Envelope identity/routing fields
    // -------------------------------------------------------------------------

    @Test
    fun toEnvelope_reusesEventOccurredAt_neverReadsAClock() {
        val event = startedEvent()
        val envelope = SynchronizationOperationalEventBridge.toEnvelope(event)
        assertEquals(event.occurredAt, envelope.occurredAt)
    }

    @Test
    fun toEnvelope_reusesRequestContextCorrelationTraceTenantIdentity() {
        val req = request(tenantId = TenantId("tenant-xyz"), traceId = TraceId("trace-xyz"))
        val event = startedEvent(req = req)
        val envelope = SynchronizationOperationalEventBridge.toEnvelope(event)
        assertEquals(req.context.correlationId, envelope.correlationId)
        assertEquals(req.context.traceId, envelope.traceId)
        assertEquals(req.context.tenantId, envelope.tenantId)
        assertEquals(req.workflowId, envelope.workflowId)
    }

    @Test
    fun toEnvelope_omitsOptionalTraceAndTenantWhenAbsent() {
        val req = request(tenantId = null, traceId = null)
        val event = startedEvent(req = req)
        val envelope = SynchronizationOperationalEventBridge.toEnvelope(event)
        assertNull(envelope.traceId)
        assertNull(envelope.tenantId)
    }

    @Test
    fun toEnvelope_isPureAndDeterministic() {
        val event = startedEvent()
        val first = SynchronizationOperationalEventBridge.toEnvelope(event)
        val second = SynchronizationOperationalEventBridge.toEnvelope(event)
        assertEquals(first, second)
    }

    // -------------------------------------------------------------------------
    // Event-id derivation
    // -------------------------------------------------------------------------

    @Test
    fun toEnvelope_sanitizesDisallowedCharactersInEventId() {
        val event = startedEvent(idValue = "event 1!#weird")
        val envelope = SynchronizationOperationalEventBridge.toEnvelope(event)
        assertTrue(envelope.id.value.none { it == ' ' || it == '!' || it == '#' })
        assertTrue(envelope.id.value.isNotBlank())
    }

    @Test
    fun toEnvelope_boundsSanitizedEventIdLength() {
        val longId = "a".repeat(500)
        val event = startedEvent(idValue = longId)
        val envelope = SynchronizationOperationalEventBridge.toEnvelope(event)
        assertTrue(envelope.id.value.length <= 128)
    }

    @Test
    fun toEnvelope_distinctEventIdsProduceDistinctEnvelopeIds() {
        val first = SynchronizationOperationalEventBridge.toEnvelope(startedEvent(idValue = "sync-event-1"))
        val second = SynchronizationOperationalEventBridge.toEnvelope(startedEvent(idValue = "sync-event-2"))
        assertTrue(first.id != second.id)
    }

    // -------------------------------------------------------------------------
    // Per-variant type/category mapping
    // -------------------------------------------------------------------------

    @Test
    fun started_mapsToLifecycleCategoryAndStableType() {
        val envelope = SynchronizationOperationalEventBridge.toEnvelope(startedEvent())
        assertEquals("dataloom.synchronization.started", envelope.type.value)
        assertEquals(OperationalEventCategory.LIFECYCLE, envelope.category)
    }

    @Test
    fun phaseChanged_mapsToLifecycleCategoryAndCarriesPhaseAttribute() {
        val event = SynchronizationEvent.PhaseChanged(
            id = SynchronizationEventId("evt-phase"),
            request = request(),
            occurredAt = DataLoomInstant(6_000L),
            phase = SynchronizationPhase.PUSHING,
        )
        val envelope = SynchronizationOperationalEventBridge.toEnvelope(event)
        assertEquals("dataloom.synchronization.phase_changed", envelope.type.value)
        assertEquals(OperationalEventCategory.LIFECYCLE, envelope.category)
        assertEquals("PUSHING", envelope.attributes["phase"])
    }

    @Test
    fun progressUpdated_mapsToTelemetryCategoryAndOmitsNullTotal() {
        val event = SynchronizationEvent.ProgressUpdated(
            id = SynchronizationEventId("evt-progress"),
            request = request(),
            occurredAt = DataLoomInstant(7_000L),
            progress = SynchronizationProgress(
                phase = SynchronizationPhase.READING_OUTBOUND,
                completed = 3L,
                total = null,
                unit = SynchronizationProgressUnit.EVENTS,
            ),
        )
        val envelope = SynchronizationOperationalEventBridge.toEnvelope(event)
        assertEquals(OperationalEventCategory.TELEMETRY, envelope.category)
        assertEquals("3", envelope.attributes["progress.completed"])
        assertNull(envelope.attributes["progress.total"])
        assertEquals("EVENTS", envelope.attributes["progress.unit"])
    }

    @Test
    fun progressUpdated_includesTotalWhenPresent() {
        val event = SynchronizationEvent.ProgressUpdated(
            id = SynchronizationEventId("evt-progress-2"),
            request = request(),
            occurredAt = DataLoomInstant(7_100L),
            progress = SynchronizationProgress(
                phase = SynchronizationPhase.PULLING,
                completed = 4L,
                total = 10L,
                unit = SynchronizationProgressUnit.BYTES,
            ),
        )
        val envelope = SynchronizationOperationalEventBridge.toEnvelope(event)
        assertEquals("10", envelope.attributes["progress.total"])
    }

    @Test
    fun retryScheduled_mapsToDiagnosticCategoryAndRemovesErrorMessage() {
        val event = SynchronizationEvent.RetryScheduled(
            id = SynchronizationEventId("evt-retry"),
            request = request(),
            occurredAt = DataLoomInstant(8_000L),
            attempt = RetryAttempt(2),
            delay = SchedulingDelay(1_500L),
            error = FakeError(),
        )
        val envelope = SynchronizationOperationalEventBridge.toEnvelope(event)
        assertEquals("dataloom.synchronization.retry_scheduled", envelope.type.value)
        assertEquals(OperationalEventCategory.DIAGNOSTIC, envelope.category)
        assertEquals("2", envelope.attributes["retry.attemptNumber"])
        assertEquals("1500", envelope.attributes["retry.delayMilliseconds"])
        assertEquals("DL-FAKE", envelope.attributes["retry.error.code"])
        assertEquals("NETWORK", envelope.attributes["retry.error.category"])
        // CONFIDENTIAL fields are removed outright, never merely masked.
        assertNull(envelope.attributes["retry.error.message"])
    }

    @Test
    fun conflictDetected_mapsToDiagnosticCategoryAndMasksEntityIdentity() {
        val entity = EntityReference(type = EntityType("Widget"), id = EntityId("entity-secret-001"))
        val local = ChangeEvent(id = ChangeEventId("ce-local"), entity = entity, operation = ChangeOperation.UPDATE)
        val remote = ChangeEvent(id = ChangeEventId("ce-remote"), entity = entity, operation = ChangeOperation.DELETE)
        val conflict = SynchronizationConflict(
            id = ConflictId("conflict-001"),
            type = ConflictType.UPDATE_DELETE,
            entity = entity,
            localChange = local,
            remoteChange = remote,
        )
        val event = SynchronizationEvent.ConflictDetected(
            id = SynchronizationEventId("evt-conflict"),
            request = request(),
            occurredAt = DataLoomInstant(9_000L),
            conflict = conflict,
        )
        val envelope = SynchronizationOperationalEventBridge.toEnvelope(event)
        assertEquals(OperationalEventCategory.DIAGNOSTIC, envelope.category)
        assertEquals("UPDATE_DELETE", envelope.attributes["conflict.type"])
        assertEquals("UPDATE", envelope.attributes["conflict.localOperation"])
        assertEquals("DELETE", envelope.attributes["conflict.remoteOperation"])
        // INTERNAL fields are masked with the redactor's constant mask, never the raw value.
        assertEquals("[REDACTED]", envelope.attributes["conflict.entityId"])
        assertEquals("[REDACTED]", envelope.attributes["conflict.entityType"])
        assertTrue(envelope.attributes["conflict.entityId"] != entity.id.value)
    }

    @Test
    fun completed_succeeded_mapsToLifecycleCategoryWithSummaryCountsAndNoErrorFields() {
        val req = request()
        val event = SynchronizationEvent.Completed(
            id = SynchronizationEventId("evt-completed-ok"),
            request = req,
            occurredAt = DataLoomInstant(10_000L),
            result = SynchronizationResult.Succeeded(
                request = req,
                completedAt = DataLoomInstant(9_999L),
                summary = SynchronizationSummary(outboundEventsRead = 5L, outboundEventsAccepted = 5L),
            ),
        )
        val envelope = SynchronizationOperationalEventBridge.toEnvelope(event)
        assertEquals(OperationalEventCategory.LIFECYCLE, envelope.category)
        assertEquals("SUCCEEDED", envelope.attributes["result.kind"])
        assertEquals("5", envelope.attributes["summary.outboundEventsRead"])
        assertEquals("5", envelope.attributes["summary.outboundEventsAccepted"])
        assertNull(envelope.attributes["result.error.code"])
    }

    @Test
    fun completed_failed_includesErrorCodeButRemovesMessage() {
        val req = request()
        val event = SynchronizationEvent.Completed(
            id = SynchronizationEventId("evt-completed-failed"),
            request = req,
            occurredAt = DataLoomInstant(11_000L),
            result = SynchronizationResult.Failed(
                request = req,
                completedAt = DataLoomInstant(10_999L),
                summary = SynchronizationSummary(),
                error = FakeError(code = ErrorCode("DL-BOOM")),
            ),
        )
        val envelope = SynchronizationOperationalEventBridge.toEnvelope(event)
        assertEquals("FAILED", envelope.attributes["result.kind"])
        assertEquals("DL-BOOM", envelope.attributes["result.error.code"])
        assertNull(envelope.attributes["result.error.message"])
    }

    @Test
    fun completed_skipped_includesSkipReason() {
        val req = request()
        val event = SynchronizationEvent.Completed(
            id = SynchronizationEventId("evt-completed-skipped"),
            request = req,
            occurredAt = DataLoomInstant(12_000L),
            result = SynchronizationResult.Skipped(
                request = req,
                completedAt = DataLoomInstant(11_999L),
                summary = SynchronizationSummary(),
                reason = SynchronizationSkipReason.NO_CHANGES,
            ),
        )
        val envelope = SynchronizationOperationalEventBridge.toEnvelope(event)
        assertEquals("SKIPPED", envelope.attributes["result.kind"])
        assertEquals("NO_CHANGES", envelope.attributes["result.skipReason"])
    }

    @Test
    fun completed_partiallySucceeded_includesErrorCountOnly() {
        val req = request()
        val event = SynchronizationEvent.Completed(
            id = SynchronizationEventId("evt-completed-partial"),
            request = req,
            occurredAt = DataLoomInstant(13_000L),
            result = SynchronizationResult.PartiallySucceeded(
                request = req,
                completedAt = DataLoomInstant(12_999L),
                summary = SynchronizationSummary(),
                errors = listOf(FakeError(), FakeError(code = ErrorCode("DL-SECOND"))),
            ),
        )
        val envelope = SynchronizationOperationalEventBridge.toEnvelope(event)
        assertEquals("PARTIALLY_SUCCEEDED", envelope.attributes["result.kind"])
        assertEquals("2", envelope.attributes["result.errorCount"])
        assertNull(envelope.attributes["result.error.code"])
    }

    // -------------------------------------------------------------------------
    // Common request attributes / classification
    // -------------------------------------------------------------------------

    @Test
    fun commonRequestAttributes_directionModePriorityArePublicSessionIsMasked() {
        val req = request(sessionIdValue = "raw-session-id-should-be-masked")
        val envelope = SynchronizationOperationalEventBridge.toEnvelope(startedEvent(req = req))
        assertEquals("PUSH", envelope.attributes["request.direction"])
        assertEquals("DELTA", envelope.attributes["request.mode"])
        assertEquals("NORMAL", envelope.attributes["request.priority"])
        assertEquals("[REDACTED]", envelope.attributes["request.sessionId"])
        assertTrue(envelope.attributes["request.sessionId"] != req.sessionId.value)
    }
}
