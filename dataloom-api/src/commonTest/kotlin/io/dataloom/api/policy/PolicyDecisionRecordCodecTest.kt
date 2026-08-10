package io.dataloom.api.policy

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.identifier.PolicyCheckId
import io.dataloom.api.identifier.PolicySetId
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PolicyDecisionRecordCodecTest {

    private val codec = PolicyDecisionRecordCodec()

    @Test
    fun roundTripsAnAllowDecisionWithNoEvidence() {
        val record = PolicyDecisionRecord(
            decision = PolicyDecision(
                policySetId = PolicySetId("retry-policy"),
                outcome = PolicyCheckOutcome.Allow("no objection"),
                winningCheckId = null,
                evidence = emptyList(),
            ),
            committedAt = DataLoomInstant(1_000L),
        )

        assertEquals(record, codec.decode(codec.encode(record)))
    }

    @Test
    fun roundTripsEveryOutcomeKindWithMetadataAndMultipleEvidenceEntries() {
        val record = PolicyDecisionRecord(
            decision = PolicyDecision(
                policySetId = PolicySetId("conflict-policy"),
                outcome = PolicyCheckOutcome.Deny(
                    justification = "quota exceeded",
                    metadata = DataLoomMetadata.of(mapOf("quotaId" to "q-1", "region" to "us")),
                ),
                winningCheckId = PolicyCheckId("check-2"),
                evidence = listOf(
                    PolicyCheckEvidence(PolicyCheckId("check-1"), PolicyCheckOutcome.Allow("fine")),
                    PolicyCheckEvidence(
                        PolicyCheckId("check-2"),
                        PolicyCheckOutcome.Deny("quota exceeded", DataLoomMetadata.of(mapOf("quotaId" to "q-1", "region" to "us"))),
                    ),
                    PolicyCheckEvidence(
                        PolicyCheckId("check-3"),
                        PolicyCheckOutcome.RequireUserAction("re-authenticate"),
                    ),
                    PolicyCheckEvidence(
                        PolicyCheckId("check-4"),
                        PolicyCheckOutcome.Defer(SchedulingDelay(5_000L), "try later"),
                    ),
                ),
            ),
            committedAt = DataLoomInstant(42_000L),
        )

        assertEquals(record, codec.decode(codec.encode(record)))
    }

    @Test
    fun roundTripsAZeroMillisecondDefer() {
        val record = PolicyDecisionRecord(
            decision = PolicyDecision(
                policySetId = PolicySetId("p"),
                outcome = PolicyCheckOutcome.Defer(SchedulingDelay(0L), "postpone"),
                winningCheckId = PolicyCheckId("c"),
                evidence = listOf(PolicyCheckEvidence(PolicyCheckId("c"), PolicyCheckOutcome.Defer(SchedulingDelay(0L), "postpone"))),
            ),
            committedAt = DataLoomInstant(0L),
        )

        assertEquals(record, codec.decode(codec.encode(record)))
    }

    @Test
    fun roundTripsJustificationAndMetadataContainingSeparatorCharacters() {
        val record = PolicyDecisionRecord(
            decision = PolicyDecision(
                policySetId = PolicySetId("p"),
                outcome = PolicyCheckOutcome.Allow(
                    justification = "ok | fine ~ done ^ yes ; sure : indeed",
                    metadata = DataLoomMetadata.of(mapOf("a|b~c" to "d^e;f:g")),
                ),
                winningCheckId = null,
                evidence = emptyList(),
            ),
            committedAt = DataLoomInstant(1L),
        )

        assertEquals(record, codec.decode(codec.encode(record)))
    }

    @Test
    fun decodeRejectsAnUnrecognizedHeader() {
        assertFailsWith<IllegalArgumentException> {
            codec.decode("NOT_A_POLICY_DECISION_RECORD|1")
        }
    }

    @Test
    fun decodeRejectsATruncatedEvidenceCount() {
        val record = PolicyDecisionRecord(
            decision = PolicyDecision(
                policySetId = PolicySetId("p"),
                outcome = PolicyCheckOutcome.Allow("ok"),
                winningCheckId = null,
                evidence = emptyList(),
            ),
            committedAt = DataLoomInstant(1L),
        )
        val encoded = codec.encode(record)
        val fields = encoded.split('|').toMutableList()
        fields[8] = "3" // claims 3 evidence entries but none are present
        assertFailsWith<IllegalArgumentException> {
            codec.decode(fields.joinToString("|"))
        }
    }

    @Test
    fun encodeRejectsAPayloadBeyondTheBoundedLimit() {
        val record = PolicyDecisionRecord(
            decision = PolicyDecision(
                policySetId = PolicySetId("p"),
                outcome = PolicyCheckOutcome.Allow("x".repeat(500_000)),
                winningCheckId = null,
                evidence = emptyList(),
            ),
            committedAt = DataLoomInstant(1L),
        )
        assertFailsWith<IllegalArgumentException> {
            codec.encode(record)
        }
    }
}
