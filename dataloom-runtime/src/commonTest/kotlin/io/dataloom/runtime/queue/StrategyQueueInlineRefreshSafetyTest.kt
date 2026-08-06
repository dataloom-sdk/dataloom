package io.dataloom.runtime.queue

import io.dataloom.api.context.ExecutionContext
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
import io.dataloom.api.strategy.StrategyTransportOutput
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.synchronization.SynchronizationSummary
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.retry.SynchronizationRetryEvaluator
import io.dataloom.runtime.strategy.StrategyCacheInlineRefreshResult
import io.dataloom.runtime.strategy.StrategyCacheServedWithInlineRefreshResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StrategyQueueInlineRefreshSafetyTest {

    @Test
    fun directInlineRefreshResultCannotCompleteDurableQueueEntry() {
        val outcome = mapper().map(
            result = StrategyCacheServedWithInlineRefreshResult(
                evaluation = evaluation(),
                evaluatedCacheState = StrategyCacheState.FRESH,
                freshness = StrategyCacheFreshnessEvidence(
                    StrategyCacheState.FRESH,
                    DataLoomInstant(1_000L),
                    DataLoomInstant(2_000L),
                ),
                refresh = StrategyCacheInlineRefreshResult.Completed(
                    completedOperations = listOf(StrategyOperation.PULL_REMOTE),
                    output = StrategyTransportOutput.ProviderBacked(
                        SynchronizationResult.Skipped(
                            request = request,
                            completedAt = DataLoomInstant(3_000L),
                            summary = SynchronizationSummary(),
                        ),
                    ),
                ),
            ),
            entry = entry(),
        )

        val failed = assertIs<QueueEntryExecutionOutcome.Failed>(outcome)
        assertEquals(
            "DL-Q-ACCEPTED-PLAN-UNEXPECTED-DIRECT-CACHE-SERVE",
            failed.error.code.value,
        )
    }

    private fun mapper() = StrategyQueueExecutionOutcomeMapper(
        retryEvaluator = SynchronizationRetryEvaluator(
            StopPolicy,
            FixedClock(DataLoomInstant(4_000L)),
        ),
        retryOperation = RetryOperation("strategy.inline-refresh.queue-safety"),
    )

    private fun evaluation() = StrategyEvaluationResult(
        decisionId = StrategyDecisionId("inline-refresh-queue-decision"),
        plan = StrategyExecutionPlan(
            id = StrategyPlanId("inline-refresh-queue-plan"),
            requestedStrategy = BuiltInSynchronizationStrategy.CACHE_FIRST,
            effectiveProfileId = StrategyProfileId("inline-refresh-queue-profile"),
            effectiveStrategy = BuiltInSynchronizationStrategy.CACHE_FIRST,
            configurationVersion = StrategyConfigurationVersion(1),
            direction = SynchronizationDirection.PULL,
            mode = SynchronizationMode.DELTA,
            disposition = StrategyDisposition.SERVE_AND_REFRESH,
            operations = listOf(
                StrategyOperation.SERVE_LOCAL,
                StrategyOperation.PULL_REMOTE,
                StrategyOperation.PERSIST_REMOTE,
            ),
            requiredCapabilities = setOf(
                StrategyProviderCapability.STORAGE,
                StrategyProviderCapability.CACHE_ACCESS,
                StrategyProviderCapability.TRANSPORT,
            ),
            dataOrigin = StrategyDataOrigin.LOCAL,
            consistency = StrategyConsistency.EVENTUAL,
        ),
        reasonCodes = listOf("cache-first.fresh-hit-refresh"),
    )

    private fun entry() = QueueEntry(
        id = QueueEntryId("inline-refresh-queue-entry"),
        synchronizationRequest = request,
        state = QueueEntryState.PENDING,
        enqueuedAt = DataLoomInstant(1_000L),
        availableAt = DataLoomInstant(1_000L),
    )

    private object StopPolicy : RetryPolicy {
        override val id = RetryPolicyId("inline-refresh-queue-stop")
        override fun evaluate(request: RetryEvaluationRequest): RetryDecision =
            RetryDecision.Stop(RetryStopReason.POLICY_REJECTED)
    }

    private class FixedClock(private val instant: DataLoomInstant) : DataLoomClock {
        override fun now() = instant
    }

    private companion object {
        val request = SynchronizationRequest(
            WorkflowId("inline-refresh-queue-workflow"),
            SynchronizationSessionId("inline-refresh-queue-session"),
            SynchronizationDirection.PULL,
            SynchronizationMode.DELTA,
            ExecutionContext(
                ExecutionId("inline-refresh-queue-execution"),
                CorrelationId("inline-refresh-queue-correlation"),
            ),
        )
    }
}
