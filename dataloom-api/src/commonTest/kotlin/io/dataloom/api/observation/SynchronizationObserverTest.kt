package io.dataloom.api.observation

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.SynchronizationEventId
import io.dataloom.api.identifier.SynchronizationObserverId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.synchronization.SynchronizationEvent
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SynchronizationObserverTest {

    // -------------------------------------------------------------------------
    // SynchronizationObserverId identifier tests
    // -------------------------------------------------------------------------

    @Test
    fun `observer id accepts valid value`() {
        val id: SynchronizationObserverId = SynchronizationObserverId("analytics-observer")
        assertEquals("analytics-observer", id.value)
    }

    @Test
    fun `observer id rejects blank value`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            SynchronizationObserverId("")
        }
    }

    @Test
    fun `observer id rejects whitespace-only value`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            SynchronizationObserverId("   ")
        }
    }

    @Test
    fun `observer id preserves exact value`() {
        val id: SynchronizationObserverId = SynchronizationObserverId("my-observer-123")
        assertEquals("my-observer-123", id.value)
    }

    @Test
    fun `observer id toString returns wrapped value`() {
        val id: SynchronizationObserverId = SynchronizationObserverId("analytics-observer")
        assertEquals("analytics-observer", id.toString())
    }

    @Test
    fun `observer ids with same value are equal`() {
        val first: SynchronizationObserverId = SynchronizationObserverId("analytics-observer")
        val second: SynchronizationObserverId = SynchronizationObserverId("analytics-observer")
        assertEquals(first, second)
    }

    @Test
    fun `observer ids with different values are not equal`() {
        val first: SynchronizationObserverId = SynchronizationObserverId("analytics-observer")
        val second: SynchronizationObserverId = SynchronizationObserverId("debug-observer")
        assertNotEquals(first, second)
    }

    // -------------------------------------------------------------------------
    // SynchronizationObserver interface tests
    // -------------------------------------------------------------------------

    @Test
    fun `observer exposes stable id`() {
        val observer: SynchronizationObserver = RecordingObserver("test-observer")
        assertEquals(SynchronizationObserverId("test-observer"), observer.id)
    }

    @Test
    fun `observer receives events through onEvent`() {
        val observer: RecordingObserver = RecordingObserver("test-observer")
        val event: SynchronizationEvent.Started = SynchronizationEvent.Started(
            id = SynchronizationEventId("event-001"),
            request = sampleRequest(),
            occurredAt = DataLoomInstant(1_000_000L),
        )

        observer.onEvent(event)

        assertEquals(1, observer.receivedEvents.size)
        assertEquals(event, observer.receivedEvents[0])
    }

    @Test
    fun `observer receives multiple events in order`() {
        val observer: RecordingObserver = RecordingObserver("test-observer")
        val request: SynchronizationRequest = sampleRequest()
        val first: SynchronizationEvent.Started = SynchronizationEvent.Started(
            id = SynchronizationEventId("event-001"),
            request = request,
            occurredAt = DataLoomInstant(1_000_000L),
        )
        val second: SynchronizationEvent.Started = SynchronizationEvent.Started(
            id = SynchronizationEventId("event-002"),
            request = request,
            occurredAt = DataLoomInstant(1_000_001L),
        )

        observer.onEvent(first)
        observer.onEvent(second)

        assertEquals(2, observer.receivedEvents.size)
        assertEquals(first, observer.receivedEvents[0])
        assertEquals(second, observer.receivedEvents[1])
    }

    @Test
    fun `multiple observers are independent`() {
        val observerA: RecordingObserver = RecordingObserver("observer-a")
        val observerB: RecordingObserver = RecordingObserver("observer-b")
        val event: SynchronizationEvent.Started = SynchronizationEvent.Started(
            id = SynchronizationEventId("event-001"),
            request = sampleRequest(),
            occurredAt = DataLoomInstant(1_000_000L),
        )

        observerA.onEvent(event)

        assertEquals(1, observerA.receivedEvents.size)
        assertTrue(observerB.receivedEvents.isEmpty())
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun sampleRequest(): SynchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("workflow-001"),
        sessionId = SynchronizationSessionId("session-001"),
        direction = SynchronizationDirection.BIDIRECTIONAL,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("execution-001"),
            correlationId = CorrelationId("corr-001"),
        ),
    )

    /**
     * Test implementation of [SynchronizationObserver] that records received events.
     */
    private class RecordingObserver(
        idValue: String,
    ) : SynchronizationObserver {
        override val id: SynchronizationObserverId = SynchronizationObserverId(idValue)

        private val _receivedEvents: MutableList<SynchronizationEvent> = mutableListOf()

        val receivedEvents: List<SynchronizationEvent>
            get() = _receivedEvents.toList()

        override fun onEvent(event: SynchronizationEvent) {
            _receivedEvents.add(event)
        }
    }
}
