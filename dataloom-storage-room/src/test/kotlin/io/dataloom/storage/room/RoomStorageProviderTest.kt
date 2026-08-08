package io.dataloom.storage.room

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.ChangeSet
import io.dataloom.api.change.EntityReference
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ChangeSetId
import io.dataloom.api.identifier.CheckpointKey
import io.dataloom.api.identifier.CheckpointToken
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.ChangeOperation
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.model.WorkflowPriority
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.storage.InboundChangeApplyRequest
import io.dataloom.api.storage.OutboundChangeReadRequest
import io.dataloom.api.storage.OutboundChangeReadResult
import io.dataloom.api.synchronization.ChangeAcknowledgementStatus
import io.dataloom.api.synchronization.ChangeEventAcknowledgement
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.synchronization.CheckpointReadRequest
import io.dataloom.api.synchronization.CheckpointWriteRequest
import io.dataloom.api.synchronization.OutboundChangeAcknowledgementRequest
import io.dataloom.api.synchronization.SynchronizationCheckpoint
import io.dataloom.storage.room.internal.DataLoomStorageRoomDatabase
import io.dataloom.storage.room.internal.InboundApplyDisposition
import io.dataloom.storage.room.internal.InboundChangeDao
import io.dataloom.storage.room.internal.OutboundChangeDao
import io.dataloom.storage.room.internal.PersistedOutboundBatch
import io.dataloom.storage.room.internal.StorageCheckpointDao
import io.dataloom.storage.room.internal.toOutboundEntity
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class RoomStorageProviderTest {

    private lateinit var database: DataLoomStorageRoomDatabase
    private lateinit var outboundChangeDao: OutboundChangeDao
    private lateinit var inboundChangeDao: InboundChangeDao
    private lateinit var checkpointDao: StorageCheckpointDao
    private lateinit var provider: RoomStorageProvider

    @Before
    fun setUp() {
        database = mock()
        outboundChangeDao = mock()
        inboundChangeDao = mock()
        checkpointDao = mock()
        whenever(database.outboundChangeDao()).thenReturn(outboundChangeDao)
        whenever(database.inboundChangeDao()).thenReturn(inboundChangeDao)
        whenever(database.storageCheckpointDao()).thenReturn(checkpointDao)
        provider = RoomStorageProvider(database)
    }

    @Test
    fun `descriptor has storage type`() {
        assertEquals(ProviderType.STORAGE, provider.descriptor.type)
    }

    @Test
    fun `readOutboundChanges returns batch from DAO`() = runBlocking {
        whenever(outboundChangeDao.readNextBatch(any(), eq(2))).thenReturn(
            PersistedOutboundBatch(
                storageSequence = 1L,
                changeSetId = "change-set-1",
                metadataJson = null,
                events = listOf(
                    changeEvent("event-1").toOutboundEntity("change-set-1", 0),
                    changeEvent("event-2").toOutboundEntity("change-set-1", 1),
                ),
                hasMore = true,
            ),
        )

        val result = provider.readOutboundChanges(OutboundChangeReadRequest(request(), maxEvents = 2))
        val success = assertIs<ProviderOperationResult.Success<OutboundChangeReadResult>>(result)
        val changes = assertIs<OutboundChangeReadResult.Changes>(success.value)
        assertEquals("change-set-1", changes.changeSet.id.value)
        assertEquals(2, changes.changeSet.events.size)
        assertEquals(true, changes.hasMore)
    }

    @Test
    fun `applyInboundChanges maps conflicting state to canonical failure`() = runBlocking {
        whenever(inboundChangeDao.applyChangeSet(any())).thenReturn(InboundApplyDisposition.CONFLICT)

        val result = provider.applyInboundChanges(
            InboundChangeApplyRequest(
                request = request(),
                changeSet = changeSet("inbound-change-set", "event-1"),
            ),
        )

        val failure = assertIs<ProviderOperationResult.Failure>(result)
        assertEquals("STORAGE_INBOUND_CHANGESET_CONFLICT", failure.error.code.value)
    }

    @Test
    fun `acknowledgeOutboundChanges maps missing event to canonical failure`() = runBlocking {
        whenever(outboundChangeDao.recordAcknowledgement(any())).thenReturn(false)

        val result = provider.acknowledgeOutboundChanges(
            OutboundChangeAcknowledgementRequest(
                request = request(),
                acknowledgement = ChangeSetAcknowledgement(
                    changeSetId = ChangeSetId("change-set-1"),
                    events = listOf(
                        ChangeEventAcknowledgement(
                            eventId = ChangeEventId("event-1"),
                            status = ChangeAcknowledgementStatus.ACCEPTED,
                        ),
                    ),
                ),
            ),
        )

        val failure = assertIs<ProviderOperationResult.Failure>(result)
        assertEquals("STORAGE_OUTBOUND_ACKNOWLEDGEMENT_TARGET_MISSING", failure.error.code.value)
    }

    @Test
    fun `readCheckpoint returns null when DAO has no row`() = runBlocking {
        whenever(checkpointDao.findByKey("checkpoint-1")).thenReturn(null)

        val result = provider.readCheckpoint(
            CheckpointReadRequest(
                request = request(),
                key = CheckpointKey("checkpoint-1"),
            ),
        )

        val success = assertIs<ProviderOperationResult.Success<SynchronizationCheckpoint?>>(result)
        assertEquals(null, success.value)
    }

    @Test
    fun `writeCheckpoint forwards upsert to DAO`() = runBlocking {
        val checkpoint = SynchronizationCheckpoint(
            key = CheckpointKey("checkpoint-1"),
            token = CheckpointToken("token-1"),
        )

        val result = provider.writeCheckpoint(
            CheckpointWriteRequest(
                request = request(),
                checkpoint = checkpoint,
            ),
        )

        assertIs<ProviderOperationResult.Success<Unit>>(result)
    }

    @Test
    fun `cancellation propagates instead of becoming a provider failure`() {
        val expected = CancellationException("cancelled")
        runBlocking {
            whenever(outboundChangeDao.readNextBatch(any(), any())).thenThrow(expected)
        }

        val actual = assertFailsWith<CancellationException> {
            runBlocking {
                provider.readOutboundChanges(OutboundChangeReadRequest(request()))
            }
        }
        assertEquals("cancelled", actual.message)
    }

    @Test
    fun `unexpected exception maps to sanitized database failure`() = runBlocking {
        whenever(checkpointDao.findByKey("checkpoint-1")).thenThrow(IllegalStateException("sql details"))

        val result = provider.readCheckpoint(
            CheckpointReadRequest(
                request = request(),
                key = CheckpointKey("checkpoint-1"),
            ),
        )

        val failure = assertIs<ProviderOperationResult.Failure>(result)
        assertEquals("STORAGE_ROOM_DATABASE_FAILURE", failure.error.code.value)
        assertEquals(null, failure.error.cause)
    }

    private fun request(): SynchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("workflow-1"),
        sessionId = SynchronizationSessionId("session-1"),
        direction = SynchronizationDirection.BIDIRECTIONAL,
        mode = SynchronizationMode.DELTA,
        priority = WorkflowPriority.NORMAL,
        context = ExecutionContext(
            executionId = ExecutionId("execution-1"),
            correlationId = CorrelationId("correlation-1"),
        ),
    )

    private fun changeSet(
        changeSetId: String,
        vararg eventIds: String,
    ): ChangeSet = ChangeSet(
        id = ChangeSetId(changeSetId),
        events = eventIds.map(::changeEvent),
    )

    private fun changeEvent(eventId: String): ChangeEvent = ChangeEvent(
        id = ChangeEventId(eventId),
        entity = EntityReference(
            type = EntityType("customer"),
            id = EntityId(eventId),
        ),
        operation = ChangeOperation.UPDATE,
    )
}
