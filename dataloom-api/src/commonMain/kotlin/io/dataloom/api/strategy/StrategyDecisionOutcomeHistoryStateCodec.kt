package io.dataloom.api.strategy

import io.dataloom.api.state.DurableStateCodec

/**
 * Deterministic bounded V1 text codec for [StrategyDecisionOutcomeHistoryState],
 * for use with a generic string-payload
 * [io.dataloom.api.state.DurableStateStore] implementation (for example
 * [RoomDurableStateStore][io.dataloom.queue.room.RoomDurableStateStore]).
 *
 * Delegates every retained attempt's own encode/decode to
 * [StrategyDecisionEventCodec] rather than re-deriving the same field layout
 * -- unlike [io.dataloom.api.asset.AssetManifestHistoryStateCodec] (which has
 * no sibling single-value codec to delegate to, since [io.dataloom.api.asset.AssetManifest]
 * is never persisted standalone), a standalone [StrategyDecisionEventCodec]
 * already exists and is already exercised by
 * [DurableStrategyDecisionEventLog]'s own Room adoption, so reusing it here
 * keeps exactly one place that knows how to encode a [StrategyDecisionEvent].
 * Each attempt occupies one line; [StrategyDecisionEventCodec.encode] never
 * produces an embedded newline (every field it emits is `|`-joined and hex-escapes
 * free-form string content), so `\n`-joining lines is unambiguous.
 */
public class StrategyDecisionOutcomeHistoryStateCodec : DurableStateCodec<StrategyDecisionOutcomeHistoryState> {

    private val eventCodec = StrategyDecisionEventCodec()

    override fun encode(state: StrategyDecisionOutcomeHistoryState): String {
        val lines = mutableListOf("$HEADER\t$FORMAT_VERSION")
        state.retainedAttempts.forEach { lines += eventCodec.encode(it) }
        val encoded = lines.joinToString("\n")
        require(encoded.length <= MAX_ENCODED_LENGTH) {
            "Encoded strategy decision outcome history exceeds the bounded V1 limit."
        }
        return encoded
    }

    override fun decode(payload: String): StrategyDecisionOutcomeHistoryState {
        require(payload.length <= MAX_ENCODED_LENGTH) {
            "Encoded strategy decision outcome history exceeds the bounded V1 limit."
        }
        return try {
            val lines = payload.split('\n')
            require(lines.isNotEmpty())
            val header = lines.first().split('\t')
            require(header.size == 2)
            require(header[0] == HEADER)
            require(header[1] == FORMAT_VERSION)
            val attempts = (1 until lines.size).map { eventCodec.decode(lines[it]) }
            StrategyDecisionOutcomeHistoryState(attempts)
        } catch (malformed: Exception) {
            throw IllegalArgumentException("Malformed strategy decision outcome history payload.", malformed)
        }
    }

    private companion object {
        const val HEADER: String = "DATALOOM_STRATEGY_DECISION_OUTCOME_HISTORY"
        const val FORMAT_VERSION: String = "1"
        const val MAX_ENCODED_LENGTH: Int = 1_048_576
    }
}
