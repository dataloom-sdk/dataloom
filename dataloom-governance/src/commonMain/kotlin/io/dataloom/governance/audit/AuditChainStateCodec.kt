package io.dataloom.governance.audit

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.identifier.TenantId
import io.dataloom.api.security.DataLoomMac
import io.dataloom.api.security.HmacAlgorithm
import io.dataloom.api.state.DurableStateCodec
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.governance.rbac.PrincipalId

/**
 * Deterministic bounded V1 text codec for [AuditChainState], for use with a
 * generic string-payload [io.dataloom.api.state.DurableStateStore]
 * implementation (for example [RoomDurableStateStore][io.dataloom.queue.room.RoomDurableStateStore]).
 *
 * Persists every field a record's MAC is computed over plus the MAC and
 * previous-MAC tags themselves -- everything [AuditChainVerifier] needs to
 * verify the chain after a round trip through durable storage. Encoding never
 * re-derives or checks a MAC; that stays [AuditChainVerifier]'s job.
 *
 * ## Format
 *
 * Top level, `|`-separated:
 *
 * ```
 * DATALOOM_GOVERNANCE_DURABLE_AUDIT_CHAIN|1|<recordCount>|<record>*recordCount
 * ```
 *
 * Each `record` is itself `,`-separated (never containing `|`):
 *
 * ```
 * <sequence>,<recordedAtEpochMillis>,<previousMac>,<tenantIdHex>,<principalIdHex>,<eventTypeHex>,<detailsHex>,<mac>
 * ```
 *
 * `previousMac`/`mac` are `-` (absent, previousMac of the first record only)
 * or `<HmacAlgorithm name>:<lowercase hex tag>`. `tenantIdHex`/`principalIdHex`/
 * `eventTypeHex` are the same byte-for-byte hex encoding
 * [io.dataloom.api.conflict.UnresolvedConflictRecordCodec] uses for identifier
 * strings. `detailsHex` reuses that same codec's metadata sub-format
 * (`;`-separated `hexKey:hexValue` entries, sorted by key).
 *
 * [record.sequence][AuditRecord.sequence] is persisted explicitly (rather than
 * only implied by list position) so a truncated or reordered payload fails
 * this codec's own structural check with a decode-time error, in addition to
 * [AuditChainState]'s own construction-time validation.
 */
public class AuditChainStateCodec : DurableStateCodec<AuditChainState> {

    override fun encode(state: AuditChainState): String {
        val fields = buildList {
            add(HEADER)
            add(FORMAT_VERSION)
            add(state.records.size.toString())
            state.records.forEach { record -> add(encodeRecord(record)) }
        }
        val encoded = fields.joinToString(SEPARATOR)
        require(encoded.length <= MAX_ENCODED_LENGTH) {
            "Encoded audit chain state exceeds the bounded V1 limit of $MAX_ENCODED_LENGTH characters."
        }
        return encoded
    }

    override fun decode(payload: String): AuditChainState {
        require(payload.length <= MAX_ENCODED_LENGTH) {
            "Encoded audit chain state exceeds the bounded V1 limit of $MAX_ENCODED_LENGTH characters."
        }
        return try {
            val fields = payload.split(SEPARATOR)
            require(fields.size >= FIXED_FIELD_COUNT)
            require(fields[0] == HEADER)
            require(fields[1] == FORMAT_VERSION)
            val recordCount = fields[2].toInt()
            require(recordCount in 0..AuditChainState.MAX_RECORD_COUNT)
            require(fields.size == FIXED_FIELD_COUNT + recordCount)
            val records = (0 until recordCount).map { index ->
                decodeRecord(index.toLong(), fields[FIXED_FIELD_COUNT + index])
            }
            AuditChainState(records)
        } catch (malformed: Exception) {
            throw IllegalArgumentException("Malformed audit chain state payload.", malformed)
        }
    }

    private fun encodeRecord(record: AuditRecord): String = listOf(
        record.sequence.toString(),
        record.recordedAt.epochMilliseconds.toString(),
        encodeMac(record.previousMac),
        hexEncode(record.event.tenantId.value),
        hexEncode(record.event.principalId.value),
        hexEncode(record.event.eventType.value),
        encodeMetadata(record.event.details),
        encodeMac(record.mac),
    ).joinToString(FIELD_SEPARATOR)

    private fun decodeRecord(expectedSequence: Long, field: String): AuditRecord {
        val parts = field.split(FIELD_SEPARATOR)
        require(parts.size == RECORD_FIELD_COUNT)
        val sequence = parts[0].toLong()
        require(sequence == expectedSequence) {
            "Audit chain record out of position: expected sequence $expectedSequence but found $sequence."
        }
        return AuditRecord(
            sequence = sequence,
            recordedAt = DataLoomInstant(parts[1].toLong()),
            previousMac = decodeMac(parts[2]),
            event = AuditEvent(
                tenantId = TenantId(hexDecode(parts[3])),
                principalId = PrincipalId(hexDecode(parts[4])),
                eventType = AuditEventType(hexDecode(parts[5])),
                details = decodeMetadata(parts[6]),
            ),
            mac = requireNotNull(decodeMac(parts[7])) { "Audit chain record mac must not be absent." },
        )
    }

    private fun encodeMac(mac: DataLoomMac?): String =
        if (mac == null) NULL else "${mac.algorithm.name}$MAC_SEPARATOR${hexEncodeBytes(mac.copyBytes())}"

    private fun decodeMac(field: String): DataLoomMac? {
        if (field == NULL) return null
        val parts = field.split(MAC_SEPARATOR, limit = 2)
        require(parts.size == 2)
        return DataLoomMac(HmacAlgorithm.valueOf(parts[0]), hexDecodeBytes(parts[1]))
    }

    private fun encodeMetadata(metadata: DataLoomMetadata): String =
        metadata.entries.entries.sortedBy { it.key }.joinToString(METADATA_ENTRY_SEPARATOR) { (key, value) ->
            "${hexEncode(key)}$METADATA_KEY_VALUE_SEPARATOR${hexEncode(value)}"
        }

    private fun decodeMetadata(field: String): DataLoomMetadata {
        if (field.isEmpty()) return DataLoomMetadata.Empty
        val entries = field.split(METADATA_ENTRY_SEPARATOR).associate { entry ->
            val parts = entry.split(METADATA_KEY_VALUE_SEPARATOR, limit = 2)
            require(parts.size == 2)
            hexDecode(parts[0]) to hexDecode(parts[1])
        }
        return DataLoomMetadata.of(entries)
    }

    private fun hexEncode(value: String): String = hexEncodeBytes(value.encodeToByteArray())

    private fun hexDecode(value: String): String = hexDecodeBytes(value).decodeToString(throwOnInvalidSequence = true)

    private fun hexEncodeBytes(bytes: ByteArray): String = buildString {
        bytes.forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            append(HEX[unsigned ushr 4])
            append(HEX[unsigned and 0x0f])
        }
    }

    private fun hexDecodeBytes(value: String): ByteArray {
        require(value.length % 2 == 0)
        val bytes = ByteArray(value.length / 2)
        for (index in bytes.indices) {
            val high = value[index * 2].hexValue()
            val low = value[(index * 2) + 1].hexValue()
            bytes[index] = ((high shl 4) or low).toByte()
        }
        return bytes
    }

    private fun Char.hexValue(): Int = when (this) {
        in '0'..'9' -> code - '0'.code
        in 'a'..'f' -> code - 'a'.code + 10
        else -> error("Invalid hex digit.")
    }

    private companion object {
        const val HEADER: String = "DATALOOM_GOVERNANCE_DURABLE_AUDIT_CHAIN"
        const val FORMAT_VERSION: String = "1"
        const val NULL: String = "-"
        const val SEPARATOR: String = "|"
        const val FIELD_SEPARATOR: String = ","
        const val MAC_SEPARATOR: String = ":"
        const val METADATA_ENTRY_SEPARATOR: String = ";"
        const val METADATA_KEY_VALUE_SEPARATOR: String = ":"
        const val HEX: String = "0123456789abcdef"
        const val FIXED_FIELD_COUNT: Int = 3
        const val RECORD_FIELD_COUNT: Int = 8

        /**
         * Same bound [io.dataloom.api.operational.OperationalEventOutboxStateCodec.MAX_ENCODED_LENGTH]
         * uses for the same "growing list persisted as one state value" shape.
         */
        const val MAX_ENCODED_LENGTH: Int = 4 * 1024 * 1024
    }
}
