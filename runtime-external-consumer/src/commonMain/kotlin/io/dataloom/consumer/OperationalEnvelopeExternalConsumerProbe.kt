package io.dataloom.consumer

import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.operational.OperationalEventCategory
import io.dataloom.api.operational.OperationalEventEnvelope
import io.dataloom.api.operational.OperationalEventId
import io.dataloom.api.operational.OperationalEventSource
import io.dataloom.api.operational.OperationalEventType
import io.dataloom.api.operational.OperationalPayloadDescriptor
import io.dataloom.api.operational.OperationalPayloadEncoding
import io.dataloom.api.operational.OperationalPayloadType
import io.dataloom.api.operational.OperationalSchemaVersion
import io.dataloom.api.security.ClassifiedData
import io.dataloom.api.security.ClassifiedDataValue
import io.dataloom.api.security.DataClassification
import io.dataloom.api.security.StrictDataLoomRedactor
import io.dataloom.api.time.DataLoomInstant

/** External JVM/iOS compilation probe for the canonical envelope and redaction boundary. */
public object OperationalEnvelopeExternalConsumerProbe {
    public fun create(): OperationalEventEnvelope {
        val attributes = StrictDataLoomRedactor().redact(
            ClassifiedData.of(
                mapOf(
                    "status" to ClassifiedDataValue("accepted", DataClassification.PUBLIC),
                ),
            ),
        ).attributes
        return OperationalEventEnvelope(
            id = OperationalEventId("consumer-event"),
            type = OperationalEventType("dataloom.consumer.accepted"),
            source = OperationalEventSource("consumer.fixture"),
            category = OperationalEventCategory.DIAGNOSTIC,
            schemaVersion = OperationalSchemaVersion(1),
            occurredAt = DataLoomInstant(0L),
            correlationId = CorrelationId("consumer-correlation"),
            payload = OperationalPayloadDescriptor(
                type = OperationalPayloadType("dataloom.consumer.signal"),
                schemaVersion = OperationalSchemaVersion(1),
                encoding = OperationalPayloadEncoding("application/json"),
                classification = DataClassification.PUBLIC,
            ),
            attributes = attributes,
        )
    }
}
