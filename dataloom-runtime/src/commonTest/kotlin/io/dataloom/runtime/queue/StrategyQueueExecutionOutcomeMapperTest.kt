package io.dataloom.runtime.queue

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
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
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.strategy.BuiltInSynchronizationStrategy
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
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.runtime.retry.SynchronizationRetryEvaluator
import io.dataloom.runtime.strategy.StrategySynchronizationExecutionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StrategyQueueExecutionOutcomeMapperTest {

    @Test
    fun partialBidirectionalPushUsesRetryPolicyInsteadOfCompletingQueueEntry() {
        val error = TestError()
        val pushResult = partialPush(listOf(error))

        val outcome = mapper().map(
            result = executed(pushResult),
            entry = entry(),
        )

        val failed = assertIs<QueueEntryExecutionOutcome.Failed>(outcome)
        assertEquals(error.code, failed.error.code)
    }

    @Test
    fun partialBidirectionalPushEvaluatesEveryErrorAndCannotHideProtectedFailure() {
        val retryable = TestError(
            code = ErrorCode("BIDIRECTIONAL_PUSH_RETRYABLE"),
        )
        val protected = TestError(
            code = ErrorCode("BIDIRECTIONAL_PUSH_AUTHENTICATION"),
            category = ErrorCategory.AUTHENTICATION,
        )
        val pushResult = partialPush(listOf(retryable, protected))

        val outcome = mapper(AlwaysRetryPolicy).map(
            result = executed(pushResult),
            entry = entry(),
        )

        val failed = assertIs<QueueEntryExecutionOutcome.Failed>(outcome)
        assertEquals(protected.code, failed.error.code)
        assertEquals(ErrorCategory.AUTHENTICATION, failed.error.category)
    }

    @Test
    fun successfulBidirectionalPushCompletesAtOverallAcceptedPlanTime() {
        val pushResult = SynchronizationResult.Succeeded(
            request = request(),
            completedAt = DataLoomInstant(2_000L),
            summary = SynchronizationSummary(),
        )

        val outcome = mapper().map(
            result = executed(pushResult),
            entry = entry(),
        )

        val completed = assertIs<QueueEntryExecutionOutcome.Completed>(outcome)
        assertEquals(DataLoomInstant(3_000L), completed.completedAt)
    }

    @Test
    fun defensiveCancelledBidirectionalPushRemainsCancelled() {
        val pushResult = SynchronizationResult.Cancelled(
            request = request(),
            completedAt = DataLoomInstant(2_000L),
            summary = SynchronizationSummary(),
        )

        val outcome = mapper().map(
            result = executed(pushResult),
            entry = entry(),
        )

        val cancelled = assertIs<QueueEntryExecutionOutcome.Cancelled>(outcome)
        assertEquals(request().context, cancelled.context)
    }

    private fun mapper(
        retryPolicy: RetryPolicy = StopPolicy,
    ): StrategyQueueExecutionOutcomeMapper =
        StrategyQueueExecutionOutcomeMapper(
            retryEvaluator = SynchronizationRetryEvaluator(
                retryPolicy = retryPolicy,
                clock = FixedClock(DataLoomInstant(4_000L)),
            ),
            retryOperation = RetryOperation("strategy.accepted.bidirectional"),
        )

    private fun partialPush(
        errors: List<DataLoomError>,
    ): SynchronizationResult.PartiallySucceeded =
        SynchronizationResult.PartiallySucceeded(
            request = request(),
            completedAt = DataLoomInstant(2_000L),
            summary = SynchronizationSummary(),
            errors = errors,
        )

    private fun executed(
        pushResult: SynchronizationResult,
    ): StrategySynchronizationExecutionResult.Executed =
        StrategySynchronizationExecutionResult.Executed(
            evaluation = evaluation(),
            completedAt = DataLoomInstant(3_000L),
            output = StrategyTransportOutput.RemoteFirstBidirectional(
                pushResult = pushResult,
                pullResult = PullChangesResult.NoChanges(),
            ),
        )

    private fun evaluation(): StrategyEvaluationResult = StrategyEvaluationResult(
        decisionId = StrategyDecisionId("decision-bidirectional"),
        plan = StrategyExecutionPlan(
            id = StrategyPlanId("plan-bidirectional"),
            requestedStrategy = BuiltInSynchronizationStrategy.REMOTE_FIRST,
            effectiveProfileId = StrategyProfileId("remote-profile"),
            effectiveStrategy = BuiltInSynchronizationStrategy.REMOTE_FIRST,
            configurationVersion = StrategyConfigurationVersion(1L),
            direction = SynchronizationDirection.BIDIRECTIONAL,
            mode = SynchronizationMode.DELTA,
            disposition = StrategyDisposition.EXECUTE,
            operations = listOf(
                StrategyOperation.READ_LOCAL,
                StrategyOperation.PUSH_REMOTE,
                StrategyOperation.PULL_REMOTE,
            ),
            requiredCapabilities = setOf(
                StrategyProviderCapability.STORAGE,
                StrategyProviderCapability.TRANSPORT,
            ),
            dataOrigin = StrategyDataOrigin.MIXED,
            consistency = StrategyConsistency.REMOTE_AUTHORITATIVE,
        ),
        reasonCodes = listOf("strategy.accepted-plan-replay"),
    )

    private fun entry(): QueueEntry = QueueEntry(
        id = QueueEntryId("entry-bidirectional"),
        synchronizationRequest = request(),
        state = QueueEntryState.PENDING,
        enqueuedAt = DataLoomInstant(1_000L),
        availableAt = DataLoomInstant(1_000L),
    )

    private fun request(): SynchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("workflow-bidirectional"),
        sessionId = SynchronizationSessionId("session-bidirectional"),
        direction = SynchronizationDirection.BIDIRECTIONAL,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("execution-bidirectional"),
            correlationId = CorrelationId("correlation-bidirectional"),
        ),
    )

    private object StopPolicy : RetryPolicy {
        override val id: RetryPolicyId = RetryPolicyId("stop")

        override fun evaluate(request: RetryEvaluationRequest): RetryDecision =
            RetryDecision.Stop(RetryStopReason.POLICY_REJECTED)
    }

    private object AlwaysRetryPolicy : RetryPolicy {
        override val id: RetryPolicyId = RetryPolicyId("always-retry")

        override fun evaluate(request: RetryEvaluationRequest): RetryDecision =
            RetryDecision.Retry(SchedulingDelay(1_000L))
    }

    private class FixedClock(private val instant: DataLoomInstant) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
    }

    private data class TestError(
        override val code: ErrorCode = ErrorCode("BIDIRECTIONAL_PUSH_PARTIAL"),
        override val category: ErrorCategory = ErrorCategory.NETWORK,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "The bidirectional push completed only partially.",
        override val cause: Throwable? = null,
    ) : DataLoomError
}
