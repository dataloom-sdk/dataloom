package io.dataloom.api.payload

/**
 * Immutable opaque payload carrying application-defined byte content.
 *
 * DataLoom treats the content of this payload as completely opaque. DataLoom
 * may transport, queue, and coordinate a payload, but it must not interpret
 * or inspect the byte content.
 *
 * The host application, or a configured serializer provider, is responsible
 * for all serialization, deserialization, encoding, decoding, compression, and
 * encryption of payload content before and after passing it to DataLoom.
 *
 * ## Defensive copying
 *
 * The constructor performs a defensive copy of the supplied byte array so that
 * mutating the source array after construction does not affect the payload.
 * [copyBytes] returns a new defensive copy so that mutating the returned array
 * does not affect the payload.
 *
 * ## Equality and hash code
 *
 * Equality compares both the content type and the byte content. Two payloads
 * with identical byte content and identical content type are considered equal.
 * Hash codes are derived from the content type and the byte content.
 *
 * ## Safe `toString()`
 *
 * `toString()` reports only the content type and size. Raw payload bytes are
 * never included in the string representation to avoid accidental logging of
 * sensitive application data.
 *
 * ## Security note
 *
 * Payload bytes may contain application-sensitive data. Host applications are
 * responsible for applying appropriate encryption and secure storage policies
 * before and after handing payloads to DataLoom. DataLoom does not
 * automatically log or inspect payload content.
 *
 * @param contentType the content type that identifies the format of the
 *   payload bytes. Ownership: payload producer.
 * @param bytes the raw payload bytes. Defensively copied on construction.
 */
public class DataLoomPayload(
    /** Content-type descriptor for the payload bytes. */
    public val contentType: PayloadContentType,
    bytes: ByteArray,
) {
    private val bytes: ByteArray = bytes.copyOf()

    /**
     * Number of bytes in this payload.
     *
     * Returns `0` when the payload is empty.
     */
    public val size: Int
        get() = bytes.size

    /**
     * Returns `true` when this payload contains no bytes.
     */
    public val isEmpty: Boolean
        get() = bytes.isEmpty()

    /**
     * Returns a defensive copy of the internal byte array.
     *
     * Mutating the returned array does not affect this payload.
     */
    public fun copyBytes(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DataLoomPayload) return false
        return contentType == other.contentType && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result: Int = contentType.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }

    /**
     * Returns a diagnostic representation that includes the content type and
     * byte count. Raw payload bytes are never included.
     */
    override fun toString(): String = "DataLoomPayload(contentType=$contentType, size=$size)"
}
