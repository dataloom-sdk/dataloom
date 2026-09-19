package io.dataloom.api.security

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Production [DataLoomHmacCalculator] backed by [javax.crypto.Mac].
 *
 * This is the default HMAC implementation for the JVM target, which also
 * currently serves native Android for the same reason documented on
 * [SystemDataLoomDigestCalculator]. `Mac`/`SecretKeySpec` are core JCA,
 * present since JDK 1.4 and on every Android API level.
 *
 * [verify] delegates to [MessageDigest.isEqual], the standard JCA
 * constant-time comparison idiom — distinct from `Arrays.equals` and
 * unrelated to `MessageDigest`'s own hashing role; it is used here purely
 * as a comparison utility.
 *
 * `Mac` instances are not safe for concurrent use, so a fresh instance is
 * obtained per call rather than held as shared mutable state; this class
 * itself has no mutable state and is safe to share across threads.
 */
public class SystemDataLoomHmacCalculator : DataLoomHmacCalculator {

    override fun hmac(algorithm: HmacAlgorithm, key: ByteArray, input: ByteArray): DataLoomMac {
        require(key.isNotEmpty()) { "key must not be empty." }
        val jcaName = algorithm.jcaName()
        val mac = Mac.getInstance(jcaName)
        mac.init(SecretKeySpec(key, jcaName))
        val tagBytes = mac.doFinal(input)
        return DataLoomMac(algorithm, tagBytes)
    }

    override fun verify(key: ByteArray, input: ByteArray, expected: DataLoomMac): Boolean {
        require(key.isNotEmpty()) { "key must not be empty." }
        val actual = hmac(expected.algorithm, key, input)
        return MessageDigest.isEqual(actual.copyBytes(), expected.copyBytes())
    }
}

private fun HmacAlgorithm.jcaName(): String = when (this) {
    HmacAlgorithm.HMAC_SHA_256 -> "HmacSHA256"
    HmacAlgorithm.HMAC_SHA_512 -> "HmacSHA512"
}
