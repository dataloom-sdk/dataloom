package io.dataloom.api.security

/**
 * Closed set of keyed message-authentication-code algorithms available for
 * [DataLoomHmacCalculator].
 *
 * This is DataLoom's deliberate, practical stand-in for "signature" as
 * referenced by the security-primitives foundation gate: it gives an
 * application-supplied key proof of origin and tamper-evidence over data,
 * without DataLoom taking on asymmetric key generation, certificate
 * handling, or a public-key infrastructure. See [KeyReference] for how an
 * application records which key it used without handing DataLoom the key
 * itself.
 *
 * Same closed-enum rationale as [DigestAlgorithm]: a small,
 * DataLoom-controlled vocabulary mapped one-to-one onto a concrete backing
 * platform call (`javax.crypto.Mac` on JVM, CommonCrypto's `CCHmac` on Apple
 * targets). Kept as a distinct type from [DigestAlgorithm] — not a shared
 * "hash algorithm" supertype — specifically so that passing an unkeyed
 * algorithm where a keyed one is required, or vice versa, is a compile
 * error rather than a runtime failure.
 */
public enum class HmacAlgorithm {
    /** HMAC using SHA-256, producing a 32-byte tag. */
    HMAC_SHA_256,

    /** HMAC using SHA-512, producing a 64-byte tag. */
    HMAC_SHA_512,
}
