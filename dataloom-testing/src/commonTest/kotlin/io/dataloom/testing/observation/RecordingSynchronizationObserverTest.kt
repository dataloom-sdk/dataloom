package io.dataloom.testing.observation

import io.dataloom.testing.observerId
import io.dataloom.testing.sampleCompletedEvent
import io.dataloom.testing.sampleProgressEvent
import io.dataloom.testing.sampleStartedEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RecordingSynchronizationObserverTest {
    @Test
    fun `id is exposed unchanged`() {
        val id = observerId("observer-123")
        val observer = RecordingSynchronizationObserver(id = id)
        assertEquals(id, observer.id)
    }

    @Test
    fun `starts with no events`() {
        val observer = RecordingSynchronizationObserver(id = observerId())
        assertEquals(0, observer.eventCount)
        assertEquals(emptyList(), observer.events)
        assertNull(observer.latestEvent)
    }

    @Test
    fun `records started event`() {
        val observer = RecordingSynchronizationObserver(id = observerId())
        val event = sampleStartedEvent()
        observer.onEvent(event)
        assertEquals(listOf(event), observer.events)
    }

    @Test
    fun `records multiple events in order`() {
        val observer = RecordingSynchronizationObserver(id = observerId())
        val first = sampleStartedEvent()
        val second = sampleProgressEvent()
        val third = sampleCompletedEvent()
        observer.onEvent(first)
        observer.onEvent(second)
        observer.onEvent(third)
        assertEquals(listOf(first, second, third), observer.events)
    }

    @Test
    fun `event count reflects recorded events`() {
        val observer = RecordingSynchronizationObserver(id = observerId())
        observer.onEvent(sampleStartedEvent())
        observer.onEvent(sampleProgressEvent())
        assertEquals(2, observer.eventCount)
    }

    @Test
    fun `latest event tracks the most recent event`() {
        val observer = RecordingSynchronizationObserver(id = observerId())
        val latest = sampleCompletedEvent()
        observer.onEvent(sampleStartedEvent())
        observer.onEvent(latest)
        assertEquals(latest, observer.latestEvent)
    }

    @Test
    fun `callback receives recorded event`() {
        var received = 0
        var lastId = ""
        val observer = RecordingSynchronizationObserver(
            id = observerId(),
            onEventCallback = {
                received += 1
                lastId = it.id.value
            },
        )
        val event = sampleStartedEvent()
        observer.onEvent(event)
        assertEquals(1, received)
        assertEquals(event.id.value, lastId)
    }

    @Test
    fun `callback is invoked for every event`() {
        var received = 0
        val observer = RecordingSynchronizationObserver(
            id = observerId(),
            onEventCallback = { received += 1 },
        )
        observer.onEvent(sampleStartedEvent())
        observer.onEvent(sampleProgressEvent())
        observer.onEvent(sampleCompletedEvent())
        assertEquals(3, received)
    }

    @Test
    fun `callback exception propagates`() {
        val observer = RecordingSynchronizationObserver(
            id = observerId(),
            onEventCallback = { throw IllegalStateException("callback failed") },
        )
        val error = assertFailsWith<IllegalStateException> {
            observer.onEvent(sampleStartedEvent())
        }
        assertEquals("callback failed", error.message)
    }

    @Test
    fun `event is recorded before callback exception is propagated`() {
        val event = sampleStartedEvent()
        val observer = RecordingSynchronizationObserver(
            id = observerId(),
            onEventCallback = { throw IllegalStateException("callback failed") },
        )
        assertFailsWith<IllegalStateException> { observer.onEvent(event) }
        assertEquals(listOf(event), observer.events)
    }

    @Test
    fun `clear recordings removes all events`() {
        val observer = RecordingSynchronizationObserver(id = observerId())
        observer.onEvent(sampleStartedEvent())
        observer.onEvent(sampleProgressEvent())
        observer.clearRecordings()
        assertEquals(emptyList(), observer.events)
        assertEquals(0, observer.eventCount)
        assertNull(observer.latestEvent)
    }

    @Test
    fun `events snapshot is defensive`() {
        val observer = RecordingSynchronizationObserver(id = observerId())
        observer.onEvent(sampleStartedEvent())
        val snapshot = observer.events
        assertEquals(1, snapshot.size)
        assertEquals(1, observer.events.size)
    }

    @Test
    fun `clear recordings is idempotent`() {
        val observer = RecordingSynchronizationObserver(id = observerId())
        observer.clearRecordings()
        observer.clearRecordings()
        assertEquals(emptyList(), observer.events)
    }

    @Test
    fun `latest event becomes null after clear recordings`() {
        val observer = RecordingSynchronizationObserver(id = observerId())
        observer.onEvent(sampleCompletedEvent())
        observer.clearRecordings()
        assertNull(observer.latestEvent)
    }

    @Test
    fun `observer accepts terminal events`() {
        val observer = RecordingSynchronizationObserver(id = observerId())
        val event = sampleCompletedEvent()
        observer.onEvent(event)
        assertEquals(event, observer.latestEvent)
    }
}
