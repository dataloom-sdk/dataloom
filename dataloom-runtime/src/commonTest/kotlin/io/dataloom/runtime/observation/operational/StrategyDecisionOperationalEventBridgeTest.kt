package io.dataloom.runtime.observation.operational

import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.operational.OperationalEventCategory
import io.dataloom.api.strategy.BuiltInSynchronizationStrategy
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyDecisionEvent
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyDecisionOutcomeKind
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves [StrategyDecisionOperationalEventBridge] maps a [StrategyDecisionEvent]
 * to a sane [io.dataloom.api.operational.OperationalEventEnvelope], and that
 * field classification never leaks a raw sensitive value.
 */
class StrategyDecisionOperationalEventBridgeTest {

    private fun event(
        planIdValue: String = "plan-should-be-masked",
        effectiveProfileIdValue: String = "profile-should-be-masked",
        reasonCodes: List<String> = listOf("STRATEGY_ADMITTED", "PROFILE_DEFAULT"),
        outcomeKind: StrategyDecisionOutcomeKind = StrategyDecisionOutcomeKind.EXECUTED,
        outcomeDetail: String? = null,
        committedAtEpochMs: Long = 5_000L,
    ): StrategyDecisionEvent = StrategyDecisionEvent(
        planId = StrategyPlanId(planIdValue),
        requestedStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
        effectiveStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
        effectiveProfileId = StrategyProfileId(effectiveProfileIdValue),
        configurationVersion = StrategyConfigurationVersion(3L),
        direction = SynchronizationDirection.BIDIRECTIONAL,
        mode = SynchronizationMode.DELTA,
        disposition = StrategyDisposition.EXECUTE,
        reasonCodes = reasonCodes,
        outcomeKind = outcomeKind,
        outcomeDetail = outcomeDetail,
        committedAt = DataLoomInstant(committedAtEpochMs),
    )

    // -------------------------------------------------------------------------
    // Identity/routing
    // -------------------------------------------------------------------------

    @Test
    fun toEnvelope_reusesDecisionIdAsCorrelationId_neverInventsOne() {
        val decisionId = StrategyDecisionId("decision-corr-001")
        val envelope = StrategyDecisionOperationalEventBridge.toEnvelope(decisionId, event())
        assertEquals(CorrelationId("decision-corr-001"), envelope.correlationId)
    }

    @Test
    fun toEnvelope_reusesCommittedAt_neverReadsAClock() {
        val decisionId = StrategyDecisionId("decision-001")
        val ev = event(committedAtEpochMs = 42_000L)
        val envelope = StrategyDecisionOperationalEventBridge.toEnvelope(decisionId, ev)
        assertEquals(DataLoomInstant(42_000L), envelope.occurredAt)
    }

    @Test
    fun toEnvelope_category_isDiagnostic() {
        val decisionId = StrategyDecisionId("decision-001")
        val envelope = StrategyDecisionOperationalEventBridge.toEnvelope(decisionId, event())
        assertEquals(OperationalEventCategory.DIAGNOSTIC, envelope.category)
    }

    @Test
    fun toEnvelope_sanitizesDisallowedCharactersInDecisionId() {
        val decisionId = StrategyDecisionId("decision id 1!#weird")
        val envelope = StrategyDecisionOperationalEventBridge.toEnvelope(decisionId, event())
        assertTrue(envelope.id.value.none { it == ' ' || it == '!' || it == '#' })
    }

    @Test
    fun toEnvelope_isPureAndDeterministic() {
        val decisionId = StrategyDecisionId("decision-001")
        val ev = event()
        val first = StrategyDecisionOperationalEventBridge.toEnvelope(decisionId, ev)
        val second = StrategyDecisionOperationalEventBridge.toEnvelope(decisionId, ev)
        assertEquals(first, second)
    }

    @Test
    fun toEnvelope_typeReflectsOutcomeKind() {
        val decisionId = StrategyDecisionId("decision-001")
        val envelope = StrategyDecisionOperationalEventBridge.toEnvelope(
            decisionId,
            event(outcomeKind = StrategyDecisionOutcomeKind.REJECTED, outcomeDetail = "STRATEGY_REJECTED"),
        )
        assertEquals("dataloom.strategy.decision.rejected", envelope.type.value)
    }

    @Test
    fun toEnvelope_tenantWorkflowTrace_areUnset() {
        val decisionId = StrategyDecisionId("decision-001")
        val envelope = StrategyDecisionOperationalEventBridge.toEnvelope(decisionId, event())
        assertNull(envelope.tenantId)
        assertNull(envelope.workflowId)
        assertNull(envelope.traceId)
    }

    // -------------------------------------------------------------------------
    // Classification
    // -------------------------------------------------------------------------

    @Test
    fun toEnvelope_masksPlanAndProfileIdentifiers_neverKeepsRawValue() {
        val decisionId = StrategyDecisionId("decision-001")
        val envelope = StrategyDecisionOperationalEventBridge.toEnvelope(
            decisionId,
            event(
                planIdValue = "plan-should-be-masked",
                effectiveProfileIdValue = "profile-should-be-masked",
            ),
        )
        assertNotEqual("plan-should-be-masked", envelope.attributes["decision.planId"])
        assertNotEqual("profile-should-be-masked", envelope.attributes["decision.effectiveProfileId"])
    }

    @Test
    fun toEnvelope_keepsReasonCodesAndOutcomeDetail_asPlainText() {
        val decisionId = StrategyDecisionId("decision-001")
        val envelope = StrategyDecisionOperationalEventBridge.toEnvelope(
            decisionId,
            event(
                reasonCodes = listOf("STRATEGY_ADMITTED", "PROFILE_DEFAULT"),
                outcomeKind = StrategyDecisionOutcomeKind.REJECTED,
                outcomeDetail = "STRATEGY_REJECTED",
            ),
        )
        assertEquals("STRATEGY_ADMITTED|PROFILE_DEFAULT", envelope.attributes["decision.reasonCodes"])
        assertEquals("2", envelope.attributes["decision.reasonCodeCount"])
        assertEquals("STRATEGY_REJECTED", envelope.attributes["decision.outcomeDetail"])
    }

    @Test
    fun toEnvelope_omitsOutcomeDetailAttribute_whenNull() {
        val decisionId = StrategyDecisionId("decision-001")
        val envelope = StrategyDecisionOperationalEventBridge.toEnvelope(
            decisionId,
            event(outcomeKind = StrategyDecisionOutcomeKind.EXECUTED, outcomeDetail = null),
        )
        assertNull(envelope.attributes["decision.outcomeDetail"])
    }

    @Test
    fun toEnvelope_keepsEnumAndCounterFields() {
        val decisionId = StrategyDecisionId("decision-001")
        val envelope = StrategyDecisionOperationalEventBridge.toEnvelope(decisionId, event())
        assertEquals("OFFLINE_FIRST", envelope.attributes["decision.requestedStrategy"])
        assertEquals("OFFLINE_FIRST", envelope.attributes["decision.effectiveStrategy"])
        assertEquals("3", envelope.attributes["decision.configurationVersion"])
        assertEquals("BIDIRECTIONAL", envelope.attributes["decision.direction"])
        assertEquals("DELTA", envelope.attributes["decision.mode"])
        assertEquals("EXECUTE", envelope.attributes["decision.disposition"])
        assertEquals("EXECUTED", envelope.attributes["decision.outcomeKind"])
    }

    private fun assertNotEqual(rawValue: String, redactedValue: String?) {
        assertTrue(redactedValue != null && redactedValue != rawValue, "Expected redaction of '$rawValue'.")
    }
}
