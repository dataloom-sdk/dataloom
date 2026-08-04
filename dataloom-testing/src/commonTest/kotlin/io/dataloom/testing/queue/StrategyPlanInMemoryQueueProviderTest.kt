package io.dataloom.testing.queue

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
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.queue.ExpiredLeaseRecoveryRequest
import io.dataloom.api.queue.QueueAcquireRequest
import io.dataloom.api.queue.QueueAcquireResult
import io.dataloom.api.queue.QueueDeferralReason
import io.dataloom.api.queue.QueueDeferralRequest
import io.dataloom.api.queue.QueueEnqueueRequest
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.queue.QueueRescheduleRequest
import io.dataloom.api.retry.RetryAttempt
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
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StrategyPlanInMemoryQueueProviderTest {

    @Test
    fun completePlanSurvivesRetryDeferralAndExpiredLeaseRecovery() {
        runSynchronously {
            val provider = InMemoryQueueProvider()
            val decision = decision()
            val plan = plan()
            assertIs<ProviderOperationResult.Success<Unit>>(
                provider.enqueue(QueueEnqueueRequest(entry(decision, plan))),
            )

            val first = acquire(provider, 2_000L, "lease-1")
            assertEquals(plan, first.strategyPlan)
            provider.reschedule(
                QueueRescheduleRequest(
                    entryId = first.id,
                    leaseId = requireNotNull(first.lease).id,
                    retryAttempt = RetryAttempt(1),
                    availableAt = DataLoomInstant(3_000L),
                    error = TestError(),
                ),
            ).assertSuccess()

            val retried = acquire(provider, 3_000L, "lease-2")
            assertEquals(plan, retried.strategyPlan)
            provider.defer(
                QueueDeferralRequest(
                    entryId = retried.id,
                    leaseId = requireNotNull(retried.lease).id,
                    availableAt = DataLoomInstant(4_000L),
                    reason = QueueDeferralReason.CONNECTIVITY_REQUIREMENT_NOT_MET,
                ),
            ).assertSuccess()

            val deferred = acquire(provider, 4_000L, "lease-3", expiresAt = 5_000L)
            assertEquals(plan, deferred.strategyPlan)
            assertIs<ProviderOperationResult.Success<io.dataloom.api.queue.ExpiredLeaseRecoveryResult>>(
                provider.recoverExpiredLeases(
                    ExpiredLeaseRecoveryRequest(DataLoomInstant(5_001L)),
                ),
            )
            assertEquals(plan, acquire(provider, 6_000L, "lease-4").strategyPlan)
        }
    }

    private fun <T> runSynchronously(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context: CoroutineContext = EmptyCoroutineContext
                override fun resumeWith(resumeResult: Result<T>) {
                    result = resumeResult
                }
            },
        )
        return checkNotNull(result) {
            "InMemoryQueueProvider unexpectedly suspended."
        }.getOrThrow()
    }

    private suspend fun acquire(
        provider: InMemoryQueueProvider,
        now: Long,
        leaseId: String,
        expiresAt: Long = now + 1_000L,
    ): QueueEntry {
        val result = assertIs<ProviderOperationResult.Success<QueueAcquireResult>>(
            provider.acquire(
                QueueAcquireRequest(
                    consumerId = QueueConsumerId("consumer-1"),
                    leaseId = QueueLeaseId(leaseId),
                    acquiredAt = DataLoomInstant(now),
                    leaseExpiresAt = DataLoomInstant(expiresAt),
                    maxEntries = 1,
                ),
            ),
        ).value
        return assertIs<QueueAcquireResult.Entries>(result).entries.single()
    }

    private fun ProviderOperationResult<Unit>.assertSuccess() {
        assertIs<ProviderOperationResult.Success<Unit>>(this)
    }

    private fun entry(
        decision: PersistedStrategyDecision,
        plan: StrategyExecutionPlan,
    ): QueueEntry = QueueEntry(
        id = QueueEntryId("entry-plan"),
        synchronizationRequest = request(),
        state = QueueEntryState.PENDING,
        enqueuedAt = DataLoomInstant(1_000L),
        availableAt = DataLoomInstant(1_000L),
        strategyDecision = decision,
        strategyPlan = plan,
    )

    private fun request(): SynchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("workflow-plan"),
        sessionId = SynchronizationSessionId("session-plan"),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("execution-plan"),
            correlationId = CorrelationId("correlation-plan"),
        ),
    )

    private fun decision(): PersistedStrategyDecision = PersistedStrategyDecision(
        decisionId = StrategyDecisionId("decision-plan"),
        planId = StrategyPlanId("plan-1"),
        requestedStrategy = BuiltInSynchronizationStrategy.ADAPTIVE,
        effectiveProfileId = StrategyProfileId("offline-profile"),
        effectiveStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
        configurationVersion = StrategyConfigurationVersion(8L),
        disposition = StrategyDisposition.DEFER,
    )

    private fun plan(): StrategyExecutionPlan = StrategyExecutionPlan(
        id = StrategyPlanId("plan-1"),
        requestedStrategy = BuiltInSynchronizationStrategy.ADAPTIVE,
        effectiveProfileId = StrategyProfileId("offline-profile"),
        effectiveStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
        configurationVersion = StrategyConfigurationVersion(8L),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        disposition = StrategyDisposition.DEFER,
        operations = listOf(StrategyOperation.ENQUEUE_DURABLE_WORK),
        requiredCapabilities = setOf(StrategyProviderCapability.QUEUE),
        dataOrigin = StrategyDataOrigin.NONE,
        consistency = StrategyConsistency.LOCAL_AUTHORITATIVE,
        deferralReason = StrategyDeferralReason.CONNECTIVITY_UNAVAILABLE,
        durableContinuation = StrategyDurableContinuationPlan(
            operations = listOf(
                StrategyOperation.READ_LOCAL,
                StrategyOperation.PUSH_REMOTE,
                StrategyOperation.RECONCILE,
            ),
            requiredCapabilities = setOf(
                StrategyProviderCapability.STORAGE,
                StrategyProviderCapability.TRANSPORT,
                StrategyProviderCapability.CONFLICT_STATE,
            ),
            dataOrigin = StrategyDataOrigin.NONE,
            consistency = StrategyConsistency.LOCAL_AUTHORITATIVE,
        ),
    )

    private data class TestError(
        override val code: ErrorCode = ErrorCode("NETWORK_RETRY"),
        override val category: ErrorCategory = ErrorCategory.NETWORK,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Retry later.",
        override val cause: Throwable? = null,
    ) : DataLoomError
}
