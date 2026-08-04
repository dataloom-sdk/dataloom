@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.runtime.queue

import io.dataloom.api.context.DataLoomMetadata
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
import io.dataloom.api.identifier.TraceId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.model.WorkflowPriority
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.queue.ExpiredLeaseRecoveryRequest
import io.dataloom.api.queue.QueueAcquireRequest
import io.dataloom.api.queue.QueueAcquireResult
import io.dataloom.api.queue.QueueCancellationRequest
import io.dataloom.api.queue.QueueCompletionRequest
import io.dataloom.api.queue.QueueDeferralReason
import io.dataloom.api.queue.QueueDeferralRequest
import io.dataloom.api.queue.QueueEnqueueRequest
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.queue.QueueFailureDisposition
import io.dataloom.api.queue.QueueFailureRequest
import io.dataloom.api.queue.QueueLease
import io.dataloom.api.queue.QueueRescheduleRequest
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.retry.RetryBudgetState
import io.dataloom.api.retry.WorkflowTimeoutState
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.strategy.BuiltInSynchronizationStrategy
import io.dataloom.api.strategy.PersistedStrategyDecision
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.time.DataLoomInstant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

class AppleFileQueueProviderRetryTest {

    @Test
    fun `stale lease is rejected without changing the current lease`() = runTest {
        val directory = uniqueDirectory()
        val provider = AppleFileQueueProvider(directory)
        provider.enqueueSuccess(entry())
        provider.acquireEntries(1_000L, 2_000L, "lease-current")

        val failure = assertIs<ProviderOperationResult.Failure>(
            provider.complete(
                QueueCompletionRequest(
                    entryId = QueueEntryId("entry-1"),
                    leaseId = QueueLeaseId("lease-stale"),
                    completedAt = DataLoomInstant(1_500L),
                ),
            ),
        )
        assertEquals("QUEUE_STALE_LEASE", failure.error.code.value)

        val recovery = provider.recoverExpiredLeases(
            ExpiredLeaseRecoveryRequest(DataLoomInstant(2_001L)),
        ).successValue()
        assertEquals(1, recovery.recoveredEntries)
    }

    @Test
    fun `reschedule and deferral preserve retry budget and immutable workflow deadline`() = runTest {
        val directory = uniqueDirectory()
        val provider = AppleFileQueueProvider(directory)
        val workflowTimeout = WorkflowTimeoutState(
            startedAt = DataLoomInstant(500L),
            deadline = DataLoomInstant(10_500L),
        )
        provider.enqueueSuccess(entry(workflowTimeoutState = workflowTimeout))
        provider.acquireEntries(1_000L, 2_000L, "lease-first")
        val budget = RetryBudgetState(
            windowStartedAt = DataLoomInstant(1_100L),
            lastEvaluatedAt = DataLoomInstant(1_200L),
            cumulativeDelay = SchedulingDelay(1_800L),
        )
        provider.reschedule(
            QueueRescheduleRequest(
                entryId = QueueEntryId("entry-1"),
                leaseId = QueueLeaseId("lease-first"),
                retryAttempt = RetryAttempt(2),
                availableAt = DataLoomInstant(3_000L),
                error = testError(),
                retryBudgetState = budget,
            ),
        ).assertSuccess()

        val afterRestart = AppleFileQueueProvider(directory).acquireEntries(
            acquiredAt = 3_000L,
            expiresAt = 4_000L,
            leaseId = "lease-second",
        ).single()
        assertEquals(RetryAttempt(2), afterRestart.retryAttempt)
        assertEquals(budget, afterRestart.retryBudgetState)
        assertEquals(workflowTimeout, afterRestart.workflowTimeoutState)
        assertNull(afterRestart.lastError)

        AppleFileQueueProvider(directory).defer(
            QueueDeferralRequest(
                entryId = afterRestart.id,
                leaseId = QueueLeaseId("lease-second"),
                availableAt = DataLoomInstant(5_000L),
                reason = QueueDeferralReason.CONNECTIVITY_REQUIREMENT_NOT_MET,
            ),
        ).assertSuccess()

        val afterDeferral = AppleFileQueueProvider(directory).acquireEntries(
            acquiredAt = 5_000L,
            expiresAt = 6_000L,
            leaseId = "lease-third",
        ).single()
        assertEquals(RetryAttempt(2), afterDeferral.retryAttempt)
        assertEquals(budget, afterDeferral.retryBudgetState)
        assertEquals(workflowTimeout, afterDeferral.workflowTimeoutState)
    }

    @Test
    fun `expired retry lease recovery preserves retry history across restart`() = runTest {
        val directory = uniqueDirectory()
        val provider = AppleFileQueueProvider(directory)
        provider.enqueueSuccess(entry())
        provider.acquireEntries(1_000L, 2_000L, "lease-1")
        val budget = RetryBudgetState(
            windowStartedAt = DataLoomInstant(1_000L),
            lastEvaluatedAt = DataLoomInstant(1_500L),
            cumulativeDelay = SchedulingDelay(500L),
        )
        provider.reschedule(
            QueueRescheduleRequest(
                entryId = QueueEntryId("entry-1"),
                leaseId = QueueLeaseId("lease-1"),
                retryAttempt = RetryAttempt(1),
                availableAt = DataLoomInstant(3_000L),
                error = testError(),
                retryBudgetState = budget,
            ),
        ).assertSuccess()
        provider.acquireEntries(3_000L, 3_500L, "lease-expiring")

        val recovered = AppleFileQueueProvider(directory).recoverExpiredLeases(
            ExpiredLeaseRecoveryRequest(DataLoomInstant(3_501L)),
        ).successValue()
        assertEquals(1, recovered.recoveredEntries)

        val reacquired = AppleFileQueueProvider(directory).acquireEntries(
            acquiredAt = 3_501L,
            expiresAt = 4_000L,
            leaseId = "lease-recovered",
        ).single()
        assertEquals(RetryAttempt(1), reacquired.retryAttempt)
        assertEquals(budget, reacquired.retryBudgetState)
    }


    @Test
    fun `strategy decision survives restart retry deferral and lease recovery`() = runTest {
        val directory = uniqueDirectory()
        val expected = strategyDecision()
        AppleFileQueueProvider(directory).enqueueSuccess(
            entry(strategyDecision = expected),
        )

        val first = AppleFileQueueProvider(directory).acquireEntries(
            acquiredAt = 1_000L,
            expiresAt = 2_000L,
            leaseId = "strategy-lease-1",
        ).single()
        assertEquals(expected, first.strategyDecision)

        AppleFileQueueProvider(directory).reschedule(
            QueueRescheduleRequest(
                entryId = first.id,
                leaseId = requireNotNull(first.lease).id,
                retryAttempt = RetryAttempt(1),
                availableAt = DataLoomInstant(3_000L),
                error = testError(),
            ),
        ).assertSuccess()

        val retried = AppleFileQueueProvider(directory).acquireEntries(
            acquiredAt = 3_000L,
            expiresAt = 4_000L,
            leaseId = "strategy-lease-2",
        ).single()
        assertEquals(expected, retried.strategyDecision)

        AppleFileQueueProvider(directory).defer(
            QueueDeferralRequest(
                entryId = retried.id,
                leaseId = requireNotNull(retried.lease).id,
                availableAt = DataLoomInstant(5_000L),
                reason = QueueDeferralReason.CONNECTIVITY_REQUIREMENT_NOT_MET,
            ),
        ).assertSuccess()

        val deferred = AppleFileQueueProvider(directory).acquireEntries(
            acquiredAt = 5_000L,
            expiresAt = 5_500L,
            leaseId = "strategy-lease-3",
        ).single()
        assertEquals(expected, deferred.strategyDecision)

        AppleFileQueueProvider(directory).recoverExpiredLeases(
            ExpiredLeaseRecoveryRequest(DataLoomInstant(5_501L)),
        ).successValue()

        val recovered = AppleFileQueueProvider(directory).acquireEntries(
            acquiredAt = 5_501L,
            expiresAt = 6_000L,
            leaseId = "strategy-lease-4",
        ).single()
        assertEquals(expected, recovered.strategyDecision)
    }

    private suspend fun AppleFileQueueProvider.enqueueSuccess(entry: QueueEntry) {
        enqueue(QueueEnqueueRequest(entry)).assertSuccess()
    }

    private suspend fun AppleFileQueueProvider.acquireEntries(
        acquiredAt: Long,
        expiresAt: Long,
        leaseId: String,
        maxEntries: Int = 10,
    ): List<QueueEntry> = assertIs<QueueAcquireResult.Entries>(
        acquire(
            acquireRequest(
                leaseId = leaseId,
                acquiredAt = acquiredAt,
                expiresAt = expiresAt,
                maxEntries = maxEntries,
            ),
        ).successValue(),
    ).entries

    private suspend fun AppleFileQueueProvider.acquireResult(
        leaseId: String,
    ): QueueAcquireResult = acquire(acquireRequest(leaseId)).successValue()

    private fun acquireRequest(
        leaseId: String,
        acquiredAt: Long = 1_000L,
        expiresAt: Long = 2_000L,
        maxEntries: Int = 10,
    ): QueueAcquireRequest = QueueAcquireRequest(
        consumerId = QueueConsumerId("consumer-1"),
        leaseId = QueueLeaseId(leaseId),
        acquiredAt = DataLoomInstant(acquiredAt),
        leaseExpiresAt = DataLoomInstant(expiresAt),
        maxEntries = maxEntries,
    )

    private fun entry(
        id: String = "entry-1",
        state: QueueEntryState = QueueEntryState.PENDING,
        enqueuedAt: Long = 1_000L,
        availableAt: Long = enqueuedAt,
        retryAttempt: RetryAttempt? = null,
        retryBudgetState: RetryBudgetState? = null,
        workflowTimeoutState: WorkflowTimeoutState? = null,
        strategyDecision: PersistedStrategyDecision? = null,
        lastError: DataLoomError? = null,
        executionMetadata: DataLoomMetadata = DataLoomMetadata.Empty,
        entryMetadata: DataLoomMetadata = DataLoomMetadata.Empty,
        lease: QueueLease? = null,
    ): QueueEntry = QueueEntry(
        id = QueueEntryId(id),
        synchronizationRequest = SynchronizationRequest(
            workflowId = WorkflowId("workflow-$id"),
            sessionId = SynchronizationSessionId("session-$id"),
            direction = SynchronizationDirection.PUSH,
            mode = SynchronizationMode.DELTA,
            priority = WorkflowPriority.NORMAL,
            context = executionContext(executionMetadata),
        ),
        state = state,
        enqueuedAt = DataLoomInstant(enqueuedAt),
        availableAt = DataLoomInstant(availableAt),
        retryAttempt = retryAttempt,
        lease = lease,
        lastError = lastError,
        metadata = entryMetadata,
        retryBudgetState = retryBudgetState,
        workflowTimeoutState = workflowTimeoutState,
        strategyDecision = strategyDecision,
    )

    private fun executionContext(
        metadata: DataLoomMetadata = DataLoomMetadata.Empty,
    ): ExecutionContext = ExecutionContext(
        executionId = ExecutionId("execution-1"),
        correlationId = CorrelationId("correlation-1"),
        traceId = TraceId("trace-1"),
        metadata = metadata,
    )

    private fun strategyDecision(): PersistedStrategyDecision =
        PersistedStrategyDecision(
            decisionId = StrategyDecisionId("decision-apple-1"),
            planId = StrategyPlanId("plan-apple-1"),
            requestedStrategy = BuiltInSynchronizationStrategy.ADAPTIVE,
            effectiveProfileId = StrategyProfileId("offline-apple-profile"),
            effectiveStrategy = BuiltInSynchronizationStrategy.OFFLINE_FIRST,
            configurationVersion = StrategyConfigurationVersion(14L),
            disposition = StrategyDisposition.DEFER,
        )

    private fun testError(): DataLoomError = TestQueueError(
        code = ErrorCode("NETWORK_TEMPORARY"),
        category = ErrorCategory.NETWORK,
        severity = ErrorSeverity.WARNING,
        recoverability = Recoverability.RECOVERABLE,
        message = "A temporary network failure occurred.",
    )

    private fun uniqueDirectory(): String = buildString {
        append(NSTemporaryDirectory().trimEnd('/'))
        append("/dataloom-apple-queue-")
        append(NSUUID().UUIDString)
    }

    private fun <T> ProviderOperationResult<T>.successValue(): T =
        assertIs<ProviderOperationResult.Success<T>>(this).value

    private fun ProviderOperationResult<Unit>.assertSuccess() {
        assertIs<ProviderOperationResult.Success<Unit>>(this)
    }

    private data class TestQueueError(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val severity: ErrorSeverity,
        override val recoverability: Recoverability,
        override val message: String,
        override val cause: Throwable? = null,
    ) : DataLoomError
}
