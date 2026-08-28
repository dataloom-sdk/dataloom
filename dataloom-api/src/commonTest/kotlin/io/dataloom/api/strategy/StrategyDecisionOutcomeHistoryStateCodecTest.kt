package io.dataloom.api.strategy

import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StrategyDecisionOutcomeHistoryStateCodecTest {

    private val codec = StrategyDecisionOutcomeHistoryStateCodec()

    @Test
    fun roundTripsAnEmptyHistory() {
        val state = StrategyDecisionOutcomeHistoryState(emptyList())
        assertEquals(state, codec.decode(codec.encode(state)))
    }

    @Test
    fun roundTripsASingleAttempt() {
        val state = StrategyDecisionOutcomeHistoryState(listOf(attempt(1, StrategyDecisionOutcomeKind.EXECUTED)))
        assertEquals(state, codec.decode(codec.encode(state)))
    }

    @Test
    fun roundTripsMultipleAttemptsWithDifferingOutcomesInOrder() {
        val state = StrategyDecisionOutcomeHistoryState(
            listOf(
                attempt(1, StrategyDecisionOutcomeKind.FAILED, "TRANSPORT_FAILURE"),
                attempt(2, StrategyDecisionOutcomeKind.FAILED, "TRANSPORT_FAILURE"),
                attempt(3, StrategyDecisionOutcomeKind.EXECUTED, null),
            ),
        )

        val decoded = codec.decode(codec.encode(state))

        assertEquals(state, decoded)
        assertEquals(
            listOf(
                StrategyDecisionOutcomeKind.FAILED,
                StrategyDecisionOutcomeKind.FAILED,
                StrategyDecisionOutcomeKind.EXECUTED,
            ),
            decoded.retainedAttempts.map { it.outcomeKind },
        )
    }

    @Test
    fun roundTripsAnAttemptContainingSeparatorCharactersInReasonCodesAndOutcomeDetail() {
        val state = StrategyDecisionOutcomeHistoryState(
            listOf(
                StrategyDecisionEvent(
                    planId = StrategyPlanId("plan-sep"),
                    requestedStrategy = BuiltInSynchronizationStrategy.CACHE_FIRST,
                    effectiveStrategy = BuiltInSynchronizationStrategy.CACHE_FIRST,
                    effectiveProfileId = StrategyProfileId("profile-sep"),
                    configurationVersion = StrategyConfigurationVersion(1L),
                    direction = SynchronizationDirection.PULL,
                    mode = SynchronizationMode.DELTA,
                    disposition = StrategyDisposition.EXECUTE,
                    reasonCodes = listOf("ok | fine ~ done : indeed", "line\nbreak\ttab"),
                    outcomeKind = StrategyDecisionOutcomeKind.FAILED,
                    outcomeDetail = "ERR|CODE~1:2\n3",
                    committedAt = DataLoomInstant(1L),
                ),
            ),
        )

        assertEquals(state, codec.decode(codec.encode(state)))
    }

    @Test
    fun decodeRejectsAnUnrecognizedHeader() {
        assertFailsWith<IllegalArgumentException> {
            codec.decode("NOT_A_STRATEGY_DECISION_OUTCOME_HISTORY\t1")
        }
    }

    @Test
    fun decodeRejectsAMalformedAttemptLine() {
        val encoded = codec.encode(StrategyDecisionOutcomeHistoryState(listOf(attempt(1, StrategyDecisionOutcomeKind.EXECUTED))))
        val corrupted = encoded + "\nnot-a-valid-strategy-decision-event-line"
        assertFailsWith<IllegalArgumentException> {
            codec.decode(corrupted)
        }
    }

    @Test
    fun encodeRejectsAPayloadBeyondTheBoundedLimit() {
        val hugeHistory = StrategyDecisionOutcomeHistoryState(
            (1..40_000).map { attempt(it, StrategyDecisionOutcomeKind.EXECUTED) },
        )
        assertFailsWith<IllegalArgumentException> {
            codec.encode(hugeHistory)
        }
    }

    private fun attempt(
        seed: Int,
        outcome: StrategyDecisionOutcomeKind,
        outcomeDetail: String? = null,
    ): StrategyDecisionEvent = StrategyDecisionEvent(
        planId = StrategyPlanId("plan-$seed"),
        requestedStrategy = BuiltInSynchronizationStrategy.NETWORK_ONLY,
        effectiveStrategy = BuiltInSynchronizationStrategy.NETWORK_ONLY,
        effectiveProfileId = StrategyProfileId("profile-$seed"),
        configurationVersion = StrategyConfigurationVersion(1L),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        disposition = StrategyDisposition.EXECUTE,
        reasonCodes = listOf("NETWORK_ONLY_DEFAULT"),
        outcomeKind = outcome,
        outcomeDetail = outcomeDetail,
        committedAt = DataLoomInstant(seed.toLong()),
    )
}
