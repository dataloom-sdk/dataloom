package io.dataloom.testing.queue

import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.queue.QueueAcquireResult
import io.dataloom.api.queue.QueueRescheduleRequest
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.retry.RetryBudgetState
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.testing.sampleQueueAcquireRequest
import io.dataloom.testing.sampleQueueEnqueueRequest
import io.dataloom.testing.runSuspend
import kotlin.test.Test
import kotlin.test.assertEquals

class RetryBudgetQueuePersistenceTest {

    @Test
    fun `reschedule deferral acquisition and lease recovery preserve budget state`() {
        val provider = InMemoryQueueProvider()
        runSuspend { provider.enqueue(sampleQueueEnqueueRequest()) }
        runSuspend { provider.acquire(sampleQueueAcquireRequest()) }

        val budget = RetryBudgetState(
            windowStartedAt = DataLoomInstant(20_000L),
            lastEvaluatedAt = DataLoomInstant(20_000L),
            cumulativeDelay = SchedulingDelay(5_000L),
        )
        runSuspend {
            provider.reschedule(
                QueueRescheduleRequest(
                    entryId = io.dataloom.api.identifier.QueueEntryId("entry-001"),
                    leaseId = io.dataloom.api.identifier.QueueLeaseId("lease-001"),
                    retryAttempt = RetryAttempt(1),
                    availableAt = DataLoomInstant(25_000L),
                    error = io.dataloom.testing.FakeDataLoomError("Retry later."),
                    retryBudgetState = budget,
                ),
            )
        }

        val reacquired = runSuspend {
            provider.acquire(sampleQueueAcquireRequest(acquiredAt = 25_000L, leaseExpiresAt = 26_000L))
        }
        val leased = ((reacquired as ProviderOperationResult.Success).value as QueueAcquireResult.Entries)
            .entries.single()
        assertEquals(budget, leased.retryBudgetState)

        runSuspend {
            provider.defer(
                io.dataloom.api.queue.QueueDeferralRequest(
                    entryId = leased.id,
                    leaseId = requireNotNull(leased.lease).id,
                    availableAt = DataLoomInstant(30_000L),
                    reason = io.dataloom.api.queue.QueueDeferralReason.CONNECTIVITY_REQUIREMENT_NOT_MET,
                ),
            )
        }
        val afterDeferral = runSuspend {
            provider.acquire(sampleQueueAcquireRequest(acquiredAt = 30_000L, leaseExpiresAt = 31_000L))
        }
        val deferredLease = ((afterDeferral as ProviderOperationResult.Success).value as QueueAcquireResult.Entries)
            .entries.single()
        assertEquals(budget, deferredLease.retryBudgetState)

        val recovered = runSuspend {
            provider.recoverExpiredLeases(
                io.dataloom.api.queue.ExpiredLeaseRecoveryRequest(DataLoomInstant(31_001L)),
            )
        }
        assertEquals(
            1,
            (recovered as ProviderOperationResult.Success).value.recoveredEntries,
        )
        val afterRecovery = runSuspend {
            provider.acquire(sampleQueueAcquireRequest(acquiredAt = 32_000L, leaseExpiresAt = 33_000L))
        }
        val recoveredLease = ((afterRecovery as ProviderOperationResult.Success).value as QueueAcquireResult.Entries)
            .entries.single()
        assertEquals(budget, recoveredLease.retryBudgetState)
    }
}
