package io.dataloom.runtime.observation

import io.dataloom.api.event.EventAppendRequest
import io.dataloom.api.event.EventBatchSize
import io.dataloom.api.event.EventConsumerId
import io.dataloom.api.event.EventLeaseId
import io.dataloom.api.identifier.IdentifierGenerator
import io.dataloom.api.observation.DurableEventExporter
import io.dataloom.api.operational.OperationalEventEnvelope
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.testing.observation.InMemoryDurableEventStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class BoundedDurableEventDeliveryTest {
    @Test
    fun `slow exporter is isolated from a healthy exporter`() = runTest {
        val store = InMemoryDurableEventStore()
        append(store, "slow-event", "workflow-slow")
        val blocked = CompletableDeferred<Unit>()
        val slow = object : DurableEventExporter {
            override val consumerId: EventConsumerId = EventConsumerId("slow")
            override suspend fun export(envelope: OperationalEventEnvelope) {
                blocked.await()
            }
        }
        val healthyEvents = mutableListOf<OperationalEventEnvelope>()
        val healthy = object : DurableEventExporter {
            override val consumerId: EventConsumerId = EventConsumerId("healthy")
            override suspend fun export(envelope: OperationalEventEnvelope) {
                healthyEvents += envelope
            }
        }
        val clock = MutableTestClock(DataLoomInstant(1_500L))
        val delivery = delivery(store, clock, listOf(slow, healthy))

        val slowPump = delivery.pump(slow.consumerId)
        val healthyPump = delivery.pump(healthy.consumerId)
        assertIs<DurableEventPumpResult.Submitted>(slowPump)
        assertIs<DurableEventPumpResult.Submitted>(healthyPump)
        runCurrent()

        assertEquals(1, healthyEvents.size)
        assertEquals(1, delivery.snapshot().exporters.single {
            it.consumerId == healthy.consumerId
        }.acknowledgedCount)
        assertEquals(0, delivery.snapshot().exporters.single {
            it.consumerId == slow.consumerId
        }.acknowledgedCount)

        blocked.complete(Unit)
        advanceUntilIdle()
        delivery.close()
        delivery.join()
    }

    @Test
    fun `failing exporter releases for replay and failure is isolated`() = runTest {
        val store = InMemoryDurableEventStore()
        append(store, "failed-event", "workflow-failed")
        val exporter = object : DurableEventExporter {
            override val consumerId: EventConsumerId = EventConsumerId("failing")
            override suspend fun export(envelope: OperationalEventEnvelope) {
                throw IllegalStateException("private exporter failure")
            }
        }
        val clock = MutableTestClock(DataLoomInstant(1_500L))
        val delivery = delivery(store, clock, listOf(exporter))

        assertIs<DurableEventPumpResult.Submitted>(delivery.pump(exporter.consumerId))
        advanceUntilIdle()
        val replay = assertIs<DurableEventPumpResult.Submitted>(delivery.pump(exporter.consumerId))
        assertEquals(1, replay.result.acceptedCount)
        advanceUntilIdle()

        val snapshot = delivery.snapshot().exporters.single()
        assertTrue(snapshot.failureCount >= 2L)
        assertTrue(snapshot.releasedCount >= 2L)
        assertEquals(DurableEventExporterFailureReason.EXCEPTION, snapshot.lastFailureReason)
        assertTrue(delivery.snapshot().metrics.keys.any {
            it.signal == DurableEventDeliverySignal.EXPORT_FAILED && it.redelivery
        })

        delivery.close()
        delivery.join()
    }

    @Test
    fun `buffer capacity produces deterministic latest overflow and replay`() = runTest {
        val store = InMemoryDurableEventStore()
        append(store, "event-a", "workflow-a")
        append(store, "event-b", "workflow-b")
        val blocked = CompletableDeferred<Unit>()
        val exporter = object : DurableEventExporter {
            override val consumerId: EventConsumerId = EventConsumerId("bounded")
            override suspend fun export(envelope: OperationalEventEnvelope) {
                blocked.await()
            }
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val clock = MutableTestClock(DataLoomInstant(1_500L))
        val delivery = BoundedDurableEventDelivery(
            coroutineContext = dispatcher,
            store = store,
            clock = clock,
            leaseIdGenerator = SequenceLeaseGenerator(),
            configuration = DurableEventDeliveryConfiguration(
                bufferCapacityPerExporter = 1,
                acquisitionBatchSize = EventBatchSize(2),
                exporterTimeout = SchedulingDelay(1_000L),
                leaseDuration = SchedulingDelay(2_000L),
            ),
            exporters = listOf(exporter),
        )

        val first = assertIs<DurableEventPumpResult.Submitted>(
            delivery.pump(exporter.consumerId),
        )
        assertEquals(2, first.result.acquiredCount)
        assertEquals(1, first.result.acceptedCount)
        assertEquals(1, first.result.overflowCount)
        assertEquals(1L, delivery.snapshot().exporters.single().overflowCount)
        assertTrue(delivery.snapshot().metrics.keys.any {
            it.signal == DurableEventDeliverySignal.BUFFER_OVERFLOW
        })

        runCurrent()
        val replay = assertIs<DurableEventPumpResult.Submitted>(
            delivery.pump(exporter.consumerId),
        )
        assertEquals(1, replay.result.acquiredCount)
        assertEquals(1, replay.result.acceptedCount)
        assertTrue(delivery.snapshot().metrics.keys.any {
            it.signal == DurableEventDeliverySignal.ACQUIRED && it.redelivery
        })

        blocked.complete(Unit)
        advanceUntilIdle()
        delivery.close()
        delivery.join()
    }

    @Test
    fun `event store failure is visible without exporter invocation`() = runTest {
        val store = InMemoryDurableEventStore(
            failureMode = io.dataloom.testing.observation.InMemoryEventStoreFailureMode.RETURN_FAILURE,
        )
        val events = mutableListOf<OperationalEventEnvelope>()
        val exporter = object : DurableEventExporter {
            override val consumerId: EventConsumerId = EventConsumerId("store-failure")
            override suspend fun export(envelope: OperationalEventEnvelope) {
                events += envelope
            }
        }
        val delivery = delivery(
            store,
            MutableTestClock(DataLoomInstant(1_500L)),
            listOf(exporter),
        )

        assertIs<DurableEventPumpResult.StoreFailure>(delivery.pump(exporter.consumerId))
        assertTrue(events.isEmpty())
        assertEquals(DurableEventExporterHealth.DEGRADED, delivery.snapshot().exporters.single().health)
        assertTrue(delivery.snapshot().metrics.keys.any {
            it.signal == DurableEventDeliverySignal.ACQUIRE_FAILED
        })
        delivery.close()
        delivery.join()
    }

    @Test
    fun `dynamic identities do not expand metric dimensions`() = runTest {
        val store = InMemoryDurableEventStore()
        repeat(50) { index: Int ->
            append(store, "event-$index", "workflow-$index")
        }
        val exporter = object : DurableEventExporter {
            override val consumerId: EventConsumerId = EventConsumerId("metrics")
            override suspend fun export(envelope: OperationalEventEnvelope) = Unit
        }
        val delivery = BoundedDurableEventDelivery(
            coroutineContext = StandardTestDispatcher(testScheduler),
            store = store,
            clock = MutableTestClock(DataLoomInstant(1_500L)),
            leaseIdGenerator = SequenceLeaseGenerator(),
            configuration = DurableEventDeliveryConfiguration(
                bufferCapacityPerExporter = 64,
                acquisitionBatchSize = EventBatchSize(50),
                exporterTimeout = SchedulingDelay(100L),
                leaseDuration = SchedulingDelay(6_500L),
            ),
            exporters = listOf(exporter),
        )

        delivery.pump(exporter.consumerId)
        advanceUntilIdle()
        assertTrue(delivery.snapshot().metrics.keys.size <= 8)
        delivery.close()
        delivery.join()
    }

    private fun delivery(
        store: InMemoryDurableEventStore,
        clock: MutableTestClock,
        exporters: List<DurableEventExporter>,
    ): BoundedDurableEventDelivery = BoundedDurableEventDelivery(
        coroutineContext = StandardTestDispatcher(testScheduler),
        store = store,
        clock = clock,
        leaseIdGenerator = SequenceLeaseGenerator(),
        configuration = DurableEventDeliveryConfiguration(
            bufferCapacityPerExporter = 4,
            acquisitionBatchSize = EventBatchSize(4),
            exporterTimeout = SchedulingDelay(10_000L),
            leaseDuration = SchedulingDelay(50_000L),
        ),
        exporters = exporters,
    )

    private suspend fun append(
        store: InMemoryDurableEventStore,
        eventId: String,
        workflowId: String,
    ) {
        store.append(
            EventAppendRequest(
                envelope = testEnvelope(eventId, workflowId),
                orderingScope = testScope(workflowId),
                retention = testRetention(expiresAtExclusive = 100_000L),
            ),
        )
    }

    private class SequenceLeaseGenerator : IdentifierGenerator<EventLeaseId> {
        private var next: Int = 1
        override fun generate(): EventLeaseId = EventLeaseId("lease-${next++}")
    }
}
