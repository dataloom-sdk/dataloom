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
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.time.DataLoomInstant
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StrategyDecisionInMemoryQueueProviderTest {

    @Test
    fun decisionSurvivesRetryDeferralAndExpiredLeaseRecovery() {
        runSynchronously {
        val provider = InMemoryQueueProvider()
        val expected = decision()
        assertIs<ProviderOperationResult.Success<Unit>>(
            provider.enqueue(QueueEnqueueRequest(entry(expected))),
        )

        val first = acquire(provider, 2_000L, "lease-1")
        assertEquals(expected, first.strategyDecision)
        assertIs<ProviderOperationResult.Success<Unit>>(
            provider.reschedule(
                QueueRescheduleRequest(
                    entryId = first.id,
                    leaseId = requireNotNull(first.lease).id,
                    retryAttempt = RetryAttempt(1),
                    availableAt = DataLoomInstant(3_000L),
                    error = TestError(),
                ),
            ),
        )

        val retried = acquire(provider, 3_000L, "lease-2")
        assertEquals(expected, retried.strategyDecision)
        assertIs<ProviderOperationResult.Success<Unit>>(
            provider.defer(
                QueueDeferralRequest(
                    entryId = retried.id,
                    leaseId = requireNotNull(retried.lease).id,
                    availableAt = DataLoomInstant(4_000L),
                    reason = QueueDeferralReason.CONNECTIVITY_REQUIREMENT_NOT_MET,
                ),
            ),
        )

        val deferred = acquire(provider, 4_000L, "lease-3", expiresAt = 5_000L)
        assertEquals(expected, deferred.strategyDecision)
        assertIs<ProviderOperationResult.Success<io.dataloom.api.queue.ExpiredLeaseRecoveryResult>>(
            provider.recoverExpiredLeases(
                ExpiredLeaseRecoveryRequest(DataLoomInstant(5_001L)),
            ),
        )

            assertEquals(
                expected,
                acquire(provider, 6_000L, "lease-4").strategyDecision,
            )
        }
    }

    /**
     * Executes the synchronous in-memory provider's suspend API without adding
     * a coroutine-test dependency to the public testing-kit module.
     */
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

    private fun entry(decision: PersistedStrategyDecision): QueueEntry = QueueEntry(
        id = QueueEntryId("entry-1"),
        synchronizationRequest = SynchronizationRequest(
            workflowId = WorkflowId("workflow-1"),
            sessionId = SynchronizationSessionId("session-1"),
            direction = SynchronizationDirection.PUSH,
            mode = SynchronizationMode.DELTA,
            context = ExecutionContext(
                executionId = ExecutionId("execution-1"),
                correlationId = CorrelationId("correlation-1"),
            ),
        ),
        state = QueueEntryState.PENDING,
        enqueuedAt = DataLoomInstant(1_000L),
        availableAt = DataLoomInstant(1_000L),
        strategyDecision = decision,
    )

    private fun decision(): PersistedStrategyDecision = PersistedStrategyDecision(
        decisionId = StrategyDecisionId("decision-1"),
        planId = StrategyPlanId("plan-1"),
        requestedStrategy = BuiltInSynchronizationStrategy.ADAPTIVE,
        effectiveProfileId = StrategyProfileId("offline-profile"),
        effectiveStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
        configurationVersion = StrategyConfigurationVersion(8L),
        disposition = StrategyDisposition.DEFER,
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
