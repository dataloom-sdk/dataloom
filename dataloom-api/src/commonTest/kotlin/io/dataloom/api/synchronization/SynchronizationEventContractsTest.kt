package io.dataloom.api.synchronization

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.EntityReference
import io.dataloom.api.conflict.ConflictType
import io.dataloom.api.conflict.SynchronizationConflict
import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.context.ExecutionContext
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
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.ChangeOperation
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SynchronizationEventContractsTest {

    // -------------------------------------------------------------------------
    // SynchronizationEvent.Started tests
    // -------------------------------------------------------------------------

    @Test
    fun `started preserves id, request, occurredAt`() {
        val id: SynchronizationEventId = SynchronizationEventId("event-001")
        val request: SynchronizationRequest = sampleRequest()
        val occurredAt: DataLoomInstant = sampleInstant()

        val event: SynchronizationEvent.Started = SynchronizationEvent.Started(
            id = id,
            request = request,
            occurredAt = occurredAt,
        )

        assertEquals(id, event.id)
        assertEquals(request, event.request)
        assertEquals(occurredAt, event.occurredAt)
    }

    @Test
    fun `started defaults metadata to empty`() {
        val event: SynchronizationEvent.Started = SynchronizationEvent.Started(
            id = SynchronizationEventId("event-001"),
            request = sampleRequest(),
            occurredAt = sampleInstant(),
        )

        assertEquals(DataLoomMetadata.Empty, event.metadata)
        assertTrue(event.metadata.isEmpty())
    }

    @Test
    fun `started preserves supplied metadata`() {
        val metadata: DataLoomMetadata = DataLoomMetadata.of(mapOf("source" to "example"))
        val event: SynchronizationEvent.Started = SynchronizationEvent.Started(
            id = SynchronizationEventId("event-001"),
            request = sampleRequest(),
            occurredAt = sampleInstant(),
            metadata = metadata,
        )

        assertEquals(metadata, event.metadata)
    }

    @Test
    fun `equal started events compare as equal`() {
        val id: SynchronizationEventId = SynchronizationEventId("event-001")
        val request: SynchronizationRequest = sampleRequest()
        val occurredAt: DataLoomInstant = sampleInstant()

        val first: SynchronizationEvent.Started = SynchronizationEvent.Started(
            id = id,
            request = request,
            occurredAt = occurredAt,
        )
        val second: SynchronizationEvent.Started = SynchronizationEvent.Started(
            id = id,
            request = request,
            occurredAt = occurredAt,
        )

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    // -------------------------------------------------------------------------
    // SynchronizationEvent.PhaseChanged tests
    // -------------------------------------------------------------------------

    @Test
    fun `phase changed preserves phase`() {
        val event: SynchronizationEvent.PhaseChanged = SynchronizationEvent.PhaseChanged(
            id = SynchronizationEventId("event-002"),
            request = sampleRequest(),
            occurredAt = sampleInstant(),
            phase = SynchronizationPhase.PUSHING,
        )

        assertEquals(SynchronizationPhase.PUSHING, event.phase)
    }

    @Test
    fun `phase changed defaults metadata to empty`() {
        val event: SynchronizationEvent.PhaseChanged = SynchronizationEvent.PhaseChanged(
            id = SynchronizationEventId("event-002"),
            request = sampleRequest(),
            occurredAt = sampleInstant(),
            phase = SynchronizationPhase.VALIDATING,
        )

        assertEquals(DataLoomMetadata.Empty, event.metadata)
    }

    @Test
    fun `phase changed events with different phases are not equal`() {
        val id: SynchronizationEventId = SynchronizationEventId("event-002")
        val request: SynchronizationRequest = sampleRequest()
        val occurredAt: DataLoomInstant = sampleInstant()

        val first: SynchronizationEvent.PhaseChanged = SynchronizationEvent.PhaseChanged(
            id = id,
            request = request,
            occurredAt = occurredAt,
            phase = SynchronizationPhase.PUSHING,
        )
        val second: SynchronizationEvent.PhaseChanged = SynchronizationEvent.PhaseChanged(
            id = id,
            request = request,
            occurredAt = occurredAt,
            phase = SynchronizationPhase.PULLING,
        )

        assertNotEquals(first, second)
    }

    // -------------------------------------------------------------------------
    // SynchronizationEvent.ProgressUpdated tests
    // -------------------------------------------------------------------------

    @Test
    fun `progress updated preserves progress`() {
        val progress: SynchronizationProgress = SynchronizationProgress(
            phase = SynchronizationPhase.PUSHING,
            completed = 5L,
            total = 10L,
            unit = SynchronizationProgressUnit.EVENTS,
        )
        val event: SynchronizationEvent.ProgressUpdated = SynchronizationEvent.ProgressUpdated(
            id = SynchronizationEventId("event-003"),
            request = sampleRequest(),
            occurredAt = sampleInstant(),
            progress = progress,
        )

        assertEquals(progress, event.progress)
    }

    @Test
    fun `progress updated defaults metadata to empty`() {
        val event: SynchronizationEvent.ProgressUpdated = SynchronizationEvent.ProgressUpdated(
            id = SynchronizationEventId("event-003"),
            request = sampleRequest(),
            occurredAt = sampleInstant(),
            progress = SynchronizationProgress(
                phase = SynchronizationPhase.PUSHING,
                completed = 0L,
                total = null,
                unit = SynchronizationProgressUnit.EVENTS,
            ),
        )

        assertEquals(DataLoomMetadata.Empty, event.metadata)
    }

    // -------------------------------------------------------------------------
    // SynchronizationEvent.RetryScheduled tests
    // -------------------------------------------------------------------------

    @Test
    fun `retry scheduled preserves attempt, delay, error`() {
        val attempt: RetryAttempt = RetryAttempt(1)
        val delay: SchedulingDelay = SchedulingDelay(5_000L)
        val error: DataLoomError = sampleError()

        val event: SynchronizationEvent.RetryScheduled = SynchronizationEvent.RetryScheduled(
            id = SynchronizationEventId("event-004"),
            request = sampleRequest(),
            occurredAt = sampleInstant(),
            attempt = attempt,
            delay = delay,
            error = error,
        )

        assertEquals(attempt, event.attempt)
        assertEquals(delay, event.delay)
        assertEquals(error, event.error)
    }

    @Test
    fun `retry scheduled defaults metadata to empty`() {
        val event: SynchronizationEvent.RetryScheduled = SynchronizationEvent.RetryScheduled(
            id = SynchronizationEventId("event-004"),
            request = sampleRequest(),
            occurredAt = sampleInstant(),
            attempt = RetryAttempt(1),
            delay = SchedulingDelay.ZERO,
            error = sampleError(),
        )

        assertEquals(DataLoomMetadata.Empty, event.metadata)
    }

    // -------------------------------------------------------------------------
    // SynchronizationEvent.ConflictDetected tests
    // -------------------------------------------------------------------------

    @Test
    fun `conflict detected preserves conflict`() {
        val conflict: SynchronizationConflict = sampleConflict()
        val event: SynchronizationEvent.ConflictDetected = SynchronizationEvent.ConflictDetected(
            id = SynchronizationEventId("event-005"),
            request = sampleRequest(),
            occurredAt = sampleInstant(),
            conflict = conflict,
        )

        assertEquals(conflict, event.conflict)
    }

    @Test
    fun `conflict detected defaults metadata to empty`() {
        val event: SynchronizationEvent.ConflictDetected = SynchronizationEvent.ConflictDetected(
            id = SynchronizationEventId("event-005"),
            request = sampleRequest(),
            occurredAt = sampleInstant(),
            conflict = sampleConflict(),
        )

        assertEquals(DataLoomMetadata.Empty, event.metadata)
    }

    // -------------------------------------------------------------------------
    // SynchronizationEvent.Completed tests
    // -------------------------------------------------------------------------

    @Test
    fun `completed preserves result`() {
        val request: SynchronizationRequest = sampleRequest()
        val result: SynchronizationResult = SynchronizationResult.Succeeded(
            request = request,
            completedAt = sampleInstant(1_000_000L),
            summary = emptySummary(),
        )
        val event: SynchronizationEvent.Completed = SynchronizationEvent.Completed(
            id = SynchronizationEventId("event-006"),
            request = request,
            occurredAt = sampleInstant(1_000_000L),
            result = result,
        )

        assertEquals(result, event.result)
    }

    @Test
    fun `completed defaults metadata to empty`() {
        val request: SynchronizationRequest = sampleRequest()
        val event: SynchronizationEvent.Completed = SynchronizationEvent.Completed(
            id = SynchronizationEventId("event-006"),
            request = request,
            occurredAt = sampleInstant(1_000_000L),
            result = SynchronizationResult.Succeeded(
                request = request,
                completedAt = sampleInstant(1_000_000L),
                summary = emptySummary(),
            ),
        )

        assertEquals(DataLoomMetadata.Empty, event.metadata)
    }

    @Test
    fun `completed rejects request not matching result request`() {
        val requestA: SynchronizationRequest = SynchronizationRequest(
            workflowId = WorkflowId("workflow-001"),
            sessionId = SynchronizationSessionId("session-001"),
            direction = SynchronizationDirection.BIDIRECTIONAL,
            mode = SynchronizationMode.DELTA,
            context = ExecutionContext(
                executionId = ExecutionId("execution-001"),
                correlationId = CorrelationId("corr-001"),
            ),
        )
        val requestB: SynchronizationRequest = SynchronizationRequest(
            workflowId = WorkflowId("workflow-002"),
            sessionId = SynchronizationSessionId("session-002"),
            direction = SynchronizationDirection.BIDIRECTIONAL,
            mode = SynchronizationMode.DELTA,
            context = ExecutionContext(
                executionId = ExecutionId("execution-002"),
                correlationId = CorrelationId("corr-002"),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            SynchronizationEvent.Completed(
                id = SynchronizationEventId("event-006"),
                request = requestA,
                occurredAt = sampleInstant(1_000_000L),
                result = SynchronizationResult.Succeeded(
                    request = requestB,
                    completedAt = sampleInstant(1_000_000L),
                    summary = emptySummary(),
                ),
            )
        }
    }

    @Test
    fun `completed rejects occurredAt earlier than result completedAt`() {
        val request: SynchronizationRequest = sampleRequest()

        assertFailsWith<IllegalArgumentException> {
            SynchronizationEvent.Completed(
                id = SynchronizationEventId("event-006"),
                request = request,
                occurredAt = DataLoomInstant(999_999L),
                result = SynchronizationResult.Succeeded(
                    request = request,
                    completedAt = DataLoomInstant(1_000_000L),
                    summary = emptySummary(),
                ),
            )
        }
    }

    @Test
    fun `completed allows occurredAt equal to result completedAt`() {
        val request: SynchronizationRequest = sampleRequest()
        val instant: DataLoomInstant = sampleInstant(1_000_000L)

        val event: SynchronizationEvent.Completed = SynchronizationEvent.Completed(
            id = SynchronizationEventId("event-006"),
            request = request,
            occurredAt = instant,
            result = SynchronizationResult.Succeeded(
                request = request,
                completedAt = instant,
                summary = emptySummary(),
            ),
        )

        assertEquals(instant, event.occurredAt)
    }

    @Test
    fun `completed allows occurredAt after result completedAt`() {
        val request: SynchronizationRequest = sampleRequest()

        val event: SynchronizationEvent.Completed = SynchronizationEvent.Completed(
            id = SynchronizationEventId("event-006"),
            request = request,
            occurredAt = DataLoomInstant(1_000_001L),
            result = SynchronizationResult.Succeeded(
                request = request,
                completedAt = DataLoomInstant(1_000_000L),
                summary = emptySummary(),
            ),
        )

        assertEquals(1_000_001L, event.occurredAt.epochMilliseconds)
    }

    // -------------------------------------------------------------------------
    // Sealed exhaustiveness
    // -------------------------------------------------------------------------

    @Test
    fun `all event variants are reachable through when expression`() {
        val request: SynchronizationRequest = sampleRequest()
        val variants: List<SynchronizationEvent> = listOf(
            SynchronizationEvent.Started(
                id = SynchronizationEventId("event-001"),
                request = request,
                occurredAt = sampleInstant(),
            ),
            SynchronizationEvent.PhaseChanged(
                id = SynchronizationEventId("event-002"),
                request = request,
                occurredAt = sampleInstant(),
                phase = SynchronizationPhase.PUSHING,
            ),
            SynchronizationEvent.ProgressUpdated(
                id = SynchronizationEventId("event-003"),
                request = request,
                occurredAt = sampleInstant(),
                progress = SynchronizationProgress(
                    phase = SynchronizationPhase.PUSHING,
                    completed = 0L,
                    total = null,
                    unit = SynchronizationProgressUnit.EVENTS,
                ),
            ),
            SynchronizationEvent.RetryScheduled(
                id = SynchronizationEventId("event-004"),
                request = request,
                occurredAt = sampleInstant(),
                attempt = RetryAttempt(1),
                delay = SchedulingDelay.ZERO,
                error = sampleError(),
            ),
            SynchronizationEvent.ConflictDetected(
                id = SynchronizationEventId("event-005"),
                request = request,
                occurredAt = sampleInstant(),
                conflict = sampleConflict(),
            ),
            SynchronizationEvent.Completed(
                id = SynchronizationEventId("event-006"),
                request = request,
                occurredAt = sampleInstant(1_000_000L),
                result = SynchronizationResult.Succeeded(
                    request = request,
                    completedAt = sampleInstant(1_000_000L),
                    summary = emptySummary(),
                ),
            ),
        )

        val matched: List<String> = variants.map { event: SynchronizationEvent ->
            when (event) {
                is SynchronizationEvent.Started -> "started"
                is SynchronizationEvent.PhaseChanged -> "phase-changed"
                is SynchronizationEvent.ProgressUpdated -> "progress-updated"
                is SynchronizationEvent.RetryScheduled -> "retry-scheduled"
                is SynchronizationEvent.ConflictDetected -> "conflict-detected"
                is SynchronizationEvent.Completed -> "completed"
            }
        }

        assertEquals(
            listOf("started", "phase-changed", "progress-updated", "retry-scheduled", "conflict-detected", "completed"),
            matched,
        )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun sampleRequest(): SynchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("workflow-001"),
        sessionId = SynchronizationSessionId("session-001"),
        direction = SynchronizationDirection.BIDIRECTIONAL,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("execution-001"),
            correlationId = CorrelationId("corr-001"),
        ),
    )

    private fun sampleInstant(ms: Long = 1_000_000L): DataLoomInstant = DataLoomInstant(ms)

    private fun emptySummary(): SynchronizationSummary = SynchronizationSummary()

    private fun sampleError(): DataLoomError = TestDataLoomError(
        code = ErrorCode("DL-TEST-001"),
        category = ErrorCategory.PROVIDER,
        severity = ErrorSeverity.ERROR,
        recoverability = Recoverability.RECOVERABLE,
        message = "Test error.",
        cause = null,
    )

    private fun sampleConflict(): SynchronizationConflict {
        val invoiceRef: EntityReference = EntityReference(
            type = EntityType("invoice"),
            id = EntityId("entity-001"),
        )
        val localEvent: ChangeEvent = ChangeEvent(
            id = ChangeEventId("event-local"),
            entity = invoiceRef,
            operation = ChangeOperation.UPDATE,
        )
        val remoteEvent: ChangeEvent = ChangeEvent(
            id = ChangeEventId("event-remote"),
            entity = invoiceRef,
            operation = ChangeOperation.UPDATE,
        )
        return SynchronizationConflict(
            id = ConflictId("conflict-001"),
            type = ConflictType.CONCURRENT_CHANGE,
            entity = invoiceRef,
            localChange = localEvent,
            remoteChange = remoteEvent,
        )
    }

    private data class TestDataLoomError(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val severity: ErrorSeverity,
        override val recoverability: Recoverability,
        override val message: String,
        override val cause: Throwable?,
    ) : DataLoomError
}
