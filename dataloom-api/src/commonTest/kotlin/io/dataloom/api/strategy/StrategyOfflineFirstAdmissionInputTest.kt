package io.dataloom.api.strategy

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class StrategyOfflineFirstAdmissionInputTest {

    @Test
    fun admissionInputRejectsBlankIdempotencyIdentity() {
        assertFailsWith<IllegalArgumentException> {
            StrategyOperationInput.OfflineFirstAdmission(
                queueEntryId = QueueEntryId("entry-1"),
                idempotencyKey = " ",
            )
        }
    }

    @Test
    fun admissionInputDiagnosticsExcludeDurableIdentity() {
        val input = StrategyOperationInput.OfflineFirstAdmission(
            queueEntryId = QueueEntryId("sensitive-entry"),
            idempotencyKey = "sensitive-intent",
        )

        assertFalse(input.toString().contains("sensitive-entry"))
        assertFalse(input.toString().contains("sensitive-intent"))
    }

    @Test
    fun admissionInputRequiresOfflineFirstProfile() {
        assertFailsWith<IllegalArgumentException> {
            StrategySynchronizationRequest(
                request = synchronizationRequest(),
                decisionId = StrategyDecisionId("decision-1"),
                planId = StrategyPlanId("plan-1"),
                profile = NetworkOnlyStrategyProfile(
                    id = StrategyProfileId("network-profile"),
                    configurationVersion = StrategyConfigurationVersion(1),
                ),
                evidence = StrategyRuntimeEvidence(
                    connectivity = StrategyConnectivity.UNAVAILABLE,
                ),
                input = StrategyOperationInput.OfflineFirstAdmission(
                    queueEntryId = QueueEntryId("entry-1"),
                    idempotencyKey = "intent-1",
                ),
            )
        }
    }

    private fun synchronizationRequest(): SynchronizationRequest =
        SynchronizationRequest(
            workflowId = WorkflowId("workflow-1"),
            sessionId = SynchronizationSessionId("session-1"),
            direction = SynchronizationDirection.PUSH,
            mode = SynchronizationMode.DELTA,
            context = ExecutionContext(
                executionId = ExecutionId("execution-1"),
                correlationId = CorrelationId("correlation-1"),
            ),
        )
}
