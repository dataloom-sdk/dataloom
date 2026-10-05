package io.dataloom.assets.file

import io.dataloom.assets.testAsset
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CommittedAssetRecordCodecTest {

    private suspend fun record(): CommittedAssetRecord {
        val asset = testAsset(id = "codec", size = 2_500, chunkSize = 1_000)
        return CommittedAssetRecord(asset.manifest, intArrayOf(1_000, 1_000, 500))
    }

    @Test
    fun `a record round trips`() = runTest {
        val original = record()
        val decoded = CommittedAssetRecordCodec.decode(CommittedAssetRecordCodec.encode(original))
        assertEquals(original.manifest, decoded.manifest)
        assertContentEquals(original.storedChunkLengths, decoded.storedChunkLengths)
        assertEquals(2_500L, decoded.storedSizeBytes)
    }

    @Test
    fun `malformed payloads are rejected`() = runTest {
        val good = CommittedAssetRecordCodec.encode(record())
        val lines = good.split('\n')
        val bad = listOf(
            "",
            good.take(good.length / 2),
            good.replace("DATALOOM_FILE_ASSET_COMMIT\t1", "DATALOOM_FILE_ASSET_COMMIT\t2"),
            good + "\n",
            // wrong count, wrong value for an untransformed manifest, non-numeric, empty
            (listOf(lines[0], "1000,1000") + lines.drop(2)).joinToString("\n"),
            (listOf(lines[0], "1000,1000,501") + lines.drop(2)).joinToString("\n"),
            (listOf(lines[0], "1000,x,500") + lines.drop(2)).joinToString("\n"),
            (listOf(lines[0], "") + lines.drop(2)).joinToString("\n"),
            "x".repeat(CommittedAssetRecordCodec.MAX_ENCODED_LENGTH + 1),
        )
        for (payload in bad) {
            assertFailsWith<IllegalArgumentException>("payload of length ${payload.length}") {
                CommittedAssetRecordCodec.decode(payload)
            }
        }
    }
}
