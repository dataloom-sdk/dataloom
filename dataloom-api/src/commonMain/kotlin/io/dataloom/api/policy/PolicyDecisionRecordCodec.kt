package io.dataloom.api.policy

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.identifier.PolicyCheckId
import io.dataloom.api.identifier.PolicySetId
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.state.DurableStateCodec
import io.dataloom.api.time.DataLoomInstant

/**
 * Deterministic bounded V1 text codec for [PolicyDecisionRecord], for use
 * with a generic string-payload [io.dataloom.api.state.DurableStateStore]
 * implementation (for example [RoomDurableStateStore][io.dataloom.queue.room.RoomDurableStateStore]).
 *
 * The format contains only decision/evidence identity, outcome kind,
 * justification text, delay milliseconds, metadata entries, and the commit
 * timestamp — never payloads, credentials, or provider values, matching
 * [PolicyCheckOutcome]'s own "sensitive-data restrictions" documentation.
 */
public class PolicyDecisionRecordCodec : DurableStateCodec<PolicyDecisionRecord> {

    override fun encode(state: PolicyDecisionRecord): String {
        val outcome = encodeOutcomeFields(state.decision.outcome)
        val fields = listOf(
            HEADER,
            FORMAT_VERSION,
            hexEncode(state.decision.policySetId.value),
            state.decision.winningCheckId?.let { hexEncode(it.value) } ?: NULL,
            outcome.kind,
            outcome.justificationHex,
            outcome.metadataField,
            outcome.delayField,
            state.decision.evidence.size.toString(),
            state.decision.evidence.joinToString(EVIDENCE_SEPARATOR) { encodeEvidence(it) },
            state.committedAt.epochMilliseconds.toString(),
        )
        val encoded = fields.joinToString("|")
        require(encoded.length <= MAX_ENCODED_LENGTH) {
            "Encoded policy decision record exceeds the bounded V1 limit."
        }
        return encoded
    }

    override fun decode(payload: String): PolicyDecisionRecord {
        require(payload.length <= MAX_ENCODED_LENGTH) {
            "Encoded policy decision record exceeds the bounded V1 limit."
        }
        return try {
            val fields = payload.split('|')
            require(fields.size == FIELD_COUNT)
            require(fields[0] == HEADER)
            require(fields[1] == FORMAT_VERSION)
            val evidenceCount = fields[8].toInt()
            require(evidenceCount >= 0)
            val evidenceField = fields[9]
            val evidence = if (evidenceCount == 0) {
                require(evidenceField.isEmpty())
                emptyList()
            } else {
                evidenceField.split(EVIDENCE_SEPARATOR).map { decodeEvidence(it) }
            }
            require(evidence.size == evidenceCount)
            PolicyDecisionRecord(
                decision = PolicyDecision(
                    policySetId = PolicySetId(hexDecode(fields[2])),
                    outcome = decodeOutcomeFields(
                        kind = fields[4],
                        justificationHex = fields[5],
                        metadataField = fields[6],
                        delayField = fields[7],
                    ),
                    winningCheckId = if (fields[3] == NULL) null else PolicyCheckId(hexDecode(fields[3])),
                    evidence = evidence,
                ),
                committedAt = DataLoomInstant(fields[10].toLong()),
            )
        } catch (malformed: Exception) {
            throw IllegalArgumentException("Malformed policy decision record payload.", malformed)
        }
    }

    private fun encodeEvidence(evidence: PolicyCheckEvidence): String {
        val outcome = encodeOutcomeFields(evidence.outcome)
        return listOf(
            hexEncode(evidence.checkId.value),
            outcome.kind,
            outcome.justificationHex,
            outcome.metadataField,
            outcome.delayField,
        ).joinToString(EVIDENCE_FIELD_SEPARATOR)
    }

    private fun decodeEvidence(field: String): PolicyCheckEvidence {
        val parts = field.split(EVIDENCE_FIELD_SEPARATOR, limit = 5)
        require(parts.size == 5)
        return PolicyCheckEvidence(
            checkId = PolicyCheckId(hexDecode(parts[0])),
            outcome = decodeOutcomeFields(kind = parts[1], justificationHex = parts[2], metadataField = parts[3], delayField = parts[4]),
        )
    }

    private fun encodeOutcomeFields(outcome: PolicyCheckOutcome): EncodedOutcome {
        val kind = when (outcome) {
            is PolicyCheckOutcome.Allow -> KIND_ALLOW
            is PolicyCheckOutcome.Deny -> KIND_DENY
            is PolicyCheckOutcome.RequireUserAction -> KIND_REQUIRE_USER_ACTION
            is PolicyCheckOutcome.Defer -> KIND_DEFER
        }
        val delayField = if (outcome is PolicyCheckOutcome.Defer) outcome.delay.milliseconds.toString() else NULL
        return EncodedOutcome(
            kind = kind,
            justificationHex = hexEncode(outcome.justification),
            metadataField = encodeMetadata(outcome.metadata),
            delayField = delayField,
        )
    }

    private fun decodeOutcomeFields(
        kind: String,
        justificationHex: String,
        metadataField: String,
        delayField: String,
    ): PolicyCheckOutcome {
        val justification = hexDecode(justificationHex)
        val metadata = decodeMetadata(metadataField)
        return when (kind) {
            KIND_ALLOW -> PolicyCheckOutcome.Allow(justification, metadata)
            KIND_DENY -> PolicyCheckOutcome.Deny(justification, metadata)
            KIND_REQUIRE_USER_ACTION -> PolicyCheckOutcome.RequireUserAction(justification, metadata)
            KIND_DEFER -> PolicyCheckOutcome.Defer(
                delay = SchedulingDelay(milliseconds = delayField.toLong()),
                justification = justification,
                metadata = metadata,
            )
            else -> error("Unknown policy check outcome kind.")
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

    private data class EncodedOutcome(
        val kind: String,
        val justificationHex: String,
        val metadataField: String,
        val delayField: String,
    )

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
        const val HEADER: String = "DATALOOM_POLICY_DECISION_RECORD"
        const val FORMAT_VERSION: String = "1"
        const val NULL: String = "-"
        const val FIELD_COUNT: Int = 11
        const val MAX_ENCODED_LENGTH: Int = 262_144
        const val EVIDENCE_SEPARATOR: String = "~"
        const val EVIDENCE_FIELD_SEPARATOR: String = "^"
        const val METADATA_ENTRY_SEPARATOR: String = ";"
        const val METADATA_KEY_VALUE_SEPARATOR: String = ":"
        const val KIND_ALLOW: String = "ALLOW"
        const val KIND_DENY: String = "DENY"
        const val KIND_REQUIRE_USER_ACTION: String = "REQUIRE_USER_ACTION"
        const val KIND_DEFER: String = "DEFER"
        const val HEX: String = "0123456789abcdef"
    }
}
