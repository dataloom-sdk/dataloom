package io.dataloom.runtime.queue

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.RetryPolicyId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.retry.RetryDecision
import io.dataloom.api.retry.RetryEvaluationRequest
import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.retry.RetryPolicy
import io.dataloom.api.retry.RetryStopReason
import io.dataloom.api.strategy.BuiltInSynchronizationStrategy
import io.dataloom.api.strategy.StrategyCacheFreshnessEvidence
import io.dataloom.api.strategy.StrategyCacheState
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyConsistency
import io.dataloom.api.strategy.StrategyDataOrigin
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyEvaluationResult
import io.dataloom.api.strategy.StrategyExecutionPlan
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.strategy.StrategyProviderCapability
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.retry.SynchronizationRetryEvaluator
import io.dataloom.runtime.strategy.StrategySynchronizationExecutionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StrategyQueueDirectCacheSafetyTest {

    @Test
    fun directCacheServingResultCannotCompleteDurableQueueEntry() {
        val outcome = mapper().map(
            result = StrategySynchronizationExecutionResult.CacheServed(
                evaluation = cacheEvaluation(),
                completedAt = DataLoomInstant(3_000L),
                evaluatedCacheState = StrategyCacheState.FRESH,
                freshness = StrategyCacheFreshnessEvidence(
                    cacheState = StrategyCacheState.FRESH,
                    observedAt = DataLoomInstant(1_000L),
                    validUntil = DataLoomInstant(2_000L),
                ),
            ),
            entry = entry(),
        )

        val failed = assertIs<QueueEntryExecutionOutcome.Failed>(outcome)
        assertEquals(
            "DL-Q-ACCEPTED-PLAN-UNEXPECTED-DIRECT-CACHE-SERVE",
            failed.error.code.value,
        )
        assertEquals(ErrorCategory.STATE, failed.error.category)
    }

    private fun mapper(): StrategyQueueExecutionOutcomeMapper =
        StrategyQueueExecutionOutcomeMapper(
            retryEvaluator = SynchronizationRetryEvaluator(
                retryPolicy = StopPolicy,
                clock = FixedClock(DataLoomInstant(4_000L)),
            ),
            retryOperation = RetryOperation("strategy.accepted.cache-safety"),
        )

    private fun cacheEvaluation(): StrategyEvaluationResult =
        StrategyEvaluationResult(
            decisionId = StrategyDecisionId("cache-queue-safety-decision"),
            plan = StrategyExecutionPlan(
                id = StrategyPlanId("cache-queue-safety-plan"),
                requestedStrategy = BuiltInSynchronizationStrategy.CACHE_FIRST,
                effectiveProfileId = StrategyProfileId("cache-queue-safety-profile"),
                effectiveStrategy = BuiltInSynchronizationStrategy.CACHE_FIRST,
                configurationVersion = StrategyConfigurationVersion(1),
                direction = SynchronizationDirection.PULL,
                mode = SynchronizationMode.DELTA,
                disposition = StrategyDisposition.EXECUTE,
                operations = listOf(StrategyOperation.SERVE_LOCAL),
                requiredCapabilities = setOf(
                    StrategyProviderCapability.STORAGE,
                    StrategyProviderCapability.CACHE_ACCESS,
                ),
                dataOrigin = StrategyDataOrigin.LOCAL,
                consistency = StrategyConsistency.EVENTUAL,
            ),
            reasonCodes = listOf("cache-first.fresh-hit"),
        )

    private fun entry(): QueueEntry = QueueEntry(
        id = QueueEntryId("cache-queue-safety-entry"),
        synchronizationRequest = SynchronizationRequest(
            workflowId = WorkflowId("cache-queue-safety-workflow"),
            sessionId = SynchronizationSessionId("cache-queue-safety-session"),
            direction = SynchronizationDirection.PULL,
            mode = SynchronizationMode.DELTA,
            context = ExecutionContext(
                executionId = ExecutionId("cache-queue-safety-execution"),
                correlationId = CorrelationId("cache-queue-safety-correlation"),
            ),
        ),
        state = QueueEntryState.PENDING,
        enqueuedAt = DataLoomInstant(1_000L),
        availableAt = DataLoomInstant(1_000L),
    )

    private object StopPolicy : RetryPolicy {
        override val id: RetryPolicyId = RetryPolicyId("cache-queue-safety-stop")

        override fun evaluate(request: RetryEvaluationRequest): RetryDecision =
            RetryDecision.Stop(RetryStopReason.POLICY_REJECTED)
    }

    private class FixedClock(
        private val instant: DataLoomInstant,
    ) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
    }
}
