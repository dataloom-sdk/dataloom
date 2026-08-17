package io.dataloom.api.strategy

import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.state.DurableStateCodec
import io.dataloom.api.time.DataLoomInstant

/**
 * Deterministic bounded V1 text codec for [StrategyDecisionEvent], for use
 * with a generic string-payload [io.dataloom.api.state.DurableStateStore]
 * implementation (for example [RoomDurableStateStore][io.dataloom.queue.room.RoomDurableStateStore]).
 *
 * The format contains only decision/plan identity, enum names, reason codes,
 * the bounded outcome-detail code, and the commit timestamp -- never
 * transport output, operation input, or provider values, matching
 * [StrategyDecisionEvent]'s own documented payload-free scope.
 */
public class StrategyDecisionEventCodec : DurableStateCodec<StrategyDecisionEvent> {

    override fun encode(state: StrategyDecisionEvent): String {
        val fields = listOf(
            HEADER,
            FORMAT_VERSION,
            hexEncode(state.planId.value),
            state.requestedStrategy.name,
            state.effectiveStrategy.name,
            hexEncode(state.effectiveProfileId.value),
            state.configurationVersion.value.toString(),
            state.direction.name,
            state.mode.name,
            state.disposition.name,
            encodeReasonCodes(state.reasonCodesList),
            state.outcomeKind.name,
            state.outcomeDetail?.let { hexEncode(it) } ?: NULL,
            state.committedAt.epochMilliseconds.toString(),
        )
        val encoded = fields.joinToString("|")
        require(encoded.length <= MAX_ENCODED_LENGTH) {
            "Encoded strategy decision event exceeds the bounded V1 limit."
        }
        return encoded
    }

    override fun decode(payload: String): StrategyDecisionEvent {
        require(payload.length <= MAX_ENCODED_LENGTH) {
            "Encoded strategy decision event exceeds the bounded V1 limit."
        }
        return try {
            val fields = payload.split('|')
            require(fields.size == FIELD_COUNT)
            require(fields[0] == HEADER)
            require(fields[1] == FORMAT_VERSION)
            StrategyDecisionEvent(
                planId = StrategyPlanId(hexDecode(fields[2])),
                requestedStrategy = BuiltInSynchronizationStrategy.valueOf(fields[3]),
                effectiveStrategy = BuiltInSynchronizationStrategy.valueOf(fields[4]),
                effectiveProfileId = StrategyProfileId(hexDecode(fields[5])),
                configurationVersion = StrategyConfigurationVersion(fields[6].toLong()),
                direction = SynchronizationDirection.valueOf(fields[7]),
                mode = SynchronizationMode.valueOf(fields[8]),
                disposition = StrategyDisposition.valueOf(fields[9]),
                reasonCodes = decodeReasonCodes(fields[10]),
                outcomeKind = StrategyDecisionOutcomeKind.valueOf(fields[11]),
                outcomeDetail = if (fields[12] == NULL) null else hexDecode(fields[12]),
                committedAt = DataLoomInstant(fields[13].toLong()),
            )
        } catch (malformed: Exception) {
            throw IllegalArgumentException("Malformed strategy decision event payload.", malformed)
        }
    }

    private fun encodeReasonCodes(reasonCodes: List<String>): String =
        reasonCodes.size.toString() + REASON_CODE_COUNT_SEPARATOR +
            reasonCodes.joinToString(REASON_CODE_SEPARATOR) { hexEncode(it) }

    private fun decodeReasonCodes(field: String): List<String> {
        val parts = field.split(REASON_CODE_COUNT_SEPARATOR, limit = 2)
        require(parts.size == 2)
        val count = parts[0].toInt()
        require(count >= 0)
        if (count == 0) {
            require(parts[1].isEmpty())
            return emptyList()
        }
        val reasonCodes = parts[1].split(REASON_CODE_SEPARATOR).map { hexDecode(it) }
        require(reasonCodes.size == count)
        return reasonCodes
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
        const val HEADER: String = "DATALOOM_STRATEGY_DECISION_EVENT"
        const val FORMAT_VERSION: String = "1"
        const val NULL: String = "-"
        const val FIELD_COUNT: Int = 14
        const val MAX_ENCODED_LENGTH: Int = 65_536
        const val REASON_CODE_SEPARATOR: String = "~"
        const val REASON_CODE_COUNT_SEPARATOR: String = ":"
        const val HEX: String = "0123456789abcdef"
    }
}
