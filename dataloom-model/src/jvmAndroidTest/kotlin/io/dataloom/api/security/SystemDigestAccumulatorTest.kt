package io.dataloom.api.security

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

class SystemDigestAccumulatorTest : IncrementalDigestCalculatorContract() {

    override fun calculator(): DataLoomIncrementalDigestCalculator = SystemDataLoomDigestCalculator()

    @Test
    fun `accumulated digest agrees with java security MessageDigest directly`() {
        val data = ByteArray(10_000) { (it * 13).toByte() }
        val accumulator = calculator().newAccumulator(DigestAlgorithm.SHA_256)
        data.asList().chunked(777).forEach { accumulator.update(it.toByteArray()) }
        val expected = MessageDigest.getInstance("SHA-256").digest(data)
        assertEquals(DataLoomDigest(DigestAlgorithm.SHA_256, expected), accumulator.finish())
    }
}
