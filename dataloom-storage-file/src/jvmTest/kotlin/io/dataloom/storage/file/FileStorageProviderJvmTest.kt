package io.dataloom.storage.file

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.ChangeSet
import io.dataloom.api.change.EntityReference
import io.dataloom.api.context.DataLoomMetadata
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
import io.dataloom.api.payload.EntityVersion
import io.dataloom.api.payload.PayloadContentType
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
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileStorageProviderJvmTest {

    private fun tempDir(): String =
        Files.createTempDirectory("dataloom-file-storage-test").toAbsolutePath().toString()

    private fun provider(dir: String = tempDir()) = FileStorageProvider(dir)

    private fun execContext(): ExecutionContext = ExecutionContext(
        executionId = ExecutionId("exec-001"),
        correlationId = CorrelationId("corr-001"),
    )

    private fun syncRequest(): SynchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("wf-001"),
        sessionId = SynchronizationSessionId("sess-001"),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        context = execContext(),
    )

    private fun changeEvent(
        id: String = "evt-001",
        entityType: String = "order",
        entityId: String = "ord-001",
        operation: ChangeOperation = ChangeOperation.CREATE,
        payload: DataLoomPayload? = null,
    ): ChangeEvent = ChangeEvent(
        id = ChangeEventId(id),
        entity = EntityReference(type = EntityType(entityType), id = EntityId(entityId)),
        operation = operation,
        payload = payload,
    )

    private fun changeSet(
        csId: String = "cs-001",
        vararg events: ChangeEvent = arrayOf(changeEvent()),
    ): ChangeSet = ChangeSet(id = ChangeSetId(csId), events = events.toList())

    // ─── Descriptor ──────────────────────────────────────────────────────────

    @Test
    fun `descriptor uses STORAGE type`() {
        val p = provider()
        assertEquals(ProviderType.STORAGE, p.descriptor.type)
    }

    // ─── Initialize and health ────────────────────────────────────────────────

    @Test
    fun `initialize creates base directory`() = runTest {
        val dir = tempDir() + "/sub"
        val p = provider(dir)
        val result = p.initialize(ProviderInitializationContext())
        assertIs<ProviderOperationResult.Success<Unit>>(result)
        assertTrue(File(dir).isDirectory)
    }

    @Test
    fun `health returns HEALTHY after initialization`() = runTest {
        val p = provider()
        p.initialize(ProviderInitializationContext())
        val h = p.health()
        assertIs<ProviderOperationResult.Success<*>>(h)
    }

    // ─── Outbound: no changes ─────────────────────────────────────────────────

    @Test
    fun `readOutboundChanges returns NoChanges when nothing stored`() = runTest {
        val p = provider()
        val result = p.readOutboundChanges(OutboundChangeReadRequest(syncRequest()))
        assertEquals(
            ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges),
            result,
        )
    }

    // ─── Outbound: store and read ─────────────────────────────────────────────

    @Test
    fun `storeOutboundChangeSet and readOutboundChanges returns stored events`() = runTest {
        val p = provider()
        val cs = changeSet("cs-001", changeEvent("e1"), changeEvent("e2"))
        p.storeOutboundChangeSet(cs)

        val result = p.readOutboundChanges(OutboundChangeReadRequest(syncRequest()))
        assertIs<ProviderOperationResult.Success<OutboundChangeReadResult>>(result)
        val changes = assertIs<OutboundChangeReadResult.Changes>(result.value)
        assertEquals(2, changes.changeSet.events.size)
        assertEquals(false, changes.hasMore)
    }

    @Test
    fun `readOutboundChanges respects maxEvents and sets hasMore`() = runTest {
        val p = provider()
        val cs = changeSet("cs-001",
            changeEvent("e1"), changeEvent("e2"), changeEvent("e3"))
        p.storeOutboundChangeSet(cs)

        val result = p.readOutboundChanges(
            OutboundChangeReadRequest(syncRequest(), maxEvents = 2),
        )
        assertIs<ProviderOperationResult.Success<OutboundChangeReadResult>>(result)
        val changes = assertIs<OutboundChangeReadResult.Changes>(result.value)
        assertEquals(2, changes.changeSet.events.size)
        assertTrue(changes.hasMore)
    }

    @Test
    fun `readOutboundChanges filters by entity type`() = runTest {
        val p = provider()
        val cs = changeSet("cs-001",
            changeEvent("e1", entityType = "order"),
            changeEvent("e2", entityType = "invoice"),
            changeEvent("e3", entityType = "order"),
        )
        p.storeOutboundChangeSet(cs)

        val result = p.readOutboundChanges(
            OutboundChangeReadRequest(syncRequest(), entityTypes = setOf(EntityType("order"))),
        )
        assertIs<ProviderOperationResult.Success<OutboundChangeReadResult>>(result)
        val changes = assertIs<OutboundChangeReadResult.Changes>(result.value)
        assertEquals(2, changes.changeSet.events.size)
        assertTrue(changes.changeSet.events.all { it.entity.type.value == "order" })
    }

    // ─── Outbound: payload round-trip ─────────────────────────────────────────

    @Test
    fun `payload is preserved through store and read`() = runTest {
        val p = provider()
        val bytes = byteArrayOf(1, 2, 3, 42, 0x7F.toByte())
        val payload = DataLoomPayload(PayloadContentType("application/octet-stream"), bytes)
        val cs = changeSet("cs-001", changeEvent("e1", payload = payload))
        p.storeOutboundChangeSet(cs)

        val result = p.readOutboundChanges(OutboundChangeReadRequest(syncRequest()))
        assertIs<ProviderOperationResult.Success<OutboundChangeReadResult>>(result)
        val changes = assertIs<OutboundChangeReadResult.Changes>(result.value)
        val readPayload = changes.changeSet.events.first().payload
        assertNotNull(readPayload)
        assertTrue(bytes.contentEquals(readPayload.copyBytes()))
    }

    // ─── Acknowledgement ──────────────────────────────────────────────────────

    @Test
    fun `ACCEPTED acknowledgement removes event from outbound`() = runTest {
        val p = provider()
        val event = changeEvent("e1")
        p.storeOutboundChangeSet(changeSet("cs-001", event))

        val ack = ChangeSetAcknowledgement(
            changeSetId = ChangeSetId("cs-001"),
            events = listOf(
                ChangeEventAcknowledgement(event.id, ChangeAcknowledgementStatus.ACCEPTED),
            ),
        )
        val ackResult = p.acknowledgeOutboundChanges(
            OutboundChangeAcknowledgementRequest(syncRequest(), ack),
        )
        assertIs<ProviderOperationResult.Success<Unit>>(ackResult)

        val read = p.readOutboundChanges(OutboundChangeReadRequest(syncRequest()))
        assertEquals(
            ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges),
            read,
        )
    }

    @Test
    fun `RETRY acknowledgement keeps event for next read`() = runTest {
        val p = provider()
        val event = changeEvent("e1")
        p.storeOutboundChangeSet(changeSet("cs-001", event))

        val ack = ChangeSetAcknowledgement(
            changeSetId = ChangeSetId("cs-001"),
            events = listOf(
                ChangeEventAcknowledgement(event.id, ChangeAcknowledgementStatus.RETRY),
            ),
        )
        p.acknowledgeOutboundChanges(OutboundChangeAcknowledgementRequest(syncRequest(), ack))

        val read = p.readOutboundChanges(OutboundChangeReadRequest(syncRequest()))
        assertIs<ProviderOperationResult.Success<OutboundChangeReadResult>>(read)
        assertIs<OutboundChangeReadResult.Changes>(read.value)
    }

    @Test
    fun `REJECTED acknowledgement moves event to rejected dir`() = runTest {
        val dir = tempDir()
        val p = provider(dir)
        val event = changeEvent("e1")
        p.storeOutboundChangeSet(changeSet("cs-001", event))

        val ack = ChangeSetAcknowledgement(
            changeSetId = ChangeSetId("cs-001"),
            events = listOf(
                ChangeEventAcknowledgement(event.id, ChangeAcknowledgementStatus.REJECTED),
            ),
        )
        p.acknowledgeOutboundChanges(OutboundChangeAcknowledgementRequest(syncRequest(), ack))

        val read = p.readOutboundChanges(OutboundChangeReadRequest(syncRequest()))
        assertEquals(
            ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges),
            read,
        )
        val rejectedFile = File("$dir/rejected/e1.evt")
        assertTrue(rejectedFile.exists(), "Expected rejected event file at ${rejectedFile.absolutePath}")
    }

    // ─── readLocalConflictCandidate ──────────────────────────────────────────

    @Test
    fun `readLocalConflictCandidate returns NotFound for an untouched entity`() = runTest {
        val p = provider()

        val result = p.readLocalConflictCandidate(
            LocalConflictCandidateReadRequest(
                request = syncRequest(),
                entity = EntityReference(type = EntityType("customer"), id = EntityId("customer-untouched")),
            ),
        )

        assertEquals(
            ProviderOperationResult.Success(LocalConflictCandidateReadResult.NotFound),
            result,
        )
    }

    @Test
    fun `readLocalConflictCandidate returns the most recently stored outbound event for the entity`() = runTest {
        val p = provider()
        val entity = EntityReference(type = EntityType("customer"), id = EntityId("customer-1"))
        p.storeOutboundChangeSet(
            changeSet("cs-first", ChangeEvent(id = ChangeEventId("event-first"), entity = entity, operation = ChangeOperation.UPDATE)),
        )
        p.storeOutboundChangeSet(
            changeSet("cs-second", ChangeEvent(id = ChangeEventId("event-second"), entity = entity, operation = ChangeOperation.UPDATE)),
        )

        val result = p.readLocalConflictCandidate(
            LocalConflictCandidateReadRequest(request = syncRequest(), entity = entity),
        )

        val found = assertIs<LocalConflictCandidateReadResult.Found>(
            assertIs<ProviderOperationResult.Success<LocalConflictCandidateReadResult>>(result).value,
        )
        assertEquals("event-second", found.localChange.id.value)
    }

    @Test
    fun `readLocalConflictCandidate returns NotFound once the only outbound edit is accepted`() = runTest {
        val p = provider()
        val entity = EntityReference(type = EntityType("customer"), id = EntityId("customer-accepted"))
        val event = ChangeEvent(id = ChangeEventId("event-accepted"), entity = entity, operation = ChangeOperation.UPDATE)
        p.storeOutboundChangeSet(changeSet("cs-accepted", event))
        p.acknowledgeOutboundChanges(
            OutboundChangeAcknowledgementRequest(
                syncRequest(),
                ChangeSetAcknowledgement(
                    changeSetId = ChangeSetId("cs-accepted"),
                    events = listOf(ChangeEventAcknowledgement(event.id, ChangeAcknowledgementStatus.ACCEPTED)),
                ),
            ),
        )

        val result = p.readLocalConflictCandidate(
            LocalConflictCandidateReadRequest(request = syncRequest(), entity = entity),
        )

        assertEquals(
            ProviderOperationResult.Success(LocalConflictCandidateReadResult.NotFound),
            result,
        )
    }

    @Test
    fun `readLocalConflictCandidate does not consult rejected events`() = runTest {
        // Deliberate scope choice, documented in the provider's own KDoc: a
        // REJECTED event moves to rejected/ with no ordering information
        // relative to any other outbound entry, so it is not treated as a
        // still-live local edit to compare against.
        val p = provider()
        val entity = EntityReference(type = EntityType("customer"), id = EntityId("customer-rejected"))
        val event = ChangeEvent(id = ChangeEventId("event-rejected"), entity = entity, operation = ChangeOperation.UPDATE)
        p.storeOutboundChangeSet(changeSet("cs-rejected", event))
        p.acknowledgeOutboundChanges(
            OutboundChangeAcknowledgementRequest(
                syncRequest(),
                ChangeSetAcknowledgement(
                    changeSetId = ChangeSetId("cs-rejected"),
                    events = listOf(ChangeEventAcknowledgement(event.id, ChangeAcknowledgementStatus.REJECTED)),
                ),
            ),
        )

        val result = p.readLocalConflictCandidate(
            LocalConflictCandidateReadRequest(request = syncRequest(), entity = entity),
        )

        assertEquals(
            ProviderOperationResult.Success(LocalConflictCandidateReadResult.NotFound),
            result,
        )
    }

    @Test
    fun `readLocalConflictCandidate ignores inbound-only history for the same entity`() = runTest {
        val p = provider()
        val entity = EntityReference(type = EntityType("customer"), id = EntityId("customer-inbound-only"))
        p.applyInboundChanges(
            InboundChangeApplyRequest(
                syncRequest(),
                changeSet(
                    "inbound-only",
                    ChangeEvent(id = ChangeEventId("event-inbound"), entity = entity, operation = ChangeOperation.UPDATE),
                ),
            ),
        )

        val result = p.readLocalConflictCandidate(
            LocalConflictCandidateReadRequest(request = syncRequest(), entity = entity),
        )

        assertEquals(
            ProviderOperationResult.Success(LocalConflictCandidateReadResult.NotFound),
            result,
        )
    }

    // ─── Inbound ─────────────────────────────────────────────────────────────

    @Test
    fun `applyInboundChanges stores batch file`() = runTest {
        val dir = tempDir()
        val p = provider(dir)
        val cs = changeSet("inbound-cs-001", changeEvent("ie1"), changeEvent("ie2"))
        val result = p.applyInboundChanges(
            InboundChangeApplyRequest(syncRequest(), cs),
        )
        assertIs<ProviderOperationResult.Success<Unit>>(result)

        val batchFile = File("$dir/inbound/inbound-cs-001.batch")
        assertTrue(batchFile.exists())
    }

    // ─── Checkpoint ──────────────────────────────────────────────────────────

    @Test
    fun `readCheckpoint returns null when no checkpoint stored`() = runTest {
        val p = provider()
        val result = p.readCheckpoint(
            CheckpointReadRequest(syncRequest(), CheckpointKey("stream-1")),
        )
        assertIs<ProviderOperationResult.Success<SynchronizationCheckpoint?>>(result)
        assertNull(result.value)
    }

    @Test
    fun `writeCheckpoint and readCheckpoint round-trip`() = runTest {
        val p = provider()
        val key = CheckpointKey("stream-1")
        val token = CheckpointToken("tok-42")
        val checkpoint = SynchronizationCheckpoint(key, token)

        p.writeCheckpoint(CheckpointWriteRequest(syncRequest(), checkpoint))

        val readResult = p.readCheckpoint(CheckpointReadRequest(syncRequest(), key))
        assertIs<ProviderOperationResult.Success<SynchronizationCheckpoint?>>(readResult)
        assertNotNull(readResult.value)
        assertEquals(key, readResult.value!!.key)
        assertEquals(token, readResult.value!!.token)
    }

    @Test
    fun `writeCheckpoint preserves metadata`() = runTest {
        val p = provider()
        val key = CheckpointKey("stream-meta")
        val token = CheckpointToken("tok-99")
        val metadata = DataLoomMetadata.of(mapOf("source" to "integration-test", "version" to "2"))
        val checkpoint = SynchronizationCheckpoint(key, token, metadata)

        p.writeCheckpoint(CheckpointWriteRequest(syncRequest(), checkpoint))

        val readResult = p.readCheckpoint(CheckpointReadRequest(syncRequest(), key))
        val read = assertIs<ProviderOperationResult.Success<SynchronizationCheckpoint?>>(readResult)
        assertEquals("integration-test", read.value!!.metadata["source"])
        assertEquals("2", read.value!!.metadata["version"])
    }

    @Test
    fun `writeCheckpoint overwrites previous checkpoint for same key`() = runTest {
        val p = provider()
        val key = CheckpointKey("stream-x")

        p.writeCheckpoint(CheckpointWriteRequest(syncRequest(), SynchronizationCheckpoint(key, CheckpointToken("tok-1"))))
        p.writeCheckpoint(CheckpointWriteRequest(syncRequest(), SynchronizationCheckpoint(key, CheckpointToken("tok-2"))))

        val read = p.readCheckpoint(CheckpointReadRequest(syncRequest(), key))
        assertIs<ProviderOperationResult.Success<SynchronizationCheckpoint?>>(read)
        assertEquals(CheckpointToken("tok-2"), read.value!!.token)
    }

    // ─── Crash-durability ────────────────────────────────────────────────────

    /**
     * Verifies that an event file written to the outbound directory but
     * **not yet added to the index** (simulating a crash between the file
     * write and the index update) does not appear in [readOutboundChanges].
     *
     * This proves the "index is source of truth" invariant: orphaned event
     * files are ignored and cannot corrupt already-committed records.
     */
    @Test
    fun `orphaned event file not in index is ignored on read`() = runTest {
        val dir = tempDir()
        val p = provider(dir)
        val committed = changeEvent("committed")
        p.storeOutboundChangeSet(changeSet("cs-ok", committed))

        // Simulate a crash: write a raw event file without updating the index.
        val orphanFile = File("$dir/outbound/orphan.evt")
        orphanFile.writeText("DATALOOM_EVT_V1\neventId=orphan\n")

        val result = p.readOutboundChanges(OutboundChangeReadRequest(syncRequest()))
        assertIs<ProviderOperationResult.Success<OutboundChangeReadResult>>(result)
        val changes = assertIs<OutboundChangeReadResult.Changes>(result.value)
        assertEquals(1, changes.changeSet.events.size)
        assertEquals("committed", changes.changeSet.events.first().id.value)
    }

    /**
     * Verifies that if an event listed in the index has a corrupt file
     * (simulating an interrupted partial write), the remaining committed
     * events are still readable and the index is automatically repaired.
     */
    @Test
    fun `corrupt event file listed in index is skipped and index is repaired`() = runTest {
        val dir = tempDir()
        val p = provider(dir)
        p.storeOutboundChangeSet(changeSet("cs-001",
            changeEvent("e1"),
            changeEvent("e2"),
        ))

        // Corrupt e1's file.
        val corruptFile = File("$dir/outbound/e1.evt")
        corruptFile.writeText("CORRUPT_DATA")

        val result = p.readOutboundChanges(OutboundChangeReadRequest(syncRequest()))
        assertIs<ProviderOperationResult.Success<OutboundChangeReadResult>>(result)
        val changes = assertIs<OutboundChangeReadResult.Changes>(result.value)
        assertEquals(1, changes.changeSet.events.size)
        assertEquals("e2", changes.changeSet.events.first().id.value)
    }

    // ─── Entity version round-trip ────────────────────────────────────────────

    @Test
    fun `entity version is preserved through store and read`() = runTest {
        val p = provider()
        val event = ChangeEvent(
            id = ChangeEventId("e-ver"),
            entity = EntityReference(
                type = EntityType("product"),
                id = EntityId("prod-1"),
                version = EntityVersion("rev-42"),
            ),
            operation = ChangeOperation.UPDATE,
        )
        p.storeOutboundChangeSet(changeSet("cs-ver", event))

        val result = p.readOutboundChanges(OutboundChangeReadRequest(syncRequest()))
        assertIs<ProviderOperationResult.Success<OutboundChangeReadResult>>(result)
        val changes = assertIs<OutboundChangeReadResult.Changes>(result.value)
        assertEquals("rev-42", changes.changeSet.events.first().entity.version?.value)
    }

    // ─── Multiple change sets ─────────────────────────────────────────────────

    @Test
    fun `multiple stored change sets are read in insertion order`() = runTest {
        val p = provider()
        p.storeOutboundChangeSet(changeSet("cs-A", changeEvent("e1")))
        p.storeOutboundChangeSet(changeSet("cs-B", changeEvent("e2")))

        val result = p.readOutboundChanges(OutboundChangeReadRequest(syncRequest()))
        assertIs<ProviderOperationResult.Success<OutboundChangeReadResult>>(result)
        val changes = assertIs<OutboundChangeReadResult.Changes>(result.value)
        assertEquals(2, changes.changeSet.events.size)
        assertEquals("e1", changes.changeSet.events[0].id.value)
        assertEquals("e2", changes.changeSet.events[1].id.value)
    }
}
