package io.dataloom.api.operational

import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.TenantId
import io.dataloom.api.identifier.TraceId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.security.DataClassification
import io.dataloom.api.security.RedactedAttributes
import io.dataloom.api.time.DataLoomInstant

/** Stable reason why an untrusted operational-envelope frame was rejected. */
public enum class OperationalEnvelopeDecodeFailure {
    EMPTY_INPUT,
    FRAME_TOO_LARGE,
    INVALID_MAGIC,
    UNSUPPORTED_WIRE_VERSION,
    INVALID_FIELD,
    TRAILING_DATA,
}

/** Structured result of decoding one untrusted operational-envelope frame. */
public sealed interface OperationalEnvelopeDecodeResult {
    /** A complete, validated V1 envelope. */
    public data class Decoded(
        public val envelope: OperationalEventEnvelope,
    ) : OperationalEnvelopeDecodeResult

    /** A frame rejected without exposing its bytes or field content. */
    public data class Rejected(
        public val failure: OperationalEnvelopeDecodeFailure,
    ) : OperationalEnvelopeDecodeResult
}

/**
 * Canonical bounded binary codec for [OperationalEventEnvelope].
 *
 * The V1 frame is deterministic and platform-independent. Integers are
 * big-endian, strings are length-prefixed UTF-8, nullable values use explicit
 * sentinels, and redacted attributes are encoded in sorted key order. The
 * decoder accepts exactly one frame and rejects trailing data.
 *
 * This codec never accepts payload bytes or arbitrary application objects.
 * Only the payload descriptor and already-redacted attributes cross the wire.
 */
public object OperationalEnvelopeWireCodec {
    /** Current immutable wire-format version. */
    public const val WIRE_VERSION: Int = 1

    /** Maximum accepted encoded frame size. */
    public const val MAX_FRAME_SIZE_BYTES: Int = 1_048_576

    /** Encodes one envelope into a fresh canonical V1 byte array. */
    public fun encode(envelope: OperationalEventEnvelope): ByteArray {
        val writer = OperationalWireWriter()
        MAGIC.forEach(writer::writeByte)
        writer.writeInt(WIRE_VERSION)
        writer.writeString(envelope.id.value)
        writer.writeString(envelope.type.value)
        writer.writeString(envelope.source.value)
        writer.writeString(envelope.category.name)
        writer.writeInt(envelope.schemaVersion.value)
        writer.writeLong(envelope.occurredAt.epochMilliseconds)
        writer.writeString(envelope.correlationId.value)
        writer.writeNullableString(envelope.causationId?.value)
        writer.writeNullableString(envelope.traceId?.value)
        writer.writeNullableString(envelope.tenantId?.value)
        writer.writeNullableString(envelope.workflowId?.value)
        writer.writeString(envelope.payload.type.value)
        writer.writeInt(envelope.payload.schemaVersion.value)
        writer.writeString(envelope.payload.encoding.value)
        writer.writeString(envelope.payload.classification.name)
        writer.writeNullableLong(envelope.payload.encodedSizeBytes)

        val attributes: List<Map.Entry<String, String>> =
            envelope.attributes.entries.entries.sortedBy { it.key }
        writer.writeInt(attributes.size)
        attributes.forEach { entry: Map.Entry<String, String> ->
            writer.writeString(entry.key)
            writer.writeString(entry.value)
        }
        return writer.toByteArray()
    }

    /** Decodes and validates exactly one canonical envelope frame. */
    public fun decode(bytes: ByteArray): OperationalEnvelopeDecodeResult {
        if (bytes.isEmpty()) {
            return OperationalEnvelopeDecodeResult.Rejected(
                OperationalEnvelopeDecodeFailure.EMPTY_INPUT,
            )
        }
        if (bytes.size > MAX_FRAME_SIZE_BYTES) {
            return OperationalEnvelopeDecodeResult.Rejected(
                OperationalEnvelopeDecodeFailure.FRAME_TOO_LARGE,
            )
        }

        return try {
            val reader = OperationalWireReader(bytes)
            MAGIC.forEach { expected: Byte ->
                if (reader.readByte() != expected) {
                    throw OperationalWireDecodeException(
                        OperationalEnvelopeDecodeFailure.INVALID_MAGIC,
                    )
                }
            }
            if (reader.readInt() != WIRE_VERSION) {
                throw OperationalWireDecodeException(
                    OperationalEnvelopeDecodeFailure.UNSUPPORTED_WIRE_VERSION,
                )
            }

            val id = OperationalEventId(reader.readString())
            val type = OperationalEventType(reader.readString())
            val source = OperationalEventSource(reader.readString())
            val categoryName: String = reader.readString()
            val category: OperationalEventCategory =
                OperationalEventCategory.entries.firstOrNull { it.name == categoryName }
                    ?: throw OperationalWireDecodeException(
                        OperationalEnvelopeDecodeFailure.INVALID_FIELD,
                    )
            val schemaVersion = OperationalSchemaVersion(reader.readInt())
            val occurredAt = DataLoomInstant(reader.readLong())
            val correlationId = CorrelationId(reader.readString())
            val causationId: OperationalEventId? =
                reader.readNullableString()?.let(::OperationalEventId)
            val traceId: TraceId? = reader.readNullableString()?.let(::TraceId)
            val tenantId: TenantId? = reader.readNullableString()?.let(::TenantId)
            val workflowId: WorkflowId? = reader.readNullableString()?.let(::WorkflowId)
            val payloadType = OperationalPayloadType(reader.readString())
            val payloadSchemaVersion = OperationalSchemaVersion(reader.readInt())
            val payloadEncoding = OperationalPayloadEncoding(reader.readString())
            val classificationName: String = reader.readString()
            val classification: DataClassification =
                DataClassification.entries.firstOrNull { it.name == classificationName }
                    ?: throw OperationalWireDecodeException(
                        OperationalEnvelopeDecodeFailure.INVALID_FIELD,
                    )
            val encodedSizeBytes: Long? = reader.readNullableLong()
            val attributeCount: Int = reader.readInt()
            if (attributeCount !in 0..MAX_ATTRIBUTE_COUNT) {
                throw OperationalWireDecodeException(
                    OperationalEnvelopeDecodeFailure.INVALID_FIELD,
                )
            }
            val attributes: MutableMap<String, String> = linkedMapOf()
            var previousAttributeKey: String? = null
            repeat(attributeCount) {
                val key: String = reader.readString()
                val value: String = reader.readString()
                if (previousAttributeKey?.let { prior: String -> key <= prior } == true) {
                    throw OperationalWireDecodeException(
                        OperationalEnvelopeDecodeFailure.INVALID_FIELD,
                    )
                }
                previousAttributeKey = key
                attributes[key] = value
            }
            if (!reader.isExhausted()) {
                throw OperationalWireDecodeException(
                    OperationalEnvelopeDecodeFailure.TRAILING_DATA,
                )
            }

            OperationalEnvelopeDecodeResult.Decoded(
                OperationalEventEnvelope(
                    id = id,
                    type = type,
                    source = source,
                    category = category,
                    schemaVersion = schemaVersion,
                    occurredAt = occurredAt,
                    correlationId = correlationId,
                    causationId = causationId,
                    traceId = traceId,
                    tenantId = tenantId,
                    workflowId = workflowId,
                    payload = OperationalPayloadDescriptor(
                        type = payloadType,
                        schemaVersion = payloadSchemaVersion,
                        encoding = payloadEncoding,
                        classification = classification,
                        encodedSizeBytes = encodedSizeBytes,
                    ),
                    attributes = RedactedAttributes.fromRedactor(attributes),
                ),
            )
        } catch (failure: OperationalWireDecodeException) {
            OperationalEnvelopeDecodeResult.Rejected(failure.failure)
        } catch (_: Exception) {
            OperationalEnvelopeDecodeResult.Rejected(
                OperationalEnvelopeDecodeFailure.INVALID_FIELD,
            )
        }
    }

    private val MAGIC: ByteArray = byteArrayOf(0x44, 0x4C, 0x4F, 0x50)
    private const val MAX_ATTRIBUTE_COUNT: Int = 256
}

private class OperationalWireWriter {
    private var buffer: ByteArray = ByteArray(INITIAL_CAPACITY)
    private var size: Int = 0

    fun writeByte(value: Byte) {
        ensureCapacity(1)
        buffer[size] = value
        size += 1
    }

    fun writeInt(value: Int) {
        ensureCapacity(Int.SIZE_BYTES)
        for (shift in INT_SHIFTS) {
            buffer[size] = (value ushr shift).toByte()
            size += 1
        }
    }

    fun writeLong(value: Long) {
        ensureCapacity(Long.SIZE_BYTES)
        for (shift in LONG_SHIFTS) {
            buffer[size] = (value ushr shift).toByte()
            size += 1
        }
    }

    fun writeString(value: String) {
        val encoded: ByteArray = value.encodeToByteArray()
        require(encoded.size <= MAX_STRING_SIZE_BYTES) {
            "Operational wire string exceeds the maximum encoded length."
        }
        writeInt(encoded.size)
        writeBytes(encoded)
    }

    fun writeNullableString(value: String?) {
        if (value == null) {
            writeInt(NULL_LENGTH)
        } else {
            writeString(value)
        }
    }

    fun writeNullableLong(value: Long?) {
        if (value == null) {
            writeByte(NULL_MARKER)
        } else {
            writeByte(PRESENT_MARKER)
            writeLong(value)
        }
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)

    private fun writeBytes(bytes: ByteArray) {
        ensureCapacity(bytes.size)
        bytes.copyInto(buffer, destinationOffset = size)
        size += bytes.size
    }

    private fun ensureCapacity(additional: Int) {
        val required: Long = size.toLong() + additional.toLong()
        require(required <= OperationalEnvelopeWireCodec.MAX_FRAME_SIZE_BYTES.toLong()) {
            "Operational envelope exceeds the maximum encoded frame size."
        }
        if (required <= buffer.size) {
            return
        }
        var capacity: Int = buffer.size
        while (capacity < required.toInt()) {
            capacity = (capacity * 2).coerceAtMost(
                OperationalEnvelopeWireCodec.MAX_FRAME_SIZE_BYTES,
            )
        }
        buffer = buffer.copyOf(capacity)
    }
}

private class OperationalWireReader(
    private val bytes: ByteArray,
) {
    private var offset: Int = 0

    fun readByte(): Byte {
        requireAvailable(1)
        val value: Byte = bytes[offset]
        offset += 1
        return value
    }

    fun readInt(): Int {
        requireAvailable(Int.SIZE_BYTES)
        var value: Int = 0
        repeat(Int.SIZE_BYTES) {
            value = (value shl Byte.SIZE_BITS) or (bytes[offset].toInt() and BYTE_MASK)
            offset += 1
        }
        return value
    }

    fun readLong(): Long {
        requireAvailable(Long.SIZE_BYTES)
        var value: Long = 0L
        repeat(Long.SIZE_BYTES) {
            value = (value shl Byte.SIZE_BITS) or (bytes[offset].toLong() and BYTE_MASK.toLong())
            offset += 1
        }
        return value
    }

    fun readString(): String {
        val length: Int = readInt()
        if (length !in 0..MAX_STRING_SIZE_BYTES) {
            rejectInvalidField()
        }
        requireAvailable(length)
        val value: String = bytes.decodeToString(
            startIndex = offset,
            endIndex = offset + length,
            throwOnInvalidSequence = true,
        )
        offset += length
        return value
    }

    fun readNullableString(): String? {
        val length: Int = readInt()
        if (length == NULL_LENGTH) {
            return null
        }
        if (length !in 0..MAX_STRING_SIZE_BYTES) {
            rejectInvalidField()
        }
        requireAvailable(length)
        val value: String = bytes.decodeToString(
            startIndex = offset,
            endIndex = offset + length,
            throwOnInvalidSequence = true,
        )
        offset += length
        return value
    }

    fun readNullableLong(): Long? = when (readByte()) {
        NULL_MARKER -> null
        PRESENT_MARKER -> readLong()
        else -> rejectInvalidField()
    }

    fun isExhausted(): Boolean = offset == bytes.size

    private fun requireAvailable(count: Int) {
        if (count < 0 || offset > bytes.size - count) {
            rejectInvalidField()
        }
    }

    private fun rejectInvalidField(): Nothing = throw OperationalWireDecodeException(
        OperationalEnvelopeDecodeFailure.INVALID_FIELD,
    )
}

private class OperationalWireDecodeException(
    val failure: OperationalEnvelopeDecodeFailure,
) : Exception()

private val INT_SHIFTS: IntArray = intArrayOf(24, 16, 8, 0)
private val LONG_SHIFTS: IntArray = intArrayOf(56, 48, 40, 32, 24, 16, 8, 0)
private const val INITIAL_CAPACITY: Int = 256
private const val MAX_STRING_SIZE_BYTES: Int = 16_384
private const val NULL_LENGTH: Int = -1
private const val NULL_MARKER: Byte = 0
private const val PRESENT_MARKER: Byte = 1
private const val BYTE_MASK: Int = 0xFF
