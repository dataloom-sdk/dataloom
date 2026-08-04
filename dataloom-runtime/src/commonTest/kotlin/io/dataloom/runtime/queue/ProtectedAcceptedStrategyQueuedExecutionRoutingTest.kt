package io.dataloom.runtime.queue

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.QueueConsumerId
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.RetryPolicyId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.StrategyProviderBindings
import io.dataloom.api.provider.SynchronizationProviderBindings
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.queue.QueueLease
import io.dataloom.api.retry.RetryDecision
import io.dataloom.api.retry.RetryEvaluationRequest
import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.retry.RetryPolicy
import io.dataloom.api.retry.RetryStopReason
import io.dataloom.api.strategy.BuiltInSynchronizationStrategy
import io.dataloom.api.strategy.PersistedStrategyDecision
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
import io.dataloom.api.strategy.StrategySynchronizationRequest
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.execution.SynchronizationExecutionRejectionReason
import io.dataloom.runtime.execution.SynchronizationExecutionResult
import io.dataloom.runtime.execution.protection.ProviderProtectedStrategySynchronizationResult
import io.dataloom.runtime.facade.DataLoomProtectedStrategySynchronization
import io.dataloom.runtime.facade.DataLoomProtectedSynchronization
import io.dataloom.runtime.facade.ProviderProtectedSynchronizationExecutionResult
import io.dataloom.runtime.retry.SynchronizationRetryEvaluator
import io.dataloom.runtime.strategy.StrategyExecutionRejectionReason
import io.dataloom.runtime.strategy.StrategySynchronizationExecutionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest

class ProtectedAcceptedStrategyQueuedExecutionRoutingTest {

    @Test
    fun planBearingWorkNeverFallsBackToLegacyProtectedSynchronization() = runTest {
        val legacy = FailingLegacyProtectedSynchronization()
        val handler = handler(legacy, protectedStrategy = null)

        val result = handler.execute(entry())

        val failed = assertIs<QueueEntryExecutionOutcome.Failed>(result.outcome)
        assertEquals(
            "DL-PROTECTED-QUEUE-ACCEPTED-PLAN-NOT-CONFIGURED",
            failed.error.code.value,
        )
        assertEquals(0, legacy.calls)
        assertEquals(null, result.executionResult)
        assertEquals(null, result.strategyExecutionResult)
    }

    @Test
    fun configuredProtectedStrategyFacadeOwnsPlanBearingQueueExecution() = runTest {
        val legacy = FailingLegacyProtectedSynchronization()
        val protectedResult = ProviderProtectedStrategySynchronizationResult(
            strategyResult = StrategySynchronizationExecutionResult.Rejected(
                evaluation = evaluation(),
                completedAt = DataLoomInstant(2_000L),
                reason = StrategyExecutionRejectionReason.PROVIDERS_NOT_INITIALIZED,
            ),
            operationEvidence = emptyList(),
        )
        val strategy = RecordingProtectedStrategySynchronization(protectedResult)
        val handler = handler(legacy, strategy)

        val result = handler.execute(entry())

        val failed = assertIs<QueueEntryExecutionOutcome.Failed>(result.outcome)
        assertEquals("DL-Q-ACCEPTED-PLAN-REJECTED", failed.error.code.value)
        assertEquals(0, legacy.calls)
        assertEquals(1, strategy.acceptedCalls)
        assertSame(protectedResult, result.strategyExecutionResult)
        assertEquals(null, result.executionResult)
    }

    private fun handler(
        legacy: DataLoomProtectedSynchronization,
        protectedStrategy: DataLoomProtectedStrategySynchronization?,
    ): ProviderProtectedQueuedSynchronizationExecutionHandler =
        ProviderProtectedQueuedSynchronizationExecutionHandler(
            workResolver = QueuedSynchronizationWorkResolver { queued ->
                QueuedSynchronizationWorkResolution.Resolved(
                    QueuedSynchronizationWork(
                        request = queued.synchronizationRequest,
                        bindings = bindings(),
                        strategyDecision = queued.strategyDecision,
                        strategyPlan = queued.strategyPlan,
                    ),
                )
            },
            protectedSynchronization = legacy,
            retryEvaluator = SynchronizationRetryEvaluator(
                retryPolicy = StopPolicy,
                clock = FixedClock(DataLoomInstant(2_000L)),
            ),
            retryOperation = RetryOperation("protected.queued.accepted-plan"),
            protectedStrategySynchronization = protectedStrategy,
        )

    private fun entry(): QueueEntry = QueueEntry(
        id = QueueEntryId("entry-protected-plan"),
        synchronizationRequest = request(),
        state = QueueEntryState.LEASED,
        enqueuedAt = DataLoomInstant(1_000L),
        availableAt = DataLoomInstant(1_000L),
        lease = QueueLease(
            id = QueueLeaseId("lease-protected-plan"),
            consumerId = QueueConsumerId("consumer-protected-plan"),
            acquiredAt = DataLoomInstant(1_500L),
            expiresAt = DataLoomInstant(10_000L),
        ),
        strategyDecision = decision(),
        strategyPlan = plan(),
    )

    private fun request(): SynchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("workflow-protected-plan"),
        sessionId = SynchronizationSessionId("session-protected-plan"),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("execution-protected-plan"),
            correlationId = CorrelationId("correlation-protected-plan"),
        ),
    )

    private fun decision(): PersistedStrategyDecision = PersistedStrategyDecision(
        decisionId = StrategyDecisionId("decision-protected-plan"),
        planId = StrategyPlanId("plan-protected"),
        requestedStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
        effectiveProfileId = StrategyProfileId("offline-profile"),
        effectiveStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
        configurationVersion = StrategyConfigurationVersion(1L),
        disposition = StrategyDisposition.DEFER,
    )

    private fun plan(): StrategyExecutionPlan = StrategyExecutionPlan(
        id = StrategyPlanId("plan-protected"),
        requestedStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
        effectiveProfileId = StrategyProfileId("offline-profile"),
        effectiveStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
        configurationVersion = StrategyConfigurationVersion(1L),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        disposition = StrategyDisposition.DEFER,
        operations = listOf(StrategyOperation.ENQUEUE_DURABLE_WORK),
        requiredCapabilities = setOf(StrategyProviderCapability.QUEUE),
        dataOrigin = StrategyDataOrigin.NONE,
        consistency = StrategyConsistency.LOCAL_AUTHORITATIVE,
        deferralReason = StrategyDeferralReason.CONNECTIVITY_UNAVAILABLE,
        durableContinuation = StrategyDurableContinuationPlan(
            operations = listOf(StrategyOperation.PUSH_REMOTE),
            requiredCapabilities = setOf(StrategyProviderCapability.TRANSPORT),
            dataOrigin = StrategyDataOrigin.NONE,
            consistency = StrategyConsistency.LOCAL_AUTHORITATIVE,
        ),
    )

    private fun evaluation(): StrategyEvaluationResult = StrategyEvaluationResult(
        decisionId = decision().decisionId,
        plan = plan(),
        reasonCodes = listOf("strategy.accepted-plan-replay"),
    )

    private fun bindings(): SynchronizationProviderBindings =
        SynchronizationProviderBindings(
            storageProviderId = ProviderId("storage-1"),
            transportProviderId = ProviderId("transport-1"),
        )

    private class FailingLegacyProtectedSynchronization : DataLoomProtectedSynchronization {
        var calls: Int = 0

        override suspend fun synchronize(
            request: SynchronizationRequest,
        ): ProviderProtectedSynchronizationExecutionResult {
            calls++
            error("Legacy protected synchronization must not execute accepted plans.")
        }

        override suspend fun synchronize(
            request: SynchronizationRequest,
            bindings: SynchronizationProviderBindings,
        ): ProviderProtectedSynchronizationExecutionResult {
            calls++
            error("Legacy protected synchronization must not execute accepted plans.")
        }
    }

    private class RecordingProtectedStrategySynchronization(
        private val result: ProviderProtectedStrategySynchronizationResult,
    ) : DataLoomProtectedStrategySynchronization {
        var acceptedCalls: Int = 0

        override suspend fun synchronize(
            request: StrategySynchronizationRequest,
        ): ProviderProtectedStrategySynchronizationResult =
            error("Current-policy strategy evaluation must not run during accepted replay.")

        override suspend fun synchronize(
            request: StrategySynchronizationRequest,
            bindings: StrategyProviderBindings,
        ): ProviderProtectedStrategySynchronizationResult =
            error("Current-policy strategy evaluation must not run during accepted replay.")

        override suspend fun synchronizeAcceptedPlan(
            request: SynchronizationRequest,
            decision: PersistedStrategyDecision,
            plan: StrategyExecutionPlan,
        ): ProviderProtectedStrategySynchronizationResult {
            acceptedCalls++
            return result
        }

        override suspend fun synchronizeAcceptedPlan(
            request: SynchronizationRequest,
            decision: PersistedStrategyDecision,
            plan: StrategyExecutionPlan,
            bindings: StrategyProviderBindings,
        ): ProviderProtectedStrategySynchronizationResult {
            acceptedCalls++
            return result
        }
    }

    private object StopPolicy : RetryPolicy {
        override val id: RetryPolicyId = RetryPolicyId("stop")
        override fun evaluate(request: RetryEvaluationRequest): RetryDecision =
            RetryDecision.Stop(RetryStopReason.POLICY_REJECTED)
    }

    private class FixedClock(private val instant: DataLoomInstant) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
    }

    @Suppress("unused")
    private data class TestError(
        override val code: ErrorCode = ErrorCode("TEST"),
        override val category: ErrorCategory = ErrorCategory.STATE,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE,
        override val message: String = "Test error.",
        override val cause: Throwable? = null,
    ) : DataLoomError
}
