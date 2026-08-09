package io.dataloom.api.security

/**
 * Closed set of unkeyed cryptographic hash algorithms available for
 * [DataLoomDigestCalculator].
 *
 * This is a closed enum, not an open extensible type, because it names a
 * small, DataLoom-controlled vocabulary that maps one-to-one onto a concrete
 * backing platform call (`java.security.MessageDigest` on JVM,
 * CommonCrypto's `CC_SHA256`/`CC_SHA512` on Apple targets). An arbitrary
 * caller-supplied string would not actually work against either backing
 * API, so an open type would only defer a real failure from compile time to
 * runtime.
 *
 * [DigestAlgorithm] is a distinct type from [HmacAlgorithm] so that an
 * unkeyed algorithm can never be passed where a keyed algorithm is
 * required, or vice versa, at compile time.
 */
public enum class DigestAlgorithm {
    /** SHA-256, producing a 32-byte digest. */
    SHA_256,

    /** SHA-512, producing a 64-byte digest. */
    SHA_512,
}
