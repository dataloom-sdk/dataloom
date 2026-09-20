@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package io.dataloom.api.security

import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.free
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_CTX
import platform.CoreCrypto.CC_SHA256_Final
import platform.CoreCrypto.CC_SHA256_Init
import platform.CoreCrypto.CC_SHA256_Update
import platform.CoreCrypto.CC_SHA512
import platform.CoreCrypto.CC_SHA512_CTX
import platform.CoreCrypto.CC_SHA512_Final
import platform.CoreCrypto.CC_SHA512_Init
import platform.CoreCrypto.CC_SHA512_Update
import kotlin.native.ref.createCleaner

/**
 * Production [DataLoomIncrementalDigestCalculator] backed by Apple's CommonCrypto.
 *
 * This is the default digest implementation for Apple Kotlin/Native targets
 * (`iosArm64`, `iosSimulatorArm64`, `iosX64`). CommonCrypto is reached
 * through Kotlin/Native's built-in `platform.CoreCrypto` cinterop binding,
 * which has been bundled with the Kotlin/Native distribution since 1.3 —
 * this module needs no new `.def` file, the same posture as `AppleDataLoomSecureRandom`'s
 * `platform.posix` usage for `arc4random_buf`.
 *
 * ## One-shot digests
 *
 * [digest] deliberately keeps using CommonCrypto's one-shot convenience
 * functions (`CC_SHA256`/`CC_SHA512`): no native context needs to outlive the
 * call. The same `usePinned`/`addressOf`/`convert` interop pattern already
 * proven in `AppleDataLoomSecureRandom` applies, called once per [digest].
 *
 * One wrinkle versus `arc4random_buf`: `CC_SHA256`/`CC_SHA512`'s output
 * parameter `md` is typed `unsigned char *`, not `void *`, so the pinned
 * output pointer needs an extra [reinterpret] to `UByteVar` before the call.
 *
 * ## Incremental digests
 *
 * [newAccumulator] uses the stateful `Init`/`Update`/`Final` trio. Earlier
 * revisions of this class avoided that trio because it requires a native
 * context struct to stay alive across separate Kotlin calls; that lifecycle
 * cost is now accepted (asset whole-object verification cannot be done in
 * bounded memory otherwise). The cost is contained in one private class:
 * each accumulator owns exactly one `nativeHeap` context, freed
 * deterministically by `finish`/`close`, with a `kotlin.native.ref.Cleaner`
 * as a safety net that frees it if an abandoned accumulator is garbage
 * collected. The context struct is never copied or shared, and only touched
 * from the accumulator's single owning thread.
 */
public class AppleDataLoomDigestCalculator : DataLoomIncrementalDigestCalculator {

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

    override fun newAccumulator(algorithm: DigestAlgorithm): DataLoomDigestAccumulator =
        AppleDigestAccumulator(algorithm)
}

private class AppleDigestAccumulator(algorithm: DigestAlgorithm) : AbstractDigestAccumulator(algorithm) {
    private val context = NativeDigestContext(algorithm)

    // The cleaner holds `context` (not this accumulator), so it can only fire
    // after this accumulator is unreachable. `context.release()` is
    // idempotent, so it is harmless after an explicit finish/close.
    @Suppress("unused")
    private val cleaner = createCleaner(context) { it.release() }

    override fun doUpdate(input: ByteArray, offset: Int, length: Int) = context.update(input, offset, length)

    override fun doFinish(): ByteArray = context.finish()

    override fun doRelease() = context.release()
}

/** Owns one `nativeHeap`-allocated CommonCrypto SHA context. */
private class NativeDigestContext(private val algorithm: DigestAlgorithm) {
    private var sha256: CC_SHA256_CTX? = null
    private var sha512: CC_SHA512_CTX? = null

    init {
        when (algorithm) {
            DigestAlgorithm.SHA_256 -> {
                val context = nativeHeap.alloc<CC_SHA256_CTX>()
                CC_SHA256_Init(context.ptr)
                sha256 = context
            }
            DigestAlgorithm.SHA_512 -> {
                val context = nativeHeap.alloc<CC_SHA512_CTX>()
                CC_SHA512_Init(context.ptr)
                sha512 = context
            }
        }
    }

    fun update(input: ByteArray, offset: Int, length: Int) {
        input.usePinned { pinned ->
            val pointer = pinned.addressOf(offset)
            sha256?.let { CC_SHA256_Update(it.ptr, pointer, length.convert()) }
            sha512?.let { CC_SHA512_Update(it.ptr, pointer, length.convert()) }
        }
    }

    fun finish(): ByteArray {
        val output = ByteArray(expectedDigestLength(algorithm))
        output.usePinned { pinned ->
            val outputPointer = pinned.addressOf(0).reinterpret<UByteVar>()
            sha256?.let { CC_SHA256_Final(outputPointer, it.ptr) }
            sha512?.let { CC_SHA512_Final(outputPointer, it.ptr) }
        }
        return output
    }

    fun release() {
        sha256?.let { nativeHeap.free(it) }
        sha256 = null
        sha512?.let { nativeHeap.free(it) }
        sha512 = null
    }
}
