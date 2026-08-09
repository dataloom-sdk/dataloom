@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.api.random

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.arc4random_buf

/**
 * Production [DataLoomSecureRandom] backed by `arc4random_buf`.
 *
 * This is the default secure-randomness implementation for Apple
 * Kotlin/Native targets (`iosArm64`, `iosSimulatorArm64`, `iosX64`).
 * `arc4random_buf` is Apple's documented recommendation for secure random
 * byte generation on Darwin platforms: it is backed by the OS CSPRNG,
 * requires no seeding, and never fails or blocks the way a file-backed
 * `/dev/random` read can. It has no mutable state and may be shared across
 * threads.
 *
 * This uses the same `usePinned`/`addressOf`/`convert` POSIX interop pattern
 * already proven to compile in this module's other Apple sources (see
 * `AppleRetryAdministrationFileIo.kt`), rather than a Foundation API.
 */
public class AppleDataLoomSecureRandom : DataLoomSecureRandom {

    override fun nextBytes(byteCount: Int): ByteArray {
        require(byteCount > 0) {
            "byteCount must be greater than zero, but was $byteCount."
        }
        val bytes = ByteArray(byteCount)
        bytes.usePinned { pinned ->
            arc4random_buf(pinned.addressOf(0), byteCount.convert())
        }
        return bytes
    }
}
