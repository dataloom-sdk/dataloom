@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

class AppleStrategyPlanQueuePersistenceTest {

    @Test
    fun versionFourRoundTripPreservesExactCompletePlan() {
        val original = entry(decision(), plan())
        val encoded = AppleQueueStateFileCodec.encodeSnapshot(
            AppleQueueSnapshot(entries = linkedMapOf(original.id.value to original)),
        )
        val decoded = AppleQueueStateFileCodec.decodeSnapshot(encoded)
            .entries.getValue(original.id.value)

        assertEquals(original.strategyDecision, decoded.strategyDecision)
        assertEquals(original.strategyPlan, decoded.strategyPlan)
    }

    @Test
    fun versionThreeDecisionSnapshotRemainsReadableWithoutInventingPlan() {
        val original = entry(decision(), plan())
        val current = AppleQueueStateFileCodec.encodeSnapshot(
            AppleQueueSnapshot(entries = linkedMapOf(original.id.value to original)),
        ).split('\n').toMutableList()
        val entryFields = current[1].split('\t').dropLast(1)
        val versionThree = buildString {
            append("DATALOOM_QUEUE_STATE\t3\n")
            append(entryFields.joinToString("\t"))
            append('\n')
        }

        val decoded = AppleQueueStateFileCodec.decodeSnapshot(versionThree)
            .entries.getValue(original.id.value)

        assertEquals(original.strategyDecision, decoded.strategyDecision)
        assertNull(decoded.strategyPlan)
    }

    @Test
    fun malformedPlanSnapshotFailsClosedWithoutExposingFrame() {
        val original = entry(decision(), plan())
        val current = AppleQueueStateFileCodec.encodeSnapshot(
            AppleQueueSnapshot(entries = linkedMapOf(original.id.value to original)),
        ).split('\n').toMutableList()
        val fields = current[1].split('\t').toMutableList()
        fields[43] = appleQueueEncodeNullableString("malformed-sensitive-plan")
        current[1] = fields.joinToString("\t")

        assertFailsWith<AppleQueueMalformedStateException> {
            AppleQueueStateFileCodec.decodeSnapshot(current.joinToString("\n"))
        }
    }

    @Test
    fun productionFileProviderPreservesPlanAcrossAllDurableTransitions() = runTest {
        val directory = uniqueDirectory()
        val expectedDecision = decision()
        val expectedPlan = plan()
        AppleFileQueueProvider(directory).enqueue(
            QueueEnqueueRequest(entry(expectedDecision, expectedPlan)),
        ).assertSuccess()

        val first = AppleFileQueueProvider(directory)
            .acquireEntry(2_000L, 3_000L, "lease-1")
        assertEquals(expectedPlan, first.strategyPlan)
        AppleFileQueueProvider(directory).reschedule(
            QueueRescheduleRequest(
                entryId = first.id,
                leaseId = requireNotNull(first.lease).id,
                retryAttempt = RetryAttempt(1),
                availableAt = DataLoomInstant(4_000L),
                error = TestError(),
            ),
        ).assertSuccess()

        val retried = AppleFileQueueProvider(directory)
            .acquireEntry(4_000L, 5_000L, "lease-2")
        assertEquals(expectedPlan, retried.strategyPlan)
        AppleFileQueueProvider(directory).defer(
            QueueDeferralRequest(
                entryId = retried.id,
                leaseId = requireNotNull(retried.lease).id,
                availableAt = DataLoomInstant(6_000L),
                reason = QueueDeferralReason.CONNECTIVITY_REQUIREMENT_NOT_MET,
            ),
        ).assertSuccess()

        val deferred = AppleFileQueueProvider(directory)
            .acquireEntry(6_000L, 7_000L, "lease-3")
        assertEquals(expectedPlan, deferred.strategyPlan)
        assertIs<ProviderOperationResult.Success<io.dataloom.api.queue.ExpiredLeaseRecoveryResult>>(
            AppleFileQueueProvider(directory).recoverExpiredLeases(
                ExpiredLeaseRecoveryRequest(DataLoomInstant(7_001L)),
            ),
        )

        val recovered = AppleFileQueueProvider(directory)
            .acquireEntry(8_000L, 9_000L, "lease-4")
        assertEquals(expectedDecision, recovered.strategyDecision)
        assertEquals(expectedPlan, recovered.strategyPlan)
    }

    private suspend fun AppleFileQueueProvider.acquireEntry(
        acquiredAt: Long,
        expiresAt: Long,
        leaseId: String,
    ): QueueEntry {
        val result = assertIs<ProviderOperationResult.Success<QueueAcquireResult>>(
            acquire(
                QueueAcquireRequest(
                    consumerId = QueueConsumerId("consumer-1"),
                    leaseId = QueueLeaseId(leaseId),
                    acquiredAt = DataLoomInstant(acquiredAt),
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

    private fun uniqueDirectory(): String = buildString {
        append(NSTemporaryDirectory().trimEnd('/'))
        append("/dataloom-strategy-plan-")
        append(NSUUID().UUIDString)
    }

    private data class TestError(
        override val code: ErrorCode = ErrorCode("NETWORK_RETRY"),
        override val category: ErrorCategory = ErrorCategory.NETWORK,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Retry later.",
        override val cause: Throwable? = null,
    ) : DataLoomError
}
