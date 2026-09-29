@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.assets.transform

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.zlib.Z_OK
import platform.zlib.compress2
import platform.zlib.compressBound
import platform.zlib.uLongVar
import platform.zlib.uLongfVar
import platform.zlib.uncompress2

// The system zlib (`libz`, linked by Kotlin/Native's `platform.zlib` binding).
// `compress2`/`uncompress2` speak the same RFC 1950 zlib stream as
// `java.util.zip` with `nowrap = false`, so chunks are portable between
// platforms.
//
// Compile-verified for all three iOS targets; only executed on macOS CI.

internal actual fun platformDeflate(input: ByteArray, level: Int): ByteArray {
    val bound = compressBound(input.size.convert()).toLong()
    require(bound in 0..Int.MAX_VALUE) { "Chunk is too large to compress." }
    val output = ByteArray(bound.toInt().coerceAtLeast(1))
    // A pinned empty array has no element 0 to address; hand zlib a one-byte scratch and length 0.
    val source = if (input.isEmpty()) ByteArray(1) else input
    memScoped {
        val outputLength = alloc<uLongfVar>()
        outputLength.value = output.size.convert()
        val status = source.usePinned { src ->
            output.usePinned { dst ->
                compress2(
                    dst.addressOf(0).reinterpret(),
                    outputLength.ptr,
                    src.addressOf(0).reinterpret(),
                    input.size.convert(),
                    level,
                )
            }
        }
        check(status == Z_OK) { "zlib compression failed." }
        return output.copyOf(outputLength.value.toLong().toInt())
    }
}

internal actual fun platformInflate(compressed: ByteArray, expectedSize: Int): ByteArray {
    // One spare byte: distinguishes "exactly expectedSize" from "more than expectedSize"
    // without ever producing unbounded output.
    val output = ByteArray(expectedSize + 1)
    val source = if (compressed.isEmpty()) ByteArray(1) else compressed
    memScoped {
        val outputLength = alloc<uLongfVar>()
        outputLength.value = output.size.convert()
        val consumed = alloc<uLongVar>()
        consumed.value = compressed.size.convert()
        val status = source.usePinned { src ->
            output.usePinned { dst ->
                uncompress2(
                    dst.addressOf(0).reinterpret(),
                    outputLength.ptr,
                    src.addressOf(0).reinterpret(),
                    consumed.ptr,
                )
            }
        }
        require(status == Z_OK) { "Compressed chunk is corrupt, truncated or too large." }
        require(outputLength.value.toLong() == expectedSize.toLong()) {
            "Compressed chunk expanded to an unexpected size."
        }
        require(consumed.value.toLong() == compressed.size.toLong()) { "Compressed chunk has trailing bytes." }
        return output.copyOf(expectedSize)
    }
}
