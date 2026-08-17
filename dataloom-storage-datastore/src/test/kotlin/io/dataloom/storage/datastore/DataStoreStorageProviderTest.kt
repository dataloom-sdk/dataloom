package io.dataloom.storage.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
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
import io.dataloom.api.payload.DataLoomPayload
import io.dataloom.api.payload.PayloadContentType
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
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
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreStorageProviderTest {

    // ── Test helpers ──────────────────────────────────────────────────────────

    private fun createDataStore(scope: TestScope, file: File): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = scope.backgroundScope,
            produceFile = { file },
        )

    private fun buildProvider(scope: TestScope): DataStoreStorageProvider {
        val file = File.createTempFile("ds_test_${System.nanoTime()}", ".preferences_pb")
            .also { it.deleteOnExit() }
        return DataStoreStorageProvider(dataStore = createDataStore(scope, file))
    }

    private fun syncRequest(suffix: String = "001"): SynchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("workflow-$suffix"),
        sessionId = SynchronizationSessionId("session-$suffix"),
        direction = SynchronizationDirection.BIDIRECTIONAL,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("exec-$suffix"),
            correlationId = CorrelationId("corr-$suffix"),
        ),
    )

    private fun changeEvent(
        eventId: String = "event-001",
        entityType: String = "Setting",
        entityId: String = "darkMode",
        operation: ChangeOperation = ChangeOperation.UPDATE,
        payload: DataLoomPayload? = null,
    ): ChangeEvent = ChangeEvent(
        id = ChangeEventId(eventId),
        entity = EntityReference(type = EntityType(entityType), id = EntityId(entityId)),
        operation = operation,
        payload = payload,
    )

    private fun changeSet(
        id: String = "cs-001",
        vararg events: ChangeEvent = arrayOf(changeEvent()),
    ): ChangeSet = ChangeSet(id = ChangeSetId(id), events = events.toList())

    private fun writeCheckpointRequest(
        key: String = "sync-ckpt",
        token: String = "token-001",
    ): CheckpointWriteRequest = CheckpointWriteRequest(
        request = syncRequest(),
        checkpoint = SynchronizationCheckpoint(
            key = CheckpointKey(key),
            token = CheckpointToken(token),
        ),
    )

    private fun readCheckpointRequest(key: String = "sync-ckpt"): CheckpointReadRequest =
        CheckpointReadRequest(request = syncRequest(), key = CheckpointKey(key))

    private fun ackRequest(
        changeSetId: String,
        vararg eventStatuses: Pair<String, ChangeAcknowledgementStatus>,
    ): OutboundChangeAcknowledgementRequest = OutboundChangeAcknowledgementRequest(
        request = syncRequest(),
        acknowledgement = ChangeSetAcknowledgement(
            changeSetId = ChangeSetId(changeSetId),
            events = eventStatuses.map { (id, status) ->
                ChangeEventAcknowledgement(eventId = ChangeEventId(id), status = status)
            },
        ),
    )

    // ── Descriptor ───────────────────────────────────────────────────────────

    @Test
    fun `descriptor type is STORAGE`() {
        val provider = buildProvider(TestScope())
        assertEquals(ProviderType.STORAGE, provider.descriptor.type)
    }

    // ── Empty queue ───────────────────────────────────────────────────────────

    @Test
    fun `readOutboundChanges returns NoChanges when queue is empty`() = runTest {
        val provider = buildProvider(this)
        val result = provider.readOutboundChanges(OutboundChangeReadRequest(syncRequest()))
        assertEquals(ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges), result)
    }

    // ── Enqueue and read ──────────────────────────────────────────────────────

    @Test
    fun `enqueue then read returns the enqueued event`() = runTest {
        val provider = buildProvider(this)
        val event = changeEvent()
        provider.enqueueOutboundChanges(changeSet(events = arrayOf(event)))

        val result = provider.readOutboundChanges(OutboundChangeReadRequest(syncRequest()))
        val changes = assertIs<OutboundChangeReadResult.Changes>(
            (result as ProviderOperationResult.Success).value,
        )
        assertEquals(1, changes.changeSet.events.size)
        assertEquals(event.id, changes.changeSet.events.first().id)
        assertEquals(event.entity.type, changes.changeSet.events.first().entity.type)
        assertEquals(event.entity.id, changes.changeSet.events.first().entity.id)
        assertEquals(event.operation, changes.changeSet.events.first().operation)
    }

    @Test
    fun `event with payload round-trips payload bytes correctly`() = runTest {
        val provider = buildProvider(this)
        val payloadBytes = byteArrayOf(0, 1, 127, 128.toByte(), 255.toByte())
        val event = changeEvent(
            payload = DataLoomPayload(PayloadContentType("application/octet-stream"), payloadBytes),
        )
        provider.enqueueOutboundChanges(changeSet(events = arrayOf(event)))

        val result = provider.readOutboundChanges(OutboundChangeReadRequest(syncRequest()))
        val changes = assertIs<OutboundChangeReadResult.Changes>(
            (result as ProviderOperationResult.Success).value,
        )
        val returnedPayload = changes.changeSet.events.first().payload
        assertEquals("application/octet-stream", returnedPayload?.contentType?.value)
        assertEquals(payloadBytes.toList(), returnedPayload?.copyBytes()?.toList())
    }

    // ── Batching / hasMore ────────────────────────────────────────────────────

    @Test
    fun `hasMore is false when all events fit without maxEvents limit`() = runTest {
        val provider = buildProvider(this)
        provider.enqueueOutboundChanges(changeSet())

        val result = provider.readOutboundChanges(OutboundChangeReadRequest(syncRequest()))
        val changes = assertIs<OutboundChangeReadResult.Changes>(
            (result as ProviderOperationResult.Success).value,
        )
        assertEquals(false, changes.hasMore)
    }

    @Test
    fun `hasMore is true when maxEvents is less than pending count`() = runTest {
        val provider = buildProvider(this)
        provider.enqueueOutboundChanges(
            changeSet(
                id = "cs-multi",
                events = arrayOf(changeEvent("ev-1", entityId = "a"), changeEvent("ev-2", entityId = "b")),
            ),
        )

        val result = provider.readOutboundChanges(
            OutboundChangeReadRequest(syncRequest(), maxEvents = 1),
        )
        val changes = assertIs<OutboundChangeReadResult.Changes>(
            (result as ProviderOperationResult.Success).value,
        )
        assertEquals(1, changes.changeSet.events.size)
        assertEquals(true, changes.hasMore)
    }

    @Test
    fun `subsequent read after partial acknowledgement returns remaining events`() = runTest {
        val provider = buildProvider(this)
        provider.enqueueOutboundChanges(
            changeSet(
                id = "cs-001",
                events = arrayOf(changeEvent("ev-1", entityId = "a"), changeEvent("ev-2", entityId = "b")),
            ),
        )

        provider.acknowledgeOutboundChanges(
            ackRequest("cs-001", "ev-1" to ChangeAcknowledgementStatus.ACCEPTED),
        )

        val result = provider.readOutboundChanges(OutboundChangeReadRequest(syncRequest()))
        val changes = assertIs<OutboundChangeReadResult.Changes>(
            (result as ProviderOperationResult.Success).value,
        )
        assertEquals(1, changes.changeSet.events.size)
        assertEquals("ev-2", changes.changeSet.events.first().id.value)
        assertEquals(false, changes.hasMore)
    }

    // ── Acknowledgement ───────────────────────────────────────────────────────

    @Test
    fun `ACCEPTED acknowledgement removes event from queue`() = runTest {
        val provider = buildProvider(this)
        provider.enqueueOutboundChanges(changeSet())

        provider.acknowledgeOutboundChanges(
            ackRequest("cs-001", "event-001" to ChangeAcknowledgementStatus.ACCEPTED),
        )

        val result = provider.readOutboundChanges(OutboundChangeReadRequest(syncRequest()))
        assertEquals(ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges), result)
    }

    @Test
    fun `REJECTED acknowledgement removes event from queue`() = runTest {
        val provider = buildProvider(this)
        provider.enqueueOutboundChanges(changeSet())

        provider.acknowledgeOutboundChanges(
            ackRequest("cs-001", "event-001" to ChangeAcknowledgementStatus.REJECTED),
        )

        val result = provider.readOutboundChanges(OutboundChangeReadRequest(syncRequest()))
        assertEquals(ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges), result)
    }

    @Test
    fun `RETRY acknowledgement keeps event in queue`() = runTest {
        val provider = buildProvider(this)
        provider.enqueueOutboundChanges(changeSet())

        provider.acknowledgeOutboundChanges(
            ackRequest("cs-001", "event-001" to ChangeAcknowledgementStatus.RETRY),
        )

        val result = provider.readOutboundChanges(OutboundChangeReadRequest(syncRequest()))
        assertIs<OutboundChangeReadResult.Changes>(
            (result as ProviderOperationResult.Success).value,
        )
    }

    @Test
    fun `acknowledgement removes only named events leaving others in queue`() = runTest {
        val provider = buildProvider(this)
        provider.enqueueOutboundChanges(
            changeSet(
                id = "cs-001",
                events = arrayOf(
                    changeEvent("ev-keep", entityId = "keep"),
                    changeEvent("ev-remove", entityId = "remove"),
                ),
            ),
        )

        provider.acknowledgeOutboundChanges(
            ackRequest("cs-001", "ev-remove" to ChangeAcknowledgementStatus.ACCEPTED),
        )

        val result = provider.readOutboundChanges(OutboundChangeReadRequest(syncRequest()))
        val changes = assertIs<OutboundChangeReadResult.Changes>(
            (result as ProviderOperationResult.Success).value,
        )
        assertEquals(1, changes.changeSet.events.size)
        assertEquals("ev-keep", changes.changeSet.events.first().id.value)
    }

    // ── Inbound apply ─────────────────────────────────────────────────────────

    @Test
    fun `applyInboundChanges returns success`() = runTest {
        val provider = buildProvider(this)
        val result = provider.applyInboundChanges(
            InboundChangeApplyRequest(request = syncRequest(), changeSet = changeSet()),
        )
        assertEquals(ProviderOperationResult.Success(Unit), result)
    }

    @Test
    fun `applyInboundChanges succeeds for multiple events in same change set`() = runTest {
        val provider = buildProvider(this)
        val result = provider.applyInboundChanges(
            InboundChangeApplyRequest(
                request = syncRequest(),
                changeSet = changeSet(
                    id = "cs-inbound",
                    events = arrayOf(
                        changeEvent("ev-a", entityId = "setting-a"),
                        changeEvent("ev-b", entityId = "setting-b"),
                    ),
                ),
            ),
        )
        assertEquals(ProviderOperationResult.Success(Unit), result)
    }

    @Test
    fun `applyInboundChanges called twice for same entity does not fail`() = runTest {
        val provider = buildProvider(this)
        val firstResult = provider.applyInboundChanges(
            InboundChangeApplyRequest(
                request = syncRequest(),
                changeSet = changeSet("cs-first", changeEvent("ev-1", entityId = "flagX")),
            ),
        )
        val secondResult = provider.applyInboundChanges(
            InboundChangeApplyRequest(
                request = syncRequest(),
                changeSet = changeSet("cs-second", changeEvent("ev-2", entityId = "flagX")),
            ),
        )
        assertEquals(ProviderOperationResult.Success(Unit), firstResult)
        assertEquals(ProviderOperationResult.Success(Unit), secondResult)
    }

    // ── readLocalConflictCandidate ────────────────────────────────────────────

    @Test
    fun `readLocalConflictCandidate returns NotFound for an untouched entity`() = runTest {
        val provider = buildProvider(this)

        val result = provider.readLocalConflictCandidate(
            LocalConflictCandidateReadRequest(
                request = syncRequest(),
                entity = EntityReference(type = EntityType("Setting"), id = EntityId("untouched")),
            ),
        )

        assertEquals(
            ProviderOperationResult.Success(LocalConflictCandidateReadResult.NotFound),
            result,
        )
    }

    @Test
    fun `readLocalConflictCandidate returns the most recently enqueued outbound event for the entity`() = runTest {
        val provider = buildProvider(this)
        val entity = EntityReference(type = EntityType("Setting"), id = EntityId("darkMode"))
        provider.enqueueOutboundChanges(
            changeSet("cs-first", changeEvent(eventId = "ev-first", entityId = "darkMode")),
        )
        provider.enqueueOutboundChanges(
            changeSet("cs-second", changeEvent(eventId = "ev-second", entityId = "darkMode")),
        )

        val result = provider.readLocalConflictCandidate(
            LocalConflictCandidateReadRequest(request = syncRequest(), entity = entity),
        )

        val found = assertIs<LocalConflictCandidateReadResult.Found>(
            (result as ProviderOperationResult.Success).value,
        )
        assertEquals("ev-second", found.localChange.id.value)
    }

    @Test
    fun `readLocalConflictCandidate returns NotFound once the only outbound edit is accepted`() = runTest {
        val provider = buildProvider(this)
        val entity = EntityReference(type = EntityType("Setting"), id = EntityId("accepted"))
        provider.enqueueOutboundChanges(
            changeSet("cs-accepted", changeEvent(eventId = "ev-accepted", entityId = "accepted")),
        )
        provider.acknowledgeOutboundChanges(
            ackRequest("cs-accepted", "ev-accepted" to ChangeAcknowledgementStatus.ACCEPTED),
        )

        val result = provider.readLocalConflictCandidate(
            LocalConflictCandidateReadRequest(request = syncRequest(), entity = entity),
        )

        assertEquals(
            ProviderOperationResult.Success(LocalConflictCandidateReadResult.NotFound),
            result,
        )
    }

    @Test
    fun `readLocalConflictCandidate returns NotFound once the only outbound edit is rejected`() = runTest {
        // Unlike SqlDelightStorageProvider (retains a filtered REJECTED row) and
        // FileStorageProvider (moves the event to rejected/), this provider deletes
        // the record entirely on REJECTED, same as it does for ACCEPTED — proven
        // here for real, not just documented in KDoc.
        val provider = buildProvider(this)
        val entity = EntityReference(type = EntityType("Setting"), id = EntityId("rejected"))
        provider.enqueueOutboundChanges(
            changeSet("cs-rejected", changeEvent(eventId = "ev-rejected", entityId = "rejected")),
        )
        provider.acknowledgeOutboundChanges(
            ackRequest("cs-rejected", "ev-rejected" to ChangeAcknowledgementStatus.REJECTED),
        )

        val result = provider.readLocalConflictCandidate(
            LocalConflictCandidateReadRequest(request = syncRequest(), entity = entity),
        )

        assertEquals(
            ProviderOperationResult.Success(LocalConflictCandidateReadResult.NotFound),
            result,
        )
    }

    @Test
    fun `readLocalConflictCandidate ignores inbound-only history for the same entity`() = runTest {
        val provider = buildProvider(this)
        val entity = EntityReference(type = EntityType("Setting"), id = EntityId("inboundOnly"))
        provider.applyInboundChanges(
            InboundChangeApplyRequest(
                request = syncRequest(),
                changeSet = changeSet("inbound-only", changeEvent(eventId = "ev-inbound", entityId = "inboundOnly")),
            ),
        )

        val result = provider.readLocalConflictCandidate(
            LocalConflictCandidateReadRequest(request = syncRequest(), entity = entity),
        )

        assertEquals(
            ProviderOperationResult.Success(LocalConflictCandidateReadResult.NotFound),
            result,
        )
    }

    // ── Checkpoint read / write ───────────────────────────────────────────────

    @Test
    fun `readCheckpoint returns null when no checkpoint stored`() = runTest {
        val provider = buildProvider(this)
        val result = provider.readCheckpoint(readCheckpointRequest())
        assertEquals(ProviderOperationResult.Success(null), result)
    }

    @Test
    fun `writeCheckpoint then readCheckpoint returns stored checkpoint`() = runTest {
        val provider = buildProvider(this)
        val writeRequest = writeCheckpointRequest()
        provider.writeCheckpoint(writeRequest)

        val result = provider.readCheckpoint(readCheckpointRequest())
        val success = assertIs<ProviderOperationResult.Success<SynchronizationCheckpoint?>>(result)
        assertEquals(writeRequest.checkpoint, success.value)
    }

    @Test
    fun `writeCheckpoint overwrites existing checkpoint for same key`() = runTest {
        val provider = buildProvider(this)
        provider.writeCheckpoint(writeCheckpointRequest(token = "token-v1"))
        provider.writeCheckpoint(writeCheckpointRequest(token = "token-v2"))

        val result = provider.readCheckpoint(readCheckpointRequest())
        val success = assertIs<ProviderOperationResult.Success<SynchronizationCheckpoint?>>(result)
        assertEquals("token-v2", success.value?.token?.value)
    }

    @Test
    fun `checkpoints for different keys are independent`() = runTest {
        val provider = buildProvider(this)
        provider.writeCheckpoint(writeCheckpointRequest(key = "key-a", token = "token-a"))
        provider.writeCheckpoint(writeCheckpointRequest(key = "key-b", token = "token-b"))

        val resultA = provider.readCheckpoint(readCheckpointRequest("key-a"))
        val resultB = provider.readCheckpoint(readCheckpointRequest("key-b"))

        assertEquals("token-a", (resultA as ProviderOperationResult.Success).value?.token?.value)
        assertEquals("token-b", (resultB as ProviderOperationResult.Success).value?.token?.value)
    }

    @Test
    fun `readCheckpoint for unknown key returns null not failure`() = runTest {
        val provider = buildProvider(this)
        provider.writeCheckpoint(writeCheckpointRequest(key = "other-key"))

        val result = provider.readCheckpoint(readCheckpointRequest("unknown-key"))
        assertEquals(ProviderOperationResult.Success(null), result)
    }

    // ── Outbound capacity limit ───────────────────────────────────────────────

    @Test
    fun `enqueueOutboundChanges returns limit-exceeded failure when queue would overflow`() = runTest {
        val provider = buildProvider(this)
        val events = (1..DataStoreStorageProvider.MAX_OUTBOUND_EVENTS).map { i ->
            changeEvent(eventId = "ev-$i", entityId = "entity-$i")
        }
        provider.enqueueOutboundChanges(ChangeSet(ChangeSetId("cs-fill"), events))

        val overflow = changeSet(id = "cs-overflow", events = arrayOf(changeEvent("ev-extra", entityId = "extra")))
        val result = provider.enqueueOutboundChanges(overflow)

        val failure = assertIs<ProviderOperationResult.Failure>(result)
        assertEquals("DATASTORE_OUTBOUND_LIMIT_EXCEEDED", failure.error.code.value)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Test
    fun `initialize returns success`() = runTest {
        val provider = buildProvider(this)
        assertEquals(
            ProviderOperationResult.Success(Unit),
            provider.initialize(ProviderInitializationContext()),
        )
    }

    @Test
    fun `close returns success`() = runTest {
        val provider = buildProvider(this)
        assertEquals(ProviderOperationResult.Success(Unit), provider.close())
    }

    @Test
    fun `health returns HEALTHY status`() = runTest {
        val provider = buildProvider(this)
        val result = provider.health()
        val health = assertIs<ProviderOperationResult.Success<io.dataloom.api.provider.ProviderHealth>>(result)
        assertEquals(ProviderHealthStatus.HEALTHY, health.value.status)
    }
}
