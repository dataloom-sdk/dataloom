package io.dataloom.storage.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.ChangeSet
import io.dataloom.api.change.EntityReference
import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
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
import io.dataloom.api.payload.DataLoomPayload
import io.dataloom.api.payload.PayloadContentType
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.storage.InboundChangeApplyRequest
import io.dataloom.api.storage.LocalConflictCandidateReadRequest
import io.dataloom.api.storage.LocalConflictCandidateReadResult
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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomStorageProviderInstrumentedTest {

    private lateinit var context: Context
    private lateinit var database: DataLoomStorageRoomDatabase
    private lateinit var provider: RoomStorageProvider

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(
            context,
            DataLoomStorageRoomDatabase::class.java,
        ).build()
        provider = RoomStorageProvider(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun readOutboundChangesHonorsMaxEventsAndHasMore() = runBlocking {
        val changeSet = ChangeSet(
            id = ChangeSetId("outbound-1"),
            events = listOf(
                changeEvent("event-1", "customer", "customer-1"),
                changeEvent("event-2", "order", "order-1"),
                changeEvent("event-3", "customer", "customer-2"),
            ),
            metadata = DataLoomMetadata.of(mapOf("batch" to "first")),
        )
        database.outboundChangeDao().appendChangeSet(changeSet)

        val result = provider.readOutboundChanges(
            OutboundChangeReadRequest(
                request = request(),
                entityTypes = setOf(EntityType("customer"), EntityType("order")),
                maxEvents = 2,
            ),
        )

        val success = assertIs<ProviderOperationResult.Success<OutboundChangeReadResult>>(result)
        val changes = assertIs<OutboundChangeReadResult.Changes>(success.value)
        assertEquals("outbound-1", changes.changeSet.id.value)
        assertEquals(2, changes.changeSet.events.size)
        assertEquals(true, changes.hasMore)
        assertEquals("event-1", changes.changeSet.events[0].id.value)
        assertEquals("event-2", changes.changeSet.events[1].id.value)
        assertEquals("first", changes.changeSet.metadata["batch"])
    }

    @Test
    fun applyInboundChangesPersistsOpaquePayloadsIdempotently() = runBlocking {
        val request = InboundChangeApplyRequest(
            request = request(),
            changeSet = ChangeSet(
                id = ChangeSetId("inbound-1"),
                events = listOf(
                    changeEvent(
                        eventId = "event-1",
                        entityType = "inventory",
                        entityId = "item-1",
                        payload = DataLoomPayload(
                            contentType = PayloadContentType("application/octet-stream"),
                            bytes = byteArrayOf(1, 2, 3, 4),
                        ),
                    ),
                ),
            ),
        )

        assertIs<ProviderOperationResult.Success<Unit>>(provider.applyInboundChanges(request))
        assertIs<ProviderOperationResult.Success<Unit>>(provider.applyInboundChanges(request))

        val storedChangeSet = database.inboundChangeDao().readStoredChangeSet("inbound-1")
        val storedEvents = database.inboundChangeDao().readStoredEvents("inbound-1")
        assertEquals("inbound-1", storedChangeSet?.changeSetId)
        assertEquals(1, storedEvents.size)
        assertEquals("application/octet-stream", storedEvents.single().payloadContentType)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), assertIs<ByteArray>(storedEvents.single().payloadBytes))
    }

    @Test
    fun acknowledgeOutboundChangesKeepsRetryEventsReadable() = runBlocking {
        val changeSet = ChangeSet(
            id = ChangeSetId("outbound-2"),
            events = listOf(
                changeEvent("event-1", "customer", "customer-1"),
                changeEvent("event-2", "customer", "customer-2"),
            ),
        )
        database.outboundChangeDao().appendChangeSet(changeSet)

        val acknowledgement = ChangeSetAcknowledgement(
            changeSetId = ChangeSetId("outbound-2"),
            events = listOf(
                ChangeEventAcknowledgement(
                    eventId = ChangeEventId("event-1"),
                    status = ChangeAcknowledgementStatus.ACCEPTED,
                ),
                ChangeEventAcknowledgement(
                    eventId = ChangeEventId("event-2"),
                    status = ChangeAcknowledgementStatus.RETRY,
                    error = testError(),
                ),
            ),
        )
        assertIs<ProviderOperationResult.Success<Unit>>(
            provider.acknowledgeOutboundChanges(
                OutboundChangeAcknowledgementRequest(
                    request = request(),
                    acknowledgement = acknowledgement,
                ),
            ),
        )

        val storedEvents = database.outboundChangeDao().findStoredEvents("outbound-2")
        assertEquals("ACCEPTED", storedEvents[0].acknowledgementStatus)
        assertEquals("RETRY", storedEvents[1].acknowledgementStatus)
        assertEquals("TEMPORARY_REMOTE_REJECTION", storedEvents[1].ackErrorCode)

        val reread = provider.readOutboundChanges(
            OutboundChangeReadRequest(
                request = request(),
                entityTypes = setOf(EntityType("customer")),
            ),
        )
        val changes = assertIs<OutboundChangeReadResult.Changes>(
            assertIs<ProviderOperationResult.Success<OutboundChangeReadResult>>(reread).value,
        )
        assertEquals(listOf("event-2"), changes.changeSet.events.map { it.id.value })
        assertEquals(false, changes.hasMore)
    }

    @Test
    fun readLocalConflictCandidateReturnsTheMostRecentlyAppendedOutboundEventForTheEntity() = runBlocking {
        database.outboundChangeDao().appendChangeSet(
            ChangeSet(
                id = ChangeSetId("outbound-3"),
                events = listOf(changeEvent("event-1", "customer", "customer-1")),
            ),
        )
        database.outboundChangeDao().appendChangeSet(
            ChangeSet(
                id = ChangeSetId("outbound-4"),
                events = listOf(changeEvent("event-2", "customer", "customer-1")),
            ),
        )

        val result = provider.readLocalConflictCandidate(
            LocalConflictCandidateReadRequest(
                request = request(),
                entity = EntityReference(type = EntityType("customer"), id = EntityId("customer-1")),
            ),
        )

        val found = assertIs<LocalConflictCandidateReadResult.Found>(
            assertIs<ProviderOperationResult.Success<LocalConflictCandidateReadResult>>(result).value,
        )
        assertEquals("event-2", found.localChange.id.value)
    }

    @Test
    fun readLocalConflictCandidateReturnsNotFoundForAnEntityWithNoOutboundHistory() = runBlocking {
        val result = provider.readLocalConflictCandidate(
            LocalConflictCandidateReadRequest(
                request = request(),
                entity = EntityReference(type = EntityType("customer"), id = EntityId("customer-untouched")),
            ),
        )

        val success = assertIs<ProviderOperationResult.Success<LocalConflictCandidateReadResult>>(result)
        assertEquals(LocalConflictCandidateReadResult.NotFound, success.value)
    }

    @Test
    fun readLocalConflictCandidateIgnoresInboundOnlyHistoryForTheSameEntity() = runBlocking {
        // Applying an inbound change for an entity that has never had a local
        // outbound edit must not surface as a conflict candidate — this is the
        // ordinary "apply the remote update" case, not a local-vs-remote
        // disagreement, matching RoomStorageProvider's own documented design.
        assertIs<ProviderOperationResult.Success<Unit>>(
            provider.applyInboundChanges(
                InboundChangeApplyRequest(
                    request = request(),
                    changeSet = ChangeSet(
                        id = ChangeSetId("inbound-2"),
                        events = listOf(changeEvent("event-3", "customer", "customer-2")),
                    ),
                ),
            ),
        )

        val result = provider.readLocalConflictCandidate(
            LocalConflictCandidateReadRequest(
                request = request(),
                entity = EntityReference(type = EntityType("customer"), id = EntityId("customer-2")),
            ),
        )

        val success = assertIs<ProviderOperationResult.Success<LocalConflictCandidateReadResult>>(result)
        assertEquals(LocalConflictCandidateReadResult.NotFound, success.value)
    }

    @Test
    fun readCheckpointReturnsNullAndWriteCheckpointOverwritesExistingValue() = runBlocking {
        val key = CheckpointKey("inventory")
        val readRequest = CheckpointReadRequest(request = request(), key = key)

        val initialRead = provider.readCheckpoint(readRequest)
        assertNull(assertIs<ProviderOperationResult.Success<SynchronizationCheckpoint?>>(initialRead).value)

        assertIs<ProviderOperationResult.Success<Unit>>(
            provider.writeCheckpoint(
                CheckpointWriteRequest(
                    request = request(),
                    checkpoint = SynchronizationCheckpoint(
                        key = key,
                        token = CheckpointToken("token-1"),
                    ),
                ),
            ),
        )
        assertIs<ProviderOperationResult.Success<Unit>>(
            provider.writeCheckpoint(
                CheckpointWriteRequest(
                    request = request(),
                    checkpoint = SynchronizationCheckpoint(
                        key = key,
                        token = CheckpointToken("token-2"),
                        metadata = DataLoomMetadata.of(mapOf("source" to "refresh")),
                    ),
                ),
            ),
        )

        val stored = provider.readCheckpoint(readRequest)
        val checkpoint = assertIs<ProviderOperationResult.Success<SynchronizationCheckpoint?>>(stored).value
        assertEquals("token-2", checkpoint?.token?.value)
        assertEquals("refresh", checkpoint?.metadata?.get("source"))
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

    private fun changeEvent(
        eventId: String,
        entityType: String,
        entityId: String,
        payload: DataLoomPayload? = null,
    ): ChangeEvent = ChangeEvent(
        id = ChangeEventId(eventId),
        entity = EntityReference(
            type = EntityType(entityType),
            id = EntityId(entityId),
        ),
        operation = ChangeOperation.UPDATE,
        payload = payload,
    )

    private fun testError(): DataLoomError = object : DataLoomError {
        override val code: ErrorCode = ErrorCode("TEMPORARY_REMOTE_REJECTION")
        override val category: ErrorCategory = ErrorCategory.NETWORK
        override val severity: ErrorSeverity = ErrorSeverity.WARNING
        override val recoverability: Recoverability = Recoverability.RECOVERABLE
        override val message: String = "The remote participant asked for a retry."
        override val cause: Throwable? = null
    }
}
