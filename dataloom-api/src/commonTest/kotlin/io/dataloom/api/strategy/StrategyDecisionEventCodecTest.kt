package io.dataloom.api.strategy

import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StrategyDecisionEventCodecTest {

    private val codec = StrategyDecisionEventCodec()

    @Test
    fun roundTripsAnExecutedEventWithNoOutcomeDetail() {
        val event = StrategyDecisionEvent(
            planId = StrategyPlanId("plan-1"),
            requestedStrategy = BuiltInSynchronizationStrategy.NETWORK_ONLY,
            effectiveStrategy = BuiltInSynchronizationStrategy.NETWORK_ONLY,
            effectiveProfileId = StrategyProfileId("profile-1"),
            configurationVersion = StrategyConfigurationVersion(1L),
            direction = SynchronizationDirection.PUSH,
            mode = SynchronizationMode.DELTA,
            disposition = StrategyDisposition.EXECUTE,
            reasonCodes = listOf("NETWORK_ONLY_DEFAULT"),
            outcomeKind = StrategyDecisionOutcomeKind.EXECUTED,
            outcomeDetail = null,
            committedAt = DataLoomInstant(1_000L),
        )

        assertEquals(event, codec.decode(codec.encode(event)))
    }

    @Test
    fun roundTripsARejectedEventWithMultipleReasonCodesAndOutcomeDetail() {
        val event = StrategyDecisionEvent(
            planId = StrategyPlanId("plan-2"),
            requestedStrategy = BuiltInSynchronizationStrategy.ADAPTIVE,
            effectiveStrategy = BuiltInSynchronizationStrategy.HYBRID,
            effectiveProfileId = StrategyProfileId("profile-2"),
            configurationVersion = StrategyConfigurationVersion(7L),
            direction = SynchronizationDirection.BIDIRECTIONAL,
            mode = SynchronizationMode.FULL,
            disposition = StrategyDisposition.REJECT,
            reasonCodes = listOf("ADAPTIVE_RESOLVED_HYBRID", "CONNECTIVITY_KNOWN"),
            outcomeKind = StrategyDecisionOutcomeKind.REJECTED,
            outcomeDetail = "PROVIDER_RESOLUTION_FAILED",
            committedAt = DataLoomInstant(42_000L),
        )

        assertEquals(event, codec.decode(codec.encode(event)))
    }

    @Test
    fun roundTripsReasonCodesAndOutcomeDetailContainingSeparatorCharacters() {
        val event = StrategyDecisionEvent(
            planId = StrategyPlanId("plan-3"),
            requestedStrategy = BuiltInSynchronizationStrategy.CACHE_FIRST,
            effectiveStrategy = BuiltInSynchronizationStrategy.CACHE_FIRST,
            effectiveProfileId = StrategyProfileId("profile-3"),
            configurationVersion = StrategyConfigurationVersion(1L),
            direction = SynchronizationDirection.PULL,
            mode = SynchronizationMode.DELTA,
            disposition = StrategyDisposition.EXECUTE,
            reasonCodes = listOf("ok | fine ~ done : indeed"),
            outcomeKind = StrategyDecisionOutcomeKind.FAILED,
            outcomeDetail = "ERR|CODE~1:2",
            committedAt = DataLoomInstant(1L),
        )

        assertEquals(event, codec.decode(codec.encode(event)))
    }

    @Test
    fun roundTripsEveryOutcomeKind() {
        StrategyDecisionOutcomeKind.entries.forEach { kind ->
            val event = StrategyDecisionEvent(
                planId = StrategyPlanId("plan-kind"),
                requestedStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
                effectiveStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
                effectiveProfileId = StrategyProfileId("profile-kind"),
                configurationVersion = StrategyConfigurationVersion(3L),
                direction = SynchronizationDirection.PULL,
                mode = SynchronizationMode.FULL,
                disposition = StrategyDisposition.SERVE_AND_REFRESH,
                reasonCodes = listOf("R"),
                outcomeKind = kind,
                outcomeDetail = null,
                committedAt = DataLoomInstant(9L),
            )
            assertEquals(event, codec.decode(codec.encode(event)))
        }
    }

    @Test
    fun decodeRejectsAnUnrecognizedHeader() {
        assertFailsWith<IllegalArgumentException> {
            codec.decode("NOT_A_STRATEGY_DECISION_EVENT|1")
        }
    }

    @Test
    fun decodeRejectsATruncatedReasonCodeCount() {
        val event = StrategyDecisionEvent(
            planId = StrategyPlanId("plan-4"),
            requestedStrategy = BuiltInSynchronizationStrategy.REMOTE_FIRST,
            effectiveStrategy = BuiltInSynchronizationStrategy.REMOTE_FIRST,
            effectiveProfileId = StrategyProfileId("profile-4"),
            configurationVersion = StrategyConfigurationVersion(1L),
            direction = SynchronizationDirection.PULL,
            mode = SynchronizationMode.DELTA,
            disposition = StrategyDisposition.EXECUTE,
            reasonCodes = listOf("R"),
            outcomeKind = StrategyDecisionOutcomeKind.EXECUTED,
            outcomeDetail = null,
            committedAt = DataLoomInstant(1L),
        )
        val encoded = codec.encode(event)
        val fields = encoded.split('|').toMutableList()
        fields[10] = "3:" + fields[10].substringAfter(':') // claims 3 reason codes but only 1 is present
        assertFailsWith<IllegalArgumentException> {
            codec.decode(fields.joinToString("|"))
        }
    }

    @Test
    fun encodeRejectsAPayloadBeyondTheBoundedLimit() {
        val event = StrategyDecisionEvent(
            planId = StrategyPlanId("plan-5"),
            requestedStrategy = BuiltInSynchronizationStrategy.NETWORK_ONLY,
            effectiveStrategy = BuiltInSynchronizationStrategy.NETWORK_ONLY,
            effectiveProfileId = StrategyProfileId("profile-5"),
            configurationVersion = StrategyConfigurationVersion(1L),
            direction = SynchronizationDirection.PUSH,
            mode = SynchronizationMode.DELTA,
            disposition = StrategyDisposition.EXECUTE,
            reasonCodes = List(40_000) { "x" },
            outcomeKind = StrategyDecisionOutcomeKind.EXECUTED,
            outcomeDetail = null,
            committedAt = DataLoomInstant(1L),
        )
        assertFailsWith<IllegalArgumentException> {
            codec.encode(event)
        }
    }
}
