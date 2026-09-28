package io.dataloom.assets.transform

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

internal actual fun platformDeflate(input: ByteArray, level: Int): ByteArray {
    val deflater = Deflater(level, false)
    try {
        deflater.setInput(input)
        deflater.finish()
        val out = ByteArrayOutputStream(maxOf(64, input.size / 2))
        val buffer = ByteArray(8 * 1024)
        while (!deflater.finished()) {
            val n = deflater.deflate(buffer)
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    } finally {
        deflater.end()
    }
}

internal actual fun platformInflate(compressed: ByteArray, expectedSize: Int): ByteArray {
    val inflater = Inflater(false)
    try {
        inflater.setInput(compressed)
        // One spare byte lets zlib consume the stream trailer when the output is exactly
        // full, and lets over-long output be detected without ever producing more.
        val out = ByteArray(expectedSize + 1)
        var total = 0
        while (!inflater.finished()) {
            val n = try {
                inflater.inflate(out, total, out.size - total)
            } catch (e: DataFormatException) {
                throw IllegalArgumentException("Compressed chunk is corrupt.", e)
            }
            total += n
            require(total <= expectedSize) { "Compressed chunk expands beyond its expected size." }
            // No progress and no end of stream: the input ran out (or needs a preset dictionary).
            require(n > 0 || inflater.finished()) { "Compressed chunk is truncated." }
        }
        require(total == expectedSize) { "Compressed chunk expanded to an unexpected size." }
        require(inflater.remaining == 0) { "Compressed chunk has trailing bytes." }
        return out.copyOf(total)
    } finally {
        inflater.end()
    }
}
