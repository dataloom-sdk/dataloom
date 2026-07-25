package io.dataloom.testing.storage

import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.storage.OutboundChangeReadResult
import io.dataloom.testing.FakeDataLoomError
import io.dataloom.testing.sampleChangeSet
import io.dataloom.testing.sampleCheckpoint
import io.dataloom.testing.sampleCheckpointReadRequest
import io.dataloom.testing.sampleCheckpointWriteRequest
import io.dataloom.testing.sampleInboundApplyRequest
import io.dataloom.testing.sampleOutboundAcknowledgementRequest
import io.dataloom.testing.sampleOutboundReadRequest
import io.dataloom.testing.runSuspend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class InMemoryStorageProviderTest {
    @Test
    fun `descriptor uses storage type`() {
        val provider = InMemoryStorageProvider()
        assertEquals(io.dataloom.api.provider.ProviderType.STORAGE, provider.descriptor.type)
    }

    @Test
    fun `read outbound defaults to no changes when script is empty`() {
        val provider = InMemoryStorageProvider()
        val result = runSuspend { provider.readOutboundChanges(sampleOutboundReadRequest()) }
        assertEquals(ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges), result)
    }

    @Test
    fun `scripted outbound result is returned in order`() {
        val provider = InMemoryStorageProvider()
        val scripted = ProviderOperationResult.Success(
            OutboundChangeReadResult.Changes(
                changeSet = sampleChangeSet(),
                hasMore = true,
            ),
        )
        provider.enqueueReadOutboundResult(scripted)
        val result = runSuspend { provider.readOutboundChanges(sampleOutboundReadRequest()) }
        assertEquals(scripted, result)
    }

    @Test
    fun `read outbound records requests`() {
        val provider = InMemoryStorageProvider()
        val request = sampleOutboundReadRequest()
        runSuspend { provider.readOutboundChanges(request) }
        assertEquals(listOf(request), provider.readOutboundRequests)
    }

    @Test
    fun `scripted apply inbound result is returned`() {
        val provider = InMemoryStorageProvider()
        val scripted = ProviderOperationResult.Failure(FakeDataLoomError(message = "apply failed"))
        provider.enqueueApplyInboundResult(scripted)
        val result = runSuspend { provider.applyInboundChanges(sampleInboundApplyRequest()) }
        assertEquals(scripted, result)
    }

    @Test
    fun `apply inbound defaults to success when script is empty`() {
        val provider = InMemoryStorageProvider()
        val result = runSuspend { provider.applyInboundChanges(sampleInboundApplyRequest()) }
        assertEquals(ProviderOperationResult.Success(Unit), result)
    }

    @Test
    fun `apply inbound records requests`() {
        val provider = InMemoryStorageProvider()
        val request = sampleInboundApplyRequest()
        runSuspend { provider.applyInboundChanges(request) }
        assertEquals(listOf(request), provider.applyInboundRequests)
    }

    @Test
    fun `scripted acknowledgement result is returned`() {
        val provider = InMemoryStorageProvider()
        val scripted = ProviderOperationResult.Failure(FakeDataLoomError(message = "ack failed"))
        provider.enqueueAcknowledgeResult(scripted)
        val result = runSuspend { provider.acknowledgeOutboundChanges(sampleOutboundAcknowledgementRequest()) }
        assertEquals(scripted, result)
    }

    @Test
    fun `acknowledgement defaults to success when script is empty`() {
        val provider = InMemoryStorageProvider()
        val result = runSuspend { provider.acknowledgeOutboundChanges(sampleOutboundAcknowledgementRequest()) }
        assertEquals(ProviderOperationResult.Success(Unit), result)
    }

    @Test
    fun `acknowledgement records requests`() {
        val provider = InMemoryStorageProvider()
        val request = sampleOutboundAcknowledgementRequest()
        runSuspend { provider.acknowledgeOutboundChanges(request) }
        assertEquals(listOf(request), provider.acknowledgeRequests)
    }

    @Test
    fun `checkpoint write persists checkpoint in memory`() {
        val provider = InMemoryStorageProvider()
        val writeRequest = sampleCheckpointWriteRequest()
        runSuspend { provider.writeCheckpoint(writeRequest) }
        val readResult = runSuspend { provider.readCheckpoint(sampleCheckpointReadRequest()) }
        assertEquals(ProviderOperationResult.Success(writeRequest.checkpoint), readResult)
    }

    @Test
    fun `checkpoint read returns null when absent`() {
        val provider = InMemoryStorageProvider()
        val result = runSuspend { provider.readCheckpoint(sampleCheckpointReadRequest()) }
        assertEquals(ProviderOperationResult.Success(null), result)
    }

    @Test
    fun `checkpoint read failure override wins over stored state`() {
        val provider = InMemoryStorageProvider()
        runSuspend { provider.writeCheckpoint(sampleCheckpointWriteRequest()) }
        provider.setCheckpointReadFailure(FakeDataLoomError(message = "read failed"))
        val result = runSuspend { provider.readCheckpoint(sampleCheckpointReadRequest()) }
        assertIs<ProviderOperationResult.Failure>(result)
    }

    @Test
    fun `checkpoint write failure override prevents persistence`() {
        val provider = InMemoryStorageProvider()
        provider.setCheckpointWriteFailure(FakeDataLoomError(message = "write failed"))
        val writeResult = runSuspend { provider.writeCheckpoint(sampleCheckpointWriteRequest()) }
        assertIs<ProviderOperationResult.Failure>(writeResult)
        val readResult = runSuspend { provider.readCheckpoint(sampleCheckpointReadRequest()) }
        assertEquals(ProviderOperationResult.Success(null), readResult)
    }

    @Test
    fun `checkpoint read and write requests are recorded`() {
        val provider = InMemoryStorageProvider()
        val writeRequest = sampleCheckpointWriteRequest()
        val readRequest = sampleCheckpointReadRequest()
        runSuspend { provider.writeCheckpoint(writeRequest) }
        runSuspend { provider.readCheckpoint(readRequest) }
        assertEquals(listOf(writeRequest), provider.checkpointWriteRequests)
        assertEquals(listOf(readRequest), provider.checkpointReadRequests)
    }

    @Test
    fun `clear recordings removes recorded requests only`() {
        val provider = InMemoryStorageProvider()
        runSuspend { provider.writeCheckpoint(sampleCheckpointWriteRequest()) }
        runSuspend { provider.readCheckpoint(sampleCheckpointReadRequest()) }
        provider.clearRecordings()
        assertEquals(emptyList(), provider.readOutboundRequests)
        assertEquals(emptyList(), provider.applyInboundRequests)
        assertEquals(emptyList(), provider.acknowledgeRequests)
        assertEquals(emptyList(), provider.checkpointReadRequests)
        assertEquals(emptyList(), provider.checkpointWriteRequests)
        val readResult = runSuspend { provider.readCheckpoint(sampleCheckpointReadRequest()) }
        assertEquals(ProviderOperationResult.Success(sampleCheckpoint()), readResult)
    }

    @Test
    fun `reset state clears checkpoints scripts and recordings`() {
        val provider = InMemoryStorageProvider()
        provider.enqueueApplyInboundResult(ProviderOperationResult.Failure(FakeDataLoomError()))
        runSuspend { provider.writeCheckpoint(sampleCheckpointWriteRequest()) }
        provider.resetState()
        val applyResult = runSuspend { provider.applyInboundChanges(sampleInboundApplyRequest()) }
        val readResult = runSuspend { provider.readCheckpoint(sampleCheckpointReadRequest()) }
        assertEquals(ProviderOperationResult.Success(Unit), applyResult)
        assertEquals(ProviderOperationResult.Success(null), readResult)
        assertEquals(emptyList(), provider.checkpointWriteRequests)
    }

    @Test
    fun `checkpoint failure overrides can be cleared`() {
        val provider = InMemoryStorageProvider()
        provider.setCheckpointReadFailure(FakeDataLoomError())
        provider.setCheckpointReadFailure(null)
        val result = runSuspend { provider.readCheckpoint(sampleCheckpointReadRequest()) }
        assertEquals(ProviderOperationResult.Success(null), result)
    }
}
