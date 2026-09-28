package io.dataloom.governance

/**
 * Raised by [CanonicalReader] for any input that is not a well-formed canonical
 * encoding: truncated, a length that overruns the input, bytes that are not
 * canonical UTF-8, or trailing bytes. Its message never contains input content,
 * so it is safe to surface from a parser of untrusted bytes.
 */
internal class CanonicalFormatException(message: String) : Exception(message)

/**
 * Writer for the governance module's length-prefixed canonical byte layouts
 * (the audit chain's record MAC input and the signed policy pack). All integers
 * are big-endian and every variable-length field is prefixed by its byte length
 * as a u32, so no field boundary is ambiguous.
 */
internal class CanonicalWriter {
    private var buffer = ByteArray(INITIAL_CAPACITY)
    private var size = 0

    fun u8(value: Int) {
        ensureCapacity(1)
        buffer[size++] = value.toByte()
    }

    fun u32(value: Int) {
        ensureCapacity(4)
        for (shift in 24 downTo 0 step 8) {
            buffer[size++] = (value ushr shift).toByte()
        }
    }

    fun u64(value: Long) {
        ensureCapacity(8)
        for (shift in 56 downTo 0 step 8) {
            buffer[size++] = (value ushr shift).toByte()
        }
    }

    /** Length-prefixed bytes. */
    fun bytes(value: ByteArray) {
        u32(value.size)
        raw(value)
    }

    /** Bytes with no length prefix, for splicing an already-encoded section. */
    fun raw(value: ByteArray) {
        ensureCapacity(value.size)
        value.copyInto(buffer, destinationOffset = size)
        size += value.size
    }

    /** Length-prefixed UTF-8. */
    fun string(value: String) {
        bytes(value.encodeToByteArray())
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)

    private fun ensureCapacity(additional: Int) {
        val required = size + additional
        if (required > buffer.size) {
            buffer = buffer.copyOf(maxOf(required, buffer.size * 2))
        }
    }

    private companion object {
        const val INITIAL_CAPACITY = 256
    }
}

/**
 * Strict reader for the layouts [CanonicalWriter] produces, for parsing
 * **untrusted** bytes.
 *
 * Every read is bounds-checked against the remaining input before any
 * allocation, so a hostile length prefix can never cause an allocation larger
 * than the input itself, and no read can throw anything but
 * [CanonicalFormatException]. Strings must be canonical UTF-8: a byte sequence
 * that is not the exact UTF-8 encoding of the string it decodes to (malformed,
 * overlong, or surrogate-encoding sequences) is rejected rather than silently
 * repaired, so one string has exactly one accepted encoding.
 */
internal class CanonicalReader(private val input: ByteArray) {

    /** Number of bytes consumed so far. */
    var position: Int = 0
        private set

    /** Number of bytes not yet consumed. */
    val remaining: Int
        get() = input.size - position

    fun u32(): Int {
        needBytes(4)
        var value = 0
        for (index in 0 until 4) {
            value = (value shl 8) or (input[position + index].toInt() and 0xFF)
        }
        position += 4
        return value
    }

    fun u64(): Long {
        needBytes(8)
        var value = 0L
        for (index in 0 until 8) {
            value = (value shl 8) or (input[position + index].toLong() and 0xFF)
        }
        position += 8
        return value
    }

    /** Length-prefixed bytes. The declared length must fit in the remaining input. */
    fun bytes(): ByteArray {
        val length = u32()
        if (length < 0 || length > remaining) {
            throw CanonicalFormatException("Length prefix does not fit the remaining input.")
        }
        val value = input.copyOfRange(position, position + length)
        position += length
        return value
    }

    /** Length-prefixed, canonical UTF-8. */
    fun string(): String {
        val raw = bytes()
        val decoded = raw.decodeToString()
        // Decoding never fails: malformed input becomes U+FFFD. Re-encoding therefore
        // reproduces the input exactly if and only if the input was canonical UTF-8.
        if (!decoded.encodeToByteArray().contentEquals(raw)) {
            throw CanonicalFormatException("String is not canonical UTF-8.")
        }
        return decoded
    }

    fun requireFullyConsumed() {
        if (remaining != 0) {
            throw CanonicalFormatException("Unexpected trailing bytes.")
        }
    }

    private fun needBytes(count: Int) {
        if (remaining < count) {
            throw CanonicalFormatException("Input ends before a complete field.")
        }
    }
}
