package io.dataloom.api.synchronization

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SynchronizationProgressContractsTest {

    // -------------------------------------------------------------------------
    // SynchronizationPhase tests
    // -------------------------------------------------------------------------

    @Test
    fun `every required phase exists`() {
        val phaseNames: Set<String> = SynchronizationPhase.entries.map { it.name }.toSet()
        val required: Set<String> = setOf(
            "VALIDATING",
            "WAITING_FOR_CONNECTIVITY",
            "READING_OUTBOUND",
            "PUSHING",
            "ACKNOWLEDGING_OUTBOUND",
            "PULLING",
            "APPLYING_INBOUND",
            "WRITING_CHECKPOINT",
            "RESOLVING_CONFLICTS",
            "WAITING_FOR_RETRY",
            "FINALIZING",
        )
        assertTrue(phaseNames.containsAll(required), "Missing phases: ${required - phaseNames}")
    }

    @Test
    fun `phases can be referenced without relying on ordinals`() {
        val validating: SynchronizationPhase = SynchronizationPhase.VALIDATING
        val finalizing: SynchronizationPhase = SynchronizationPhase.FINALIZING

        assertNotEquals(validating, finalizing)
        assertEquals(SynchronizationPhase.VALIDATING, validating)
        assertEquals(SynchronizationPhase.FINALIZING, finalizing)
    }

    // -------------------------------------------------------------------------
    // SynchronizationProgressUnit tests
    // -------------------------------------------------------------------------

    @Test
    fun `every required progress unit exists`() {
        val unitNames: Set<String> = SynchronizationProgressUnit.entries.map { it.name }.toSet()
        val required: Set<String> = setOf("EVENTS", "BYTES", "OPERATIONS", "STEPS")
        assertTrue(unitNames.containsAll(required), "Missing units: ${required - unitNames}")
    }

    @Test
    fun `progress units can be referenced without relying on ordinals`() {
        val events: SynchronizationProgressUnit = SynchronizationProgressUnit.EVENTS
        val bytes: SynchronizationProgressUnit = SynchronizationProgressUnit.BYTES

        assertNotEquals(events, bytes)
        assertEquals(SynchronizationProgressUnit.EVENTS, events)
    }

    // -------------------------------------------------------------------------
    // SynchronizationProgress tests
    // -------------------------------------------------------------------------

    @Test
    fun `progress preserves phase, completed, unit`() {
        val progress: SynchronizationProgress = SynchronizationProgress(
            phase = SynchronizationPhase.PUSHING,
            completed = 10L,
            total = 100L,
            unit = SynchronizationProgressUnit.EVENTS,
        )

        assertEquals(SynchronizationPhase.PUSHING, progress.phase)
        assertEquals(10L, progress.completed)
        assertEquals(100L, progress.total)
        assertEquals(SynchronizationProgressUnit.EVENTS, progress.unit)
    }

    @Test
    fun `progress defaults metadata to empty`() {
        val progress: SynchronizationProgress = SynchronizationProgress(
            phase = SynchronizationPhase.PULLING,
            completed = 0L,
            total = null,
            unit = SynchronizationProgressUnit.OPERATIONS,
        )

        assertEquals(DataLoomMetadata.Empty, progress.metadata)
        assertTrue(progress.metadata.isEmpty())
    }

    @Test
    fun `progress preserves supplied metadata`() {
        val metadata: DataLoomMetadata = DataLoomMetadata.of(mapOf("source" to "example"))
        val progress: SynchronizationProgress = SynchronizationProgress(
            phase = SynchronizationPhase.APPLYING_INBOUND,
            completed = 5L,
            total = 10L,
            unit = SynchronizationProgressUnit.EVENTS,
            metadata = metadata,
        )

        assertEquals(metadata, progress.metadata)
    }

    @Test
    fun `progress accepts null total for indeterminate progress`() {
        val progress: SynchronizationProgress = SynchronizationProgress(
            phase = SynchronizationPhase.PULLING,
            completed = 3L,
            total = null,
            unit = SynchronizationProgressUnit.EVENTS,
        )

        assertNull(progress.total)
    }

    @Test
    fun `progress accepts zero completed`() {
        val progress: SynchronizationProgress = SynchronizationProgress(
            phase = SynchronizationPhase.VALIDATING,
            completed = 0L,
            total = null,
            unit = SynchronizationProgressUnit.STEPS,
        )

        assertEquals(0L, progress.completed)
    }

    @Test
    fun `progress accepts completed equal to total`() {
        val progress: SynchronizationProgress = SynchronizationProgress(
            phase = SynchronizationPhase.FINALIZING,
            completed = 100L,
            total = 100L,
            unit = SynchronizationProgressUnit.EVENTS,
        )

        assertEquals(100L, progress.completed)
        assertEquals(100L, progress.total)
    }

    @Test
    fun `progress accepts zero total`() {
        val progress: SynchronizationProgress = SynchronizationProgress(
            phase = SynchronizationPhase.PUSHING,
            completed = 0L,
            total = 0L,
            unit = SynchronizationProgressUnit.EVENTS,
        )

        assertEquals(0L, progress.total)
    }

    @Test
    fun `progress rejects negative completed`() {
        assertFailsWith<IllegalArgumentException> {
            SynchronizationProgress(
                phase = SynchronizationPhase.PUSHING,
                completed = -1L,
                total = null,
                unit = SynchronizationProgressUnit.EVENTS,
            )
        }
    }

    @Test
    fun `progress rejects negative total`() {
        assertFailsWith<IllegalArgumentException> {
            SynchronizationProgress(
                phase = SynchronizationPhase.PUSHING,
                completed = 0L,
                total = -1L,
                unit = SynchronizationProgressUnit.EVENTS,
            )
        }
    }

    @Test
    fun `progress rejects completed exceeding total`() {
        assertFailsWith<IllegalArgumentException> {
            SynchronizationProgress(
                phase = SynchronizationPhase.PUSHING,
                completed = 101L,
                total = 100L,
                unit = SynchronizationProgressUnit.EVENTS,
            )
        }
    }

    @Test
    fun `equal progress instances compare as equal`() {
        val first: SynchronizationProgress = SynchronizationProgress(
            phase = SynchronizationPhase.PUSHING,
            completed = 5L,
            total = 10L,
            unit = SynchronizationProgressUnit.EVENTS,
        )
        val second: SynchronizationProgress = SynchronizationProgress(
            phase = SynchronizationPhase.PUSHING,
            completed = 5L,
            total = 10L,
            unit = SynchronizationProgressUnit.EVENTS,
        )

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun `progress instances with different phases are not equal`() {
        val first: SynchronizationProgress = SynchronizationProgress(
            phase = SynchronizationPhase.PUSHING,
            completed = 5L,
            total = 10L,
            unit = SynchronizationProgressUnit.EVENTS,
        )
        val second: SynchronizationProgress = SynchronizationProgress(
            phase = SynchronizationPhase.PULLING,
            completed = 5L,
            total = 10L,
            unit = SynchronizationProgressUnit.EVENTS,
        )

        assertNotEquals(first, second)
    }

    // -------------------------------------------------------------------------
    // SynchronizationSummary tests
    // -------------------------------------------------------------------------

    @Test
    fun `summary defaults all counters to zero`() {
        val summary: SynchronizationSummary = SynchronizationSummary()

        assertEquals(0L, summary.outboundEventsRead)
        assertEquals(0L, summary.outboundEventsAccepted)
        assertEquals(0L, summary.outboundEventsMarkedForRetry)
        assertEquals(0L, summary.outboundEventsRejected)
        assertEquals(0L, summary.inboundEventsReceived)
        assertEquals(0L, summary.inboundEventsApplied)
        assertEquals(0L, summary.conflictsDetected)
        assertEquals(0, summary.retryAttempts)
    }

    @Test
    fun `summary defaults metadata to empty`() {
        val summary: SynchronizationSummary = SynchronizationSummary()

        assertEquals(DataLoomMetadata.Empty, summary.metadata)
        assertTrue(summary.metadata.isEmpty())
    }

    @Test
    fun `summary preserves supplied counter values`() {
        val summary: SynchronizationSummary = SynchronizationSummary(
            outboundEventsRead = 10L,
            outboundEventsAccepted = 8L,
            outboundEventsMarkedForRetry = 1L,
            outboundEventsRejected = 1L,
            inboundEventsReceived = 20L,
            inboundEventsApplied = 20L,
            conflictsDetected = 2L,
            retryAttempts = 3,
        )

        assertEquals(10L, summary.outboundEventsRead)
        assertEquals(8L, summary.outboundEventsAccepted)
        assertEquals(1L, summary.outboundEventsMarkedForRetry)
        assertEquals(1L, summary.outboundEventsRejected)
        assertEquals(20L, summary.inboundEventsReceived)
        assertEquals(20L, summary.inboundEventsApplied)
        assertEquals(2L, summary.conflictsDetected)
        assertEquals(3, summary.retryAttempts)
    }

    @Test
    fun `summary rejects negative outbound events read`() {
        assertFailsWith<IllegalArgumentException> {
            SynchronizationSummary(outboundEventsRead = -1L)
        }
    }

    @Test
    fun `summary rejects outbound accepted exceeding read`() {
        assertFailsWith<IllegalArgumentException> {
            SynchronizationSummary(
                outboundEventsRead = 5L,
                outboundEventsAccepted = 6L,
            )
        }
    }

    @Test
    fun `summary rejects outbound retry exceeding read`() {
        assertFailsWith<IllegalArgumentException> {
            SynchronizationSummary(
                outboundEventsRead = 5L,
                outboundEventsMarkedForRetry = 6L,
            )
        }
    }

    @Test
    fun `summary rejects outbound rejected exceeding read`() {
        assertFailsWith<IllegalArgumentException> {
            SynchronizationSummary(
                outboundEventsRead = 5L,
                outboundEventsRejected = 6L,
            )
        }
    }

    @Test
    fun `summary rejects inbound applied exceeding received`() {
        assertFailsWith<IllegalArgumentException> {
            SynchronizationSummary(
                inboundEventsReceived = 5L,
                inboundEventsApplied = 6L,
            )
        }
    }

    @Test
    fun `summary rejects negative retry attempts`() {
        assertFailsWith<IllegalArgumentException> {
            SynchronizationSummary(retryAttempts = -1)
        }
    }

    @Test
    fun `summary allows accepted plus retry plus rejected less than read`() {
        // Some events may remain unprocessed when a workflow terminates early.
        val summary: SynchronizationSummary = SynchronizationSummary(
            outboundEventsRead = 10L,
            outboundEventsAccepted = 3L,
            outboundEventsMarkedForRetry = 2L,
            outboundEventsRejected = 1L,
        )

        assertEquals(10L, summary.outboundEventsRead)
        assertEquals(3L, summary.outboundEventsAccepted)
    }

    @Test
    fun `equal summaries compare as equal`() {
        val first: SynchronizationSummary = SynchronizationSummary(
            outboundEventsRead = 5L,
            inboundEventsReceived = 3L,
        )
        val second: SynchronizationSummary = SynchronizationSummary(
            outboundEventsRead = 5L,
            inboundEventsReceived = 3L,
        )

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun `summaries with different counters are not equal`() {
        val first: SynchronizationSummary = SynchronizationSummary(outboundEventsRead = 5L)
        val second: SynchronizationSummary = SynchronizationSummary(outboundEventsRead = 6L)

        assertNotEquals(first, second)
    }

    // -------------------------------------------------------------------------
    // SynchronizationSkipReason tests
    // -------------------------------------------------------------------------

    @Test
    fun `every required skip reason exists`() {
        val reasonNames: Set<String> = SynchronizationSkipReason.entries.map { it.name }.toSet()
        val required: Set<String> = setOf(
            "NO_CHANGES",
            "CONSTRAINTS_NOT_SATISFIED",
            "POLICY_REJECTED",
            "DUPLICATE_REQUEST",
        )
        assertTrue(reasonNames.containsAll(required), "Missing skip reasons: ${required - reasonNames}")
    }

    @Test
    fun `skip reasons can be referenced without relying on ordinals`() {
        val noChanges: SynchronizationSkipReason = SynchronizationSkipReason.NO_CHANGES
        val policyRejected: SynchronizationSkipReason = SynchronizationSkipReason.POLICY_REJECTED

        assertNotEquals(noChanges, policyRejected)
        assertEquals(SynchronizationSkipReason.NO_CHANGES, noChanges)
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

    private fun sampleError(): DataLoomError = TestDataLoomError(
        code = ErrorCode("DL-TEST-001"),
        category = ErrorCategory.PROVIDER,
        severity = ErrorSeverity.ERROR,
        recoverability = Recoverability.RECOVERABLE,
        message = "Test error.",
        cause = null,
    )

    private data class TestDataLoomError(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val severity: ErrorSeverity,
        override val recoverability: Recoverability,
        override val message: String,
        override val cause: Throwable?,
    ) : DataLoomError
}
