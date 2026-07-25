package io.dataloom.testing.transport

import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.testing.FakeDataLoomError
import io.dataloom.testing.sampleAcknowledgement
import io.dataloom.testing.sampleChangeSet
import io.dataloom.testing.sampleCheckpoint
import io.dataloom.testing.sampleSynchronizationRequest
import io.dataloom.testing.runSuspend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ScriptedTransportProviderTest {
    private fun pushRequest(): PushChangesRequest = PushChangesRequest(
        request = sampleSynchronizationRequest(),
        changeSet = sampleChangeSet(),
    )

    private fun pullRequest(): PullChangesRequest = PullChangesRequest(
        request = sampleSynchronizationRequest(),
        checkpoint = sampleCheckpoint(),
    )

    private fun ack(): ChangeSetAcknowledgement = sampleAcknowledgement()

    @Test
    fun `descriptor uses transport type`() {
        val provider = ScriptedTransportProvider()
        assertEquals(io.dataloom.api.provider.ProviderType.TRANSPORT, provider.descriptor.type)
    }

    @Test
    fun `enqueue push result returns scripted success`() {
        val provider = ScriptedTransportProvider()
        val scripted = ProviderOperationResult.Success(ack())
        provider.enqueuePushResult(scripted)
        assertEquals(scripted, runSuspend { provider.pushChanges(pushRequest()) })
    }

    @Test
    fun `enqueue pull result returns scripted success`() {
        val provider = ScriptedTransportProvider()
        val scripted = ProviderOperationResult.Success(PullChangesResult.NoChanges(sampleCheckpoint()))
        provider.enqueuePullResult(scripted)
        assertEquals(scripted, runSuspend { provider.pullChanges(pullRequest()) })
    }

    @Test
    fun `push requests are recorded in order`() {
        val provider = ScriptedTransportProvider()
        provider.enqueuePushResult(ProviderOperationResult.Success(ack()))
        provider.enqueuePushResult(ProviderOperationResult.Success(ack()))
        val first = pushRequest()
        val second = pushRequest().copy(request = sampleSynchronizationRequest("002"))
        runSuspend { provider.pushChanges(first) }
        runSuspend { provider.pushChanges(second) }
        assertEquals(listOf(first, second), provider.pushRequests)
    }

    @Test
    fun `pull requests are recorded in order`() {
        val provider = ScriptedTransportProvider()
        provider.enqueuePullResult(ProviderOperationResult.Success(PullChangesResult.NoChanges()))
        provider.enqueuePullResult(ProviderOperationResult.Success(PullChangesResult.NoChanges()))
        val first = pullRequest()
        val second = PullChangesRequest(request = sampleSynchronizationRequest("002"))
        runSuspend { provider.pullChanges(first) }
        runSuspend { provider.pullChanges(second) }
        assertEquals(listOf(first, second), provider.pullRequests)
    }

    @Test
    fun `push can return failure result`() {
        val provider = ScriptedTransportProvider()
        val scripted = ProviderOperationResult.Failure(FakeDataLoomError(message = "push failed"))
        provider.enqueuePushResult(scripted)
        val result = runSuspend { provider.pushChanges(pushRequest()) }
        assertIs<ProviderOperationResult.Failure>(result)
        assertEquals(scripted, result)
    }

    @Test
    fun `pull can return failure result`() {
        val provider = ScriptedTransportProvider()
        val scripted = ProviderOperationResult.Failure(FakeDataLoomError(message = "pull failed"))
        provider.enqueuePullResult(scripted)
        val result = runSuspend { provider.pullChanges(pullRequest()) }
        assertIs<ProviderOperationResult.Failure>(result)
        assertEquals(scripted, result)
    }

    @Test
    fun `push exhaustion throws informative exception`() {
        val provider = ScriptedTransportProvider()
        val error = assertFailsWith<IllegalStateException> {
            runSuspend { provider.pushChanges(pushRequest()) }
        }
        assertEquals(true, error.message.orEmpty().contains("push script exhausted"))
    }

    @Test
    fun `pull exhaustion throws informative exception`() {
        val provider = ScriptedTransportProvider()
        val error = assertFailsWith<IllegalStateException> {
            runSuspend { provider.pullChanges(pullRequest()) }
        }
        assertEquals(true, error.message.orEmpty().contains("pull script exhausted"))
    }

    @Test
    fun `requests are recorded before exhaustion failure`() {
        val provider = ScriptedTransportProvider()
        val request = pushRequest()
        assertFailsWith<IllegalStateException> {
            runSuspend { provider.pushChanges(request) }
        }
        assertEquals(listOf(request), provider.pushRequests)
    }

    @Test
    fun `push script is consumed one result at a time`() {
        val provider = ScriptedTransportProvider()
        val first = ProviderOperationResult.Success(ack())
        val second = ProviderOperationResult.Failure(FakeDataLoomError())
        provider.enqueuePushResult(first)
        provider.enqueuePushResult(second)
        assertEquals(first, runSuspend { provider.pushChanges(pushRequest()) })
        assertEquals(second, runSuspend { provider.pushChanges(pushRequest()) })
    }

    @Test
    fun `pull script is consumed one result at a time`() {
        val provider = ScriptedTransportProvider()
        val first = ProviderOperationResult.Success(PullChangesResult.NoChanges())
        val second = ProviderOperationResult.Success(
            PullChangesResult.Changes(changeSet = sampleChangeSet("changes-002"), hasMore = false),
        )
        provider.enqueuePullResult(first)
        provider.enqueuePullResult(second)
        assertEquals(first, runSuspend { provider.pullChanges(pullRequest()) })
        assertEquals(second, runSuspend { provider.pullChanges(pullRequest()) })
    }

    @Test
    fun `clear recordings preserves scripts`() {
        val provider = ScriptedTransportProvider()
        val scripted = ProviderOperationResult.Success(ack())
        provider.enqueuePushResult(scripted)
        provider.clearRecordings()
        assertEquals(scripted, runSuspend { provider.pushChanges(pushRequest()) })
    }

    @Test
    fun `clear recordings clears request lists`() {
        val provider = ScriptedTransportProvider()
        provider.enqueuePushResult(ProviderOperationResult.Success(ack()))
        runSuspend { provider.pushChanges(pushRequest()) }
        provider.clearRecordings()
        assertEquals(emptyList(), provider.pushRequests)
        assertEquals(emptyList(), provider.pullRequests)
    }

    @Test
    fun `reset state clears scripts and recordings`() {
        val provider = ScriptedTransportProvider()
        provider.enqueuePullResult(ProviderOperationResult.Success(PullChangesResult.NoChanges()))
        runSuspend { provider.pullChanges(pullRequest()) }
        provider.resetState()
        assertEquals(emptyList(), provider.pullRequests)
        assertFailsWith<IllegalStateException> {
            runSuspend { provider.pullChanges(pullRequest()) }
        }
    }

    @Test
    fun `pull result can carry checkpoint`() {
        val provider = ScriptedTransportProvider()
        val checkpoint = sampleCheckpoint(token = "next")
        val scripted = ProviderOperationResult.Success(PullChangesResult.NoChanges(nextCheckpoint = checkpoint))
        provider.enqueuePullResult(scripted)
        val result = runSuspend { provider.pullChanges(pullRequest()) }
        assertEquals(scripted, result)
    }
}
