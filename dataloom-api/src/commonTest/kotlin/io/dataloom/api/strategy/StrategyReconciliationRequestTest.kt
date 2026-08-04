package io.dataloom.api.strategy

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class StrategyReconciliationRequestTest {

    @Test
    fun completedOperationEvidenceIsDefensivelyCopied() {
        val operations = mutableListOf(
            StrategyOperation.READ_LOCAL,
            StrategyOperation.PUSH_REMOTE,
        )
        val reconciliation = reconciliation(operations)

        operations.clear()
        runCatching {
            (reconciliation.completedOperations as? MutableList<StrategyOperation>)?.clear()
        }

        assertEquals(
            listOf(
                StrategyOperation.READ_LOCAL,
                StrategyOperation.PUSH_REMOTE,
            ),
            reconciliation.completedOperations,
        )
    }

    @Test
    fun duplicateAndRecursiveReconciliationEvidenceFailsClosed() {
        assertFailsWith<IllegalArgumentException> {
            reconciliation(
                listOf(
                    StrategyOperation.PUSH_REMOTE,
                    StrategyOperation.PUSH_REMOTE,
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            reconciliation(
                listOf(
                    StrategyOperation.PUSH_REMOTE,
                    StrategyOperation.RECONCILE,
                ),
            )
        }
    }

    @Test
    fun diagnosticsExcludeDynamicRequestAndPlanIdentifiers() {
        val diagnostic = reconciliation(
            listOf(StrategyOperation.PUSH_REMOTE),
        ).toString()

        assertFalse("workflow-sensitive" in diagnostic)
        assertFalse("session-sensitive" in diagnostic)
        assertFalse("decision-sensitive" in diagnostic)
        assertFalse("plan-sensitive" in diagnostic)
        assertFalse("profile-sensitive" in diagnostic)
        assertEquals(
            "StrategyReconciliationRequest(operationCount=1, configurationVersion=7)",
            diagnostic,
        )
    }

    private fun reconciliation(
        operations: List<StrategyOperation>,
    ): StrategyReconciliationRequest = StrategyReconciliationRequest(
        request = SynchronizationRequest(
            workflowId = WorkflowId("workflow-sensitive"),
            sessionId = SynchronizationSessionId("session-sensitive"),
            direction = SynchronizationDirection.PUSH,
            mode = SynchronizationMode.DELTA,
            context = ExecutionContext(
                executionId = ExecutionId("execution-sensitive"),
                correlationId = CorrelationId("correlation-sensitive"),
            ),
        ),
        decisionId = StrategyDecisionId("decision-sensitive"),
        planId = StrategyPlanId("plan-sensitive"),
        profileId = StrategyProfileId("profile-sensitive"),
        configurationVersion = StrategyConfigurationVersion(7L),
        completedOperations = operations,
    )
}
