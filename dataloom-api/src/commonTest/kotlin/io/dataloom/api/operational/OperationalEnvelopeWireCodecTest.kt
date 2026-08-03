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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OperationalEnvelopeWireCodecTest {
    @Test
    fun encodeUsesFrozenV1Layout() {
        val encoded = OperationalEnvelopeWireCodec.encode(minimalEnvelope())

        assertEquals(
            "444c4f50000000010000000165000000017400000001730000000653595354454d" +
                "0000000100000000000000000000000163ffffffffffffffffffffffffffffffff" +
                "000000017000000001000000046a736f6e000000065055424c49430000000000",
            encoded.toHexString(),
        )
    }

    @Test
    fun fullyPopulatedEnvelopeRoundTripsWithoutLosingRedactedAttributes() {
        val original = populatedEnvelope()

        val result = assertIs<OperationalEnvelopeDecodeResult.Decoded>(
            OperationalEnvelopeWireCodec.decode(OperationalEnvelopeWireCodec.encode(original)),
        )

        assertEquals(original, result.envelope)
        assertEquals("ready", result.envelope.attributes["public.status"])
        assertEquals("[REDACTED]", result.envelope.attributes["internal.node"])
    }

    @Test
    fun encodingIsDeterministicAcrossInputMapIterationOrder() {
        val first = populatedEnvelope(
            linkedMapOf(
                "public.status" to classified("ready", DataClassification.PUBLIC),
                "internal.node" to classified("node-7", DataClassification.INTERNAL),
            ),
        )
        val second = populatedEnvelope(
            linkedMapOf(
                "internal.node" to classified("node-7", DataClassification.INTERNAL),
                "public.status" to classified("ready", DataClassification.PUBLIC),
            ),
        )

        assertContentEquals(
            OperationalEnvelopeWireCodec.encode(first),
            OperationalEnvelopeWireCodec.encode(second),
        )
    }

    @Test
    fun decoderRejectsEmptyOversizedInvalidAndTrailingFrames() {
        assertRejected(
            ByteArray(0),
            OperationalEnvelopeDecodeFailure.EMPTY_INPUT,
        )
        assertRejected(
            ByteArray(OperationalEnvelopeWireCodec.MAX_FRAME_SIZE_BYTES + 1),
            OperationalEnvelopeDecodeFailure.FRAME_TOO_LARGE,
        )

        val valid = OperationalEnvelopeWireCodec.encode(minimalEnvelope())
        val invalidMagic = valid.copyOf().also { it[0] = 0 }
        assertRejected(invalidMagic, OperationalEnvelopeDecodeFailure.INVALID_MAGIC)

        val unsupportedVersion = valid.copyOf().also { it[7] = 2 }
        assertRejected(
            unsupportedVersion,
            OperationalEnvelopeDecodeFailure.UNSUPPORTED_WIRE_VERSION,
        )
        assertRejected(valid.copyOf(valid.size - 1), OperationalEnvelopeDecodeFailure.INVALID_FIELD)
        assertRejected(valid + byteArrayOf(0), OperationalEnvelopeDecodeFailure.TRAILING_DATA)
    }

    @Test
    fun decoderRejectsInvalidUtf8WithoutLeakingFrameContent() {
        val valid = OperationalEnvelopeWireCodec.encode(minimalEnvelope())
        val invalidUtf8 = valid.copyOf().also { bytes ->
            val eventIdByteIndex = 12
            bytes[eventIdByteIndex] = 0x80.toByte()
        }

        val result = assertIs<OperationalEnvelopeDecodeResult.Rejected>(
            OperationalEnvelopeWireCodec.decode(invalidUtf8),
        )

        assertEquals(OperationalEnvelopeDecodeFailure.INVALID_FIELD, result.failure)
        assertEquals(
            "Rejected(failure=INVALID_FIELD)",
            result.toString(),
        )
    }

    @Test
    fun decoderRejectsNonCanonicalOrderAndUnsafeAttributeKeys() {
        val valid = OperationalEnvelopeWireCodec.encode(populatedEnvelope())
        val keyBytes = "internal.node".encodeToByteArray()
        val keyOffset = valid.indexOf(keyBytes)

        val nonCanonicalOrder = valid.copyOf().also { bytes ->
            bytes[keyOffset] = 'z'.code.toByte()
        }
        assertRejected(
            nonCanonicalOrder,
            OperationalEnvelopeDecodeFailure.INVALID_FIELD,
        )

        val unsafeKey = valid.copyOf().also { bytes ->
            bytes[keyOffset] = '\n'.code.toByte()
        }
        assertRejected(unsafeKey, OperationalEnvelopeDecodeFailure.INVALID_FIELD)
    }

    private fun assertRejected(
        bytes: ByteArray,
        expected: OperationalEnvelopeDecodeFailure,
    ) {
        val result = assertIs<OperationalEnvelopeDecodeResult.Rejected>(
            OperationalEnvelopeWireCodec.decode(bytes),
        )
        assertEquals(expected, result.failure)
    }

    private fun minimalEnvelope(): OperationalEventEnvelope = OperationalEventEnvelope(
        id = OperationalEventId("e"),
        type = OperationalEventType("t"),
        source = OperationalEventSource("s"),
        category = OperationalEventCategory.SYSTEM,
        schemaVersion = OperationalSchemaVersion(1),
        occurredAt = DataLoomInstant(0L),
        correlationId = CorrelationId("c"),
        payload = OperationalPayloadDescriptor(
            type = OperationalPayloadType("p"),
            schemaVersion = OperationalSchemaVersion(1),
            encoding = OperationalPayloadEncoding("json"),
            classification = DataClassification.PUBLIC,
        ),
    )

    private fun populatedEnvelope(
        classifiedAttributes: Map<String, ClassifiedDataValue> = linkedMapOf(
            "public.status" to classified("ready", DataClassification.PUBLIC),
            "internal.node" to classified("node-7", DataClassification.INTERNAL),
        ),
    ): OperationalEventEnvelope = OperationalEventEnvelope(
        id = OperationalEventId("event-001"),
        type = OperationalEventType("dataloom.retry.scheduled"),
        source = OperationalEventSource("dataloom.runtime.retry"),
        category = OperationalEventCategory.TELEMETRY,
        schemaVersion = OperationalSchemaVersion(2),
        occurredAt = DataLoomInstant(1_234L),
        correlationId = CorrelationId("correlation-001"),
        causationId = OperationalEventId("event-000"),
        traceId = TraceId("trace-001"),
        tenantId = TenantId("tenant-001"),
        workflowId = WorkflowId("workflow-001"),
        payload = OperationalPayloadDescriptor(
            type = OperationalPayloadType("dataloom.retry.signal"),
            schemaVersion = OperationalSchemaVersion(3),
            encoding = OperationalPayloadEncoding("application/json"),
            classification = DataClassification.INTERNAL,
            encodedSizeBytes = 256L,
        ),
        attributes = StrictDataLoomRedactor().redact(
            ClassifiedData.of(classifiedAttributes),
        ).attributes,
    )

    private fun classified(
        value: String,
        classification: DataClassification,
    ): ClassifiedDataValue = ClassifiedDataValue(value, classification)
}

private fun ByteArray.toHexString(): String = joinToString(separator = "") { byte: Byte ->
    byte.toUByte().toString(radix = 16).padStart(2, '0')
}

private fun ByteArray.indexOf(expected: ByteArray): Int {
    for (start in 0..size - expected.size) {
        if (expected.indices.all { index: Int -> this[start + index] == expected[index] }) {
            return start
        }
    }
    error("Expected byte sequence was not present in the frame.")
}
