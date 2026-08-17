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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OperationalEventOutboxStateCodecTest {

    private val codec = OperationalEventOutboxStateCodec()

    @Test
    fun roundTripsAnEmptyState() {
        val state = OperationalEventOutboxState(emptyList())
        assertEquals(state, codec.decode(codec.encode(state)))
    }

    @Test
    fun roundTripsOneEnvelope() {
        val state = OperationalEventOutboxState(listOf(envelope("event-1")))
        assertEquals(state, codec.decode(codec.encode(state)))
    }

    @Test
    fun roundTripsMultipleEnvelopesPreservingOrder() {
        val state = OperationalEventOutboxState(
            listOf(envelope("event-1"), envelope("event-2"), envelope("event-3")),
        )
        val decoded = codec.decode(codec.encode(state))
        assertEquals(state, decoded)
        assertEquals(listOf("event-1", "event-2", "event-3"), decoded.entries.map { it.id.value })
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
        val state = OperationalEventOutboxState(listOf(envelope))
        assertEquals(state, codec.decode(codec.encode(state)))
    }

    @Test
    fun decodeRejectsAnEmptyPayload() {
        assertFailsWith<IllegalArgumentException> { codec.decode("") }
    }

    @Test
    fun decodeRejectsAWrongHeader() {
        assertFailsWith<IllegalArgumentException> { codec.decode("NOT_THE_HEADER|1|0") }
    }

    @Test
    fun decodeRejectsAnEntryCountMismatch() {
        val encoded = codec.encode(OperationalEventOutboxState(listOf(envelope("event-1"))))
        val tampered = encoded.replaceFirst("|1|1|", "|1|2|")
        assertFailsWith<IllegalArgumentException> { codec.decode(tampered) }
    }

    @Test
    fun decodeRejectsAMalformedEntryFrame() {
        assertFailsWith<IllegalArgumentException> {
            codec.decode("DATALOOM_OPERATIONAL_EVENT_OUTBOX|1|1|not-valid-base64-frame")
        }
    }

    private fun envelope(id: String): OperationalEventEnvelope = OperationalEventEnvelope(
        id = OperationalEventId(id),
        type = OperationalEventType("dataloom.retry.scheduled"),
        source = OperationalEventSource("dataloom.runtime.retry"),
        category = OperationalEventCategory.TELEMETRY,
        schemaVersion = OperationalSchemaVersion(1),
        occurredAt = DataLoomInstant(1_000L),
        correlationId = CorrelationId("correlation-1"),
        payload = OperationalPayloadDescriptor(
            type = OperationalPayloadType("dataloom.retry.signal"),
            schemaVersion = OperationalSchemaVersion(1),
            encoding = OperationalPayloadEncoding("application/json"),
            classification = DataClassification.INTERNAL,
        ),
    )
}
