package io.dataloom.api.conflict

import io.dataloom.api.change.EntityReference
import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.model.ChangeOperation
import io.dataloom.api.payload.EntityVersion
import io.dataloom.api.state.DurableStateCodec
import io.dataloom.api.time.DataLoomInstant

/**
 * Deterministic bounded V1 text codec for [UnresolvedConflictRecord], for use
 * with a generic string-payload [io.dataloom.api.state.DurableStateStore]
 * implementation (for example [RoomDurableStateStore][io.dataloom.queue.room.RoomDurableStateStore]).
 *
 * The format contains only conflict/entity/change identity, enum names, and
 * metadata entries — never [io.dataloom.api.change.ChangeEvent.payload]
 * content, matching [UnresolvedConflictChangeSummary]'s own documented
 * exclusion.
 */
public class UnresolvedConflictRecordCodec : DurableStateCodec<UnresolvedConflictRecord> {

    override fun encode(state: UnresolvedConflictRecord): String {
        val fields = listOf(
            HEADER,
            FORMAT_VERSION,
            state.conflictType.name,
            hexEncode(state.entity.type.value),
            hexEncode(state.entity.id.value),
            state.entity.version?.value?.let { hexEncode(it) } ?: NULL,
            hexEncode(state.localChange.changeEventId.value),
            state.localChange.operation.name,
            encodeMetadata(state.localChange.metadata),
            hexEncode(state.remoteChange.changeEventId.value),
            state.remoteChange.operation.name,
            encodeMetadata(state.remoteChange.metadata),
            encodeMetadata(state.conflictMetadata),
            state.reason.name,
            state.committedAt.epochMilliseconds.toString(),
        )
        val encoded = fields.joinToString("|")
        require(encoded.length <= MAX_ENCODED_LENGTH) {
            "Encoded unresolved conflict record exceeds the bounded V1 limit."
        }
        return encoded
    }

    override fun decode(payload: String): UnresolvedConflictRecord {
        require(payload.length <= MAX_ENCODED_LENGTH) {
            "Encoded unresolved conflict record exceeds the bounded V1 limit."
        }
        return try {
            val fields = payload.split('|')
            require(fields.size == FIELD_COUNT)
            require(fields[0] == HEADER)
            require(fields[1] == FORMAT_VERSION)
            UnresolvedConflictRecord(
                conflictType = ConflictType.valueOf(fields[2]),
                entity = EntityReference(
                    type = EntityType(hexDecode(fields[3])),
                    id = EntityId(hexDecode(fields[4])),
                    version = if (fields[5] == NULL) null else EntityVersion(hexDecode(fields[5])),
                ),
                localChange = UnresolvedConflictChangeSummary(
                    changeEventId = ChangeEventId(hexDecode(fields[6])),
                    operation = ChangeOperation.valueOf(fields[7]),
                    metadata = decodeMetadata(fields[8]),
                ),
                remoteChange = UnresolvedConflictChangeSummary(
                    changeEventId = ChangeEventId(hexDecode(fields[9])),
                    operation = ChangeOperation.valueOf(fields[10]),
                    metadata = decodeMetadata(fields[11]),
                ),
                conflictMetadata = decodeMetadata(fields[12]),
                reason = UnresolvedConflictReason.valueOf(fields[13]),
                committedAt = DataLoomInstant(fields[14].toLong()),
            )
        } catch (malformed: Exception) {
            throw IllegalArgumentException("Malformed unresolved conflict record payload.", malformed)
        }
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

    private fun hexEncode(value: String): String = buildString {
        value.encodeToByteArray().forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            append(HEX[unsigned ushr 4])
            append(HEX[unsigned and 0x0f])
        }
    }

    private fun hexDecode(value: String): String {
        require(value.length % 2 == 0)
        val bytes = ByteArray(value.length / 2)
        for (index in bytes.indices) {
            val high = value[index * 2].hexValue()
            val low = value[(index * 2) + 1].hexValue()
            bytes[index] = ((high shl 4) or low).toByte()
        }
        return bytes.decodeToString(throwOnInvalidSequence = true)
    }

    private fun Char.hexValue(): Int = when (this) {
        in '0'..'9' -> code - '0'.code
        in 'a'..'f' -> code - 'a'.code + 10
        else -> error("Invalid hex digit.")
    }

    private companion object {
        const val HEADER: String = "DATALOOM_UNRESOLVED_CONFLICT_RECORD"
        const val FORMAT_VERSION: String = "1"
        const val NULL: String = "-"
        const val FIELD_COUNT: Int = 15
        const val MAX_ENCODED_LENGTH: Int = 65_536
        const val METADATA_ENTRY_SEPARATOR: String = ";"
        const val METADATA_KEY_VALUE_SEPARATOR: String = ":"
        const val HEX: String = "0123456789abcdef"
    }
}
