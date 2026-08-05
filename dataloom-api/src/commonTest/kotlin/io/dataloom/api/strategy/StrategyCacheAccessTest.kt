package io.dataloom.api.strategy

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

class StrategyCacheAccessTest {

    @Test
    fun requestRequiresEvaluatedServeableCacheState() {
        assertFailsWith<IllegalArgumentException> {
            request(
                evaluatedCacheState = StrategyCacheState.MISSING,
                allowStale = false,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            request(
                evaluatedCacheState = StrategyCacheState.STALE,
                allowStale = false,
            )
        }

        assertEquals(
            StrategyCacheState.FRESH,
            request(
                evaluatedCacheState = StrategyCacheState.FRESH,
                allowStale = false,
            ).evaluatedCacheState,
        )
        assertEquals(
            StrategyCacheState.STALE,
            request(
                evaluatedCacheState = StrategyCacheState.STALE,
                allowStale = true,
            ).evaluatedCacheState,
        )
    }

    @Test
    fun requestDiagnosticsExcludeDynamicIdentities() {
        val request = request(
            evaluatedCacheState = StrategyCacheState.FRESH,
            allowStale = false,
        )

        val diagnostic = request.toString()
        assertFalse(diagnostic.contains("workflow-sensitive"))
        assertFalse(diagnostic.contains("decision-sensitive"))
        assertFalse(diagnostic.contains("plan-sensitive"))
        assertFalse(diagnostic.contains("profile-sensitive"))
    }

    @Test
    fun freshEvidenceRequiresUnexpiredExclusiveDeadline() {
        val evidence = StrategyCacheFreshnessEvidence(
            cacheState = StrategyCacheState.FRESH,
            observedAt = DataLoomInstant(1_000L),
            validUntil = DataLoomInstant(1_001L),
        )
        assertEquals(StrategyCacheState.FRESH, evidence.cacheState)

        assertFailsWith<IllegalArgumentException> {
            StrategyCacheFreshnessEvidence(
                cacheState = StrategyCacheState.FRESH,
                observedAt = DataLoomInstant(1_000L),
                validUntil = DataLoomInstant(1_000L),
            )
        }
    }

    @Test
    fun staleEvidenceRequiresExpiredDeadline() {
        val evidence = StrategyCacheFreshnessEvidence(
            cacheState = StrategyCacheState.STALE,
            observedAt = DataLoomInstant(1_000L),
            validUntil = DataLoomInstant(1_000L),
        )
        assertEquals(StrategyCacheState.STALE, evidence.cacheState)

        assertFailsWith<IllegalArgumentException> {
            StrategyCacheFreshnessEvidence(
                cacheState = StrategyCacheState.STALE,
                observedAt = DataLoomInstant(999L),
                validUntil = DataLoomInstant(1_000L),
            )
        }
    }

    @Test
    fun availableAndUnavailableResultsRemainDisjoint() {
        val available = StrategyCacheAccessResult.Available(
            StrategyCacheFreshnessEvidence(
                cacheState = StrategyCacheState.FRESH,
                observedAt = DataLoomInstant(1_000L),
                validUntil = DataLoomInstant(2_000L),
            ),
        )
        assertIs<StrategyCacheAccessResult.Available>(available)

        val unavailable = StrategyCacheAccessResult.Unavailable(
            cacheState = StrategyCacheState.MISSING,
        )
        assertEquals(StrategyCacheState.MISSING, unavailable.cacheState)

        assertFailsWith<IllegalArgumentException> {
            StrategyCacheAccessResult.Unavailable(StrategyCacheState.FRESH)
        }
        assertFailsWith<IllegalArgumentException> {
            StrategyCacheAccessResult.Unavailable(StrategyCacheState.STALE)
        }
    }

    private fun request(
        evaluatedCacheState: StrategyCacheState,
        allowStale: Boolean,
    ): StrategyCacheAccessRequest =
        StrategyCacheAccessRequest(
            request = SynchronizationRequest(
                workflowId = WorkflowId("workflow-sensitive"),
                sessionId = SynchronizationSessionId("session-sensitive"),
                direction = SynchronizationDirection.PULL,
                mode = SynchronizationMode.DELTA,
                context = ExecutionContext(
                    executionId = ExecutionId("execution-sensitive"),
                    correlationId = CorrelationId("correlation-sensitive"),
                ),
            ),
            decisionId = StrategyDecisionId("decision-sensitive"),
            planId = StrategyPlanId("plan-sensitive"),
            profileId = StrategyProfileId("profile-sensitive"),
            configurationVersion = StrategyConfigurationVersion(7),
            evaluatedCacheState = evaluatedCacheState,
            allowStale = allowStale,
        )
}
