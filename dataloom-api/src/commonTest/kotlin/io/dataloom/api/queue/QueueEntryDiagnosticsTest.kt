package io.dataloom.api.queue

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.strategy.BuiltInSynchronizationStrategy
import io.dataloom.api.strategy.PersistedStrategyDecision
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueueEntryDiagnosticsTest {

    @Test
    fun toStringExcludesEntryContextMetadataAndStrategyIdentifiers() {
        val entry = QueueEntry(
            id = QueueEntryId("sensitive-entry"),
            synchronizationRequest = SynchronizationRequest(
                workflowId = WorkflowId("sensitive-workflow"),
                sessionId = SynchronizationSessionId("sensitive-session"),
                direction = SynchronizationDirection.PUSH,
                mode = SynchronizationMode.DELTA,
                context = ExecutionContext(
                    executionId = ExecutionId("sensitive-execution"),
                    correlationId = CorrelationId("sensitive-correlation"),
                ),
            ),
            state = QueueEntryState.PENDING,
            enqueuedAt = DataLoomInstant(1_000L),
            availableAt = DataLoomInstant(1_000L),
            metadata = DataLoomMetadata.of(
                mapOf("authorization" to "sensitive-metadata-value"),
            ),
            strategyDecision = PersistedStrategyDecision(
                decisionId = StrategyDecisionId("sensitive-decision"),
                planId = StrategyPlanId("sensitive-plan"),
                requestedStrategy = BuiltInSynchronizationStrategy.ADAPTIVE,
                effectiveProfileId = StrategyProfileId("sensitive-profile"),
                effectiveStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
                configurationVersion = StrategyConfigurationVersion(3L),
                disposition = StrategyDisposition.DEFER,
            ),
        )

        val diagnostic = entry.toString()

        listOf(
            "sensitive-entry",
            "sensitive-workflow",
            "sensitive-session",
            "sensitive-execution",
            "sensitive-correlation",
            "sensitive-metadata-value",
            "sensitive-decision",
            "sensitive-plan",
            "sensitive-profile",
        ).forEach { value -> assertFalse(value in diagnostic) }
        assertTrue("metadataEntryCount=1" in diagnostic)
        assertTrue("hasStrategyDecision=true" in diagnostic)
    }
}
