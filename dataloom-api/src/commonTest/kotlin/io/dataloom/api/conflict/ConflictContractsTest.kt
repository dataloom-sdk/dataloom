package io.dataloom.api.conflict

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.EntityReference
import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ConflictDetectorId
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.ConflictResolverId
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
import io.dataloom.api.payload.EntityVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ConflictContractsTest {

    // -------------------------------------------------------------------------
    // Test fixtures
    // -------------------------------------------------------------------------

    private val invoiceType: EntityType = EntityType("invoice")
    private val paymentType: EntityType = EntityType("payment")
    private val entityId: EntityId = EntityId("entity-001")
    private val otherEntityId: EntityId = EntityId("entity-002")
    private val versionV1: EntityVersion = EntityVersion("v1")
    private val versionV2: EntityVersion = EntityVersion("v2")

    private val invoiceRef: EntityReference = EntityReference(invoiceType, entityId)
    private val invoiceRefV1: EntityReference = EntityReference(invoiceType, entityId, versionV1)
    private val invoiceRefV2: EntityReference = EntityReference(invoiceType, entityId, versionV2)
    private val otherEntityRef: EntityReference = EntityReference(invoiceType, otherEntityId)
    private val otherTypeRef: EntityReference = EntityReference(paymentType, entityId)

    private val localEvent: ChangeEvent = ChangeEvent(
        id = ChangeEventId("event-local"),
        entity = invoiceRefV1,
        operation = ChangeOperation.UPDATE,
    )
    private val remoteEvent: ChangeEvent = ChangeEvent(
        id = ChangeEventId("event-remote"),
        entity = invoiceRefV2,
        operation = ChangeOperation.UPDATE,
    )

    private val conflictId: ConflictId = ConflictId("conflict-001")

    private val sampleConflict: SynchronizationConflict = SynchronizationConflict(
        id = conflictId,
        type = ConflictType.CONCURRENT_CHANGE,
        entity = invoiceRef,
        localChange = localEvent,
        remoteChange = remoteEvent,
    )

    private val syncRequest: SynchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("workflow-001"),
        sessionId = SynchronizationSessionId("session-001"),
        direction = SynchronizationDirection.BIDIRECTIONAL,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("execution-001"),
            correlationId = CorrelationId("corr-001"),
        ),
    )

    // -------------------------------------------------------------------------
    // ConflictId identifier
    // -------------------------------------------------------------------------

    @Test
    fun `conflict id accepts valid value`() {
        val id: ConflictId = ConflictId("conflict-001")
        assertEquals("conflict-001", id.value)
    }

    @Test
    fun `conflict id rejects blank value`() {
        assertFailsWith<IllegalArgumentException> { ConflictId("") }
    }

    @Test
    fun `conflict id rejects whitespace-only value`() {
        assertFailsWith<IllegalArgumentException> { ConflictId("   ") }
    }

    @Test
    fun `conflict id preserves exact value`() {
        val raw = "conflict-exact-value"
        val id: ConflictId = ConflictId(raw)
        assertEquals(raw, id.value)
    }

    @Test
    fun `equal conflict ids compare as equal`() {
        val a: ConflictId = ConflictId("conflict-001")
        val b: ConflictId = ConflictId("conflict-001")
        assertEquals(a, b)
    }

    @Test
    fun `different conflict ids compare as unequal`() {
        val a: ConflictId = ConflictId("conflict-001")
        val b: ConflictId = ConflictId("conflict-002")
        assertNotEquals(a, b)
    }

    @Test
    fun `conflict id toString returns wrapped value`() {
        val id: ConflictId = ConflictId("conflict-tostring")
        assertEquals("conflict-tostring", id.toString())
    }

    // -------------------------------------------------------------------------
    // ConflictDetectorId identifier
    // -------------------------------------------------------------------------

    @Test
    fun `conflict detector id accepts valid value`() {
        val id: ConflictDetectorId = ConflictDetectorId("entity-version-detector")
        assertEquals("entity-version-detector", id.value)
    }

    @Test
    fun `conflict detector id rejects blank value`() {
        assertFailsWith<IllegalArgumentException> { ConflictDetectorId("") }
    }

    @Test
    fun `conflict detector id rejects whitespace-only value`() {
        assertFailsWith<IllegalArgumentException> { ConflictDetectorId("   ") }
    }

    @Test
    fun `conflict detector id preserves exact value`() {
        val raw = "application-order-detector"
        val id: ConflictDetectorId = ConflictDetectorId(raw)
        assertEquals(raw, id.value)
    }

    @Test
    fun `equal conflict detector ids compare as equal`() {
        val a: ConflictDetectorId = ConflictDetectorId("detector-001")
        val b: ConflictDetectorId = ConflictDetectorId("detector-001")
        assertEquals(a, b)
    }

    @Test
    fun `different conflict detector ids compare as unequal`() {
        val a: ConflictDetectorId = ConflictDetectorId("detector-001")
        val b: ConflictDetectorId = ConflictDetectorId("detector-002")
        assertNotEquals(a, b)
    }

    @Test
    fun `conflict detector id toString returns wrapped value`() {
        val id: ConflictDetectorId = ConflictDetectorId("default-conflict-detector")
        assertEquals("default-conflict-detector", id.toString())
    }

    // -------------------------------------------------------------------------
    // ConflictResolverId identifier
    // -------------------------------------------------------------------------

    @Test
    fun `conflict resolver id accepts valid value`() {
        val id: ConflictResolverId = ConflictResolverId("client-preferred-resolver")
        assertEquals("client-preferred-resolver", id.value)
    }

    @Test
    fun `conflict resolver id rejects blank value`() {
        assertFailsWith<IllegalArgumentException> { ConflictResolverId("") }
    }

    @Test
    fun `conflict resolver id rejects whitespace-only value`() {
        assertFailsWith<IllegalArgumentException> { ConflictResolverId("   ") }
    }

    @Test
    fun `conflict resolver id preserves exact value`() {
        val raw = "application-merge-resolver"
        val id: ConflictResolverId = ConflictResolverId(raw)
        assertEquals(raw, id.value)
    }

    @Test
    fun `equal conflict resolver ids compare as equal`() {
        val a: ConflictResolverId = ConflictResolverId("resolver-001")
        val b: ConflictResolverId = ConflictResolverId("resolver-001")
        assertEquals(a, b)
    }

    @Test
    fun `different conflict resolver ids compare as unequal`() {
        val a: ConflictResolverId = ConflictResolverId("resolver-001")
        val b: ConflictResolverId = ConflictResolverId("resolver-002")
        assertNotEquals(a, b)
    }

    @Test
    fun `conflict resolver id toString returns wrapped value`() {
        val id: ConflictResolverId = ConflictResolverId("server-preferred-resolver")
        assertEquals("server-preferred-resolver", id.toString())
    }

    // -------------------------------------------------------------------------
    // ConflictType
    // -------------------------------------------------------------------------

    @Test
    fun `concurrent change conflict type exists`() {
        val type: ConflictType = ConflictType.CONCURRENT_CHANGE
        assertEquals(ConflictType.CONCURRENT_CHANGE, type)
    }

    @Test
    fun `version mismatch conflict type exists`() {
        val type: ConflictType = ConflictType.VERSION_MISMATCH
        assertEquals(ConflictType.VERSION_MISMATCH, type)
    }

    @Test
    fun `update delete conflict type exists`() {
        val type: ConflictType = ConflictType.UPDATE_DELETE
        assertEquals(ConflictType.UPDATE_DELETE, type)
    }

    @Test
    fun `delete update conflict type exists`() {
        val type: ConflictType = ConflictType.DELETE_UPDATE
        assertEquals(ConflictType.DELETE_UPDATE, type)
    }

    @Test
    fun `create collision conflict type exists`() {
        val type: ConflictType = ConflictType.CREATE_COLLISION
        assertEquals(ConflictType.CREATE_COLLISION, type)
    }

    @Test
    fun `custom conflict type exists`() {
        val type: ConflictType = ConflictType.CUSTOM
        assertEquals(ConflictType.CUSTOM, type)
    }

    @Test
    fun `all required conflict types are distinct`() {
        val types: Set<ConflictType> = setOf(
            ConflictType.CONCURRENT_CHANGE,
            ConflictType.VERSION_MISMATCH,
            ConflictType.UPDATE_DELETE,
            ConflictType.DELETE_UPDATE,
            ConflictType.CREATE_COLLISION,
            ConflictType.CUSTOM,
        )
        assertEquals(6, types.size)
    }

    @Test
    fun `conflict types can be compared by name without relying on ordinal`() {
        assertEquals("CONCURRENT_CHANGE", ConflictType.CONCURRENT_CHANGE.name)
        assertEquals("VERSION_MISMATCH", ConflictType.VERSION_MISMATCH.name)
        assertEquals("UPDATE_DELETE", ConflictType.UPDATE_DELETE.name)
        assertEquals("DELETE_UPDATE", ConflictType.DELETE_UPDATE.name)
        assertEquals("CREATE_COLLISION", ConflictType.CREATE_COLLISION.name)
        assertEquals("CUSTOM", ConflictType.CUSTOM.name)
    }

    // -------------------------------------------------------------------------
    // SynchronizationConflict
    // -------------------------------------------------------------------------

    @Test
    fun `synchronization conflict preserves required properties`() {
        val conflict: SynchronizationConflict = SynchronizationConflict(
            id = conflictId,
            type = ConflictType.CONCURRENT_CHANGE,
            entity = invoiceRef,
            localChange = localEvent,
            remoteChange = remoteEvent,
        )

        assertEquals(conflictId, conflict.id)
        assertEquals(ConflictType.CONCURRENT_CHANGE, conflict.type)
        assertEquals(invoiceRef, conflict.entity)
        assertEquals(localEvent, conflict.localChange)
        assertEquals(remoteEvent, conflict.remoteChange)
    }

    @Test
    fun `synchronization conflict metadata defaults to empty`() {
        val conflict: SynchronizationConflict = SynchronizationConflict(
            id = conflictId,
            type = ConflictType.CONCURRENT_CHANGE,
            entity = invoiceRef,
            localChange = localEvent,
            remoteChange = remoteEvent,
        )

        assertTrue(conflict.metadata.isEmpty())
    }

    @Test
    fun `synchronization conflict preserves supplied metadata`() {
        val metadata: DataLoomMetadata = DataLoomMetadata.of(mapOf("source" to "host-app"))
        val conflict: SynchronizationConflict = SynchronizationConflict(
            id = conflictId,
            type = ConflictType.CONCURRENT_CHANGE,
            entity = invoiceRef,
            localChange = localEvent,
            remoteChange = remoteEvent,
            metadata = metadata,
        )

        assertEquals(metadata, conflict.metadata)
    }

    @Test
    fun `equal synchronization conflicts compare as equal`() {
        val a: SynchronizationConflict = SynchronizationConflict(
            id = conflictId,
            type = ConflictType.CONCURRENT_CHANGE,
            entity = invoiceRef,
            localChange = localEvent,
            remoteChange = remoteEvent,
        )
        val b: SynchronizationConflict = SynchronizationConflict(
            id = conflictId,
            type = ConflictType.CONCURRENT_CHANGE,
            entity = invoiceRef,
            localChange = localEvent,
            remoteChange = remoteEvent,
        )

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `synchronization conflict accepts same entity type and id with different versions`() {
        val localWithV1: ChangeEvent = ChangeEvent(
            id = ChangeEventId("event-local"),
            entity = invoiceRefV1,
            operation = ChangeOperation.UPDATE,
        )
        val remoteWithV2: ChangeEvent = ChangeEvent(
            id = ChangeEventId("event-remote"),
            entity = invoiceRefV2,
            operation = ChangeOperation.UPDATE,
        )

        val conflict: SynchronizationConflict = SynchronizationConflict(
            id = conflictId,
            type = ConflictType.VERSION_MISMATCH,
            entity = invoiceRef,
            localChange = localWithV1,
            remoteChange = remoteWithV2,
        )

        assertEquals(invoiceRefV1, conflict.localChange.entity)
        assertEquals(invoiceRefV2, conflict.remoteChange.entity)
    }

    @Test
    fun `synchronization conflict rejects changes for different entity ids`() {
        val remoteWithDifferentId: ChangeEvent = ChangeEvent(
            id = ChangeEventId("event-remote"),
            entity = otherEntityRef,
            operation = ChangeOperation.UPDATE,
        )

        assertFailsWith<IllegalArgumentException> {
            SynchronizationConflict(
                id = conflictId,
                type = ConflictType.CONCURRENT_CHANGE,
                entity = invoiceRef,
                localChange = localEvent,
                remoteChange = remoteWithDifferentId,
            )
        }
    }

    @Test
    fun `synchronization conflict rejects changes for different entity types`() {
        val remoteWithDifferentType: ChangeEvent = ChangeEvent(
            id = ChangeEventId("event-remote"),
            entity = otherTypeRef,
            operation = ChangeOperation.UPDATE,
        )

        assertFailsWith<IllegalArgumentException> {
            SynchronizationConflict(
                id = conflictId,
                type = ConflictType.CONCURRENT_CHANGE,
                entity = invoiceRef,
                localChange = localEvent,
                remoteChange = remoteWithDifferentType,
            )
        }
    }

    @Test
    fun `synchronization conflict rejects entity that does not match change entity type`() {
        assertFailsWith<IllegalArgumentException> {
            SynchronizationConflict(
                id = conflictId,
                type = ConflictType.CONCURRENT_CHANGE,
                entity = otherTypeRef,
                localChange = localEvent,
                remoteChange = remoteEvent,
            )
        }
    }

    @Test
    fun `synchronization conflict rejects entity that does not match change entity id`() {
        assertFailsWith<IllegalArgumentException> {
            SynchronizationConflict(
                id = conflictId,
                type = ConflictType.CONCURRENT_CHANGE,
                entity = otherEntityRef,
                localChange = localEvent,
                remoteChange = remoteEvent,
            )
        }
    }

    @Test
    fun `synchronization conflict construction performs no resolution`() {
        // Verify construction completes without side effects — no exception means no runtime action.
        val conflict: SynchronizationConflict = SynchronizationConflict(
            id = conflictId,
            type = ConflictType.CONCURRENT_CHANGE,
            entity = invoiceRef,
            localChange = localEvent,
            remoteChange = remoteEvent,
        )
        assertEquals(conflictId, conflict.id)
    }

    // -------------------------------------------------------------------------
    // ConflictDetectionRequest
    // -------------------------------------------------------------------------

    @Test
    fun `conflict detection request preserves synchronization request`() {
        val request: ConflictDetectionRequest = ConflictDetectionRequest(
            synchronizationRequest = syncRequest,
            localChange = localEvent,
            remoteChange = remoteEvent,
        )

        assertEquals(syncRequest, request.synchronizationRequest)
    }

    @Test
    fun `conflict detection request preserves local and remote changes`() {
        val request: ConflictDetectionRequest = ConflictDetectionRequest(
            synchronizationRequest = syncRequest,
            localChange = localEvent,
            remoteChange = remoteEvent,
        )

        assertEquals(localEvent, request.localChange)
        assertEquals(remoteEvent, request.remoteChange)
    }

    @Test
    fun `conflict detection request metadata defaults to empty`() {
        val request: ConflictDetectionRequest = ConflictDetectionRequest(
            synchronizationRequest = syncRequest,
            localChange = localEvent,
            remoteChange = remoteEvent,
        )

        assertTrue(request.metadata.isEmpty())
    }

    @Test
    fun `conflict detection request preserves supplied metadata`() {
        val metadata: DataLoomMetadata = DataLoomMetadata.of(mapOf("channel" to "manual"))
        val request: ConflictDetectionRequest = ConflictDetectionRequest(
            synchronizationRequest = syncRequest,
            localChange = localEvent,
            remoteChange = remoteEvent,
            metadata = metadata,
        )

        assertEquals(metadata, request.metadata)
    }

    @Test
    fun `conflict detection request rejects changes for different entity ids`() {
        val remoteWithDifferentId: ChangeEvent = ChangeEvent(
            id = ChangeEventId("event-remote"),
            entity = otherEntityRef,
            operation = ChangeOperation.UPDATE,
        )

        assertFailsWith<IllegalArgumentException> {
            ConflictDetectionRequest(
                synchronizationRequest = syncRequest,
                localChange = localEvent,
                remoteChange = remoteWithDifferentId,
            )
        }
    }

    @Test
    fun `conflict detection request rejects changes for different entity types`() {
        val remoteWithDifferentType: ChangeEvent = ChangeEvent(
            id = ChangeEventId("event-remote"),
            entity = otherTypeRef,
            operation = ChangeOperation.UPDATE,
        )

        assertFailsWith<IllegalArgumentException> {
            ConflictDetectionRequest(
                synchronizationRequest = syncRequest,
                localChange = localEvent,
                remoteChange = remoteWithDifferentType,
            )
        }
    }

    @Test
    fun `equal conflict detection requests compare as equal`() {
        val a: ConflictDetectionRequest = ConflictDetectionRequest(
            synchronizationRequest = syncRequest,
            localChange = localEvent,
            remoteChange = remoteEvent,
        )
        val b: ConflictDetectionRequest = ConflictDetectionRequest(
            synchronizationRequest = syncRequest,
            localChange = localEvent,
            remoteChange = remoteEvent,
        )

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `conflict detection request construction performs no detection`() {
        // Verify construction completes without side effects.
        val request: ConflictDetectionRequest = ConflictDetectionRequest(
            synchronizationRequest = syncRequest,
            localChange = localEvent,
            remoteChange = remoteEvent,
        )
        assertEquals(localEvent, request.localChange)
    }

    // -------------------------------------------------------------------------
    // ConflictDetectionResult
    // -------------------------------------------------------------------------

    @Test
    fun `no conflict result is representable`() {
        val result: ConflictDetectionResult = ConflictDetectionResult.NoConflict
        assertIs<ConflictDetectionResult.NoConflict>(result)
    }

    @Test
    fun `conflict detected result preserves conflict`() {
        val result: ConflictDetectionResult = ConflictDetectionResult.ConflictDetected(
            conflict = sampleConflict,
        )

        assertIs<ConflictDetectionResult.ConflictDetected>(result)
        assertEquals(sampleConflict, result.conflict)
    }

    @Test
    fun `no conflict and conflict detected results remain distinct`() {
        val noConflict: ConflictDetectionResult = ConflictDetectionResult.NoConflict
        val conflictDetected: ConflictDetectionResult = ConflictDetectionResult.ConflictDetected(
            conflict = sampleConflict,
        )

        assertNotEquals(noConflict, conflictDetected)
    }

    @Test
    fun `equal conflict detected results compare as equal`() {
        val a: ConflictDetectionResult.ConflictDetected = ConflictDetectionResult.ConflictDetected(
            conflict = sampleConflict,
        )
        val b: ConflictDetectionResult.ConflictDetected = ConflictDetectionResult.ConflictDetected(
            conflict = sampleConflict,
        )

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    // -------------------------------------------------------------------------
    // ConflictResolutionRequest
    // -------------------------------------------------------------------------

    @Test
    fun `conflict resolution request preserves synchronization request`() {
        val request: ConflictResolutionRequest = ConflictResolutionRequest(
            synchronizationRequest = syncRequest,
            conflict = sampleConflict,
        )

        assertEquals(syncRequest, request.synchronizationRequest)
    }

    @Test
    fun `conflict resolution request preserves conflict`() {
        val request: ConflictResolutionRequest = ConflictResolutionRequest(
            synchronizationRequest = syncRequest,
            conflict = sampleConflict,
        )

        assertEquals(sampleConflict, request.conflict)
    }

    @Test
    fun `conflict resolution request metadata defaults to empty`() {
        val request: ConflictResolutionRequest = ConflictResolutionRequest(
            synchronizationRequest = syncRequest,
            conflict = sampleConflict,
        )

        assertTrue(request.metadata.isEmpty())
    }

    @Test
    fun `conflict resolution request preserves supplied metadata`() {
        val metadata: DataLoomMetadata = DataLoomMetadata.of(mapOf("resolver" to "domain"))
        val request: ConflictResolutionRequest = ConflictResolutionRequest(
            synchronizationRequest = syncRequest,
            conflict = sampleConflict,
            metadata = metadata,
        )

        assertEquals(metadata, request.metadata)
    }

    @Test
    fun `equal conflict resolution requests compare as equal`() {
        val a: ConflictResolutionRequest = ConflictResolutionRequest(
            synchronizationRequest = syncRequest,
            conflict = sampleConflict,
        )
        val b: ConflictResolutionRequest = ConflictResolutionRequest(
            synchronizationRequest = syncRequest,
            conflict = sampleConflict,
        )

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `conflict resolution request construction performs no resolution`() {
        // Verify construction completes without side effects.
        val request: ConflictResolutionRequest = ConflictResolutionRequest(
            synchronizationRequest = syncRequest,
            conflict = sampleConflict,
        )
        assertEquals(sampleConflict, request.conflict)
    }

    // -------------------------------------------------------------------------
    // ConflictResolutionDecision — UseLocal
    // -------------------------------------------------------------------------

    @Test
    fun `use local decision is representable`() {
        val decision: ConflictResolutionDecision = ConflictResolutionDecision.UseLocal()
        assertIs<ConflictResolutionDecision.UseLocal>(decision)
    }

    @Test
    fun `use local decision metadata defaults to empty`() {
        val decision: ConflictResolutionDecision.UseLocal = ConflictResolutionDecision.UseLocal()
        assertTrue(decision.metadata.isEmpty())
    }

    @Test
    fun `use local decision preserves supplied metadata`() {
        val metadata: DataLoomMetadata = DataLoomMetadata.of(mapOf("reason" to "local-policy"))
        val decision: ConflictResolutionDecision.UseLocal = ConflictResolutionDecision.UseLocal(metadata)
        assertEquals(metadata, decision.metadata)
    }

    @Test
    fun `equal use local decisions compare as equal`() {
        val a: ConflictResolutionDecision.UseLocal = ConflictResolutionDecision.UseLocal()
        val b: ConflictResolutionDecision.UseLocal = ConflictResolutionDecision.UseLocal()
        assertEquals(a, b)
    }

    // -------------------------------------------------------------------------
    // ConflictResolutionDecision — UseRemote
    // -------------------------------------------------------------------------

    @Test
    fun `use remote decision is representable`() {
        val decision: ConflictResolutionDecision = ConflictResolutionDecision.UseRemote()
        assertIs<ConflictResolutionDecision.UseRemote>(decision)
    }

    @Test
    fun `use remote decision metadata defaults to empty`() {
        val decision: ConflictResolutionDecision.UseRemote = ConflictResolutionDecision.UseRemote()
        assertTrue(decision.metadata.isEmpty())
    }

    @Test
    fun `use remote decision preserves supplied metadata`() {
        val metadata: DataLoomMetadata = DataLoomMetadata.of(mapOf("reason" to "remote-policy"))
        val decision: ConflictResolutionDecision.UseRemote = ConflictResolutionDecision.UseRemote(metadata)
        assertEquals(metadata, decision.metadata)
    }

    @Test
    fun `equal use remote decisions compare as equal`() {
        val a: ConflictResolutionDecision.UseRemote = ConflictResolutionDecision.UseRemote()
        val b: ConflictResolutionDecision.UseRemote = ConflictResolutionDecision.UseRemote()
        assertEquals(a, b)
    }

    // -------------------------------------------------------------------------
    // ConflictResolutionDecision — Merge
    // -------------------------------------------------------------------------

    @Test
    fun `merge decision preserves resolved change`() {
        val resolvedEvent: ChangeEvent = ChangeEvent(
            id = ChangeEventId("event-merged"),
            entity = invoiceRef,
            operation = ChangeOperation.UPDATE,
        )
        val decision: ConflictResolutionDecision.Merge = ConflictResolutionDecision.Merge(
            expectedEntity = invoiceRef,
            resolvedChange = resolvedEvent,
        )

        assertEquals(resolvedEvent, decision.resolvedChange)
    }

    @Test
    fun `merge decision metadata defaults to empty`() {
        val resolvedEvent: ChangeEvent = ChangeEvent(
            id = ChangeEventId("event-merged"),
            entity = invoiceRef,
            operation = ChangeOperation.UPDATE,
        )
        val decision: ConflictResolutionDecision.Merge = ConflictResolutionDecision.Merge(
            expectedEntity = invoiceRef,
            resolvedChange = resolvedEvent,
        )

        assertTrue(decision.metadata.isEmpty())
    }

    @Test
    fun `merge decision accepts resolved change with different entity version`() {
        val resolvedWithNewVersion: ChangeEvent = ChangeEvent(
            id = ChangeEventId("event-merged"),
            entity = EntityReference(invoiceType, entityId, EntityVersion("v-merged")),
            operation = ChangeOperation.UPDATE,
        )

        val decision: ConflictResolutionDecision.Merge = ConflictResolutionDecision.Merge(
            expectedEntity = invoiceRef,
            resolvedChange = resolvedWithNewVersion,
        )

        assertEquals(resolvedWithNewVersion, decision.resolvedChange)
    }

    @Test
    fun `merge decision rejects resolved change for different entity id`() {
        val wrongEntityEvent: ChangeEvent = ChangeEvent(
            id = ChangeEventId("event-wrong"),
            entity = otherEntityRef,
            operation = ChangeOperation.UPDATE,
        )

        assertFailsWith<IllegalArgumentException> {
            ConflictResolutionDecision.Merge(
                expectedEntity = invoiceRef,
                resolvedChange = wrongEntityEvent,
            )
        }
    }

    @Test
    fun `merge decision rejects resolved change for different entity type`() {
        val wrongTypeEvent: ChangeEvent = ChangeEvent(
            id = ChangeEventId("event-wrong"),
            entity = otherTypeRef,
            operation = ChangeOperation.UPDATE,
        )

        assertFailsWith<IllegalArgumentException> {
            ConflictResolutionDecision.Merge(
                expectedEntity = invoiceRef,
                resolvedChange = wrongTypeEvent,
            )
        }
    }

    @Test
    fun `equal merge decisions compare as equal`() {
        val resolvedEvent: ChangeEvent = ChangeEvent(
            id = ChangeEventId("event-merged"),
            entity = invoiceRef,
            operation = ChangeOperation.UPDATE,
        )
        val a: ConflictResolutionDecision.Merge = ConflictResolutionDecision.Merge(
            expectedEntity = invoiceRef,
            resolvedChange = resolvedEvent,
        )
        val b: ConflictResolutionDecision.Merge = ConflictResolutionDecision.Merge(
            expectedEntity = invoiceRef,
            resolvedChange = resolvedEvent,
        )

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    // -------------------------------------------------------------------------
    // ConflictResolutionDecision — Defer
    // -------------------------------------------------------------------------

    @Test
    fun `defer decision is representable`() {
        val decision: ConflictResolutionDecision = ConflictResolutionDecision.Defer()
        assertIs<ConflictResolutionDecision.Defer>(decision)
    }

    @Test
    fun `defer decision metadata defaults to empty`() {
        val decision: ConflictResolutionDecision.Defer = ConflictResolutionDecision.Defer()
        assertTrue(decision.metadata.isEmpty())
    }

    @Test
    fun `defer decision preserves supplied metadata`() {
        val metadata: DataLoomMetadata = DataLoomMetadata.of(mapOf("reason" to "requires-review"))
        val decision: ConflictResolutionDecision.Defer = ConflictResolutionDecision.Defer(metadata)
        assertEquals(metadata, decision.metadata)
    }

    @Test
    fun `equal defer decisions compare as equal`() {
        val a: ConflictResolutionDecision.Defer = ConflictResolutionDecision.Defer()
        val b: ConflictResolutionDecision.Defer = ConflictResolutionDecision.Defer()
        assertEquals(a, b)
    }

    // -------------------------------------------------------------------------
    // ConflictResolutionDecision — Fail
    // -------------------------------------------------------------------------

    @Test
    fun `fail decision is representable`() {
        val error: DataLoomError = TestDataLoomError(
            code = ErrorCode("DL-CONFLICT-001"),
            category = ErrorCategory.CONFLICT,
            severity = ErrorSeverity.ERROR,
            recoverability = Recoverability.NON_RECOVERABLE,
            message = "Conflict cannot be resolved under current policy.",
            cause = null,
        )
        val decision: ConflictResolutionDecision = ConflictResolutionDecision.Fail(error = error)
        assertIs<ConflictResolutionDecision.Fail>(decision)
    }

    @Test
    fun `fail decision preserves canonical error`() {
        val error: DataLoomError = TestDataLoomError(
            code = ErrorCode("DL-CONFLICT-001"),
            category = ErrorCategory.CONFLICT,
            severity = ErrorSeverity.ERROR,
            recoverability = Recoverability.NON_RECOVERABLE,
            message = "Conflict cannot be resolved under current policy.",
            cause = null,
        )
        val decision: ConflictResolutionDecision.Fail = ConflictResolutionDecision.Fail(error = error)
        assertEquals(error, decision.error)
    }

    @Test
    fun `fail decision metadata defaults to empty`() {
        val error: DataLoomError = TestDataLoomError(
            code = ErrorCode("DL-CONFLICT-001"),
            category = ErrorCategory.CONFLICT,
            severity = ErrorSeverity.ERROR,
            recoverability = Recoverability.NON_RECOVERABLE,
            message = "Unresolvable conflict.",
            cause = null,
        )
        val decision: ConflictResolutionDecision.Fail = ConflictResolutionDecision.Fail(error = error)
        assertTrue(decision.metadata.isEmpty())
    }

    // -------------------------------------------------------------------------
    // ConflictResolutionDecision variants remain distinct
    // -------------------------------------------------------------------------

    @Test
    fun `conflict resolution decision variants are distinct`() {
        val resolvedEvent: ChangeEvent = ChangeEvent(
            id = ChangeEventId("event-merged"),
            entity = invoiceRef,
            operation = ChangeOperation.UPDATE,
        )
        val error: DataLoomError = TestDataLoomError(
            code = ErrorCode("DL-CONFLICT-001"),
            category = ErrorCategory.CONFLICT,
            severity = ErrorSeverity.ERROR,
            recoverability = Recoverability.NON_RECOVERABLE,
            message = "Unresolvable.",
            cause = null,
        )

        val useLocal: ConflictResolutionDecision = ConflictResolutionDecision.UseLocal()
        val useRemote: ConflictResolutionDecision = ConflictResolutionDecision.UseRemote()
        val merge: ConflictResolutionDecision = ConflictResolutionDecision.Merge(
            expectedEntity = invoiceRef,
            resolvedChange = resolvedEvent,
        )
        val defer: ConflictResolutionDecision = ConflictResolutionDecision.Defer()
        val fail: ConflictResolutionDecision = ConflictResolutionDecision.Fail(error = error)

        assertNotEquals(useLocal, useRemote)
        assertNotEquals(useLocal, merge)
        assertNotEquals(useLocal, defer)
        assertNotEquals(useLocal, fail)
        assertNotEquals(useRemote, merge)
        assertNotEquals(useRemote, defer)
        assertNotEquals(useRemote, fail)
        assertNotEquals(merge, defer)
        assertNotEquals(merge, fail)
        assertNotEquals(defer, fail)
    }

    @Test
    fun `conflict resolution decision construction performs no runtime action`() {
        // Verify construction completes without side effects.
        val decision: ConflictResolutionDecision = ConflictResolutionDecision.UseLocal()
        assertIs<ConflictResolutionDecision.UseLocal>(decision)
    }

    // -------------------------------------------------------------------------
    // ConflictDetector interface
    // -------------------------------------------------------------------------

    @Test
    fun `fake detector exposes its id`() {
        val detector: ConflictDetector = FakeDetector(
            detectorId = ConflictDetectorId("entity-version-detector"),
            result = ConflictDetectionResult.NoConflict,
        )

        assertEquals(ConflictDetectorId("entity-version-detector"), detector.id)
    }

    @Test
    fun `fake detector can return no conflict`() {
        val detector: ConflictDetector = FakeDetector(
            detectorId = ConflictDetectorId("test-detector"),
            result = ConflictDetectionResult.NoConflict,
        )
        val request: ConflictDetectionRequest = ConflictDetectionRequest(
            synchronizationRequest = syncRequest,
            localChange = localEvent,
            remoteChange = remoteEvent,
        )

        val result: ConflictDetectionResult = detector.detect(request)

        assertIs<ConflictDetectionResult.NoConflict>(result)
    }

    @Test
    fun `fake detector can return a detected conflict`() {
        val detector: ConflictDetector = FakeDetector(
            detectorId = ConflictDetectorId("test-detector"),
            result = ConflictDetectionResult.ConflictDetected(conflict = sampleConflict),
        )
        val request: ConflictDetectionRequest = ConflictDetectionRequest(
            synchronizationRequest = syncRequest,
            localChange = localEvent,
            remoteChange = remoteEvent,
        )

        val result: ConflictDetectionResult = detector.detect(request)

        assertIs<ConflictDetectionResult.ConflictDetected>(result)
        assertEquals(sampleConflict, result.conflict)
    }

    @Test
    fun `conflict detector does not require platform-specific types`() {
        // Verified by the fact that FakeDetector compiles in commonTest.
        val detector: ConflictDetector = FakeDetector(
            detectorId = ConflictDetectorId("platform-independent-detector"),
            result = ConflictDetectionResult.NoConflict,
        )
        assertIs<ConflictDetector>(detector)
    }

    // -------------------------------------------------------------------------
    // ConflictResolver interface
    // -------------------------------------------------------------------------

    @Test
    fun `fake resolver exposes its id`() {
        val resolver: ConflictResolver = FakeResolver(
            resolverId = ConflictResolverId("client-preferred-resolver"),
            decision = ConflictResolutionDecision.UseLocal(),
        )

        assertEquals(ConflictResolverId("client-preferred-resolver"), resolver.id)
    }

    @Test
    fun `fake resolver can choose local`() {
        val resolver: ConflictResolver = FakeResolver(
            resolverId = ConflictResolverId("test-resolver"),
            decision = ConflictResolutionDecision.UseLocal(),
        )
        val request: ConflictResolutionRequest = ConflictResolutionRequest(
            synchronizationRequest = syncRequest,
            conflict = sampleConflict,
        )

        val decision: ConflictResolutionDecision = resolver.resolve(request)

        assertIs<ConflictResolutionDecision.UseLocal>(decision)
    }

    @Test
    fun `fake resolver can choose remote`() {
        val resolver: ConflictResolver = FakeResolver(
            resolverId = ConflictResolverId("test-resolver"),
            decision = ConflictResolutionDecision.UseRemote(),
        )
        val request: ConflictResolutionRequest = ConflictResolutionRequest(
            synchronizationRequest = syncRequest,
            conflict = sampleConflict,
        )

        val decision: ConflictResolutionDecision = resolver.resolve(request)

        assertIs<ConflictResolutionDecision.UseRemote>(decision)
    }

    @Test
    fun `fake resolver can return merged data`() {
        val resolvedEvent: ChangeEvent = ChangeEvent(
            id = ChangeEventId("event-merged"),
            entity = invoiceRef,
            operation = ChangeOperation.UPDATE,
        )
        val resolver: ConflictResolver = FakeResolver(
            resolverId = ConflictResolverId("test-resolver"),
            decision = ConflictResolutionDecision.Merge(
                expectedEntity = invoiceRef,
                resolvedChange = resolvedEvent,
            ),
        )
        val request: ConflictResolutionRequest = ConflictResolutionRequest(
            synchronizationRequest = syncRequest,
            conflict = sampleConflict,
        )

        val decision: ConflictResolutionDecision = resolver.resolve(request)

        assertIs<ConflictResolutionDecision.Merge>(decision)
        assertEquals(resolvedEvent, decision.resolvedChange)
    }

    @Test
    fun `fake resolver can defer`() {
        val resolver: ConflictResolver = FakeResolver(
            resolverId = ConflictResolverId("test-resolver"),
            decision = ConflictResolutionDecision.Defer(),
        )
        val request: ConflictResolutionRequest = ConflictResolutionRequest(
            synchronizationRequest = syncRequest,
            conflict = sampleConflict,
        )

        val decision: ConflictResolutionDecision = resolver.resolve(request)

        assertIs<ConflictResolutionDecision.Defer>(decision)
    }

    @Test
    fun `fake resolver can fail`() {
        val error: DataLoomError = TestDataLoomError(
            code = ErrorCode("DL-CONFLICT-001"),
            category = ErrorCategory.CONFLICT,
            severity = ErrorSeverity.ERROR,
            recoverability = Recoverability.NON_RECOVERABLE,
            message = "Policy violation: conflicting financial operations.",
            cause = null,
        )
        val resolver: ConflictResolver = FakeResolver(
            resolverId = ConflictResolverId("test-resolver"),
            decision = ConflictResolutionDecision.Fail(error = error),
        )
        val request: ConflictResolutionRequest = ConflictResolutionRequest(
            synchronizationRequest = syncRequest,
            conflict = sampleConflict,
        )

        val decision: ConflictResolutionDecision = resolver.resolve(request)

        assertIs<ConflictResolutionDecision.Fail>(decision)
        assertEquals(error, decision.error)
    }

    @Test
    fun `conflict resolver does not require platform-specific types`() {
        // Verified by the fact that FakeResolver compiles in commonTest.
        val resolver: ConflictResolver = FakeResolver(
            resolverId = ConflictResolverId("platform-independent-resolver"),
            decision = ConflictResolutionDecision.UseLocal(),
        )
        assertIs<ConflictResolver>(resolver)
    }

    // -------------------------------------------------------------------------
    // Private test doubles
    // -------------------------------------------------------------------------

    private class FakeDetector(
        detectorId: ConflictDetectorId,
        private val result: ConflictDetectionResult,
    ) : ConflictDetector {
        override val id: ConflictDetectorId = detectorId
        override fun detect(request: ConflictDetectionRequest): ConflictDetectionResult = result
    }

    private class FakeResolver(
        resolverId: ConflictResolverId,
        private val decision: ConflictResolutionDecision,
    ) : ConflictResolver {
        override val id: ConflictResolverId = resolverId
        override fun resolve(request: ConflictResolutionRequest): ConflictResolutionDecision = decision
    }

    private data class TestDataLoomError(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val severity: ErrorSeverity,
        override val recoverability: Recoverability,
        override val message: String,
        override val cause: Throwable?,
    ) : DataLoomError
}
