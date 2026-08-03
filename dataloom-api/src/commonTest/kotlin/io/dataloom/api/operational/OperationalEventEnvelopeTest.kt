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
import kotlin.test.assertFalse

class OperationalEventEnvelopeTest {
    @Test
    fun envelopePreservesCanonicalIdentityRoutingAndPayloadMetadata() {
        val attributes = StrictDataLoomRedactor().redact(
            ClassifiedData.of(
                mapOf(
                    "status" to ClassifiedDataValue("scheduled", DataClassification.PUBLIC),
                ),
            ),
        ).attributes
        val envelope = OperationalEventEnvelope(
            id = OperationalEventId("event-001"),
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

        assertEquals(OperationalEventId("event-001"), envelope.id)
        assertEquals(OperationalEventCategory.TELEMETRY, envelope.category)
        assertEquals(OperationalEventId("event-000"), envelope.causationId)
        assertEquals(TenantId("tenant-001"), envelope.tenantId)
        assertEquals(WorkflowId("workflow-001"), envelope.workflowId)
        assertEquals(128L, envelope.payload.encodedSizeBytes)
        assertEquals("scheduled", envelope.attributes["status"])
    }

    @Test
    fun diagnosticsExcludeDynamicIdentitiesAndAttributes() {
        val secretIdentity = "tenant-secret-identity"
        val secretAttribute = "private-customer-value"
        val secretType = "private.event.type"
        val secretSource = "private.event.source"
        val attributes = StrictDataLoomRedactor().redact(
            ClassifiedData.of(
                mapOf(
                    "customer" to ClassifiedDataValue(
                        secretAttribute,
                        DataClassification.RESTRICTED,
                    ),
                ),
            ),
        ).attributes
        val envelope = sampleEnvelope(
            tenantId = TenantId(secretIdentity),
            attributes = attributes,
        ).copy(
            type = OperationalEventType(secretType),
            source = OperationalEventSource(secretSource),
        )

        val diagnostic = envelope.toString()
        assertFalse(diagnostic.contains(secretIdentity))
        assertFalse(diagnostic.contains(secretAttribute))
        assertFalse(diagnostic.contains(secretType))
        assertFalse(diagnostic.contains(secretSource))
        assertFalse(diagnostic.contains("correlation-001"))
        assertFalse(diagnostic.contains("trace-001"))
        assertFalse(diagnostic.contains("event-001"))
    }

    @Test
    fun envelopeRejectsSelfCausation() {
        assertFailsWith<IllegalArgumentException> {
            sampleEnvelope(causationId = OperationalEventId("event-001"))
        }
    }

    @Test
    fun identifiersAndSchemaVersionsRejectUnsafeValues() {
        assertFailsWith<IllegalArgumentException> { OperationalEventId(" ") }
        assertFailsWith<IllegalArgumentException> { OperationalEventType("unsafe\ntype") }
        assertFailsWith<IllegalArgumentException> { OperationalEventSource("source value") }
        assertFailsWith<IllegalArgumentException> { OperationalSchemaVersion(0) }
        assertFailsWith<IllegalArgumentException> {
            OperationalPayloadDescriptor(
                type = OperationalPayloadType("payload"),
                schemaVersion = OperationalSchemaVersion(1),
                encoding = OperationalPayloadEncoding("application/json"),
                classification = DataClassification.PUBLIC,
                encodedSizeBytes = -1L,
            )
        }
    }

    private fun sampleEnvelope(
        tenantId: TenantId? = null,
        causationId: OperationalEventId? = null,
        attributes: io.dataloom.api.security.RedactedAttributes =
            io.dataloom.api.security.RedactedAttributes.Empty,
    ): OperationalEventEnvelope = OperationalEventEnvelope(
        id = OperationalEventId("event-001"),
        type = OperationalEventType("dataloom.lifecycle.started"),
        source = OperationalEventSource("dataloom.runtime"),
        category = OperationalEventCategory.LIFECYCLE,
        schemaVersion = OperationalSchemaVersion(1),
        occurredAt = DataLoomInstant(1_000L),
        correlationId = CorrelationId("correlation-001"),
        causationId = causationId,
        traceId = TraceId("trace-001"),
        tenantId = tenantId,
        workflowId = WorkflowId("workflow-001"),
        payload = OperationalPayloadDescriptor(
            type = OperationalPayloadType("dataloom.lifecycle.signal"),
            schemaVersion = OperationalSchemaVersion(1),
            encoding = OperationalPayloadEncoding("application/json"),
            classification = DataClassification.INTERNAL,
        ),
        attributes = attributes,
    )
}
