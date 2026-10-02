package io.dataloom.assets.policy

import io.dataloom.api.asset.AssetMediaType
import io.dataloom.api.identifier.AssetId
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.assets.AssetTransferDirection
import io.dataloom.assets.AssetTransferSessionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** [AssetContentQuarantineRecordCodec]: round trips and fail-closed decoding of malformed payloads. */
class AssetContentQuarantineRecordCodecTest {

    private val codec = AssetContentQuarantineRecordCodec()

    private val held = AssetContentQuarantineRecord(
        sessionId = AssetTransferSessionId("s-1|with-pipe"),
        assetId = AssetId("doc|1"),
        version = 7,
        mediaType = AssetMediaType("application/octet-stream"),
        direction = AssetTransferDirection.DOWNLOAD,
        chunkIndex = 3,
        reasonCode = "deny-listed|hash",
        matchedDigestHex = "ab".repeat(32),
        quarantinedAt = DataLoomInstant(1_000L),
    )

    private val released = held.copy(
        status = AssetContentQuarantineStatus.RELEASED,
        release = AssetContentQuarantineRelease("reviewer|one", "cleared|ok", DataLoomInstant(2_000L)),
    )

    @Test
    fun `a held record round trips exactly including pipe characters in free text fields`() {
        val encoded = codec.encode(held)
        assertEquals(held, codec.decode(encoded))
    }

    @Test
    fun `a released record round trips exactly`() {
        val encoded = codec.encode(released)
        assertEquals(released, codec.decode(encoded))
    }

    @Test
    fun `decode rejects an unknown header`() {
        val encoded = codec.encode(held).replaceFirst("DATALOOM_ASSET_CONTENT_QUARANTINE", "SOMETHING_ELSE")
        assertFailsWith<IllegalArgumentException> { codec.decode(encoded) }
    }

    @Test
    fun `decode rejects an unknown format version`() {
        val encoded = codec.encode(held).replaceFirst("\t1\n", "\t99\n")
        assertFailsWith<IllegalArgumentException> { codec.decode(encoded) }
    }

    @Test
    fun `decode rejects a truncated payload`() {
        val encoded = codec.encode(held)
        val truncated = encoded.substring(0, encoded.length / 2)
        assertFailsWith<IllegalArgumentException> { codec.decode(truncated) }
    }

    @Test
    fun `decode rejects a payload with too few fields`() {
        val lines = codec.encode(held).split('\n').toMutableList()
        lines[1] = lines[1].substringBeforeLast('|')
        assertFailsWith<IllegalArgumentException> { codec.decode(lines.joinToString("\n")) }
    }

    @Test
    fun `decode rejects an oversized payload`() {
        val encoded = codec.encode(held)
        val oversized = encoded + "x".repeat(AssetContentQuarantineRecordCodec.MAX_ENCODED_LENGTH)
        assertFailsWith<IllegalArgumentException> { codec.decode(oversized) }
    }

    @Test
    fun `a record with every text field at its maximum length still round trips`() {
        val maxed = held.copy(
            reasonCode = "x".repeat(AssetContentQuarantineRecord.MAX_TEXT_FIELD_LENGTH),
            matchedDigestHex = "f".repeat(AssetContentQuarantineRecord.MAX_TEXT_FIELD_LENGTH),
        )
        assertEquals(maxed, codec.decode(codec.encode(maxed)))
    }
}
