package io.dataloom.storage.sqldelight

import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.storage.OutboundChangeReadResult
import io.dataloom.api.synchronization.ChangeAcknowledgementStatus
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SqlDelightStorageProviderTest {
    @Test
    fun `read outbound changes honors max events and hasMore`() {
        val provider = SqlDelightStorageProvider(createTestSqlDelightStorageDatabase())
        val changeSet = sampleChangeSet(
            changeSetId = "changes-001",
            eventIds = listOf("event-001", "event-002", "event-003"),
        )

        val writeResult = runSuspend { provider.persistOutboundChanges(changeSet) }
        assertEquals(ProviderOperationResult.Success(Unit), writeResult)

        val readResult = runSuspend {
            provider.readOutboundChanges(
                sampleOutboundReadRequest(maxEvents = 2),
            )
        }

        val success = assertIs<ProviderOperationResult.Success<OutboundChangeReadResult>>(readResult)
        val changes = assertIs<OutboundChangeReadResult.Changes>(success.value)
        assertEquals(2, changes.changeSet.events.size)
        assertEquals(true, changes.hasMore)
    }

    @Test
    fun `apply inbound changes returns success`() {
        val provider = SqlDelightStorageProvider(createTestSqlDelightStorageDatabase())
        val request = sampleInboundApplyRequest(
            changeSet = sampleChangeSet(
                changeSetId = "inbound-001",
                eventIds = listOf("inbound-event-001"),
            ),
        )

        val result = runSuspend { provider.applyInboundChanges(request) }
        assertEquals(ProviderOperationResult.Success(Unit), result)
    }

    @Test
    fun `acknowledge accepted change removes outbound event`() {
        val provider = SqlDelightStorageProvider(createTestSqlDelightStorageDatabase())
        runSuspend {
            provider.persistOutboundChanges(
                sampleChangeSet(changeSetId = "changes-accept", eventIds = listOf("event-accept")),
            )
        }

        val acknowledgeResult = runSuspend {
            provider.acknowledgeOutboundChanges(
                sampleOutboundAcknowledgementRequest(
                    changeSetId = "changes-accept",
                    eventId = "event-accept",
                    status = ChangeAcknowledgementStatus.ACCEPTED,
                ),
            )
        }
        assertEquals(ProviderOperationResult.Success(Unit), acknowledgeResult)

        val readResult = runSuspend { provider.readOutboundChanges(sampleOutboundReadRequest()) }
        assertEquals(ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges), readResult)
    }

    @Test
    fun `acknowledge retry keeps outbound event eligible`() {
        val provider = SqlDelightStorageProvider(createTestSqlDelightStorageDatabase())
        runSuspend {
            provider.persistOutboundChanges(
                sampleChangeSet(changeSetId = "changes-retry", eventIds = listOf("event-retry")),
            )
        }

        val acknowledgeResult = runSuspend {
            provider.acknowledgeOutboundChanges(
                sampleOutboundAcknowledgementRequest(
                    changeSetId = "changes-retry",
                    eventId = "event-retry",
                    status = ChangeAcknowledgementStatus.RETRY,
                ),
            )
        }
        assertEquals(ProviderOperationResult.Success(Unit), acknowledgeResult)

        val readResult = runSuspend { provider.readOutboundChanges(sampleOutboundReadRequest()) }
        val success = assertIs<ProviderOperationResult.Success<OutboundChangeReadResult>>(readResult)
        assertIs<OutboundChangeReadResult.Changes>(success.value)
    }

    @Test
    fun `checkpoint read returns null when absent`() {
        val provider = SqlDelightStorageProvider(createTestSqlDelightStorageDatabase())
        val result = runSuspend { provider.readCheckpoint(sampleCheckpointReadRequest()) }
        assertEquals(ProviderOperationResult.Success(null), result)
    }

    @Test
    fun `checkpoint write overwrites existing checkpoint`() {
        val provider = SqlDelightStorageProvider(createTestSqlDelightStorageDatabase())
        val firstWrite = sampleCheckpointWriteRequest(token = "token-1")
        val secondWrite = sampleCheckpointWriteRequest(token = "token-2")

        val firstResult = runSuspend { provider.writeCheckpoint(firstWrite) }
        val secondResult = runSuspend { provider.writeCheckpoint(secondWrite) }
        val readResult = runSuspend { provider.readCheckpoint(sampleCheckpointReadRequest()) }

        assertEquals(ProviderOperationResult.Success(Unit), firstResult)
        assertEquals(ProviderOperationResult.Success(Unit), secondResult)
        assertEquals(ProviderOperationResult.Success(secondWrite.checkpoint), readResult)
    }
}

private object PendingResult

private fun <T> runSuspend(block: suspend () -> T): T {
    var rawResult: Any? = PendingResult
    var failure: Throwable? = null
    block.startCoroutine(
        object : kotlin.coroutines.Continuation<T> {
            override val context: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext

            override fun resumeWith(result: Result<T>) {
                result.fold(
                    onSuccess = { value -> rawResult = value },
                    onFailure = { throwable -> failure = throwable },
                )
            }
        },
    )
    failure?.let { throw it }
    check(rawResult !== PendingResult) { "Suspend block did not complete synchronously in test." }
    @Suppress("UNCHECKED_CAST")
    return rawResult as T
}
