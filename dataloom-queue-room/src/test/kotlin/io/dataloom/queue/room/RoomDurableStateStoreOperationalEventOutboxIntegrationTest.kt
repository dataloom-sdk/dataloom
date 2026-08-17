package io.dataloom.queue.room

import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.operational.OperationalEventCategory
import io.dataloom.api.operational.OperationalEventEnvelope
import io.dataloom.api.operational.OperationalEventId
import io.dataloom.api.operational.OperationalEventOutboxScope
import io.dataloom.api.operational.OperationalEventOutboxState
import io.dataloom.api.operational.OperationalEventOutboxStateCodec
import io.dataloom.api.operational.OperationalEventSource
import io.dataloom.api.operational.OperationalEventType
import io.dataloom.api.operational.OperationalPayloadDescriptor
import io.dataloom.api.operational.OperationalPayloadEncoding
import io.dataloom.api.operational.OperationalPayloadType
import io.dataloom.api.operational.OperationalSchemaVersion
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.security.DataClassification
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.queue.room.internal.DataLoomRoomDatabase
import io.dataloom.queue.room.internal.DurableStateCompareAndSetEntityResult
import io.dataloom.queue.room.internal.DurableStateDao
import io.dataloom.queue.room.internal.DurableStateEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Fourth real domain exercised through [RoomDurableStateStore], after
 * `RoomDurableStateStoreTest`'s stand-in fixtures,
 * `RoomDurableStateStorePolicyDecisionIntegrationTest`'s policy-decision
 * proof, and `RoomDurableStateStoreUnresolvedConflictIntegrationTest`'s
 * unresolved-conflict proof — again with zero new Room DAO/entity code, just
 * [OperationalEventOutboxStateCodec] and [OperationalEventOutboxScope.KeyEncoder].
 *
 * Proves the bounded first slice of DL-042's durable outbox: an
 * [OperationalEventEnvelope] persisted through the generic Room store is
 * readable back with the exact same identity and ordering, the way it would
 * be after a process restart re-opens the same underlying database.
 */
class RoomDurableStateStoreOperationalEventOutboxIntegrationTest {
    private lateinit var database: DataLoomRoomDatabase
    private lateinit var dao: DurableStateDao
    private lateinit var store: RoomDurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState>

    private val scope = OperationalEventOutboxScope("retry-events")
    private val firstEnvelope = envelope("event-1")
    private val secondEnvelope = envelope("event-2")

    @Before
    fun setUp() {
        database = mock()
        dao = mock()
        whenever(database.durableStateDao()).thenReturn(dao)
        store = RoomDurableStateStore(
            database,
            "operational-event-outbox",
            OperationalEventOutboxScope.KeyEncoder,
            OperationalEventOutboxStateCodec(),
        )
    }

    @Test
    fun insertsAndRoundTripsOneEnvelopeThroughTheGenericRoomStore() {
        runBlocking {
            val encodedKey = OperationalEventOutboxScope.KeyEncoder.encode(scope)
            val state = OperationalEventOutboxState(listOf(firstEnvelope))
            val encodedPayload = OperationalEventOutboxStateCodec().encode(state)
            val persistedEntity = DurableStateEntity(
                namespace = "operational-event-outbox",
                scopeKey = encodedKey,
                statePayload = encodedPayload,
                schemaVersion = 1,
                recordVersion = 0L,
            )
            whenever(dao.compareAndSet(eq(null), any())).thenReturn(
                DurableStateCompareAndSetEntityResult.Updated(persistedEntity),
            )
            whenever(dao.load("operational-event-outbox", encodedKey)).thenReturn(persistedEntity)

            val inserted = assertIs<ProviderOperationResult.Success<DurableStateCompareAndSetResult<OperationalEventOutboxState>>>(
                store.compareAndSet(DurableStateCompareAndSetRequest(scope, null, state, 1)),
            )
            val updated = assertIs<DurableStateCompareAndSetResult.Updated<OperationalEventOutboxState>>(inserted.value)
            assertEquals(state, updated.record.state)

            // Reading back through a freshly constructed store instance -- the
            // same shape a process restart would take, since nothing but the
            // encoded row (returned here by the mocked DAO) survives.
            val reopenedStore = RoomDurableStateStore(
                database,
                "operational-event-outbox",
                OperationalEventOutboxScope.KeyEncoder,
                OperationalEventOutboxStateCodec(),
            )
            val loaded = assertIs<ProviderOperationResult.Success<DurableStateLoadResult<OperationalEventOutboxState>>>(
                reopenedStore.load(scope),
            )
            val found = assertIs<DurableStateLoadResult.Found<OperationalEventOutboxState>>(loaded.value)
            assertEquals(state, found.record.state)
            assertEquals(listOf(firstEnvelope), found.record.state.entries)
        }
    }

    @Test
    fun appendingASecondEnvelopePreservesOrderThroughTheGenericRoomStore() {
        runBlocking {
            val encodedKey = OperationalEventOutboxScope.KeyEncoder.encode(scope)
            val codec = OperationalEventOutboxStateCodec()
            val firstState = OperationalEventOutboxState(listOf(firstEnvelope))
            val secondState = OperationalEventOutboxState(listOf(firstEnvelope, secondEnvelope))
            val firstEntity = DurableStateEntity(
                namespace = "operational-event-outbox",
                scopeKey = encodedKey,
                statePayload = codec.encode(firstState),
                schemaVersion = 1,
                recordVersion = 0L,
            )
            val secondEntity = DurableStateEntity(
                namespace = "operational-event-outbox",
                scopeKey = encodedKey,
                statePayload = codec.encode(secondState),
                schemaVersion = 1,
                recordVersion = 1L,
            )
            whenever(dao.compareAndSet(eq(null), any())).thenReturn(
                DurableStateCompareAndSetEntityResult.Updated(firstEntity),
            )
            whenever(dao.compareAndSet(eq(0L), any())).thenReturn(
                DurableStateCompareAndSetEntityResult.Updated(secondEntity),
            )
            whenever(dao.load("operational-event-outbox", encodedKey)).thenReturn(secondEntity)

            store.compareAndSet(DurableStateCompareAndSetRequest(scope, null, firstState, 1))
            val secondInsert = assertIs<ProviderOperationResult.Success<DurableStateCompareAndSetResult<OperationalEventOutboxState>>>(
                store.compareAndSet(DurableStateCompareAndSetRequest(scope, 0L, secondState, 1)),
            )
            val updated = assertIs<DurableStateCompareAndSetResult.Updated<OperationalEventOutboxState>>(secondInsert.value)
            assertEquals(listOf(firstEnvelope, secondEnvelope), updated.record.state.entries)

            val loaded = assertIs<ProviderOperationResult.Success<DurableStateLoadResult<OperationalEventOutboxState>>>(
                store.load(scope),
            )
            val found = assertIs<DurableStateLoadResult.Found<OperationalEventOutboxState>>(loaded.value)
            assertEquals(listOf(firstEnvelope, secondEnvelope), found.record.state.entries)
        }
    }

    private companion object {
        fun envelope(id: String): OperationalEventEnvelope = OperationalEventEnvelope(
            id = OperationalEventId(id),
            type = OperationalEventType("dataloom.retry.scheduled"),
            source = OperationalEventSource("dataloom.runtime.retry"),
            category = OperationalEventCategory.TELEMETRY,
            schemaVersion = OperationalSchemaVersion(1),
            occurredAt = DataLoomInstant(10_000L),
            correlationId = CorrelationId("correlation-1"),
            payload = OperationalPayloadDescriptor(
                type = OperationalPayloadType("dataloom.retry.signal"),
                schemaVersion = OperationalSchemaVersion(1),
                encoding = OperationalPayloadEncoding("application/json"),
                classification = DataClassification.INTERNAL,
            ),
        )
    }
}
