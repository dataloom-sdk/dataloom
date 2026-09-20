package io.dataloom.api.security

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Platform-independent behavioural contract for [DataLoomIncrementalDigestCalculator]
 * and [DataLoomDigestAccumulator]. Each platform's real implementation
 * (JVM `MessageDigest`, Apple CommonCrypto) extends this class in its own
 * test source set, so both must satisfy byte-identical expectations.
 *
 * The known-answer vectors are the same NIST vectors the one-shot tests use.
 */
abstract class IncrementalDigestCalculatorContract {

    protected abstract fun calculator(): DataLoomIncrementalDigestCalculator

    private val algorithms = DigestAlgorithm.entries

    @Test
    fun `SHA_256 of abc fed in three separate updates matches the known digest`() {
        val accumulator = calculator().newAccumulator(DigestAlgorithm.SHA_256)
        accumulator.update("a".encodeToByteArray())
        accumulator.update("b".encodeToByteArray())
        accumulator.update("c".encodeToByteArray())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", accumulator.finish().toHex())
    }

    @Test
    fun `SHA_512 of abc fed in two updates matches the known digest`() {
        val accumulator = calculator().newAccumulator(DigestAlgorithm.SHA_512)
        accumulator.update("ab".encodeToByteArray())
        accumulator.update("c".encodeToByteArray())
        assertEquals(
            "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
            accumulator.finish().toHex(),
        )
    }

    @Test
    fun `finish with no updates equals the digest of empty input`() {
        val calculator = calculator()
        for (algorithm in algorithms) {
            assertEquals(
                calculator.digest(algorithm, ByteArray(0)),
                calculator.newAccumulator(algorithm).finish(),
            )
        }
    }

    @Test
    fun `SHA_256 of empty input matches the known digest`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            calculator().newAccumulator(DigestAlgorithm.SHA_256).finish().toHex(),
        )
    }

    @Test
    fun `accumulated digest equals the one-shot digest for every split size`() {
        val calculator = calculator()
        // 300 bytes crosses several 64-byte (SHA-256) and 128-byte (SHA-512)
        // block boundaries, so split points land inside and across blocks.
        val data = ByteArray(300) { (it * 31 + 7).toByte() }
        for (algorithm in algorithms) {
            val expected = calculator.digest(algorithm, data)
            for (splitSize in listOf(1, 2, 3, 7, 63, 64, 65, 127, 128, 129, 299, 300, 1000)) {
                val accumulator = calculator.newAccumulator(algorithm)
                var position = 0
                while (position < data.size) {
                    val length = minOf(splitSize, data.size - position)
                    accumulator.update(data, position, length)
                    position += length
                }
                assertEquals(expected, accumulator.finish(), "algorithm=$algorithm splitSize=$splitSize")
            }
        }
    }

    @Test
    fun `accumulated digest equals the one-shot digest for a multi-megabyte stream`() {
        val calculator = calculator()
        val chunk = ByteArray(64 * 1024) { (it % 251).toByte() }
        val chunkCount = 40 // 2.5 MiB streamed through one 64 KiB buffer
        val whole = ByteArray(chunk.size * chunkCount) { chunk[it % chunk.size] }
        for (algorithm in algorithms) {
            val accumulator = calculator.newAccumulator(algorithm)
            repeat(chunkCount) { accumulator.update(chunk) }
            assertEquals(calculator.digest(algorithm, whole), accumulator.finish())
        }
    }

    @Test
    fun `update honours offset and length`() {
        val calculator = calculator()
        val data = "xxabcxx".encodeToByteArray()
        for (algorithm in algorithms) {
            val accumulator = calculator.newAccumulator(algorithm)
            accumulator.update(data, 2, 3)
            assertEquals(calculator.digest(algorithm, "abc".encodeToByteArray()), accumulator.finish())
        }
    }

    @Test
    fun `zero-length update is a no-op even at the end of the buffer`() {
        val calculator = calculator()
        val data = "abc".encodeToByteArray()
        val accumulator = calculator.newAccumulator(DigestAlgorithm.SHA_256)
        accumulator.update(data, data.size, 0)
        accumulator.update(ByteArray(0))
        accumulator.update(data)
        assertEquals(calculator.digest(DigestAlgorithm.SHA_256, data), accumulator.finish())
    }

    @Test
    fun `update does not modify the input buffer`() {
        val data = ByteArray(200) { it.toByte() }
        val copy = data.copyOf()
        val accumulator = calculator().newAccumulator(DigestAlgorithm.SHA_512)
        accumulator.update(data)
        accumulator.finish()
        assertContentEquals(copy, data)
    }

    @Test
    fun `accumulator reports the requested algorithm and result carries it`() {
        for (algorithm in algorithms) {
            val accumulator = calculator().newAccumulator(algorithm)
            assertEquals(algorithm, accumulator.algorithm)
            assertEquals(algorithm, accumulator.finish().algorithm)
        }
    }

    @Test
    fun `invalid ranges are rejected and leave the accumulator usable`() {
        val calculator = calculator()
        val data = "abc".encodeToByteArray()
        val accumulator = calculator.newAccumulator(DigestAlgorithm.SHA_256)
        assertFailsWith<IllegalArgumentException> { accumulator.update(data, -1, 1) }
        assertFailsWith<IllegalArgumentException> { accumulator.update(data, 0, -1) }
        assertFailsWith<IllegalArgumentException> { accumulator.update(data, 0, 4) }
        assertFailsWith<IllegalArgumentException> { accumulator.update(data, 3, 1) }
        assertFailsWith<IllegalArgumentException> { accumulator.update(data, 4, 0) }
        accumulator.update(data)
        assertEquals(calculator.digest(DigestAlgorithm.SHA_256, data), accumulator.finish())
    }

    @Test
    fun `finish closes the accumulator`() {
        val accumulator = calculator().newAccumulator(DigestAlgorithm.SHA_256)
        accumulator.finish()
        assertFailsWith<IllegalStateException> { accumulator.finish() }
        assertFailsWith<IllegalStateException> { accumulator.update("x".encodeToByteArray()) }
    }

    @Test
    fun `close is idempotent and a no-op after finish and blocks further use`() {
        val closedEarly = calculator().newAccumulator(DigestAlgorithm.SHA_512)
        closedEarly.update("partial".encodeToByteArray())
        closedEarly.close()
        closedEarly.close()
        assertFailsWith<IllegalStateException> { closedEarly.update("x".encodeToByteArray()) }
        assertFailsWith<IllegalStateException> { closedEarly.finish() }

        val finished = calculator().newAccumulator(DigestAlgorithm.SHA_256)
        finished.finish()
        finished.close()
        finished.close()
    }

    @Test
    fun `use closes the accumulator when the block throws`() {
        val accumulator = calculator().newAccumulator(DigestAlgorithm.SHA_256)
        assertFailsWith<IllegalStateException> {
            accumulator.use {
                it.update("abc".encodeToByteArray())
                throw IllegalStateException("boom")
            }
        }
        assertFailsWith<IllegalStateException> { accumulator.update("x".encodeToByteArray()) }
    }

    @Test
    fun `interleaved accumulators are independent`() {
        val calculator = calculator()
        val a = calculator.newAccumulator(DigestAlgorithm.SHA_256)
        val b = calculator.newAccumulator(DigestAlgorithm.SHA_512)
        val c = calculator.newAccumulator(DigestAlgorithm.SHA_256)
        a.update("hello ".encodeToByteArray())
        b.update("other".encodeToByteArray())
        c.update("world".encodeToByteArray())
        a.update("world".encodeToByteArray())
        assertEquals(calculator.digest(DigestAlgorithm.SHA_256, "hello world".encodeToByteArray()), a.finish())
        assertEquals(calculator.digest(DigestAlgorithm.SHA_512, "other".encodeToByteArray()), b.finish())
        assertEquals(calculator.digest(DigestAlgorithm.SHA_256, "world".encodeToByteArray()), c.finish())
    }

    @Test
    fun `one-shot digest is unaffected by open accumulators`() {
        val calculator = calculator()
        val open = calculator.newAccumulator(DigestAlgorithm.SHA_256)
        open.update("partial".encodeToByteArray())
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            calculator.digest(DigestAlgorithm.SHA_256, "abc".encodeToByteArray()).toHex(),
        )
        open.close()
    }
}
