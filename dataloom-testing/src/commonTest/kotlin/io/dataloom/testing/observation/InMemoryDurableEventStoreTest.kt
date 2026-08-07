package io.dataloom.testing.observation

import io.dataloom.api.event.EventAcknowledgeRequest
import io.dataloom.api.event.EventAcknowledgeResult
import io.dataloom.api.event.EventAcquireRequest
import io.dataloom.api.event.EventAcquireResult
import io.dataloom.api.event.EventAppendRequest
import io.dataloom.api.event.EventAppendResult
import io.dataloom.api.event.EventBatchSize
import io.dataloom.api.event.EventConsumerId
import io.dataloom.api.event.EventCrossScopeOrdering
import io.dataloom.api.event.EventFilter
import io.dataloom.api.event.EventLeaseId
import io.dataloom.api.event.EventPurgeRequest
import io.dataloom.api.event.EventPurgeResult
import io.dataloom.api.event.EventReleaseReason
import io.dataloom.api.event.EventReleaseRequest
import io.dataloom.api.event.EventReleaseResult
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.TenantId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.operational.OperationalEventCategory
import io.dataloom.api.operational.OperationalEventEnvelope
import io.dataloom.api.operational.OperationalEventId
import io.dataloom.api.operational.OperationalEventSource
import io.dataloom.api.operational.OperationalEventType
import io.dataloom.api.operational.OperationalPayloadDescriptor
import io.dataloom.api.operational.OperationalPayloadEncoding
import io.dataloom.api.operational.OperationalPayloadType
import io.dataloom.api.operational.OperationalSchemaVersion
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.security.DataClassification
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class InMemoryDurableEventStoreTest {
    @Test
    fun `events are ordered inside a workflow and cross workflow order is undefined`() = runTest {
        val store = InMemoryDurableEventStore()
        val firstA = append(store, "a-1", "workflow-a", 1_000L)
        val firstB = append(store, "b-1", "workflow-b", 1_001L)
        val secondA = append(store, "a-2", "workflow-a", 1_002L)

        assertEquals(1L, firstA.record.sequence.value)
        assertEquals(1L, firstB.record.sequence.value)
        assertEquals(2L, secondA.record.sequence.value)
        assertEquals(EventCrossScopeOrdering.NONE, EventCrossScopeOrdering.NONE)

        val consumer = EventConsumerId("exporter")
        val acquired = assertIs<EventAcquireResult.Events>(
            store.acquire(acquireRequest(consumer, "lease-1", 1_500L, 3)),
        )
        assertEquals(2, acquired.deliveries.size)
        assertEquals(
            setOf("a-1", "b-1"),
            acquired.deliveries.mapTo(linkedSetOf()) { it.record.envelope.id.value },
        )
        assertFalse(acquired.deliveries.any { it.record.envelope.id.value == "a-2" })
    }

    @Test
    fun `acknowledgement and replay survive store recreation`() = runTest {
        val original = InMemoryDurableEventStore()
        append(original, "event-1", "workflow-1", 1_000L)
        val consumer = EventConsumerId("consumer-1")
        val first = assertIs<EventAcquireResult.Events>(
            original.acquire(acquireRequest(consumer, "lease-1", 1_500L, 1)),
        ).deliveries.single()
        assertFalse(first.isRedelivery)

        val restartedBeforeAck = InMemoryDurableEventStore(original.snapshotState())
        val stillLeased = restartedBeforeAck.acquire(
            acquireRequest(consumer, "lease-2", 1_600L, 1),
        )
        assertIs<EventAcquireResult.NoEvents>(stillLeased)

        val replayed = assertIs<EventAcquireResult.Events>(
            restartedBeforeAck.acquire(acquireRequest(consumer, "lease-3", 2_501L, 1)),
        ).deliveries.single()
        assertTrue(replayed.isRedelivery)
        assertEquals(2, replayed.attempt.value)

        val acknowledged = restartedBeforeAck.acknowledge(
            EventAcknowledgeRequest(
                consumerId = consumer,
                leaseId = EventLeaseId("lease-3"),
                eventIds = setOf(replayed.record.envelope.id),
                acknowledgedAt = DataLoomInstant(2_600L),
            ),
        )
        assertIs<EventAcknowledgeResult.Acknowledged>(acknowledged)

        val restartedAfterAck = InMemoryDurableEventStore(restartedBeforeAck.snapshotState())
        assertIs<EventAcquireResult.NoEvents>(
            restartedAfterAck.acquire(acquireRequest(consumer, "lease-4", 3_000L, 1)),
        )
    }

    @Test
    fun `release makes duplicate delivery explicit and increments attempt`() = runTest {
        val store = InMemoryDurableEventStore()
        append(store, "event-release", "workflow-release", 1_000L)
        val consumer = EventConsumerId("release-consumer")
        val first = assertIs<EventAcquireResult.Events>(
            store.acquire(acquireRequest(consumer, "lease-1", 1_500L, 1)),
        ).deliveries.single()

        val released = store.release(
            EventReleaseRequest(
                consumerId = consumer,
                leaseId = first.leaseId,
                eventIds = setOf(first.record.envelope.id),
                releasedAt = DataLoomInstant(1_600L),
                reason = EventReleaseReason.EXPORTER_FAILED,
            ),
        )
        assertIs<EventReleaseResult.Released>(released)
        val second = assertIs<EventAcquireResult.Events>(
            store.acquire(acquireRequest(consumer, "lease-2", 1_700L, 1)),
        ).deliveries.single()
        assertTrue(second.isRedelivery)
        assertEquals(2, second.attempt.value)
    }

    @Test
    fun `expiration is exact and sequence high water survives purge and restart`() = runTest {
        val store = InMemoryDurableEventStore()
        val first = append(
            store,
            "event-expired",
            "workflow-expiry",
            occurredAt = 1_000L,
            expiresAtExclusive = 2_000L,
        )
        assertFalse(first.record.isExpiredAt(DataLoomInstant(1_999L)))
        assertTrue(first.record.isExpiredAt(DataLoomInstant(2_000L)))
        assertEquals(
            1,
            assertIs<EventPurgeResult.Purged>(
                store.purgeExpired(EventPurgeRequest(DataLoomInstant(2_000L))),
            ).purgedCount,
        )

        val restarted = InMemoryDurableEventStore(store.snapshotState())
        val second = append(
            restarted,
            "event-after-purge",
            "workflow-expiry",
            occurredAt = 2_001L,
            expiresAtExclusive = 4_000L,
        )
        assertEquals(2L, second.record.sequence.value)
    }

    @Test
    fun `bounded filters select only canonical routing fields`() = runTest {
        val store = InMemoryDurableEventStore()
        append(store, "life", "workflow-filter", 1_000L, OperationalEventCategory.LIFECYCLE)
        append(store, "audit", "workflow-audit", 1_001L, OperationalEventCategory.AUDIT)

        val result = assertIs<EventAcquireResult.Events>(
            store.acquire(
                acquireRequest(
                    consumerId = EventConsumerId("filter-consumer"),
                    leaseId = "filter-lease",
                    acquiredAt = 1_500L,
                    batchSize = 10,
                    filter = EventFilter.of(
                        categories = setOf(OperationalEventCategory.AUDIT),
                    ),
                ),
            ),
        )
        assertEquals(listOf("audit"), result.deliveries.map { it.record.envelope.id.value })
    }

    @Test
    fun `idempotent append preserves event identity and conflicting reuse is rejected`() = runTest {
        val store = InMemoryDurableEventStore()
        val request = appendRequest("stable-id", "workflow-stable", 1_000L)
        val first = assertIs<EventAppendResult.Appended>(store.append(request))
        val duplicate = assertIs<EventAppendResult.AlreadyPresent>(store.append(request))
        assertEquals(first.record, duplicate.record)

        val conflict = store.append(
            request.copy(
                envelope = request.envelope.copy(
                    correlationId = CorrelationId("different-correlation"),
                ),
            ),
        )
        val rejected = assertIs<EventAppendResult.Rejected>(conflict)
        assertEquals(
            io.dataloom.api.event.EventAppendRejectionReason.EVENT_IDENTITY_CONFLICT,
            rejected.reason,
        )
    }

    @Test
    fun `recording exporter makes duplicate delivery observable for idempotent consumers`() = runTest {
        val exporter = RecordingDurableEventExporter(EventConsumerId("recording"))
        val envelope = envelope("duplicate", "workflow-duplicate", 1_000L)
        exporter.export(envelope)
        exporter.export(envelope)
        assertEquals(2, exporter.deliveryCountFor(envelope.id))
        assertEquals(1, exporter.events.map { it.id }.distinct().size)
    }

    private suspend fun append(
        store: InMemoryDurableEventStore,
        eventId: String,
        workflowId: String,
        occurredAt: Long,
        category: OperationalEventCategory = OperationalEventCategory.LIFECYCLE,
        expiresAtExclusive: Long = 10_000L,
    ): EventAppendResult.Appended = assertIs(
        store.append(
            appendRequest(eventId, workflowId, occurredAt, category, expiresAtExclusive),
        ),
    )

    private fun appendRequest(
        eventId: String,
        workflowId: String,
        occurredAt: Long,
        category: OperationalEventCategory = OperationalEventCategory.LIFECYCLE,
        expiresAtExclusive: Long = 10_000L,
    ): EventAppendRequest = EventAppendRequest(
        envelope = envelope(eventId, workflowId, occurredAt, category),
        orderingScope = io.dataloom.api.event.EventOrderingScope.Workflow(WorkflowId(workflowId)),
        retention = io.dataloom.api.event.EventRetentionWindow(
            storedAt = DataLoomInstant(occurredAt),
            expiresAtExclusive = DataLoomInstant(expiresAtExclusive),
        ),
    )

    private fun envelope(
        eventId: String,
        workflowId: String,
        occurredAt: Long,
        category: OperationalEventCategory = OperationalEventCategory.LIFECYCLE,
    ): OperationalEventEnvelope = OperationalEventEnvelope(
        id = OperationalEventId(eventId),
        type = OperationalEventType("dataloom.testing.event"),
        source = OperationalEventSource("dataloom.testing.store"),
        category = category,
        schemaVersion = OperationalSchemaVersion(1),
        occurredAt = DataLoomInstant(occurredAt),
        correlationId = CorrelationId("correlation-$eventId"),
        tenantId = TenantId("tenant-testing"),
        workflowId = WorkflowId(workflowId),
        payload = OperationalPayloadDescriptor(
            type = OperationalPayloadType("dataloom.testing.signal"),
            schemaVersion = OperationalSchemaVersion(1),
            encoding = OperationalPayloadEncoding("application/vnd.dataloom.signal"),
            classification = DataClassification.INTERNAL,
            encodedSizeBytes = 0L,
        ),
    )

    private fun acquireRequest(
        consumerId: EventConsumerId,
        leaseId: String,
        acquiredAt: Long,
        batchSize: Int,
        filter: EventFilter = EventFilter.All,
    ): EventAcquireRequest = EventAcquireRequest(
        consumerId = consumerId,
        leaseId = EventLeaseId(leaseId),
        acquiredAt = DataLoomInstant(acquiredAt),
        leaseDuration = SchedulingDelay(1_000L),
        batchSize = EventBatchSize(batchSize),
        filter = filter,
    )
}
