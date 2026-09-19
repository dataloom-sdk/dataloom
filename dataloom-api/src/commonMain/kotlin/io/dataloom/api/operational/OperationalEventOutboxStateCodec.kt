package io.dataloom.api.operational

import io.dataloom.api.state.DurableStateCodec
import io.dataloom.api.time.DataLoomInstant
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Deterministic bounded text codec for [OperationalEventOutboxState], for use
 * with a generic string-payload [io.dataloom.api.state.DurableStateStore]
 * implementation (for example [RoomDurableStateStore][io.dataloom.queue.room.RoomDurableStateStore]).
 *
 * ## Reuses the frozen envelope wire frame, does not reinvent it
 *
 * Each [OperationalEventEnvelope] entry is encoded with the existing frozen
 * [OperationalEnvelopeWireCodec] V1 frame -- see
 * [the operational envelope wire format doc](https://github.com/dataloom-sdk/dataloom/blob/main/docs/api/operational-envelope-wire-format.md)
 * for that frame's own byte layout and compatibility rules. This codec never
 * re-derives envelope bytes itself; it only Base64-wraps each envelope's
 * existing frame so that multiple entries can be joined into one text
 * payload, and decodes an entry back through
 * [OperationalEnvelopeWireCodec.decode] -- so a frame that fails wire
 * validation fails this codec's decoding too, rather than silently
 * round-tripping corrupt or non-canonical bytes.
 *
 * ## Payload format
 *
 * The outbox payload format is versioned independently of the envelope frame.
 * Fields are `|`-separated; no field can contain `|` (numbers, Base64, and
 * the `,`-joined compounds below).
 *
 * Version `2` (written):
 *
 * ```
 * DATALOOM_OPERATIONAL_EVENT_OUTBOX|2|<sequenceFloor>|<markCount>|<entryCount>
 *   |<mark>*markCount|<entry>*entryCount
 * mark  = base64(utf8(orderingKey)),<highWaterSequence>     (sorted by key)
 * entry = <sequence>,<acknowledgedAtEpochMillis or ->,base64(envelopeFrame)
 * ```
 *
 * Version `1` (read only): `HEADER|1|<entryCount>|base64(envelopeFrame)*` --
 * bare envelopes with no sequence numbers or acknowledgement state, written
 * before per-workflow ordering existed. Decoding it assigns each entry the
 * next sequence for its ordering key in list order and treats every entry as
 * pending, which is exactly the state a version-`1` outbox represented
 * (acknowledgement then deleted, so no tombstones ever existed).
 *
 * The persisted [io.dataloom.api.state.DurableStateRecord.schemaVersion]
 * written alongside this payload (see [DurableOperationalEventOutbox.CURRENT_SCHEMA_VERSION])
 * is informational to this codec: the payload's own version field decides how
 * it is read.
 *
 * ## What this never encodes
 *
 * [OperationalEnvelopeWireCodec] itself never accepts raw payload bytes or
 * application objects, only envelope identity, routing, and already-redacted
 * attributes -- this codec inherits that boundary unchanged.
 */
@OptIn(ExperimentalEncodingApi::class)
public class OperationalEventOutboxStateCodec : DurableStateCodec<OperationalEventOutboxState> {

    override fun encode(state: OperationalEventOutboxState): String {
        require(state.entries.size <= MAX_ENTRY_COUNT) {
            "OperationalEventOutboxState entry count exceeds the bounded limit."
        }
        require(state.sequenceHighWaterMarks.size <= DurableOperationalEventOutbox.MAXIMUM_TRACKED_ORDERING_KEYS) {
            "OperationalEventOutboxState sequence high-water mark count exceeds the bounded limit."
        }
        val fields = buildList {
            add(HEADER)
            add(FORMAT_VERSION_2)
            add(state.sequenceFloor.toString())
            add(state.sequenceHighWaterMarks.size.toString())
            add(state.entries.size.toString())
            state.sequenceHighWaterMarks.entries.sortedBy { it.key.value }.forEach { (key, sequence) ->
                add(Base64.encode(key.value.encodeToByteArray()) + COMPOUND_SEPARATOR + sequence)
            }
            state.entries.forEach { entry ->
                add(
                    listOf(
                        entry.sequence.toString(),
                        entry.acknowledgedAt?.epochMilliseconds?.toString() ?: NOT_ACKNOWLEDGED,
                        Base64.encode(OperationalEnvelopeWireCodec.encode(entry.envelope)),
                    ).joinToString(COMPOUND_SEPARATOR),
                )
            }
        }
        val encoded = fields.joinToString(SEPARATOR)
        require(encoded.length <= MAX_ENCODED_LENGTH) {
            "Encoded operational event outbox state exceeds the bounded limit."
        }
        return encoded
    }

    override fun decode(payload: String): OperationalEventOutboxState {
        require(payload.length <= MAX_ENCODED_LENGTH) {
            "Encoded operational event outbox state exceeds the bounded limit."
        }
        return try {
            val fields = payload.split(SEPARATOR)
            require(fields.size >= 2)
            require(fields[0] == HEADER)
            when (fields[1]) {
                FORMAT_VERSION_1 -> decodeVersion1(fields)
                FORMAT_VERSION_2 -> decodeVersion2(fields)
                else -> throw IllegalArgumentException("Unsupported operational event outbox payload version.")
            }
        } catch (malformed: Exception) {
            throw IllegalArgumentException("Malformed operational event outbox state payload.", malformed)
        }
    }

    private fun decodeVersion1(fields: List<String>): OperationalEventOutboxState {
        require(fields.size >= V1_FIXED_FIELD_COUNT)
        val count = fields[2].toInt()
        require(count in 0..MAX_ENTRY_COUNT)
        require(fields.size == V1_FIXED_FIELD_COUNT + count)
        val lastSequenceByKey = HashMap<OperationalEventOrderingKey, Long>()
        val entries = (0 until count).map { index ->
            val envelope = decodeEnvelope(fields[V1_FIXED_FIELD_COUNT + index])
            val key = OperationalEventOrderingKey.forWorkflow(envelope.workflowId)
            val sequence = (lastSequenceByKey[key] ?: 0L) + 1L
            lastSequenceByKey[key] = sequence
            OperationalEventOutboxEntry(sequence, envelope)
        }
        return OperationalEventOutboxState(entries)
    }

    private fun decodeVersion2(fields: List<String>): OperationalEventOutboxState {
        require(fields.size >= V2_FIXED_FIELD_COUNT)
        val floor = fields[2].toLong()
        val markCount = fields[3].toInt()
        val entryCount = fields[4].toInt()
        require(markCount in 0..DurableOperationalEventOutbox.MAXIMUM_TRACKED_ORDERING_KEYS)
        require(entryCount in 0..MAX_ENTRY_COUNT)
        require(fields.size == V2_FIXED_FIELD_COUNT + markCount + entryCount)
        val marks = LinkedHashMap<OperationalEventOrderingKey, Long>()
        (0 until markCount).forEach { index ->
            val parts = fields[V2_FIXED_FIELD_COUNT + index].split(COMPOUND_SEPARATOR)
            require(parts.size == 2)
            val key = OperationalEventOrderingKey(Base64.decode(parts[0]).decodeToString())
            require(marks.put(key, parts[1].toLong()) == null)
        }
        val entries = (0 until entryCount).map { index ->
            val parts = fields[V2_FIXED_FIELD_COUNT + markCount + index].split(COMPOUND_SEPARATOR)
            require(parts.size == 3)
            OperationalEventOutboxEntry(
                sequence = parts[0].toLong(),
                envelope = decodeEnvelope(parts[2]),
                acknowledgedAt = parts[1].takeUnless { it == NOT_ACKNOWLEDGED }?.let { DataLoomInstant(it.toLong()) },
            )
        }
        return OperationalEventOutboxState(entries, marks, floor)
    }

    private fun decodeEnvelope(base64Frame: String): OperationalEventEnvelope =
        when (val decoded = OperationalEnvelopeWireCodec.decode(Base64.decode(base64Frame))) {
            is OperationalEnvelopeDecodeResult.Decoded -> decoded.envelope
            is OperationalEnvelopeDecodeResult.Rejected ->
                throw IllegalArgumentException("Malformed operational event outbox entry frame.")
        }

    private companion object {
        const val HEADER: String = "DATALOOM_OPERATIONAL_EVENT_OUTBOX"
        const val FORMAT_VERSION_1: String = "1"
        const val FORMAT_VERSION_2: String = "2"
        const val SEPARATOR: String = "|"
        const val COMPOUND_SEPARATOR: String = ","
        const val NOT_ACKNOWLEDGED: String = "-"
        const val V1_FIXED_FIELD_COUNT: Int = 3
        const val V2_FIXED_FIELD_COUNT: Int = 5
        const val MAX_ENTRY_COUNT: Int = 10_000
        const val MAX_ENCODED_LENGTH: Int = 4 * 1024 * 1024
    }
}
