package io.dataloom.api.operational

import io.dataloom.api.state.DurableStateCodec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Deterministic bounded V1 text codec for [OperationalEventOutboxState], for
 * use with a generic string-payload [io.dataloom.api.state.DurableStateStore]
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
            "OperationalEventOutboxState entry count exceeds the bounded V1 limit."
        }
        val fields = buildList {
            add(HEADER)
            add(FORMAT_VERSION)
            add(state.entries.size.toString())
            state.entries.forEach { envelope ->
                add(Base64.encode(OperationalEnvelopeWireCodec.encode(envelope)))
            }
        }
        val encoded = fields.joinToString(SEPARATOR)
        require(encoded.length <= MAX_ENCODED_LENGTH) {
            "Encoded operational event outbox state exceeds the bounded V1 limit."
        }
        return encoded
    }

    override fun decode(payload: String): OperationalEventOutboxState {
        require(payload.length <= MAX_ENCODED_LENGTH) {
            "Encoded operational event outbox state exceeds the bounded V1 limit."
        }
        return try {
            val fields = payload.split(SEPARATOR)
            require(fields.size >= FIXED_FIELD_COUNT)
            require(fields[0] == HEADER)
            require(fields[1] == FORMAT_VERSION)
            val count = fields[2].toInt()
            require(count in 0..MAX_ENTRY_COUNT)
            require(fields.size == FIXED_FIELD_COUNT + count)
            val entries = (0 until count).map { index ->
                val frame = Base64.decode(fields[FIXED_FIELD_COUNT + index])
                when (val decoded = OperationalEnvelopeWireCodec.decode(frame)) {
                    is OperationalEnvelopeDecodeResult.Decoded -> decoded.envelope
                    is OperationalEnvelopeDecodeResult.Rejected ->
                        throw IllegalArgumentException("Malformed operational event outbox entry frame.")
                }
            }
            OperationalEventOutboxState(entries)
        } catch (malformed: Exception) {
            throw IllegalArgumentException("Malformed operational event outbox state payload.", malformed)
        }
    }

    private companion object {
        const val HEADER: String = "DATALOOM_OPERATIONAL_EVENT_OUTBOX"
        const val FORMAT_VERSION: String = "1"
        const val SEPARATOR: String = "|"
        const val FIXED_FIELD_COUNT: Int = 3
        const val MAX_ENTRY_COUNT: Int = 10_000
        const val MAX_ENCODED_LENGTH: Int = 4 * 1024 * 1024
    }
}
