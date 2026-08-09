@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.api.security

import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA512

/**
 * Production [DataLoomDigestCalculator] backed by Apple's CommonCrypto.
 *
 * This is the default digest implementation for Apple Kotlin/Native targets
 * (`iosArm64`, `iosSimulatorArm64`, `iosX64`). CommonCrypto is reached
 * through Kotlin/Native's built-in `platform.CoreCrypto` cinterop binding,
 * which has been bundled with the Kotlin/Native distribution since 1.3 —
 * this module needs no new `.def` file, the same posture as `AppleDataLoomSecureRandom`'s
 * `platform.posix` usage for `arc4random_buf`.
 *
 * This deliberately uses CommonCrypto's one-shot convenience functions
 * (`CC_SHA256`/`CC_SHA512`), not the `Init`/`Update`/`Final` stateful trio:
 * the stateful trio would require holding a native context struct alive
 * across separate Kotlin calls, real added complexity this simpler,
 * one-call-per-digest shape does not need. The same `usePinned`/`addressOf`/`convert`
 * interop pattern already proven to compile in `AppleDataLoomSecureRandom`
 * applies here, called once per [digest].
 *
 * One wrinkle versus `arc4random_buf`: `CC_SHA256`/`CC_SHA512`'s output
 * parameter `md` is typed `unsigned char *`, not `void *`, so the pinned
 * output pointer needs an extra [reinterpret] to `UByteVar` before the call.
 */
public class AppleDataLoomDigestCalculator : DataLoomDigestCalculator {

    override fun digest(algorithm: DigestAlgorithm, input: ByteArray): DataLoomDigest {
        val output = ByteArray(expectedDigestLength(algorithm))
        output.usePinned { outputPinned ->
            val outputPointer = outputPinned.addressOf(0).reinterpret<UByteVar>()
            input.usePinnedAddressOrNull { inputPointer ->
                when (algorithm) {
                    DigestAlgorithm.SHA_256 -> CC_SHA256(inputPointer, input.size.convert(), outputPointer)
                    DigestAlgorithm.SHA_512 -> CC_SHA512(inputPointer, input.size.convert(), outputPointer)
                }
            }
        }
        return DataLoomDigest(algorithm, output)
    }
}
