@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.api.security

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.kCCHmacAlgSHA256
import platform.CoreCrypto.kCCHmacAlgSHA512

/**
 * Production [DataLoomHmacCalculator] backed by Apple's CommonCrypto.
 *
 * This is the default HMAC implementation for Apple Kotlin/Native targets
 * (`iosArm64`, `iosSimulatorArm64`, `iosX64`), reached the same way
 * [AppleDataLoomDigestCalculator] reaches CommonCrypto — the built-in,
 * bundled `platform.CoreCrypto` binding, no new `.def` file needed.
 *
 * Uses CommonCrypto's one-shot `CCHmac(algorithm, key, keyLength, data,
 * dataLength, macOut)` function rather than the `CCHmacInit`/`Update`/`Final`
 * stateful trio, for the same reason [AppleDataLoomDigestCalculator] prefers
 * `CC_SHA256`/`CC_SHA512` over their stateful equivalents. All of `CCHmac`'s
 * pointer parameters (`key`, `data`, `macOut`) are `const void*`/`void*`, so
 * — unlike the digest calculator's output parameter — no `reinterpret` is
 * needed.
 *
 * CommonCrypto has no constant-time comparator equivalent to JCA's
 * `MessageDigest.isEqual`, so [verify] hand-rolls one.
 */
public class AppleDataLoomHmacCalculator : DataLoomHmacCalculator {

    override fun hmac(algorithm: HmacAlgorithm, key: ByteArray, input: ByteArray): DataLoomMac {
        require(key.isNotEmpty()) { "key must not be empty." }
        val output = ByteArray(expectedMacLength(algorithm))
        val commonCryptoAlgorithm = algorithm.commonCryptoAlgorithm()
        output.usePinned { outputPinned ->
            key.usePinned { keyPinned ->
                input.usePinnedAddressOrNull { inputPointer ->
                    CCHmac(
                        commonCryptoAlgorithm,
                        keyPinned.addressOf(0),
                        key.size.convert(),
                        inputPointer,
                        input.size.convert(),
                        outputPinned.addressOf(0),
                    )
                }
            }
        }
        return DataLoomMac(algorithm, output)
    }

    override fun verify(key: ByteArray, input: ByteArray, expected: DataLoomMac): Boolean {
        require(key.isNotEmpty()) { "key must not be empty." }
        val actual = hmac(expected.algorithm, key, input)
        return constantTimeEquals(actual.copyBytes(), expected.copyBytes())
    }
}

private fun HmacAlgorithm.commonCryptoAlgorithm() = when (this) {
    HmacAlgorithm.HMAC_SHA_256 -> kCCHmacAlgSHA256
    HmacAlgorithm.HMAC_SHA_512 -> kCCHmacAlgSHA512
}

/**
 * Constant-time byte-array comparison.
 *
 * Accumulates the bitwise OR of all byte differences without
 * short-circuiting, then checks once at the end, so comparison time does not
 * depend on where the first differing byte occurs. Length is checked first
 * and may return immediately on mismatch — length itself is not secret,
 * only content is.
 */
private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var difference = 0
    for (i in a.indices) {
        difference = difference or (a[i].toInt() xor b[i].toInt())
    }
    return difference == 0
}
