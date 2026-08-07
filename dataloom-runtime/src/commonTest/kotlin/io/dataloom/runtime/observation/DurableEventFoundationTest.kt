package io.dataloom.runtime.observation

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.event.DurableEventInfrastructureFailure
import io.dataloom.api.event.DurableEventPublishRequest
import io.dataloom.api.event.DurableEventPublishResult
import io.dataloom.api.event.EventAppendResult
import io.dataloom.api.event.EventFilter
import io.dataloom.api.event.EventPageSize
import io.dataloom.api.event.EventPurgeRequest
import io.dataloom.api.event.EventPurgeResult
import io.dataloom.api.event.EventQueryRequest
import io.dataloom.api.event.EventQueryResult
import io.dataloom.api.event.OperationalEventDraft
import io.dataloom.api.event.OperationalEventEnvelopeFactory
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.SynchronizationObserverId
import io.dataloom.api.observation.SynchronizationObserver
import io.dataloom.api.operational.OperationalEventCategory
import io.dataloom.api.operational.OperationalEventId
import io.dataloom.api.operational.OperationalEventSource
import io.dataloom.api.operational.OperationalEventType
import io.dataloom.api.operational.OperationalPayloadDescriptor
import io.dataloom.api.operational.OperationalPayloadEncoding
import io.dataloom.api.operational.OperationalPayloadType
import io.dataloom.api.operational.OperationalSchemaVersion
import io.dataloom.api.security.ClassifiedData
import io.dataloom.api.security.ClassifiedDataValue
import io.dataloom.api.security.DataClassification
import io.dataloom.api.security.StrictDataLoomRedactor
import io.dataloom.api.synchronization.SynchronizationEvent
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.operations.StoreBackedEventOperationalReadModel
import io.dataloom.testing.observation.InMemoryDurableEventStore
import io.dataloom.testing.observation.InMemoryEventStoreFailureMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class DurableEventFoundationTest {
    @Test
    fun `canonical envelope preserves stable identity and propagation fields`() = runTest {
        val store = InMemoryDurableEventStore()
        val publisher = DurableOperationalEventPublisher(
            envelopeFactory = OperationalEventEnvelopeFactory(StrictDataLoomRedactor()),
            store = store,
        )
        val result: DurableEventPublishResult = publisher.publish(
            DurableEventPublishRequest(
                draft = draft(),
                orderingScope = testScope(),
                retention = testRetention(),
            ),
        )

        val published = assertIs<DurableEventPublishResult.Published>(result)
        with(published.record.envelope) {
            assertEquals(OperationalEventId("event-001"), id)
            assertEquals(OperationalSchemaVersion(3), schemaVersion)
            assertEquals(CorrelationId("correlation-001"), correlationId)
            assertEquals(OperationalEventId("cause-001"), causationId)
            assertEquals("trace-001", traceId?.value)
            assertEquals("tenant-001", tenantId?.value)
            assertEquals("workflow-001", workflowId?.value)
        }
    }

    @Test
    fun `central redaction removes adversarial sensitive values`() = runTest {
        val sensitive = "credential=secret-token\nAuthorization: Bearer private"
        val publisher = DurableOperationalEventPublisher(
            envelopeFactory = OperationalEventEnvelopeFactory(StrictDataLoomRedactor()),
            store = InMemoryDurableEventStore(),
        )
        val result = publisher.publish(
            DurableEventPublishRequest(
                draft = draft(
                    classifiedData = ClassifiedData.of(
                        mapOf(
                            "public.status" to ClassifiedDataValue(
                                "ready",
                                DataClassification.PUBLIC,
                            ),
                            "internal.worker" to ClassifiedDataValue(
                                sensitive,
                                DataClassification.INTERNAL,
                            ),
                            "confidential.auth" to ClassifiedDataValue(
                                sensitive,
                                DataClassification.CONFIDENTIAL,
                            ),
                            "restricted.key" to ClassifiedDataValue(
                                sensitive,
                                DataClassification.RESTRICTED,
                            ),
                        ),
                    ),
                ),
                orderingScope = testScope(),
                retention = testRetention(),
            ),
        )
        val published = assertIs<DurableEventPublishResult.Published>(result)
        val attributes = published.record.envelope.attributes
        assertEquals("ready", attributes["public.status"])
        assertEquals("[REDACTED]", attributes["internal.worker"])
        assertNull(attributes["confidential.auth"])
        assertNull(attributes["restricted.key"])
        assertFalse(attributes.toString().contains(sensitive))
        assertEquals(1, published.redactionSummary.maskedFieldCount)
        assertEquals(2, published.redactionSummary.removedFieldCount)
    }

    @Test
    fun `filter dimensions and total cardinality are bounded`() {
        val tooManyTypes = (1..33).mapTo(linkedSetOf()) {
            OperationalEventType("dataloom.test.$it")
        }
        assertFailsWith<IllegalArgumentException> {
            EventFilter.of(types = tooManyTypes)
        }

        val bounded = EventFilter.of(
            categories = setOf(OperationalEventCategory.LIFECYCLE),
            workflowIds = setOf(io.dataloom.api.identifier.WorkflowId("workflow-001")),
        )
        val record = io.dataloom.api.event.DurableEventRecord(
            envelope = testEnvelope("event-filter"),
            orderingScope = testScope(),
            sequence = io.dataloom.api.event.EventSequence(1),
            retention = testRetention(),
        )
        assertTrue(bounded.matches(record))
    }

    @Test
    fun `retention expires exactly at exclusive boundary and purge preserves truth`() = runTest {
        val store = InMemoryDurableEventStore()
        val appended = store.append(
            io.dataloom.api.event.EventAppendRequest(
                envelope = testEnvelope("event-expiry"),
                orderingScope = testScope(),
                retention = testRetention(storedAt = 1_000L, expiresAtExclusive = 2_000L),
            ),
        )
        val record = assertIs<EventAppendResult.Appended>(appended).record
        assertFalse(record.isExpiredAt(DataLoomInstant(1_999L)))
        assertTrue(record.isExpiredAt(DataLoomInstant(2_000L)))

        val beforeBoundary = store.purgeExpired(EventPurgeRequest(DataLoomInstant(1_999L)))
        assertEquals(0, assertIs<EventPurgeResult.Purged>(beforeBoundary).purgedCount)
        val atBoundary = store.purgeExpired(EventPurgeRequest(DataLoomInstant(2_000L)))
        assertEquals(1, assertIs<EventPurgeResult.Purged>(atBoundary).purgedCount)
    }

    @Test
    fun `store failure and thrown infrastructure failure are isolated and visible`() = runTest {
        val store = InMemoryDurableEventStore(
            failureMode = InMemoryEventStoreFailureMode.RETURN_FAILURE,
        )
        val publisher = DurableOperationalEventPublisher(
            OperationalEventEnvelopeFactory(StrictDataLoomRedactor()),
            store,
        )
        val storeFailure = publisher.publish(
            DurableEventPublishRequest(draft(), testScope(), testRetention()),
        )
        assertIs<DurableEventPublishResult.StoreFailure>(storeFailure)

        store.failureMode = InMemoryEventStoreFailureMode.THROW_EXCEPTION
        val infrastructureFailure = publisher.publish(
            DurableEventPublishRequest(draft(), testScope(), testRetention()),
        )
        val isolated = assertIs<DurableEventPublishResult.InfrastructureFailure>(
            infrastructureFailure,
        )
        assertEquals(DurableEventInfrastructureFailure.STORE_THREW, isolated.failure)
    }

    @Test
    fun `query read model exposes neither payload nor redacted attributes`() = runTest {
        val store = InMemoryDurableEventStore()
        store.append(
            io.dataloom.api.event.EventAppendRequest(
                envelope = testEnvelope("event-query"),
                orderingScope = testScope(),
                retention = testRetention(),
            ),
        )
        val direct = store.query(
            EventQueryRequest(
                now = DataLoomInstant(1_500L),
                pageSize = EventPageSize(10),
            ),
        )
        val page = assertIs<EventQueryResult.Page>(direct)
        assertEquals(1, page.records.size)
        val summaryText = page.records.single().toString()
        assertFalse(summaryText.contains("payload", ignoreCase = true))
        assertFalse(summaryText.contains("attribute", ignoreCase = true))
        assertFalse(summaryText.contains("event-query"))

        val readModel = StoreBackedEventOperationalReadModel(store)
        val snapshot = readModel.snapshot(
            EventQueryRequest(DataLoomInstant(1_500L), pageSize = EventPageSize(10)),
        )
        assertEquals(1, snapshot.recentRecords.size)
        assertNull(snapshot.lastStoreFailureCode)
    }

    @Test
    fun `current synchronous observer behavior remains compatible`() {
        class RecordingObserver : SynchronizationObserver {
            override val id: SynchronizationObserverId = SynchronizationObserverId("legacy")
            val events = mutableListOf<SynchronizationEvent>()

            override fun onEvent(event: SynchronizationEvent) {
                events += event
            }
        }
        val observer = RecordingObserver()
        val dispatcher = SynchronizationEventDispatcher(
            SynchronizationObserverRegistry(listOf(observer)),
        )
        val event = testStartedEvent()
        val result = dispatcher.dispatch(event)

        assertIs<SynchronizationEventDispatchResult.Delivered>(result)
        assertEquals(listOf(event), observer.events)

        val draft = SynchronizationEventOperationalDraftFactory.create(
            event = event,
            eventId = OperationalEventId("durable-event-001"),
        )
        assertEquals(event.request.context.correlationId, draft.correlationId)
        assertEquals(event.request.context.traceId, draft.traceId)
        assertEquals(event.request.workflowId, draft.workflowId)
        assertEquals(ClassifiedData.Empty, draft.classifiedAttributes)
        assertEquals(0L, draft.payload.encodedSizeBytes)
    }

    @Test
    fun `operational query degrades without exposing thrown store exception`() = runTest {
        val store = InMemoryDurableEventStore(
            failureMode = InMemoryEventStoreFailureMode.THROW_EXCEPTION,
        )
        val snapshot = StoreBackedEventOperationalReadModel(store).snapshot(
            EventQueryRequest(DataLoomInstant(1_000L)),
        )
        assertTrue(snapshot.recentRecords.isEmpty())
        assertNull(snapshot.lastStoreFailureCode)
        assertEquals(io.dataloom.api.event.EventOperationalHealth.DEGRADED, snapshot.health)
    }

    private fun draft(
        classifiedData: ClassifiedData = ClassifiedData.Empty,
    ): OperationalEventDraft = OperationalEventDraft(
        id = OperationalEventId("event-001"),
        type = OperationalEventType("dataloom.test.published"),
        source = OperationalEventSource("dataloom.test.publisher"),
        category = OperationalEventCategory.LIFECYCLE,
        schemaVersion = OperationalSchemaVersion(3),
        occurredAt = DataLoomInstant(1_000L),
        correlationId = CorrelationId("correlation-001"),
        causationId = OperationalEventId("cause-001"),
        traceId = io.dataloom.api.identifier.TraceId("trace-001"),
        tenantId = io.dataloom.api.identifier.TenantId("tenant-001"),
        workflowId = io.dataloom.api.identifier.WorkflowId("workflow-001"),
        payload = OperationalPayloadDescriptor(
            type = OperationalPayloadType("dataloom.test.signal"),
            schemaVersion = OperationalSchemaVersion(3),
            encoding = OperationalPayloadEncoding("application/vnd.dataloom.signal"),
            classification = DataClassification.INTERNAL,
            encodedSizeBytes = 0L,
        ),
        classifiedAttributes = classifiedData,
    )

    @Suppress("unused")
    private data class FakeError(
        override val code: ErrorCode = ErrorCode("DL-TEST"),
        override val category: ErrorCategory = ErrorCategory.PROVIDER,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "test",
        override val cause: Throwable? = null,
    ) : DataLoomError
}
