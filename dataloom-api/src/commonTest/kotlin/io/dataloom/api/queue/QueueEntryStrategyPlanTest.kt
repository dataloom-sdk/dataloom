package io.dataloom.api.queue

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
import io.dataloom.api.strategy.StrategyConsistency
import io.dataloom.api.strategy.StrategyDataOrigin
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyDeferralReason
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyDurableContinuationPlan
import io.dataloom.api.strategy.StrategyExecutionPlan
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.strategy.StrategyProviderCapability
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class QueueEntryStrategyPlanTest {

    @Test
    fun completeAcceptedPlanIsAdmittedWithoutDiagnosticDisclosure() {
        val entry = entry(decision(), plan())
        val diagnostic = entry.toString()
        assertFalse("plan-sensitive" in diagnostic)
        assertFalse("profile-sensitive" in diagnostic)
    }

    @Test
    fun planWithoutDecisionIsRejected() {
        assertFailsWith<IllegalArgumentException> { entry(null, plan()) }
    }

    @Test
    fun identityDirectionModeAndContinuationAreRequired() {
        assertFailsWith<IllegalArgumentException> {
            entry(decision(version = 2L), plan())
        }
        assertFailsWith<IllegalArgumentException> {
            entry(decision(), plan(direction = SynchronizationDirection.PULL))
        }
        assertFailsWith<IllegalArgumentException> {
            entry(decision(), plan(includeContinuation = false))
        }
    }

    private fun entry(
        decision: PersistedStrategyDecision?,
        plan: StrategyExecutionPlan?,
    ): QueueEntry = QueueEntry(
        id = QueueEntryId("entry-1"),
        synchronizationRequest = request(),
        state = QueueEntryState.PENDING,
        enqueuedAt = DataLoomInstant(1_000L),
        availableAt = DataLoomInstant(1_000L),
        strategyDecision = decision,
        strategyPlan = plan,
    )

    private fun request(): SynchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("workflow-1"),
        sessionId = SynchronizationSessionId("session-1"),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("execution-1"),
            correlationId = CorrelationId("correlation-1"),
        ),
    )

    private fun decision(version: Long = 1L): PersistedStrategyDecision =
        PersistedStrategyDecision(
            decisionId = StrategyDecisionId("decision-sensitive"),
            planId = StrategyPlanId("plan-sensitive"),
            requestedStrategy = BuiltInSynchronizationStrategy.ADAPTIVE,
            effectiveProfileId = StrategyProfileId("profile-sensitive"),
            effectiveStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
            configurationVersion = StrategyConfigurationVersion(version),
            disposition = StrategyDisposition.DEFER,
        )

    private fun plan(
        direction: SynchronizationDirection = SynchronizationDirection.PUSH,
        includeContinuation: Boolean = true,
    ): StrategyExecutionPlan = StrategyExecutionPlan(
        id = StrategyPlanId("plan-sensitive"),
        requestedStrategy = BuiltInSynchronizationStrategy.ADAPTIVE,
        effectiveProfileId = StrategyProfileId("profile-sensitive"),
        effectiveStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
        configurationVersion = StrategyConfigurationVersion(1L),
        direction = direction,
        mode = SynchronizationMode.DELTA,
        disposition = StrategyDisposition.DEFER,
        operations = listOf(
            StrategyOperation.ACCEPT_LOCAL,
            StrategyOperation.ENQUEUE_DURABLE_WORK,
        ),
        requiredCapabilities = setOf(
            StrategyProviderCapability.STORAGE,
            StrategyProviderCapability.QUEUE,
        ),
        dataOrigin = StrategyDataOrigin.LOCAL,
        consistency = StrategyConsistency.LOCAL_AUTHORITATIVE,
        deferralReason = StrategyDeferralReason.CONNECTIVITY_UNAVAILABLE,
        durableContinuation = if (includeContinuation) {
            StrategyDurableContinuationPlan(
                operations = listOf(
                    StrategyOperation.READ_LOCAL,
                    StrategyOperation.PUSH_REMOTE,
                ),
                requiredCapabilities = setOf(
                    StrategyProviderCapability.STORAGE,
                    StrategyProviderCapability.TRANSPORT,
                ),
                dataOrigin = StrategyDataOrigin.NONE,
                consistency = StrategyConsistency.LOCAL_AUTHORITATIVE,
            )
        } else {
            null
        },
    )
}
