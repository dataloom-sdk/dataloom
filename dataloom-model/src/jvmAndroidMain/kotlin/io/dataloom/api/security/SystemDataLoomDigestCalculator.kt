package io.dataloom.api.security

import java.security.MessageDigest

/**
 * Production [DataLoomDigestCalculator] backed by [java.security.MessageDigest].
 *
 * This is the default digest implementation for the JVM target, which also
 * currently serves native Android because the current Android adapter
 * modules consume this module's JVM target directly — the same rationale
 * documented on `SystemDataLoomSecureRandom`. `MessageDigest` is core JCA,
 * present since JDK 1.1 and on every Android API level, well below this
 * repository's `minSdk = 21`.
 *
 * `MessageDigest` instances are not safe for concurrent use, so a fresh
 * instance is obtained per [digest] call rather than held as shared mutable
 * state; this class itself has no mutable state and is safe to share across
 * threads.
 */
public class SystemDataLoomDigestCalculator : DataLoomDigestCalculator {

    override fun digest(algorithm: DigestAlgorithm, input: ByteArray): DataLoomDigest {
        val digestBytes = MessageDigest.getInstance(algorithm.jcaName()).digest(input)
        return DataLoomDigest(algorithm, digestBytes)
    }
}

private fun DigestAlgorithm.jcaName(): String = when (this) {
    DigestAlgorithm.SHA_256 -> "SHA-256"
    DigestAlgorithm.SHA_512 -> "SHA-512"
}
