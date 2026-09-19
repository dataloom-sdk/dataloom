package io.dataloom.api.operational

import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.TenantId
import io.dataloom.api.identifier.TraceId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.security.ClassifiedData
import io.dataloom.api.security.ClassifiedDataValue
import io.dataloom.api.security.DataClassification
import io.dataloom.api.security.StrictDataLoomRedactor
import io.dataloom.api.time.DataLoomInstant
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
class OperationalEventOutboxStateCodecTest {

    private val codec = OperationalEventOutboxStateCodec()

    @Test
    fun roundTripsAnEmptyState() {
        val state = OperationalEventOutboxState(emptyList())
        assertEquals(state, codec.decode(codec.encode(state)))
    }

    @Test
    fun roundTripsOneEnvelope() {
        val state = stateOf(envelope("event-1"))
        assertEquals(state, codec.decode(codec.encode(state)))
    }

    @Test
    fun roundTripsMultipleEnvelopesPreservingOrder() {
        val state = stateOf(envelope("event-1"), envelope("event-2"), envelope("event-3"))
        val decoded = codec.decode(codec.encode(state))
        assertEquals(state, decoded)
        assertEquals(listOf("event-1", "event-2", "event-3"), decoded.entries.map { it.envelope.id.value })
    }

    @Test
    fun encodesTheCurrentVersionTwoHeader() {
        assertTrue(codec.encode(stateOf(envelope("event-1"))).startsWith("DATALOOM_OPERATIONAL_EVENT_OUTBOX|2|"))
        assertEquals(2, DurableOperationalEventOutbox.CURRENT_SCHEMA_VERSION)
    }

    @Test
    fun roundTripsSequencesAcknowledgementTombstonesHighWaterMarksAndTheFloor() {
        val a = OperationalEventOrderingKey.forWorkflow(WorkflowId("a"))
        val droppedKeyStillTracked = OperationalEventOrderingKey.forWorkflow(WorkflowId("gone|with,odd chars"))
        val state = OperationalEventOutboxState(
            entries = listOf(
                OperationalEventOutboxEntry(4L, envelope("event-1", workflow = "a"), acknowledgedAt = DataLoomInstant(2_500L)),
                OperationalEventOutboxEntry(2L, envelope("event-2")),
                OperationalEventOutboxEntry(5L, envelope("event-3", workflow = "a")),
            ),
            sequenceHighWaterMarks = mapOf(
                a to 9L, // higher than any retained sequence: entries were pruned since
                OperationalEventOrderingKey.Global to 2L,
                droppedKeyStillTracked to 6L,
            ),
            sequenceFloor = 3L,
        )

        val decoded = codec.decode(codec.encode(state))

        assertEquals(state, decoded)
        assertEquals(DataLoomInstant(2_500L), decoded.entries[0].acknowledgedAt)
        assertEquals(null, decoded.entries[1].acknowledgedAt)
        assertEquals(9L, decoded.sequenceHighWaterMarks[a])
        assertEquals(3L, decoded.sequenceFloor)
    }

    @Test
    fun encodingIsDeterministicRegardlessOfMarkInsertionOrder() {
        val a = OperationalEventOrderingKey.forWorkflow(WorkflowId("a"))
        val b = OperationalEventOrderingKey.forWorkflow(WorkflowId("b"))
        val one = OperationalEventOutboxState(emptyList(), linkedMapOf(a to 1L, b to 2L))
        val two = OperationalEventOutboxState(emptyList(), linkedMapOf(b to 2L, a to 1L))

        assertEquals(codec.encode(one), codec.encode(two))
    }

    @Test
    fun roundTripsAnEnvelopeWithOptionalIdentitiesAndAttributes() {
        val attributes = StrictDataLoomRedactor().redact(
            ClassifiedData.of(
                mapOf(
                    "status" to ClassifiedDataValue("scheduled", DataClassification.PUBLIC),
                ),
            ),
        ).attributes
        val envelope = OperationalEventEnvelope(
            id = OperationalEventId("event-full"),
            type = OperationalEventType("dataloom.retry.scheduled"),
            source = OperationalEventSource("dataloom.runtime.retry"),
            category = OperationalEventCategory.TELEMETRY,
            schemaVersion = OperationalSchemaVersion(1),
            occurredAt = DataLoomInstant(1_000L),
            correlationId = CorrelationId("correlation-001"),
            causationId = OperationalEventId("event-000"),
            traceId = TraceId("trace-001"),
            tenantId = TenantId("tenant-001"),
            workflowId = WorkflowId("workflow-001"),
            payload = OperationalPayloadDescriptor(
                type = OperationalPayloadType("dataloom.retry.signal"),
                schemaVersion = OperationalSchemaVersion(2),
                encoding = OperationalPayloadEncoding("application/json"),
                classification = DataClassification.INTERNAL,
                encodedSizeBytes = 128L,
            ),
            attributes = attributes,
        )
        val state = stateOf(envelope)
        assertEquals(state, codec.decode(codec.encode(state)))
    }

    @Test
    fun decodesTheVersionOnePayloadFormatAssigningSequencesPerWorkflowInListOrder() {
        val envelopes = listOf(
            envelope("event-1", workflow = "a"),
            envelope("event-2"),
            envelope("event-3", workflow = "a"),
            envelope("event-4", workflow = "b"),
        )
        val v1Payload = (listOf("DATALOOM_OPERATIONAL_EVENT_OUTBOX", "1", envelopes.size.toString()) +
            envelopes.map { Base64.encode(OperationalEnvelopeWireCodec.encode(it)) }).joinToString("|")

        val decoded = codec.decode(v1Payload)

        assertEquals(listOf("event-1", "event-2", "event-3", "event-4"), decoded.entries.map { it.envelope.id.value })
        assertEquals(listOf(1L, 1L, 2L, 1L), decoded.entries.map { it.sequence })
        assertTrue(decoded.entries.none { it.isAcknowledged })
        assertEquals(2L, decoded.sequenceHighWaterMarks[OperationalEventOrderingKey.forWorkflow(WorkflowId("a"))])
        assertEquals(0L, decoded.sequenceFloor)
        // Re-encoding upgrades the payload to the current version, and it round-trips.
        assertEquals(decoded, codec.decode(codec.encode(decoded)))
    }

    @Test
    fun decodesAnEmptyVersionOnePayload() {
        assertEquals(
            OperationalEventOutboxState(emptyList()),
            codec.decode("DATALOOM_OPERATIONAL_EVENT_OUTBOX|1|0"),
        )
    }

    @Test
    fun decodeRejectsAnEmptyPayload() {
        assertFailsWith<IllegalArgumentException> { codec.decode("") }
    }

    @Test
    fun decodeRejectsAWrongHeader() {
        assertFailsWith<IllegalArgumentException> { codec.decode("NOT_THE_HEADER|2|0|0|0") }
    }

    @Test
    fun decodeRejectsAnUnsupportedVersion() {
        assertFailsWith<IllegalArgumentException> { codec.decode("DATALOOM_OPERATIONAL_EVENT_OUTBOX|3|0|0|0") }
    }

    @Test
    fun decodeRejectsAnEntryCountMismatch() {
        val encoded = codec.encode(stateOf(envelope("event-1")))
        // Header|2|floor|markCount|entryCount: claim two entries while only one is present.
        val tampered = encoded.replaceFirst("|2|0|1|1|", "|2|0|1|2|")
        assertFailsWith<IllegalArgumentException> { codec.decode(tampered) }
    }

    @Test
    fun decodeRejectsAMarkCountMismatch() {
        val encoded = codec.encode(stateOf(envelope("event-1")))
        val tampered = encoded.replaceFirst("|2|0|1|1|", "|2|0|2|1|")
        assertFailsWith<IllegalArgumentException> { codec.decode(tampered) }
    }

    @Test
    fun decodeRejectsAMalformedEntryFrame() {
        assertFailsWith<IllegalArgumentException> {
            codec.decode("DATALOOM_OPERATIONAL_EVENT_OUTBOX|2|0|1|1|Z2xvYmFs,1|1,-,not-valid-base64-frame")
        }
        assertFailsWith<IllegalArgumentException> {
            codec.decode("DATALOOM_OPERATIONAL_EVENT_OUTBOX|1|1|not-valid-base64-frame")
        }
    }

    @Test
    fun decodeRejectsAStatePayloadWhoseSequencesAreNotStrictlyIncreasingPerWorkflow() {
        val frame = Base64.encode(OperationalEnvelopeWireCodec.encode(envelope("event-1")))
        val other = Base64.encode(OperationalEnvelopeWireCodec.encode(envelope("event-2")))
        val globalMark = Base64.encode("global".encodeToByteArray())
        val duplicateSequences = "DATALOOM_OPERATIONAL_EVENT_OUTBOX|2|0|1|2|$globalMark,5|5,-,$frame|5,-,$other"
        assertFailsWith<IllegalArgumentException> { codec.decode(duplicateSequences) }
    }

    @Test
    fun decodeRejectsAHighWaterMarkBelowARetainedSequence() {
        val frame = Base64.encode(OperationalEnvelopeWireCodec.encode(envelope("event-1")))
        val globalMark = Base64.encode("global".encodeToByteArray())
        val payload = "DATALOOM_OPERATIONAL_EVENT_OUTBOX|2|0|1|1|$globalMark,3|5,-,$frame"
        assertFailsWith<IllegalArgumentException> { codec.decode(payload) }
    }

    @Test
    fun decodeRejectsADuplicatedHighWaterMarkKey() {
        val globalMark = Base64.encode("global".encodeToByteArray())
        val payload = "DATALOOM_OPERATIONAL_EVENT_OUTBOX|2|0|2|0|$globalMark,3|$globalMark,4"
        assertFailsWith<IllegalArgumentException> { codec.decode(payload) }
    }

    @Test
    fun decodeRejectsAMalformedAcknowledgementTimestamp() {
        val frame = Base64.encode(OperationalEnvelopeWireCodec.encode(envelope("event-1")))
        val globalMark = Base64.encode("global".encodeToByteArray())
        val payload = "DATALOOM_OPERATIONAL_EVENT_OUTBOX|2|0|1|1|$globalMark,1|1,yesterday,$frame"
        assertFailsWith<IllegalArgumentException> { codec.decode(payload) }
    }

    private fun stateOf(vararg envelopes: OperationalEventEnvelope): OperationalEventOutboxState =
        OperationalEventOutboxState(envelopes.mapIndexed { index, envelope -> OperationalEventOutboxEntry(index + 1L, envelope) })

    private fun envelope(id: String, workflow: String? = null): OperationalEventEnvelope = OperationalEventEnvelope(
        id = OperationalEventId(id),
        type = OperationalEventType("dataloom.retry.scheduled"),
        source = OperationalEventSource("dataloom.runtime.retry"),
        category = OperationalEventCategory.TELEMETRY,
        schemaVersion = OperationalSchemaVersion(1),
        occurredAt = DataLoomInstant(1_000L),
        correlationId = CorrelationId("correlation-1"),
        workflowId = workflow?.let { WorkflowId(it) },
        payload = OperationalPayloadDescriptor(
            type = OperationalPayloadType("dataloom.retry.signal"),
            schemaVersion = OperationalSchemaVersion(1),
            encoding = OperationalPayloadEncoding("application/json"),
            classification = DataClassification.INTERNAL,
        ),
    )
}
