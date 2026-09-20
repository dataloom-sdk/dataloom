package io.dataloom.assets

import io.dataloom.api.security.DataLoomDigest
import io.dataloom.api.security.DigestAlgorithm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AssetChunkPlanTest {

    private fun digest(seed: Int) = DataLoomDigest(DigestAlgorithm.SHA_256, ByteArray(32) { seed.toByte() })

    @Test
    fun `an exact multiple splits into equal chunks`() {
        val plan = AssetChunkPlan(4_096, 1_024)
        assertEquals(4, plan.chunkCount)
        for (i in 0 until 4) {
            assertEquals(i * 1_024L, plan.offsetBytes(i))
            assertEquals(1_024, plan.lengthBytes(i))
        }
    }

    @Test
    fun `the last chunk holds the remainder`() {
        val plan = AssetChunkPlan(2_500, 1_024)
        assertEquals(3, plan.chunkCount)
        assertEquals(1_024, plan.lengthBytes(0))
        assertEquals(1_024, plan.lengthBytes(1))
        assertEquals(452, plan.lengthBytes(2))
        assertEquals(2_048L, plan.offsetBytes(2))
    }

    @Test
    fun `an asset smaller than one chunk is a single short chunk`() {
        val plan = AssetChunkPlan(10, 1_024)
        assertEquals(1, plan.chunkCount)
        assertEquals(10, plan.lengthBytes(0))
    }

    @Test
    fun `lengths always sum to the total size`() {
        for (total in listOf(1L, 2L, 1_023L, 1_024L, 1_025L, 99_999L)) {
            val plan = AssetChunkPlan(total, 1_024)
            assertEquals(total, (0 until plan.chunkCount).sumOf { plan.lengthBytes(it).toLong() })
        }
    }

    @Test
    fun `sizes above Int range are supported through long offsets`() {
        val plan = AssetChunkPlan(5L * 1024 * 1024 * 1024, 64 * 1024 * 1024)
        assertEquals(80, plan.chunkCount)
        assertEquals(79L * 64 * 1024 * 1024, plan.offsetBytes(79))
        assertEquals(64 * 1024 * 1024, plan.lengthBytes(79))
    }

    @Test
    fun `non-positive sizes are rejected`() {
        assertFailsWith<IllegalArgumentException> { AssetChunkPlan(0, 1_024) }
        assertFailsWith<IllegalArgumentException> { AssetChunkPlan(-1, 1_024) }
        assertFailsWith<IllegalArgumentException> { AssetChunkPlan(10, 0) }
        assertFailsWith<IllegalArgumentException> { AssetChunkPlan(10, -5) }
    }

    @Test
    fun `a plan needing more chunks than an Int can index is rejected`() {
        assertFailsWith<IllegalArgumentException> { AssetChunkPlan(Long.MAX_VALUE, 1) }
    }

    @Test
    fun `out of range chunk indices are rejected`() {
        val plan = AssetChunkPlan(2_500, 1_024)
        assertFailsWith<IllegalArgumentException> { plan.offsetBytes(-1) }
        assertFailsWith<IllegalArgumentException> { plan.lengthBytes(3) }
    }

    @Test
    fun `toLayout produces a contiguous layout with the supplied checksums`() {
        val plan = AssetChunkPlan(2_500, 1_024)
        val checksums = listOf(digest(1), digest(2), digest(3))
        val layout = plan.toLayout(checksums)
        assertEquals(3, layout.chunkCount)
        assertEquals(2_500L, layout.totalSizeBytes)
        assertEquals(checksums, layout.chunks.map { it.checksum })
        assertEquals(listOf(0L, 1_024L, 2_048L), layout.chunks.map { it.offsetBytes })
        assertEquals(listOf(1_024L, 1_024L, 452L), layout.chunks.map { it.lengthBytes })
    }

    @Test
    fun `toLayout rejects a wrong number of checksums`() {
        val plan = AssetChunkPlan(2_500, 1_024)
        assertFailsWith<IllegalArgumentException> { plan.toLayout(listOf(digest(1))) }
        assertFailsWith<IllegalArgumentException> { plan.toLayout(List(4) { digest(it) }) }
    }

    @Test
    fun `plans are compared by value`() {
        assertEquals(AssetChunkPlan(100, 10), AssetChunkPlan(100, 10))
        assertEquals(AssetChunkPlan(100, 10).hashCode(), AssetChunkPlan(100, 10).hashCode())
    }

    @Test
    fun `negotiation clamps the requested size into the provider bounds`() {
        val bounds = AssetChunkSizeBounds(minBytes = 1_000, maxBytes = 8_000)
        assertEquals(1_000, bounds.negotiate(10))
        assertEquals(4_000, bounds.negotiate(4_000))
        assertEquals(8_000, bounds.negotiate(1_000_000))
        val plan = AssetChunkPlan.negotiate(20_000, 1_000_000, bounds)
        assertEquals(8_000, plan.chunkSizeBytes)
        assertEquals(3, plan.chunkCount)
    }

    @Test
    fun `bounds validate their invariants`() {
        assertFailsWith<IllegalArgumentException> { AssetChunkSizeBounds(0, 10) }
        assertFailsWith<IllegalArgumentException> { AssetChunkSizeBounds(10, 9) }
        assertEquals(AssetChunkSizeBounds(5, 5), AssetChunkSizeBounds(5, 5))
    }

    @Test
    fun `the documented default chunk size sits inside the default bounds`() {
        val bounds = AssetChunkSizeBounds.DEFAULT
        assertEquals(
            AssetChunkSizeBounds.DEFAULT_CHUNK_SIZE_BYTES,
            bounds.negotiate(AssetChunkSizeBounds.DEFAULT_CHUNK_SIZE_BYTES),
        )
    }
}
