package io.dataloom.runtime.strategy

import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.strategy.BuiltInSynchronizationStrategy
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyConsistency
import io.dataloom.api.strategy.StrategyDataOrigin
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyDeferralReason
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyDurableContinuationPlan
import io.dataloom.api.strategy.StrategyEvaluationResult
import io.dataloom.api.strategy.StrategyExecutionPlan
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.strategy.StrategyProviderCapability
import io.dataloom.api.strategy.StrategyRejectionReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class StrategyQueueAdmissionEvaluatorTest {

    @Test
    fun offlineFirstDeferralProducesExactDurableDecision() {
        val plan = plan(
            disposition = StrategyDisposition.DEFER,
            operations = listOf(
                StrategyOperation.ACCEPT_LOCAL,
                StrategyOperation.ENQUEUE_DURABLE_WORK,
            ),
            capabilities = setOf(
                StrategyProviderCapability.STORAGE,
                StrategyProviderCapability.QUEUE,
            ),
            deferralReason = StrategyDeferralReason.CONNECTIVITY_UNAVAILABLE,
        )
        val evaluation = evaluation(plan)

        val admitted = assertIs<StrategyQueueAdmissionResult.Admitted>(
            StrategyQueueAdmissionEvaluator.evaluate(evaluation),
        )

        assertSame(plan, admitted.plan)
        assertEquals(evaluation.decisionId, admitted.persistedDecision.decisionId)
        assertEquals(plan.id, admitted.persistedDecision.planId)
        assertEquals(plan.requestedStrategy, admitted.persistedDecision.requestedStrategy)
        assertEquals(plan.effectiveProfileId, admitted.persistedDecision.effectiveProfileId)
        assertEquals(plan.effectiveStrategy, admitted.persistedDecision.effectiveStrategy)
        assertEquals(
            plan.configurationVersion,
            admitted.persistedDecision.configurationVersion,
        )
        assertEquals(plan.disposition, admitted.persistedDecision.disposition)
    }

    @Test
    fun adaptiveRequestPersistsTheConcreteEffectiveStrategy() {
        val plan = plan(
            requestedStrategy = BuiltInSynchronizationStrategy.ADAPTIVE,
            effectiveStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
            disposition = StrategyDisposition.EXECUTE,
            operations = listOf(
                StrategyOperation.ACCEPT_LOCAL,
                StrategyOperation.ENQUEUE_DURABLE_WORK,
                StrategyOperation.PUSH_REMOTE,
            ),
            capabilities = setOf(
                StrategyProviderCapability.STORAGE,
                StrategyProviderCapability.QUEUE,
                StrategyProviderCapability.TRANSPORT,
            ),
        )

        val admitted = assertIs<StrategyQueueAdmissionResult.Admitted>(
            StrategyQueueAdmissionEvaluator.evaluate(evaluation(plan)),
        )

        assertEquals(
            BuiltInSynchronizationStrategy.ADAPTIVE,
            admitted.persistedDecision.requestedStrategy,
        )
        assertEquals(
            BuiltInSynchronizationStrategy.OFFLINE_FIRST,
            admitted.persistedDecision.effectiveStrategy,
        )
    }

    @Test
    fun rejectedPlanFailsBeforeQueueOperationChecks() {
        val plan = plan(
            disposition = StrategyDisposition.REJECT,
            operations = emptyList(),
            capabilities = emptySet(),
            dataOrigin = StrategyDataOrigin.NONE,
            rejectionReason = StrategyRejectionReason.CACHE_MISS,
        )

        val rejected = assertIs<StrategyQueueAdmissionResult.Rejected>(
            StrategyQueueAdmissionEvaluator.evaluate(evaluation(plan)),
        )

        assertEquals(plan.id, rejected.planId)
        assertEquals(
            StrategyQueueAdmissionRejectionReason.PLAN_REJECTED,
            rejected.reason,
        )
    }

    @Test
    fun planWithoutDurableQueueOperationIsRejected() {
        val plan = plan(
            effectiveStrategy = BuiltInSynchronizationStrategy.REMOTE_FIRST,
            disposition = StrategyDisposition.EXECUTE,
            operations = listOf(StrategyOperation.PULL_REMOTE),
            capabilities = setOf(StrategyProviderCapability.TRANSPORT),
            dataOrigin = StrategyDataOrigin.REMOTE,
        )

        val rejected = assertIs<StrategyQueueAdmissionResult.Rejected>(
            StrategyQueueAdmissionEvaluator.evaluate(evaluation(plan)),
        )

        assertEquals(
            StrategyQueueAdmissionRejectionReason.MISSING_DURABLE_QUEUE_OPERATION,
            rejected.reason,
        )
    }

    @Test
    fun planWithoutDurableContinuationIsRejected() {
        val plan = plan(
            disposition = StrategyDisposition.DEFER,
            operations = listOf(StrategyOperation.ENQUEUE_DURABLE_WORK),
            capabilities = setOf(StrategyProviderCapability.QUEUE),
            deferralReason = StrategyDeferralReason.CONNECTIVITY_UNAVAILABLE,
            includeContinuation = false,
        )

        val rejected = assertIs<StrategyQueueAdmissionResult.Rejected>(
            StrategyQueueAdmissionEvaluator.evaluate(evaluation(plan)),
        )

        assertEquals(
            StrategyQueueAdmissionRejectionReason.MISSING_DURABLE_CONTINUATION,
            rejected.reason,
        )
    }

    @Test
    fun queueOperationWithoutQueueCapabilityIsRejected() {
        val plan = plan(
            disposition = StrategyDisposition.EXECUTE,
            operations = listOf(
                StrategyOperation.ACCEPT_LOCAL,
                StrategyOperation.ENQUEUE_DURABLE_WORK,
            ),
            capabilities = setOf(StrategyProviderCapability.STORAGE),
        )

        val rejected = assertIs<StrategyQueueAdmissionResult.Rejected>(
            StrategyQueueAdmissionEvaluator.evaluate(evaluation(plan)),
        )

        assertEquals(
            StrategyQueueAdmissionRejectionReason.MISSING_QUEUE_CAPABILITY,
            rejected.reason,
        )
    }

    private fun evaluation(plan: StrategyExecutionPlan): StrategyEvaluationResult =
        StrategyEvaluationResult(
            decisionId = StrategyDecisionId("decision-1"),
            plan = plan,
            reasonCodes = listOf("OFFLINE_FIRST_DURABLE_ADMISSION"),
        )

    private fun plan(
        requestedStrategy: BuiltInSynchronizationStrategy =
            BuiltInSynchronizationStrategy.OFFLINE_FIRST,
        effectiveStrategy: BuiltInSynchronizationStrategy =
            BuiltInSynchronizationStrategy.OFFLINE_FIRST,
        disposition: StrategyDisposition,
        operations: List<StrategyOperation>,
        capabilities: Set<StrategyProviderCapability>,
        dataOrigin: StrategyDataOrigin = StrategyDataOrigin.LOCAL,
        deferralReason: StrategyDeferralReason? = null,
        rejectionReason: StrategyRejectionReason? = null,
        includeContinuation: Boolean = true,
    ): StrategyExecutionPlan = StrategyExecutionPlan(
        id = StrategyPlanId("plan-1"),
        requestedStrategy = requestedStrategy,
        effectiveProfileId = StrategyProfileId("profile-1"),
        effectiveStrategy = effectiveStrategy,
        configurationVersion = StrategyConfigurationVersion(7L),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        disposition = disposition,
        operations = operations,
        requiredCapabilities = capabilities,
        dataOrigin = dataOrigin,
        consistency = StrategyConsistency.READ_YOUR_WRITES,
        deferralReason = deferralReason,
        rejectionReason = rejectionReason,
        durableContinuation = if (
            includeContinuation &&
            StrategyOperation.ENQUEUE_DURABLE_WORK in operations
        ) {
            StrategyDurableContinuationPlan(
                operations = listOf(StrategyOperation.PUSH_REMOTE),
                requiredCapabilities = setOf(StrategyProviderCapability.TRANSPORT),
                dataOrigin = StrategyDataOrigin.NONE,
                consistency = StrategyConsistency.READ_YOUR_WRITES,
            )
        } else {
            null
        },
    )
}
