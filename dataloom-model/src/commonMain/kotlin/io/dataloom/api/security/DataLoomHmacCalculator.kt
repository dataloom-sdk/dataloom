package io.dataloom.api.security

/**
 * Contract for computing and verifying a keyed HMAC over a byte array.
 *
 * ## Purpose
 *
 * [DataLoomHmacCalculator] is the security-primitives foundation gate's
 * "signature/key references" primitive: keyed MAC compute plus
 * constant-time verify. It authenticates with a caller-supplied key; it is
 * not an asymmetric signature scheme, does not perform key management, and
 * does not persist tags.
 *
 * ## Key handling
 *
 * [key] is accepted as a plain, transient `ByteArray` the caller already
 * resolved — for example from a platform keystore entry named by a
 * [KeyReference] — for the duration of one call only. This interface never
 * accepts a [KeyReference] and never resolves one; DataLoom does no key
 * management. Implementations must not log, persist, cache, or include
 * [key] in any diagnostic output.
 *
 * ## Injection
 *
 * Must be injected into components that need keyed integrity. It must not
 * be accessed through a global singleton or a companion-object field.
 *
 * ## Thread safety
 *
 * Implementations shared across threads must be thread-safe.
 */
public interface DataLoomHmacCalculator {

    /**
     * Computes a [DataLoomMac] of [input] keyed by [key] using [algorithm].
     *
     * @param algorithm the HMAC algorithm to compute.
     * @param key the transient secret key bytes. Must not be empty.
     * @param input the complete content to authenticate.
     * @return a new [DataLoomMac] of [input] under [algorithm] and [key].
     * @throws IllegalArgumentException if [key] is empty.
     */
    public fun hmac(algorithm: HmacAlgorithm, key: ByteArray, input: ByteArray): DataLoomMac

    /**
     * Returns `true` if and only if recomputing the HMAC of [input] with
     * [key] under `expected.algorithm` equals [expected].
     *
     * Reads its algorithm from [expected] rather than accepting a second,
     * independent algorithm parameter, removing a footgun where the two
     * could silently disagree. Implementations must compare tag bytes in
     * constant time regardless of where the first differing byte occurs.
     *
     * @param key the transient secret key bytes. Must not be empty.
     * @param input the complete content that was authenticated.
     * @param expected the previously computed [DataLoomMac] to verify against.
     * @return `true` if [input] authenticates under [key] to [expected].
     * @throws IllegalArgumentException if [key] is empty.
     */
    public fun verify(key: ByteArray, input: ByteArray, expected: DataLoomMac): Boolean
}
