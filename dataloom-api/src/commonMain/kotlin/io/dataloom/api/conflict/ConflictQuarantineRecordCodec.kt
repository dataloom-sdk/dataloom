package io.dataloom.api.conflict

import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.ConflictResolverId
import io.dataloom.api.state.DurableStateCodec
import io.dataloom.api.time.DataLoomInstant

/**
 * Deterministic bounded V1 text codec for [ConflictQuarantineRecord], for use
 * with a generic string-payload [io.dataloom.api.state.DurableStateStore]
 * implementation (for example `RoomDurableStateStore`).
 *
 * The format contains only counts, timestamps, enum names, and identifiers --
 * never change-event payload content.
 */
public class ConflictQuarantineRecordCodec : DurableStateCodec<ConflictQuarantineRecord> {

    override fun encode(state: ConflictQuarantineRecord): String {
        val release = state.lastRelease
        val fields = listOf(
            HEADER,
            FORMAT_VERSION,
            state.status.name,
            state.occurrenceCount.toString(),
            state.firstSeenAt.epochMilliseconds.toString(),
            state.lastSeenAt.epochMilliseconds.toString(),
            hexEncode(state.lastConflictId.value),
            state.lastResolverId?.value?.let { hexEncode(it) } ?: NULL,
            state.quarantinedAt?.epochMilliseconds?.toString() ?: NULL,
            state.releaseCount.toString(),
            release?.commandId?.value?.let { hexEncode(it) } ?: NULL,
            release?.principalId?.value?.let { hexEncode(it) } ?: NULL,
            release?.authorizationId?.value?.let { hexEncode(it) } ?: NULL,
            release?.reason?.value?.let { hexEncode(it) } ?: NULL,
            release?.releasedAt?.epochMilliseconds?.toString() ?: NULL,
        )
        val encoded = fields.joinToString("|")
        require(encoded.length <= MAX_ENCODED_LENGTH) {
            "Encoded conflict quarantine record exceeds the bounded V1 limit."
        }
        return encoded
    }

    override fun decode(payload: String): ConflictQuarantineRecord {
        require(payload.length <= MAX_ENCODED_LENGTH) {
            "Encoded conflict quarantine record exceeds the bounded V1 limit."
        }
        return try {
            val fields = payload.split('|')
            require(fields.size == FIELD_COUNT)
            require(fields[0] == HEADER)
            require(fields[1] == FORMAT_VERSION)
            val releaseFields = fields.subList(10, 15)
            val release = if (releaseFields.all { it == NULL }) {
                null
            } else {
                require(releaseFields.none { it == NULL })
                ConflictQuarantineRelease(
                    commandId = ConflictAdministrationCommandId(hexDecode(releaseFields[0])),
                    principalId = ConflictAdministrationPrincipalId(hexDecode(releaseFields[1])),
                    authorizationId = ConflictAdministrationAuthorizationId(hexDecode(releaseFields[2])),
                    reason = ConflictAdministrationReason(hexDecode(releaseFields[3])),
                    releasedAt = DataLoomInstant(releaseFields[4].toLong()),
                )
            }
            ConflictQuarantineRecord(
                status = ConflictQuarantineStatus.valueOf(fields[2]),
                occurrenceCount = fields[3].toInt(),
                firstSeenAt = DataLoomInstant(fields[4].toLong()),
                lastSeenAt = DataLoomInstant(fields[5].toLong()),
                lastConflictId = ConflictId(hexDecode(fields[6])),
                lastResolverId = if (fields[7] == NULL) null else ConflictResolverId(hexDecode(fields[7])),
                quarantinedAt = if (fields[8] == NULL) null else DataLoomInstant(fields[8].toLong()),
                releaseCount = fields[9].toInt(),
                lastRelease = release,
            )
        } catch (malformed: Exception) {
            throw IllegalArgumentException("Malformed conflict quarantine record payload.", malformed)
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
        const val HEADER: String = "DATALOOM_CONFLICT_QUARANTINE_RECORD"
        const val FORMAT_VERSION: String = "1"
        const val NULL: String = "-"
        const val FIELD_COUNT: Int = 15
        const val MAX_ENCODED_LENGTH: Int = 65_536
        const val HEX: String = "0123456789abcdef"
    }
}
