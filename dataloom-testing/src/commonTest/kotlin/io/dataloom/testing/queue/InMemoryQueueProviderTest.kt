package io.dataloom.testing.queue

import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.queue.ExpiredLeaseRecoveryRequest
import io.dataloom.api.queue.ExpiredLeaseRecoveryResult
import io.dataloom.api.queue.QueueAcquireResult
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.queue.QueueEnqueueRequest
import io.dataloom.api.queue.QueueFailureDisposition
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.testing.sampleQueueAcquireRequest
import io.dataloom.testing.sampleQueueCancellationRequest
import io.dataloom.testing.sampleQueueCompletionRequest
import io.dataloom.testing.sampleQueueEnqueueRequest
import io.dataloom.testing.sampleQueueEntry
import io.dataloom.testing.sampleQueueFailureRequest
import io.dataloom.testing.sampleQueueRescheduleRequest
import io.dataloom.testing.runSuspend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class InMemoryQueueProviderTest {
    @Test
    fun `descriptor uses queue type`() {
        val provider = InMemoryQueueProvider()
        assertEquals(io.dataloom.api.provider.ProviderType.QUEUE, provider.descriptor.type)
    }

    @Test
    fun `enqueue stores pending entry`() {
        val provider = InMemoryQueueProvider()
        val result = runSuspend { provider.enqueue(sampleQueueEnqueueRequest()) }
        assertEquals(ProviderOperationResult.Success(Unit), result)
        assertEquals(listOf(io.dataloom.api.identifier.QueueEntryId("entry-001")), provider.snapshotEntryIds())
        assertEquals(mapOf(io.dataloom.api.identifier.QueueEntryId("entry-001") to QueueEntryState.PENDING), provider.snapshotStates())
    }

    @Test
    fun `duplicate enqueue returns failure`() {
        val provider = InMemoryQueueProvider()
        runSuspend { provider.enqueue(sampleQueueEnqueueRequest()) }
        val result = runSuspend { provider.enqueue(sampleQueueEnqueueRequest()) }
        assertIs<ProviderOperationResult.Failure>(result)
    }

    @Test
    fun `acquire returns no entries when queue is empty`() {
        val provider = InMemoryQueueProvider()
        val result = runSuspend { provider.acquire(sampleQueueAcquireRequest()) }
        assertEquals(ProviderOperationResult.Success(QueueAcquireResult.NoEntries), result)
    }

    @Test
    fun `acquire transitions eligible pending entry to leased`() {
        val provider = InMemoryQueueProvider()
        runSuspend { provider.enqueue(sampleQueueEnqueueRequest()) }
        val result = runSuspend { provider.acquire(sampleQueueAcquireRequest()) }
        val entries = (result as ProviderOperationResult.Success).value as QueueAcquireResult.Entries
        assertEquals(QueueEntryState.LEASED, entries.entries.single().state)
        assertEquals(entries.lease, entries.entries.single().lease)
    }

    @Test
    fun `acquire respects max entries`() {
        val provider = InMemoryQueueProvider()
        runSuspend { provider.enqueue(sampleQueueEnqueueRequest("entry-001")) }
        runSuspend { provider.enqueue(sampleQueueEnqueueRequest("entry-002")) }
        val result = runSuspend { provider.acquire(sampleQueueAcquireRequest(maxEntries = 1)) }
        val entries = (result as ProviderOperationResult.Success).value as QueueAcquireResult.Entries
        assertEquals(1, entries.entries.size)
    }

    @Test
    fun `acquire orders by availableAt then enqueue order`() {
        val provider = InMemoryQueueProvider()
        runSuspend { provider.enqueue(QueueEnqueueRequest(sampleQueueEntry(id = "entry-001", availableAt = 3_000L))) }
        runSuspend { provider.enqueue(QueueEnqueueRequest(sampleQueueEntry(id = "entry-002", availableAt = 2_000L))) }
        runSuspend { provider.enqueue(QueueEnqueueRequest(sampleQueueEntry(id = "entry-003", availableAt = 3_000L))) }
        val result = runSuspend { provider.acquire(sampleQueueAcquireRequest(acquiredAt = 10_000L, maxEntries = 3)) }
        val entries = ((result as ProviderOperationResult.Success).value as QueueAcquireResult.Entries).entries
        assertEquals(listOf("entry-002", "entry-001", "entry-003"), entries.map { it.id.value })
    }

    @Test
    fun `acquire skips retry waiting entries until available`() {
        val provider = InMemoryQueueProvider()
        runSuspend { provider.enqueue(sampleQueueEnqueueRequest()) }
        runSuspend { provider.acquire(sampleQueueAcquireRequest()) }
        runSuspend { provider.reschedule(sampleQueueRescheduleRequest()) }
        val result = runSuspend { provider.acquire(sampleQueueAcquireRequest(acquiredAt = 49_000L, leaseExpiresAt = 60_000L)) }
        assertEquals(ProviderOperationResult.Success(QueueAcquireResult.NoEntries), result)
    }

    @Test
    fun `acquire includes retry waiting entries once available`() {
        val provider = InMemoryQueueProvider()
        runSuspend { provider.enqueue(sampleQueueEnqueueRequest()) }
        runSuspend { provider.acquire(sampleQueueAcquireRequest()) }
        runSuspend { provider.reschedule(sampleQueueRescheduleRequest()) }
        val result = runSuspend { provider.acquire(sampleQueueAcquireRequest(acquiredAt = 50_000L, leaseExpiresAt = 60_000L)) }
        val entries = ((result as ProviderOperationResult.Success).value as QueueAcquireResult.Entries).entries
        assertEquals(QueueEntryState.LEASED, entries.single().state)
        assertEquals(RetryAttempt(2), entries.single().retryAttempt)
    }

    @Test
    fun `complete requires matching lease`() {
        val provider = InMemoryQueueProvider()
        runSuspend { provider.enqueue(sampleQueueEnqueueRequest()) }
        runSuspend { provider.acquire(sampleQueueAcquireRequest()) }
        val result = runSuspend { provider.complete(sampleQueueCompletionRequest(leaseId = "lease-999")) }
        assertIs<ProviderOperationResult.Failure>(result)
    }

    @Test
    fun `complete transitions leased entry to completed`() {
        val provider = InMemoryQueueProvider()
        runSuspend { provider.enqueue(sampleQueueEnqueueRequest()) }
        runSuspend { provider.acquire(sampleQueueAcquireRequest()) }
        val result = runSuspend { provider.complete(sampleQueueCompletionRequest()) }
        assertEquals(ProviderOperationResult.Success(Unit), result)
        assertEquals(QueueEntryState.COMPLETED, provider.snapshotStates().values.single())
    }

    @Test
    fun `reschedule transitions leased entry to retry waiting`() {
        val provider = InMemoryQueueProvider()
        runSuspend { provider.enqueue(sampleQueueEnqueueRequest()) }
        runSuspend { provider.acquire(sampleQueueAcquireRequest()) }
        val result = runSuspend { provider.reschedule(sampleQueueRescheduleRequest()) }
        assertEquals(ProviderOperationResult.Success(Unit), result)
        assertEquals(QueueEntryState.RETRY_WAITING, provider.snapshotStates().values.single())
    }

    @Test
    fun `fail with failed disposition transitions entry to failed`() {
        val provider = InMemoryQueueProvider()
        runSuspend { provider.enqueue(sampleQueueEnqueueRequest()) }
        runSuspend { provider.acquire(sampleQueueAcquireRequest()) }
        runSuspend { provider.fail(sampleQueueFailureRequest(disposition = QueueFailureDisposition.FAILED)) }
        assertEquals(QueueEntryState.FAILED, provider.snapshotStates().values.single())
    }

    @Test
    fun `fail with dead letter disposition transitions entry to dead letter`() {
        val provider = InMemoryQueueProvider()
        runSuspend { provider.enqueue(sampleQueueEnqueueRequest()) }
        runSuspend { provider.acquire(sampleQueueAcquireRequest()) }
        runSuspend { provider.fail(sampleQueueFailureRequest(disposition = QueueFailureDisposition.DEAD_LETTER)) }
        assertEquals(QueueEntryState.DEAD_LETTER, provider.snapshotStates().values.single())
    }

    @Test
    fun `cancel transitions pending entry to cancelled`() {
        val provider = InMemoryQueueProvider()
        runSuspend { provider.enqueue(sampleQueueEnqueueRequest()) }
        runSuspend { provider.cancel(sampleQueueCancellationRequest()) }
        assertEquals(QueueEntryState.CANCELLED, provider.snapshotStates().values.single())
    }

    @Test
    fun `cancel also transitions leased entry to cancelled for tests`() {
        val provider = InMemoryQueueProvider()
        runSuspend { provider.enqueue(sampleQueueEnqueueRequest()) }
        runSuspend { provider.acquire(sampleQueueAcquireRequest()) }
        runSuspend { provider.cancel(sampleQueueCancellationRequest()) }
        assertEquals(QueueEntryState.CANCELLED, provider.snapshotStates().values.single())
    }

    @Test
    fun `recover expired leases moves expired leased entries back to pending`() {
        val provider = InMemoryQueueProvider()
        runSuspend { provider.enqueue(sampleQueueEnqueueRequest()) }
        runSuspend { provider.acquire(sampleQueueAcquireRequest(acquiredAt = 20_000L, leaseExpiresAt = 25_000L)) }
        val result = runSuspend {
            provider.recoverExpiredLeases(ExpiredLeaseRecoveryRequest(currentTime = DataLoomInstant(26_000L)))
        }
        assertEquals(ProviderOperationResult.Success(ExpiredLeaseRecoveryResult(1)), result)
        assertEquals(QueueEntryState.PENDING, provider.snapshotStates().values.single())
    }

    @Test
    fun `recover expired leases ignores active leases`() {
        val provider = InMemoryQueueProvider()
        runSuspend { provider.enqueue(sampleQueueEnqueueRequest()) }
        runSuspend { provider.acquire(sampleQueueAcquireRequest(acquiredAt = 20_000L, leaseExpiresAt = 25_000L)) }
        val result = runSuspend {
            provider.recoverExpiredLeases(ExpiredLeaseRecoveryRequest(currentTime = DataLoomInstant(25_000L)))
        }
        assertEquals(ProviderOperationResult.Success(ExpiredLeaseRecoveryResult(0)), result)
        assertEquals(QueueEntryState.LEASED, provider.snapshotStates().values.single())
    }

    @Test
    fun `clear recordings preserves entries`() {
        val provider = InMemoryQueueProvider()
        runSuspend { provider.enqueue(sampleQueueEnqueueRequest()) }
        provider.clearRecordings()
        assertEquals(emptyList(), provider.enqueueRequests)
        assertEquals(1, provider.entryCount)
    }

    @Test
    fun `reset state clears entries and recordings`() {
        val provider = InMemoryQueueProvider()
        runSuspend { provider.enqueue(sampleQueueEnqueueRequest()) }
        provider.resetState()
        assertEquals(0, provider.entryCount)
        assertEquals(emptyList(), provider.snapshotEntryIds())
        assertEquals(emptyList(), provider.enqueueRequests)
    }
}
