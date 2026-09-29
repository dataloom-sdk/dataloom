package io.dataloom.assets

import io.dataloom.assets.transform.DeflateAssetCompressor
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DeflateAssetCompressorTest {

    private val compressor = DeflateAssetCompressor()

    @Test
    fun `round trips at and around chunk boundaries`() = runTest {
        val chunk = 1_024
        for (size in listOf(1, 2, chunk - 1, chunk, chunk + 1, 3 * chunk, 64 * 1024 + 1)) {
            for (input in listOf(patternBytes(size), noiseBytes(size), ByteArray(size))) {
                val compressed = compressor.compress(input)
                assertContentEquals(input, compressor.decompress(compressed, size), "size $size")
            }
        }
    }

    @Test
    fun `compressible data shrinks a lot`() = runTest {
        val input = ByteArray(64 * 1024) { 'A'.code.toByte() }
        val compressed = compressor.compress(input)
        assertTrue(compressed.size < input.size / 20, "compressed to ${compressed.size}")
    }

    @Test
    fun `incompressible data does not blow up`() = runTest {
        for (size in listOf(1, 100, 1_024, 64 * 1024, 1024 * 1024)) {
            val compressed = compressor.compress(noiseBytes(size, seed = size))
            // zlib stores incompressible input in stored blocks: a few bytes of overhead per 16 KiB.
            assertTrue(compressed.size <= size + 32 + size / 1_000, "size $size grew to ${compressed.size}")
        }
    }

    @Test
    fun `every level yields a stream every level reads`() = runTest {
        val input = patternBytes(20_000)
        for (level in 1..9) {
            val compressed = DeflateAssetCompressor(level).compress(input)
            assertContentEquals(input, compressor.decompress(compressed, input.size), "level $level")
        }
    }

    @Test
    fun `reads a standard zlib stream produced elsewhere`() = runTest {
        // zlib.compress(b"a"): the same bytes on every platform that speaks RFC 1950.
        val stream = byteArrayOf(0x78, 0x9c.toByte(), 0x4b, 0x04, 0x00, 0x00, 0x62, 0x00, 0x62)
        assertContentEquals("a".encodeToByteArray(), compressor.decompress(stream, 1))
    }

    @Test
    fun `the output is a zlib stream with the standard header`() = runTest {
        val compressed = compressor.compress(patternBytes(500))
        assertEquals(0x78, compressed[0].toInt() and 0xFF)
    }

    @Test
    fun `decompression is bounded by the expected size in both directions`() = runTest {
        val compressed = compressor.compress(ByteArray(10_000))
        assertFailsWith<IllegalArgumentException> { compressor.decompress(compressed, 9_999) }
        assertFailsWith<IllegalArgumentException> { compressor.decompress(compressed, 10_001) }
        assertFailsWith<IllegalArgumentException> { compressor.decompress(compressed, 1) }
    }

    @Test
    fun `a decompression bomb is rejected without producing its full output`() = runTest {
        // ~4 MiB of zeros deflates to a few KiB; asking for 1 KiB must fail.
        val bomb = compressor.compress(ByteArray(4 * 1024 * 1024))
        assertTrue(bomb.size < 8_192)
        assertFailsWith<IllegalArgumentException> { compressor.decompress(bomb, 1_024) }
    }

    @Test
    fun `corrupt truncated empty and trailing-garbage streams are rejected`() = runTest {
        val input = patternBytes(4_000)
        val compressed = compressor.compress(input)
        assertFailsWith<IllegalArgumentException> { compressor.decompress(compressed.copyOf(compressed.size / 2), input.size) }
        assertFailsWith<IllegalArgumentException> { compressor.decompress(compressed.copyOf(compressed.size - 1), input.size) }
        assertFailsWith<IllegalArgumentException> { compressor.decompress(ByteArray(0), input.size) }
        assertFailsWith<IllegalArgumentException> { compressor.decompress(compressed + byteArrayOf(0), input.size) }
        val flipped = compressed.copyOf().also { it[compressed.size / 2] = (it[compressed.size / 2].toInt() xor 0x55).toByte() }
        assertFailsWith<IllegalArgumentException> { compressor.decompress(flipped, input.size) }
        assertFailsWith<IllegalArgumentException> { compressor.decompress(noiseBytes(100), input.size) }
    }

    @Test
    fun `an invalid level is rejected`() {
        assertFailsWith<IllegalArgumentException> { DeflateAssetCompressor(0) }
        assertFailsWith<IllegalArgumentException> { DeflateAssetCompressor(10) }
    }

    @Test
    fun `the label is stable and the compressor is supported`() {
        assertEquals("zlib-deflate", compressor.algorithm.value)
        assertTrue(compressor.isSupported)
    }
}
