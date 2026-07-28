package io.dataloom.api.strategy

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.ChangeSet
import io.dataloom.api.change.EntityReference
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ChangeSetId
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.ChangeOperation
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StrategySynchronizationRequestTest {
    @Test
    fun directTransportDefensivelyCopiesEntityTypes() {
        val source = mutableSetOf(EntityType("document"))
        val input = StrategyOperationInput.DirectTransport(entityTypes = source)

        source += EntityType("image")

        assertEquals(setOf(EntityType("document")), input.entityTypes)
    }

    @Test
    fun directPushRequiresCallerOwnedOutboundChanges() {
        assertFailsWith<IllegalArgumentException> {
            strategyRequest(
                direction = SynchronizationDirection.PUSH,
                input = StrategyOperationInput.DirectTransport(),
            )
        }
    }

    @Test
    fun directPullRejectsUnusedOutboundChanges() {
        assertFailsWith<IllegalArgumentException> {
            strategyRequest(
                direction = SynchronizationDirection.PULL,
                input = StrategyOperationInput.DirectTransport(
                    outboundChangeSet = changeSet(),
                ),
            )
        }
    }

    @Test
    fun evaluationRequestPreservesStrategyAxesExactly() {
        val request = strategyRequest(
            direction = SynchronizationDirection.BIDIRECTIONAL,
            input = StrategyOperationInput.DirectTransport(
                outboundChangeSet = changeSet(),
            ),
        )

        val evaluation = request.evaluationRequest()

        assertEquals(request.decisionId, evaluation.decisionId)
        assertEquals(request.planId, evaluation.planId)
        assertEquals(request.profile, evaluation.profile)
        assertEquals(request.request.direction, evaluation.direction)
        assertEquals(request.request.mode, evaluation.mode)
        assertEquals(request.evidence, evaluation.evidence)
    }

    private fun strategyRequest(
        direction: SynchronizationDirection,
        input: StrategyOperationInput,
    ): StrategySynchronizationRequest =
        StrategySynchronizationRequest(
            request = SynchronizationRequest(
                workflowId = WorkflowId("workflow"),
                sessionId = SynchronizationSessionId("session"),
                direction = direction,
                mode = SynchronizationMode.DELTA,
                context = ExecutionContext(
                    executionId = ExecutionId("execution"),
                    correlationId = CorrelationId("correlation"),
                ),
            ),
            decisionId = StrategyDecisionId("decision"),
            planId = StrategyPlanId("plan"),
            profile = NetworkOnlyStrategyProfile(
                id = StrategyProfileId("network"),
                configurationVersion = StrategyConfigurationVersion(1),
            ),
            evidence = StrategyRuntimeEvidence(
                connectivity = StrategyConnectivity.AVAILABLE,
            ),
            input = input,
        )

    private fun changeSet(): ChangeSet =
        ChangeSet(
            id = ChangeSetId("changes"),
            events = listOf(
                ChangeEvent(
                    id = ChangeEventId("change"),
                    entity = EntityReference(
                        type = EntityType("document"),
                        id = EntityId("document-1"),
                    ),
                    operation = ChangeOperation.UPDATE,
                ),
            ),
        )
}
