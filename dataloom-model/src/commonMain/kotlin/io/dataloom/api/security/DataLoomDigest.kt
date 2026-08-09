package io.dataloom.api.security

/**
 * Immutable, algorithm-labeled cryptographic digest produced by
 * [DataLoomDigestCalculator].
 *
 * ## Defensive copying
 *
 * The constructor performs a defensive copy of the supplied byte array so
 * that mutating the source array after construction does not affect this
 * digest. [copyBytes] returns a new defensive copy so that mutating the
 * returned array does not affect this digest — the same pattern
 * `io.dataloom.api.payload.DataLoomPayload` uses for its opaque byte
 * content.
 *
 * ## Render safety
 *
 * A digest is a one-way, non-secret output of hashing, not a secret itself
 * — unlike [DataLoomMac] input key material — so unlike most byte-carrying
 * types in this codebase, [toHex] and [toString] may safely render it.
 *
 * ## Equality
 *
 * [equals] and [contentEquals] are ordinary (non-constant-time) comparison,
 * which is safe here: nothing about digest-comparison timing exposes a
 * secret. Constant-time comparison matters for [DataLoomMac], not for this
 * type.
 *
 * @param algorithm the algorithm that produced [bytes].
 * @param bytes the raw digest bytes. Defensively copied on construction.
 * @throws IllegalArgumentException if [bytes] does not have the fixed
 *   length required by [algorithm] (32 bytes for [DigestAlgorithm.SHA_256],
 *   64 bytes for [DigestAlgorithm.SHA_512]).
 */
public class DataLoomDigest(
    public val algorithm: DigestAlgorithm,
    bytes: ByteArray,
) {
    private val bytes: ByteArray = bytes.copyOf()

    init {
        val expectedSize = expectedDigestLength(algorithm)
        require(this.bytes.size == expectedSize) {
            "$algorithm requires a digest of exactly $expectedSize bytes, but was ${this.bytes.size}."
        }
    }

    /** Number of bytes in this digest. */
    public val size: Int
        get() = bytes.size

    /**
     * Returns a defensive copy of the internal digest bytes.
     *
     * Mutating the returned array does not affect this digest.
     */
    public fun copyBytes(): ByteArray = bytes.copyOf()

    /** Lowercase hexadecimal encoding of the digest bytes. */
    public fun toHex(): String = bytes.toHexEncoded()

    /** Content-based comparison of [algorithm] and digest bytes. */
    public infix fun contentEquals(other: DataLoomDigest): Boolean =
        algorithm == other.algorithm && bytes.contentEquals(other.bytes)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DataLoomDigest) return false
        return contentEquals(other)
    }

    override fun hashCode(): Int {
        var result: Int = algorithm.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }

    /** Renders as `algorithm:hex`, for example `SHA_256:9f86d0...`. */
    override fun toString(): String = "$algorithm:${toHex()}"
}

internal fun expectedDigestLength(algorithm: DigestAlgorithm): Int = when (algorithm) {
    DigestAlgorithm.SHA_256 -> 32
    DigestAlgorithm.SHA_512 -> 64
}

/**
 * Lowercase hexadecimal encoding of this byte array.
 *
 * Named to avoid any ambiguity with the Kotlin standard library's own
 * `ByteArray.toHexString()` (stable since Kotlin 2.0) rather than shadowing
 * it under the same name.
 */
internal fun ByteArray.toHexEncoded(): String {
    val hexChars = "0123456789abcdef"
    val builder = StringBuilder(size * 2)
    for (byte in this) {
        val value = byte.toInt() and 0xFF
        builder.append(hexChars[value ushr 4])
        builder.append(hexChars[value and 0x0F])
    }
    return builder.toString()
}
