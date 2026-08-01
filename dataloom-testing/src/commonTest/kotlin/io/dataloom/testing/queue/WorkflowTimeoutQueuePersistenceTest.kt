package io.dataloom.testing.queue

import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.queue.ExpiredLeaseRecoveryRequest
import io.dataloom.api.queue.QueueAcquireResult
import io.dataloom.api.queue.QueueDeferralReason
import io.dataloom.api.queue.QueueDeferralRequest
import io.dataloom.api.queue.QueueRescheduleRequest
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.retry.WorkflowTimeoutState
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.testing.FakeDataLoomError
import io.dataloom.testing.runSuspend
import io.dataloom.testing.sampleQueueAcquireRequest
import io.dataloom.testing.sampleQueueEnqueueRequest
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkflowTimeoutQueuePersistenceTest {

    @Test
    fun `retry deferral acquisition and lease recovery preserve immutable workflow timeout`() {
        val timeoutState = WorkflowTimeoutState(
            startedAt = DataLoomInstant(10_000L),
            deadline = DataLoomInstant(60_000L),
        )
        val provider = InMemoryQueueProvider()
        val baseRequest = sampleQueueEnqueueRequest()
        runSuspend {
            provider.enqueue(
                baseRequest.copy(
                    entry = baseRequest.entry.copy(workflowTimeoutState = timeoutState),
                ),
            )
        }
        runSuspend { provider.acquire(sampleQueueAcquireRequest()) }

        runSuspend {
            provider.reschedule(
                QueueRescheduleRequest(
                    entryId = QueueEntryId("entry-001"),
                    leaseId = QueueLeaseId("lease-001"),
                    retryAttempt = RetryAttempt(1),
                    availableAt = DataLoomInstant(25_000L),
                    error = FakeDataLoomError(message = "Retry later."),
                ),
            )
        }

        val reacquired = runSuspend {
            provider.acquire(sampleQueueAcquireRequest(acquiredAt = 25_000L, leaseExpiresAt = 26_000L))
        }
        val leased = ((reacquired as ProviderOperationResult.Success).value as QueueAcquireResult.Entries)
            .entries.single()
        assertEquals(timeoutState, leased.workflowTimeoutState)

        runSuspend {
            provider.defer(
                QueueDeferralRequest(
                    entryId = leased.id,
                    leaseId = requireNotNull(leased.lease).id,
                    availableAt = DataLoomInstant(30_000L),
                    reason = QueueDeferralReason.CONNECTIVITY_REQUIREMENT_NOT_MET,
                ),
            )
        }
        val afterDeferral = runSuspend {
            provider.acquire(sampleQueueAcquireRequest(acquiredAt = 30_000L, leaseExpiresAt = 31_000L))
        }
        val deferredLease = ((afterDeferral as ProviderOperationResult.Success).value as QueueAcquireResult.Entries)
            .entries.single()
        assertEquals(timeoutState, deferredLease.workflowTimeoutState)

        runSuspend {
            provider.recoverExpiredLeases(
                ExpiredLeaseRecoveryRequest(currentTime = DataLoomInstant(31_001L)),
            )
        }
        val afterRecovery = runSuspend {
            provider.acquire(sampleQueueAcquireRequest(acquiredAt = 32_000L, leaseExpiresAt = 33_000L))
        }
        val recoveredLease = ((afterRecovery as ProviderOperationResult.Success).value as QueueAcquireResult.Entries)
            .entries.single()
        assertEquals(timeoutState, recoveredLease.workflowTimeoutState)
    }
}
