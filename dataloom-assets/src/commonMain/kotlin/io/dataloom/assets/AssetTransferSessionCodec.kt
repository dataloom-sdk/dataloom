package io.dataloom.assets

import io.dataloom.api.asset.AssetManifestHistoryState
import io.dataloom.api.asset.AssetManifestHistoryStateCodec
import io.dataloom.api.state.DurableStateCodec

/**
 * Deterministic bounded V1 text codec for [AssetTransferSession], for use with
 * a generic string-payload [io.dataloom.api.state.DurableStateStore]
 * implementation such as `RoomDurableStateStore`.
 *
 * ## Payload layout
 *
 * ```
 * DATALOOM_ASSET_TRANSFER_SESSION<TAB>1
 * <hex session id>|<direction>|<phase>|<revision>|<failure kind or N>|<committed indices, ascending, comma separated>
 * <the AssetManifestHistoryStateCodec payload of exactly one manifest>
 * ```
 *
 * The manifest is embedded through [AssetManifestHistoryStateCodec] rather than
 * re-implemented, so it is decoded (and its cross-field invariants enforced)
 * by the same code that already guards persisted manifest history.
 *
 * ## What is persisted, and what is not
 *
 * Only structural facts: identifiers, phase, the ascending list of committed
 * chunk indices, the failure kind, the revision, and the manifest (whose
 * per-chunk and whole-object values are *digests*). It never carries asset
 * bytes, chunk bytes, source or sink locations, or key material, so a leaked
 * session record discloses no asset content. The whole-object digest state
 * needed to finish verification after a restart is therefore just the
 * manifest's own whole-object digest plus the `VERIFYING` phase: an in-flight
 * hash accumulator has no portable serialisable form, so verification re-reads
 * the staged bytes and re-hashes them (see the transfer design ADRs).
 *
 * ## Fail-closed decoding
 *
 * [decode] throws [IllegalArgumentException] for a payload that is oversized,
 * truncated, has an unknown header or format version, unparseable fields,
 * committed indices that are not strictly ascending (a canonical writer never
 * produces duplicates or out-of-order lists, so either indicates corruption or
 * tampering), or that violates any [AssetTransferSession] or
 * [io.dataloom.api.asset.AssetManifest] invariant (index outside the layout,
 * `VERIFYING`/`COMPLETED` without every chunk, failure kind inconsistent with
 * phase, gaps in the chunk layout, wrong digest lengths). Those invariants are
 * enforced by the real constructors rather than duplicated here.
 *
 * Bounded: an encoded session is limited to [MAX_ENCODED_LENGTH] characters
 * (the embedded manifest is separately limited by
 * [AssetManifestHistoryStateCodec]), which comfortably covers assets of many
 * thousands of chunks; larger assets need a larger chunk size.
 */
public class AssetTransferSessionCodec : DurableStateCodec<AssetTransferSession> {

    private val manifestCodec = AssetManifestHistoryStateCodec()

    override fun encode(state: AssetTransferSession): String {
        val header = "$HEADER\t$FORMAT_VERSION"
        val fields = listOf(
            hexEncode(state.sessionId.value),
            state.direction.name,
            state.phase.name,
            state.revision.toString(),
            state.failure?.name ?: ABSENT,
            state.committedChunks.joinToString(","),
        ).joinToString("|")
        val manifest = manifestCodec.encode(AssetManifestHistoryState(listOf(state.manifest)))
        val encoded = "$header\n$fields\n$manifest"
        require(encoded.length <= MAX_ENCODED_LENGTH) {
            "Encoded asset transfer session exceeds the bounded V1 limit."
        }
        return encoded
    }

    override fun decode(payload: String): AssetTransferSession {
        require(payload.length <= MAX_ENCODED_LENGTH) {
            "Encoded asset transfer session exceeds the bounded V1 limit."
        }
        return try {
            val lines = payload.split('\n')
            require(lines.size >= 4)
            val header = lines[0].split('\t')
            require(header.size == 2 && header[0] == HEADER && header[1] == FORMAT_VERSION)
            val fields = lines[1].split('|')
            require(fields.size == FIELD_COUNT)
            val manifests = manifestCodec.decode(lines.drop(2).joinToString("\n")).retainedManifests
            require(manifests.size == 1)
            AssetTransferSession(
                sessionId = AssetTransferSessionId(hexDecode(fields[0])),
                direction = AssetTransferDirection.valueOf(fields[1]),
                manifest = manifests.single(),
                phase = AssetTransferPhase.valueOf(fields[2]),
                committedChunks = decodeCommitted(fields[5]),
                failure = if (fields[4] == ABSENT) null else AssetErrorKind.valueOf(fields[4]),
                revision = fields[3].toLong(),
            )
        } catch (malformed: Exception) {
            throw IllegalArgumentException("Malformed asset transfer session payload.", malformed)
        }
    }

    private fun decodeCommitted(field: String): Set<Int> {
        if (field.isEmpty()) return emptySet()
        val indices = field.split(',').map { it.toInt() }
        // Canonical form is strictly ascending; anything else is corruption.
        require(indices.zipWithNext().all { (a, b) -> a < b })
        return indices.toSet()
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
        /** Upper bound on an encoded session, in characters. */
        public const val MAX_ENCODED_LENGTH: Int = 2 * 1_048_576

        private const val HEADER: String = "DATALOOM_ASSET_TRANSFER_SESSION"
        private const val FORMAT_VERSION: String = "1"
        private const val FIELD_COUNT: Int = 6
        private const val ABSENT: String = "N"
        private const val HEX: String = "0123456789abcdef"
    }
}
