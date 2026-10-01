package io.dataloom.assets.policy

import io.dataloom.api.asset.AssetMediaType
import io.dataloom.api.identifier.AssetId
import io.dataloom.api.state.DurableStateCodec
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.assets.AssetTransferDirection
import io.dataloom.assets.AssetTransferSessionId

/**
 * Deterministic, bounded, fail-closed text codec for
 * [AssetContentQuarantineRecord], for use with a generic string-payload
 * [io.dataloom.api.state.DurableStateStore] implementation such as
 * `RoomDurableStateStore` -- the same role
 * [io.dataloom.assets.AssetTransferSessionCodec] plays for transfer
 * sessions.
 *
 * ## Payload layout
 *
 * ```
 * DATALOOM_ASSET_CONTENT_QUARANTINE<TAB>1
 * <hex sessionId>|<hex assetId>|<version>|<hex mediaType>|<direction>|<chunkIndex>|<hex reasonCode>|<matchedDigestHex>|<quarantinedAtMillis>|<status>
 * <release line: "N", or <hex principal>|<hex reason>|<releasedAtMillis>>
 * ```
 *
 * Free-text fields ([AssetId.value], [AssetMediaType.value], reason codes and
 * release principal/reason) are hex-encoded so no field's content can
 * contain the `|` delimiter and shift a later field's boundary; numeric and
 * enum fields are not, since this codec itself controls their character
 * set.
 *
 * ## What is persisted, and what is not
 *
 * Only metadata: identifiers, the chunk index, the matched digest's hex
 * rendering, the reason code and release evidence. Never the chunk's actual
 * bytes, matching this module's established "never persist payload bytes"
 * rule ([io.dataloom.assets.AssetTransferSessionCodec]'s own doc).
 *
 * ## Fail-closed decoding
 *
 * [decode] throws [IllegalArgumentException] for a payload that is
 * oversized, has an unknown header or format version, an unparseable or
 * wrong-count field list, or that violates an [AssetContentQuarantineRecord]
 * constructor invariant.
 */
public class AssetContentQuarantineRecordCodec : DurableStateCodec<AssetContentQuarantineRecord> {

    override fun encode(state: AssetContentQuarantineRecord): String {
        val header = "$HEADER\t$FORMAT_VERSION"
        val fields = listOf(
            hexEncode(state.sessionId.value),
            hexEncode(state.assetId.value),
            state.version.toString(),
            hexEncode(state.mediaType.value),
            state.direction.name,
            state.chunkIndex.toString(),
            hexEncode(state.reasonCode),
            state.matchedDigestHex,
            state.quarantinedAt.epochMilliseconds.toString(),
            state.status.name,
        ).joinToString("|")
        val release = state.release
        val releaseLine = if (release == null) {
            ABSENT
        } else {
            listOf(
                hexEncode(release.principal),
                hexEncode(release.reason),
                release.releasedAt.epochMilliseconds.toString(),
            ).joinToString("|")
        }
        val encoded = "$header\n$fields\n$releaseLine"
        require(encoded.length <= MAX_ENCODED_LENGTH) {
            "Encoded asset content quarantine record exceeds the bounded V1 limit."
        }
        return encoded
    }

    override fun decode(payload: String): AssetContentQuarantineRecord {
        require(payload.length <= MAX_ENCODED_LENGTH) {
            "Encoded asset content quarantine record exceeds the bounded V1 limit."
        }
        return try {
            val lines = payload.split('\n')
            require(lines.size == 3)
            val header = lines[0].split('\t')
            require(header.size == 2 && header[0] == HEADER && header[1] == FORMAT_VERSION)
            val fields = lines[1].split('|')
            require(fields.size == FIELD_COUNT)
            val releaseFields = lines[2]
            val release = if (releaseFields == ABSENT) {
                null
            } else {
                val parts = releaseFields.split('|')
                require(parts.size == RELEASE_FIELD_COUNT)
                AssetContentQuarantineRelease(
                    principal = hexDecode(parts[0]),
                    reason = hexDecode(parts[1]),
                    releasedAt = DataLoomInstant(parts[2].toLong()),
                )
            }
            AssetContentQuarantineRecord(
                sessionId = AssetTransferSessionId(hexDecode(fields[0])),
                assetId = AssetId(hexDecode(fields[1])),
                version = fields[2].toLong(),
                mediaType = AssetMediaType(hexDecode(fields[3])),
                direction = AssetTransferDirection.valueOf(fields[4]),
                chunkIndex = fields[5].toInt(),
                reasonCode = hexDecode(fields[6]),
                matchedDigestHex = fields[7],
                quarantinedAt = DataLoomInstant(fields[8].toLong()),
                status = AssetContentQuarantineStatus.valueOf(fields[9]),
                release = release,
            )
        } catch (malformed: Exception) {
            throw IllegalArgumentException("Malformed asset content quarantine record payload.", malformed)
        }
    }

    private fun hexEncode(value: String): String = buildString {
        value.encodeToByteArray().forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            append(HEX[unsigned ushr 4])
            append(HEX[unsigned and 0x0f])
        }
    }

    private fun hexDecode(value: String): String {
        require(value.length % 2 == 0)
        val bytes = ByteArray(value.length / 2) { index ->
            val high = value[index * 2].hexValue()
            val low = value[index * 2 + 1].hexValue()
            ((high shl 4) or low).toByte()
        }
        return bytes.decodeToString(throwOnInvalidSequence = true)
    }

    private fun Char.hexValue(): Int = when (this) {
        in '0'..'9' -> code - '0'.code
        in 'a'..'f' -> code - 'a'.code + 10
        else -> error("Invalid hex digit.")
    }

    public companion object {
        /** Upper bound on an encoded record, in characters. */
        public const val MAX_ENCODED_LENGTH: Int = 8_192

        private const val HEADER: String = "DATALOOM_ASSET_CONTENT_QUARANTINE"
        private const val FORMAT_VERSION: String = "1"
        private const val FIELD_COUNT: Int = 10
        private const val RELEASE_FIELD_COUNT: Int = 3
        private const val ABSENT: String = "N"
        private const val HEX: String = "0123456789abcdef"
    }
}
