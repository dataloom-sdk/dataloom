package io.dataloom.api.synchronization

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.CheckpointKey
import io.dataloom.api.identifier.CheckpointToken
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ChangeSetId
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SynchronizationContractsTest {

    // -------------------------------------------------------------------------
    // SynchronizationCheckpoint tests
    // -------------------------------------------------------------------------

    @Test
    fun `checkpoint preserves key and token`() {
        val checkpoint: SynchronizationCheckpoint = SynchronizationCheckpoint(
            key = CheckpointKey("customers-pull"),
            token = CheckpointToken("token-001"),
        )

        assertEquals(CheckpointKey("customers-pull"), checkpoint.key)
        assertEquals(CheckpointToken("token-001"), checkpoint.token)
    }

    @Test
    fun `checkpoint defaults metadata to empty metadata`() {
        val checkpoint: SynchronizationCheckpoint = SynchronizationCheckpoint(
            key = CheckpointKey("customers-pull"),
            token = CheckpointToken("token-001"),
        )

        assertEquals(DataLoomMetadata.Empty, checkpoint.metadata)
        assertTrue(checkpoint.metadata.isEmpty())
    }

    @Test
    fun `checkpoint preserves supplied metadata`() {
        val metadata: DataLoomMetadata = DataLoomMetadata.of(mapOf("source" to "example"))
        val checkpoint: SynchronizationCheckpoint = SynchronizationCheckpoint(
            key = CheckpointKey("customers-pull"),
            token = CheckpointToken("token-001"),
            metadata = metadata,
        )

        assertEquals(metadata, checkpoint.metadata)
    }

    @Test
    fun `equal checkpoints compare as equal`() {
        val first: SynchronizationCheckpoint = SynchronizationCheckpoint(
            key = CheckpointKey("customers-pull"),
            token = CheckpointToken("token-001"),
        )
        val second: SynchronizationCheckpoint = SynchronizationCheckpoint(
            key = CheckpointKey("customers-pull"),
            token = CheckpointToken("token-001"),
        )

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun `checkpoints with different tokens are not equal`() {
        val first: SynchronizationCheckpoint = SynchronizationCheckpoint(
            key = CheckpointKey("customers-pull"),
            token = CheckpointToken("token-001"),
        )
        val second: SynchronizationCheckpoint = SynchronizationCheckpoint(
            key = CheckpointKey("customers-pull"),
            token = CheckpointToken("token-002"),
        )

        assertNotEquals(first, second)
    }

    // -------------------------------------------------------------------------
    // ChangeAcknowledgementStatus tests
    // -------------------------------------------------------------------------

    @Test
    fun `every required acknowledgement status exists`() {
        val statuses: Set<String> = ChangeAcknowledgementStatus.entries.map { it.name }.toSet()

        assertEquals(setOf("ACCEPTED", "RETRY", "REJECTED"), statuses)
    }

    @Test
    fun `acknowledgement status can be referenced without relying on ordinals`() {
        val accepted: ChangeAcknowledgementStatus = ChangeAcknowledgementStatus.ACCEPTED
        val retry: ChangeAcknowledgementStatus = ChangeAcknowledgementStatus.RETRY
        val rejected: ChangeAcknowledgementStatus = ChangeAcknowledgementStatus.REJECTED

        assertNotEquals(accepted, retry)
        assertNotEquals(retry, rejected)
        assertNotEquals(accepted, rejected)
    }

    // -------------------------------------------------------------------------
    // ChangeEventAcknowledgement tests
    // -------------------------------------------------------------------------

    @Test
    fun `event acknowledgement preserves event id and status`() {
        val acknowledgement: ChangeEventAcknowledgement = ChangeEventAcknowledgement(
            eventId = ChangeEventId("event-001"),
            status = ChangeAcknowledgementStatus.ACCEPTED,
        )

        assertEquals(ChangeEventId("event-001"), acknowledgement.eventId)
        assertEquals(ChangeAcknowledgementStatus.ACCEPTED, acknowledgement.status)
    }

    @Test
    fun `event acknowledgement error may be absent`() {
        val acknowledgement: ChangeEventAcknowledgement = ChangeEventAcknowledgement(
            eventId = ChangeEventId("event-001"),
            status = ChangeAcknowledgementStatus.ACCEPTED,
        )

        assertNull(acknowledgement.error)
    }

    @Test
    fun `event acknowledgement error may be supplied`() {
        val error: DataLoomError = sampleError()
        val acknowledgement: ChangeEventAcknowledgement = ChangeEventAcknowledgement(
            eventId = ChangeEventId("event-001"),
            status = ChangeAcknowledgementStatus.RETRY,
            error = error,
        )

        assertEquals(error, acknowledgement.error)
    }

    @Test
    fun `event acknowledgement defaults metadata to empty metadata`() {
        val acknowledgement: ChangeEventAcknowledgement = ChangeEventAcknowledgement(
            eventId = ChangeEventId("event-001"),
            status = ChangeAcknowledgementStatus.ACCEPTED,
        )

        assertEquals(DataLoomMetadata.Empty, acknowledgement.metadata)
    }

    @Test
    fun `equal event acknowledgements compare as equal`() {
        val first: ChangeEventAcknowledgement = ChangeEventAcknowledgement(
            eventId = ChangeEventId("event-001"),
            status = ChangeAcknowledgementStatus.ACCEPTED,
        )
        val second: ChangeEventAcknowledgement = ChangeEventAcknowledgement(
            eventId = ChangeEventId("event-001"),
            status = ChangeAcknowledgementStatus.ACCEPTED,
        )

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    // -------------------------------------------------------------------------
    // ChangeSetAcknowledgement tests
    // -------------------------------------------------------------------------

    @Test
    fun `change set acknowledgement preserves change set id`() {
        val acknowledgement: ChangeSetAcknowledgement = ChangeSetAcknowledgement(
            changeSetId = ChangeSetId("changeset-001"),
            events = listOf(sampleEventAcknowledgement("event-001")),
        )

        assertEquals(ChangeSetId("changeset-001"), acknowledgement.changeSetId)
    }

    @Test
    fun `change set acknowledgement preserves event order`() {
        val first: ChangeEventAcknowledgement = sampleEventAcknowledgement("event-001")
        val second: ChangeEventAcknowledgement = sampleEventAcknowledgement("event-002")
        val acknowledgement: ChangeSetAcknowledgement = ChangeSetAcknowledgement(
            changeSetId = ChangeSetId("changeset-001"),
            events = listOf(first, second),
        )

        assertEquals(listOf(first, second), acknowledgement.events)
    }

    @Test
    fun `change set acknowledgement rejects empty event list`() {
        assertFailsWith<IllegalArgumentException> {
            ChangeSetAcknowledgement(
                changeSetId = ChangeSetId("changeset-001"),
                events = emptyList(),
            )
        }
    }

    @Test
    fun `change set acknowledgement rejects duplicate event ids`() {
        assertFailsWith<IllegalArgumentException> {
            ChangeSetAcknowledgement(
                changeSetId = ChangeSetId("changeset-001"),
                events = listOf(
                    sampleEventAcknowledgement("event-001"),
                    sampleEventAcknowledgement("event-001"),
                ),
            )
        }
    }

    @Test
    fun `change set acknowledgement defensively copies source event list`() {
        val source: MutableList<ChangeEventAcknowledgement> = mutableListOf(
            sampleEventAcknowledgement("event-001"),
        )
        val acknowledgement: ChangeSetAcknowledgement = ChangeSetAcknowledgement(
            changeSetId = ChangeSetId("changeset-001"),
            events = source,
        )

        source += sampleEventAcknowledgement("event-002")

        assertEquals(listOf(sampleEventAcknowledgement("event-001")), acknowledgement.events)
    }

    @Test
    fun `change set acknowledgement defaults metadata to empty metadata`() {
        val acknowledgement: ChangeSetAcknowledgement = ChangeSetAcknowledgement(
            changeSetId = ChangeSetId("changeset-001"),
            events = listOf(sampleEventAcknowledgement("event-001")),
        )

        assertEquals(DataLoomMetadata.Empty, acknowledgement.metadata)
    }

    @Test
    fun `equal change set acknowledgements compare as equal`() {
        val events: List<ChangeEventAcknowledgement> = listOf(sampleEventAcknowledgement("event-001"))
        val first: ChangeSetAcknowledgement = ChangeSetAcknowledgement(
            changeSetId = ChangeSetId("changeset-001"),
            events = events,
        )
        val second: ChangeSetAcknowledgement = ChangeSetAcknowledgement(
            changeSetId = ChangeSetId("changeset-001"),
            events = events,
        )

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    // -------------------------------------------------------------------------
    // Request contract tests
    // -------------------------------------------------------------------------

    @Test
    fun `outbound acknowledgement request preserves request and acknowledgement`() {
        val request: SynchronizationRequest = sampleSynchronizationRequest()
        val acknowledgement: ChangeSetAcknowledgement = ChangeSetAcknowledgement(
            changeSetId = ChangeSetId("changeset-001"),
            events = listOf(sampleEventAcknowledgement("event-001")),
        )
        val acknowledgementRequest: OutboundChangeAcknowledgementRequest = OutboundChangeAcknowledgementRequest(
            request = request,
            acknowledgement = acknowledgement,
        )

        assertEquals(request, acknowledgementRequest.request)
        assertEquals(acknowledgement, acknowledgementRequest.acknowledgement)
    }

    @Test
    fun `checkpoint read request preserves request and key`() {
        val request: SynchronizationRequest = sampleSynchronizationRequest()
        val key: CheckpointKey = CheckpointKey("customers-pull")
        val readRequest: CheckpointReadRequest = CheckpointReadRequest(request = request, key = key)

        assertEquals(request, readRequest.request)
        assertEquals(key, readRequest.key)
    }

    @Test
    fun `checkpoint write request preserves request and checkpoint`() {
        val request: SynchronizationRequest = sampleSynchronizationRequest()
        val checkpoint: SynchronizationCheckpoint = SynchronizationCheckpoint(
            key = CheckpointKey("customers-pull"),
            token = CheckpointToken("token-001"),
        )
        val writeRequest: CheckpointWriteRequest = CheckpointWriteRequest(
            request = request,
            checkpoint = checkpoint,
        )

        assertEquals(request, writeRequest.request)
        assertEquals(checkpoint, writeRequest.checkpoint)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun sampleEventAcknowledgement(eventId: String): ChangeEventAcknowledgement = ChangeEventAcknowledgement(
        eventId = ChangeEventId(eventId),
        status = ChangeAcknowledgementStatus.ACCEPTED,
    )

    private fun sampleError(): DataLoomError = TestDataLoomError(
        code = ErrorCode("DL-SYNC-001"),
        category = ErrorCategory.PROVIDER,
        severity = ErrorSeverity.ERROR,
        recoverability = Recoverability.RECOVERABLE,
        message = "Retry required.",
        cause = null,
    )

    private fun sampleSynchronizationRequest(): SynchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("workflow-001"),
        sessionId = SynchronizationSessionId("session-001"),
        direction = SynchronizationDirection.BIDIRECTIONAL,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("execution-001"),
            correlationId = CorrelationId("corr-001"),
        ),
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
