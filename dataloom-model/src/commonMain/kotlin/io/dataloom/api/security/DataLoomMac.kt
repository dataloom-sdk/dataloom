package io.dataloom.api.security

/**
 * Immutable, algorithm-labeled Message Authentication Code (MAC) tag
 * produced by [DataLoomHmacCalculator].
 *
 * ("Mac" is the cryptographic term for this construction; this type has no
 * relationship to Apple hardware.)
 *
 * ## Defensive copying
 *
 * Same pattern as [DataLoomDigest] and `io.dataloom.api.payload.DataLoomPayload`:
 * the constructor defensively copies [bytes], and [copyBytes] returns a
 * fresh defensive copy.
 *
 * ## Render safety
 *
 * A MAC tag proves the holder of the secret key produced it, but the tag
 * itself is not secret — only the key is — so like [DataLoomDigest], [toHex]
 * and [toString] may safely render it.
 *
 * ## Security note — equality is not verification
 *
 * [equals] and [contentEquals] are ordinary (non-constant-time) array
 * comparison, provided only for tests, collections, and logging-safe
 * diagnostics. Callers verifying an untrusted tag against an expected value
 * must use [DataLoomHmacCalculator.verify], **never**
 * `DataLoomMac == DataLoomMac` or `contentEquals`, because an early-exit
 * comparison can leak timing information an attacker can use to forge a tag
 * byte by byte.
 *
 * @param algorithm the algorithm that produced [bytes].
 * @param bytes the raw MAC tag bytes. Defensively copied on construction.
 * @throws IllegalArgumentException if [bytes] does not have the fixed
 *   length required by [algorithm] (32 bytes for
 *   [HmacAlgorithm.HMAC_SHA_256], 64 bytes for [HmacAlgorithm.HMAC_SHA_512]).
 */
public class DataLoomMac(
    public val algorithm: HmacAlgorithm,
    bytes: ByteArray,
) {
    private val bytes: ByteArray = bytes.copyOf()

    init {
        val expectedSize = expectedMacLength(algorithm)
        require(this.bytes.size == expectedSize) {
            "$algorithm requires a tag of exactly $expectedSize bytes, but was ${this.bytes.size}."
        }
    }

    /** Number of bytes in this MAC tag. */
    public val size: Int
        get() = bytes.size

    /**
     * Returns a defensive copy of the internal MAC tag bytes.
     *
     * Mutating the returned array does not affect this MAC.
     */
    public fun copyBytes(): ByteArray = bytes.copyOf()

    /** Lowercase hexadecimal encoding of the MAC tag bytes. */
    public fun toHex(): String = bytes.toHexEncoded()

    /**
     * Ordinary content-based comparison of [algorithm] and tag bytes.
     *
     * Not constant-time. Do not use to verify an untrusted tag — use
     * [DataLoomHmacCalculator.verify] instead.
     */
    public infix fun contentEquals(other: DataLoomMac): Boolean =
        algorithm == other.algorithm && bytes.contentEquals(other.bytes)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DataLoomMac) return false
        return contentEquals(other)
    }

    override fun hashCode(): Int {
        var result: Int = algorithm.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }

    /** Renders as `algorithm:hex`, for example `HMAC_SHA_256:9f86d0...`. */
    override fun toString(): String = "$algorithm:${toHex()}"
}

internal fun expectedMacLength(algorithm: HmacAlgorithm): Int = when (algorithm) {
    HmacAlgorithm.HMAC_SHA_256 -> 32
    HmacAlgorithm.HMAC_SHA_512 -> 64
}
