package io.dataloom.api.security

import java.security.MessageDigest

/**
 * Production [DataLoomIncrementalDigestCalculator] backed by [java.security.MessageDigest].
 *
 * This is the default digest implementation for the JVM target, which also
 * currently serves native Android because the current Android adapter
 * modules consume this module's JVM target directly — the same rationale
 * documented on `SystemDataLoomSecureRandom`. `MessageDigest` is core JCA,
 * present since JDK 1.1 and on every Android API level, well below this
 * repository's `minSdk = 21`.
 *
 * `MessageDigest` instances are not safe for concurrent use, so a fresh
 * instance is obtained per [digest] call and per [newAccumulator] call
 * rather than held as shared mutable state; this class itself has no mutable
 * state and is safe to share across threads. An accumulator's instance is
 * owned by that accumulator alone.
 */
public class SystemDataLoomDigestCalculator : DataLoomIncrementalDigestCalculator {

    override fun digest(algorithm: DigestAlgorithm, input: ByteArray): DataLoomDigest {
        val digestBytes = MessageDigest.getInstance(algorithm.jcaName()).digest(input)
        return DataLoomDigest(algorithm, digestBytes)
    }

    override fun newAccumulator(algorithm: DigestAlgorithm): DataLoomDigestAccumulator =
        SystemDigestAccumulator(algorithm)
}

private class SystemDigestAccumulator(algorithm: DigestAlgorithm) : AbstractDigestAccumulator(algorithm) {
    private val messageDigest: MessageDigest = MessageDigest.getInstance(algorithm.jcaName())

    override fun doUpdate(input: ByteArray, offset: Int, length: Int) {
        messageDigest.update(input, offset, length)
    }

    override fun doFinish(): ByteArray = messageDigest.digest()

    override fun doRelease() {
        // MessageDigest holds no native resources; it is reclaimed by GC.
    }
}

private fun DigestAlgorithm.jcaName(): String = when (this) {
    DigestAlgorithm.SHA_256 -> "SHA-256"
    DigestAlgorithm.SHA_512 -> "SHA-512"
}
