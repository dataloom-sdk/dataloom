package io.dataloom.api.change

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ChangeSetId
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.model.ChangeOperation
import io.dataloom.api.payload.DataLoomPayload
import io.dataloom.api.payload.EntityVersion
import io.dataloom.api.payload.PayloadContentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChangeContractsTest {

    // -------------------------------------------------------------------------
    // Test fixtures
    // -------------------------------------------------------------------------

    private val invoiceType: EntityType = EntityType("invoice")
    private val entityId: EntityId = EntityId("entity-001")
    private val entityVersion: EntityVersion = EntityVersion("v1")
    private val ref: EntityReference = EntityReference(invoiceType, entityId)
    private val refWithVersion: EntityReference = EntityReference(invoiceType, entityId, entityVersion)
    private val eventId: ChangeEventId = ChangeEventId("event-001")
    private val setId: ChangeSetId = ChangeSetId("changeset-001")
    private val jsonType: PayloadContentType = PayloadContentType("application/json")
    private val samplePayload: DataLoomPayload = DataLoomPayload(jsonType, byteArrayOf(1, 2, 3))

    // -------------------------------------------------------------------------
    // EntityReference
    // -------------------------------------------------------------------------

    @Test
    fun `entity reference preserves type and id`() {
        assertEquals(invoiceType, ref.type)
        assertEquals(entityId, ref.id)
    }

    @Test
    fun `entity reference version is absent when not supplied`() {
        assertNull(ref.version)
    }

    @Test
    fun `entity reference preserves version when supplied`() {
        assertEquals(entityVersion, refWithVersion.version)
    }

    @Test
    fun `equal entity references compare as equal`() {
        val a: EntityReference = EntityReference(invoiceType, entityId)
        val b: EntityReference = EntityReference(invoiceType, entityId)
        assertEquals(a, b)
    }

    @Test
    fun `entity references with different ids compare as unequal`() {
        val a: EntityReference = EntityReference(invoiceType, EntityId("entity-001"))
        val b: EntityReference = EntityReference(invoiceType, EntityId("entity-002"))
        assertNotEquals(a, b)
    }

    @Test
    fun `entity references with different types compare as unequal`() {
        val a: EntityReference = EntityReference(EntityType("invoice"), entityId)
        val b: EntityReference = EntityReference(EntityType("payment"), entityId)
        assertNotEquals(a, b)
    }

    @Test
    fun `entity references with different versions compare as unequal`() {
        val a: EntityReference = EntityReference(invoiceType, entityId, EntityVersion("v1"))
        val b: EntityReference = EntityReference(invoiceType, entityId, EntityVersion("v2"))
        assertNotEquals(a, b)
    }

    // -------------------------------------------------------------------------
    // ChangeEvent
    // -------------------------------------------------------------------------

    @Test
    fun `change event preserves required properties`() {
        val event: ChangeEvent = ChangeEvent(
            id = eventId,
            entity = ref,
            operation = ChangeOperation.CREATE,
        )
        assertEquals(eventId, event.id)
        assertEquals(ref, event.entity)
        assertEquals(ChangeOperation.CREATE, event.operation)
    }

    @Test
    fun `change event payload is absent when not supplied`() {
        val event: ChangeEvent = ChangeEvent(
            id = eventId,
            entity = ref,
            operation = ChangeOperation.DELETE,
        )
        assertNull(event.payload)
    }

    @Test
    fun `change event preserves payload when supplied`() {
        val event: ChangeEvent = ChangeEvent(
            id = eventId,
            entity = ref,
            operation = ChangeOperation.UPDATE,
            payload = samplePayload,
        )
        assertEquals(samplePayload, event.payload)
    }

    @Test
    fun `change event metadata defaults to empty`() {
        val event: ChangeEvent = ChangeEvent(
            id = eventId,
            entity = ref,
            operation = ChangeOperation.CREATE,
        )
        assertTrue(event.metadata.isEmpty())
    }

    @Test
    fun `change event preserves supplied metadata`() {
        val metadata: DataLoomMetadata = DataLoomMetadata.of(mapOf("source" to "host-app"))
        val event: ChangeEvent = ChangeEvent(
            id = eventId,
            entity = ref,
            operation = ChangeOperation.CREATE,
            metadata = metadata,
        )
        assertEquals(metadata, event.metadata)
    }

    @Test
    fun `equal change events compare as equal`() {
        val a: ChangeEvent = ChangeEvent(eventId, ref, ChangeOperation.CREATE)
        val b: ChangeEvent = ChangeEvent(eventId, ref, ChangeOperation.CREATE)
        assertEquals(a, b)
    }

    // -------------------------------------------------------------------------
    // ChangeSet
    // -------------------------------------------------------------------------

    private fun makeEvent(idValue: String = "event-001"): ChangeEvent =
        ChangeEvent(ChangeEventId(idValue), ref, ChangeOperation.UPDATE)

    @Test
    fun `change set preserves id`() {
        val event: ChangeEvent = makeEvent()
        val changeSet: ChangeSet = ChangeSet(setId, listOf(event))
        assertEquals(setId, changeSet.id)
    }

    @Test
    fun `change set preserves events`() {
        val event: ChangeEvent = makeEvent()
        val changeSet: ChangeSet = ChangeSet(setId, listOf(event))
        assertEquals(listOf(event), changeSet.events)
    }

    @Test
    fun `change set preserves event order`() {
        val e1: ChangeEvent = makeEvent("event-001")
        val e2: ChangeEvent = makeEvent("event-002")
        val e3: ChangeEvent = makeEvent("event-003")
        val changeSet: ChangeSet = ChangeSet(setId, listOf(e1, e2, e3))
        assertEquals(listOf(e1, e2, e3), changeSet.events)
    }

    @Test
    fun `change set rejects empty event list`() {
        assertFailsWith<IllegalArgumentException> {
            ChangeSet(setId, emptyList())
        }
    }

    @Test
    fun `change set metadata defaults to empty`() {
        val changeSet: ChangeSet = ChangeSet(setId, listOf(makeEvent()))
        assertTrue(changeSet.metadata.isEmpty())
    }

    @Test
    fun `change set preserves supplied metadata`() {
        val metadata: DataLoomMetadata = DataLoomMetadata.of(mapOf("channel" to "manual"))
        val changeSet: ChangeSet = ChangeSet(setId, listOf(makeEvent()), metadata)
        assertEquals(metadata, changeSet.metadata)
    }

    @Test
    fun `mutating source list does not mutate existing change set`() {
        val event: ChangeEvent = makeEvent()
        val source: MutableList<ChangeEvent> = mutableListOf(event)
        val changeSet: ChangeSet = ChangeSet(setId, source)
        val extraEvent: ChangeEvent = makeEvent("event-999")
        source.add(extraEvent)
        assertEquals(1, changeSet.events.size)
    }

    @Test
    fun `equal change sets compare as equal`() {
        val event: ChangeEvent = makeEvent()
        val a: ChangeSet = ChangeSet(setId, listOf(event))
        val b: ChangeSet = ChangeSet(setId, listOf(event))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `change sets with different ids compare as unequal`() {
        val event: ChangeEvent = makeEvent()
        val a: ChangeSet = ChangeSet(ChangeSetId("set-001"), listOf(event))
        val b: ChangeSet = ChangeSet(ChangeSetId("set-002"), listOf(event))
        assertNotEquals(a, b)
    }

    @Test
    fun `change sets with different events compare as unequal`() {
        val e1: ChangeEvent = makeEvent("event-001")
        val e2: ChangeEvent = makeEvent("event-002")
        val a: ChangeSet = ChangeSet(setId, listOf(e1))
        val b: ChangeSet = ChangeSet(setId, listOf(e2))
        assertNotEquals(a, b)
    }
}
