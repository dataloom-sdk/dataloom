package io.dataloom.runtime.observation

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.EntityReference
import io.dataloom.api.conflict.ConflictType
import io.dataloom.api.conflict.SynchronizationConflict
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.SynchronizationEventId
import io.dataloom.api.identifier.SynchronizationObserverId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.ChangeOperation
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.observation.SynchronizationObserver
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.synchronization.SynchronizationEvent
import io.dataloom.api.synchronization.SynchronizationPhase
import io.dataloom.api.synchronization.SynchronizationProgress
import io.dataloom.api.synchronization.SynchronizationProgressUnit
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.synchronization.SynchronizationSummary
import io.dataloom.api.time.DataLoomInstant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Deterministic common tests for DL-028 synchronization event dispatcher and
 * observer registry.
 *
 * All fakes are stateless or deterministically stateful. No real database,
 * real network, filesystem, system clock, random identifiers, Thread.sleep,
 * arbitrary delays, Android APIs, JVM-only APIs, reflection, ServiceLoader,
 * production credentials, or personal data are used.
 */
class SynchronizationEventDispatcherTest {

    // =========================================================================
    // Fake helpers
    // =========================================================================

    /** Simple observer that records received events. */
    private class RecordingObserver(idValue: String) : SynchronizationObserver {
        override val id: SynchronizationObserverId = SynchronizationObserverId(idValue)

        private val _events = mutableListOf<SynchronizationEvent>()
        val events: List<SynchronizationEvent> get() = _events.toList()
        var callCount: Int = 0

        override fun onEvent(event: SynchronizationEvent) {
            callCount++
            _events.add(event)
        }
    }

    /** Observer that always throws an ordinary exception from onEvent. */
    private class FailingObserver(idValue: String) : SynchronizationObserver {
        override val id: SynchronizationObserverId = SynchronizationObserverId(idValue)
        var callCount: Int = 0

        override fun onEvent(event: SynchronizationEvent) {
            callCount++
            throw RuntimeException("Observer callback intentionally failed for test.")
        }
    }

    /** Observer that throws a CancellationException from onEvent. */
    private class CancellingObserver(idValue: String) : SynchronizationObserver {
        override val id: SynchronizationObserverId = SynchronizationObserverId(idValue)
        var callCount: Int = 0

        override fun onEvent(event: SynchronizationEvent) {
            callCount++
            throw CancellationException("Test cancellation from observer.")
        }
    }

    /** Observer that fails on the nth call (1-indexed). */
    private class ConditionallyFailingObserver(
        idValue: String,
        private val failOnCall: Int,
    ) : SynchronizationObserver {
        override val id: SynchronizationObserverId = SynchronizationObserverId(idValue)
        private var callCount = 0

        override fun onEvent(event: SynchronizationEvent) {
            callCount++
            if (callCount == failOnCall) {
                throw RuntimeException("Conditional failure on call $callCount.")
            }
        }
    }

    private data class FakeDataLoomError(
        override val code: ErrorCode = ErrorCode("DL-TEST-001"),
        override val category: ErrorCategory = ErrorCategory.PROVIDER,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Test error.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    // =========================================================================
    // Event fixtures
    // =========================================================================

    private val request = SynchronizationRequest(
        workflowId = WorkflowId("workflow-001"),
        sessionId = SynchronizationSessionId("session-001"),
        direction = SynchronizationDirection.BIDIRECTIONAL,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("execution-001"),
            correlationId = CorrelationId("corr-001"),
        ),
    )

    private val baseInstant = DataLoomInstant(1_000_000L)

    private fun startedEvent(id: String = "event-001"): SynchronizationEvent.Started =
        SynchronizationEvent.Started(
            id = SynchronizationEventId(id),
            request = request,
            occurredAt = baseInstant,
        )

    private fun phaseChangedEvent(id: String = "event-002"): SynchronizationEvent.PhaseChanged =
        SynchronizationEvent.PhaseChanged(
            id = SynchronizationEventId(id),
            request = request,
            occurredAt = baseInstant,
            phase = SynchronizationPhase.PUSHING,
        )

    private fun progressUpdatedEvent(id: String = "event-003"): SynchronizationEvent.ProgressUpdated =
        SynchronizationEvent.ProgressUpdated(
            id = SynchronizationEventId(id),
            request = request,
            occurredAt = baseInstant,
            progress = SynchronizationProgress(
                phase = SynchronizationPhase.PUSHING,
                completed = 5L,
                total = 10L,
                unit = SynchronizationProgressUnit.EVENTS,
            ),
        )

    private fun retryScheduledEvent(id: String = "event-004"): SynchronizationEvent.RetryScheduled =
        SynchronizationEvent.RetryScheduled(
            id = SynchronizationEventId(id),
            request = request,
            occurredAt = baseInstant,
            attempt = RetryAttempt(1),
            delay = SchedulingDelay(1000L),
            error = FakeDataLoomError(),
        )

    private fun conflictDetectedEvent(id: String = "event-005"): SynchronizationEvent.ConflictDetected {
        val entityRef = EntityReference(
            type = EntityType("invoice"),
            id = EntityId("entity-001"),
        )
        val localChange = ChangeEvent(
            id = ChangeEventId("change-local"),
            entity = entityRef,
            operation = ChangeOperation.UPDATE,
        )
        val remoteChange = ChangeEvent(
            id = ChangeEventId("change-remote"),
            entity = entityRef,
            operation = ChangeOperation.UPDATE,
        )
        return SynchronizationEvent.ConflictDetected(
            id = SynchronizationEventId(id),
            request = request,
            occurredAt = baseInstant,
            conflict = SynchronizationConflict(
                id = ConflictId("conflict-001"),
                type = ConflictType.CONCURRENT_CHANGE,
                entity = entityRef,
                localChange = localChange,
                remoteChange = remoteChange,
            ),
        )
    }

    private fun completedEvent(id: String = "event-006"): SynchronizationEvent.Completed {
        val result = SynchronizationResult.Succeeded(
            request = request,
            completedAt = baseInstant,
            summary = SynchronizationSummary(),
        )
        return SynchronizationEvent.Completed(
            id = SynchronizationEventId(id),
            request = request,
            occurredAt = baseInstant,
            result = result,
        )
    }

    // =========================================================================
    // SynchronizationObserverRegistry tests
    // =========================================================================

    @Test
    fun `empty registry has zero size and isEmpty is true`() {
        val registry = SynchronizationObserverRegistry(emptyList())
        assertEquals(0, registry.size)
        assertTrue(registry.isEmpty())
    }

    @Test
    fun `registry with one observer has size one`() {
        val observer = RecordingObserver("obs-a")
        val registry = SynchronizationObserverRegistry(listOf(observer))
        assertEquals(1, registry.size)
        assertFalse(registry.isEmpty())
    }

    @Test
    fun `registry with multiple observers has correct size`() {
        val registry = SynchronizationObserverRegistry(
            listOf(
                RecordingObserver("obs-a"),
                RecordingObserver("obs-b"),
                RecordingObserver("obs-c"),
            ),
        )
        assertEquals(3, registry.size)
    }

    @Test
    fun `registry preserves registration order`() {
        val a = RecordingObserver("obs-a")
        val b = RecordingObserver("obs-b")
        val c = RecordingObserver("obs-c")
        val registry = SynchronizationObserverRegistry(listOf(a, b, c))

        val ids = registry.observers.map { it.id.value }
        assertEquals(listOf("obs-a", "obs-b", "obs-c"), ids)
    }

    @Test
    fun `registry lookup returns observer for existing ID`() {
        val observer = RecordingObserver("obs-a")
        val registry = SynchronizationObserverRegistry(listOf(observer))

        val found = registry.lookup(SynchronizationObserverId("obs-a"))
        assertNotNull(found)
        assertSame(observer, found)
    }

    @Test
    fun `registry lookup returns null for missing ID`() {
        val registry = SynchronizationObserverRegistry(listOf(RecordingObserver("obs-a")))

        val result = registry.lookup(SynchronizationObserverId("obs-missing"))
        assertNull(result)
    }

    @Test
    fun `registry rejects duplicate observer IDs`() {
        val a1 = RecordingObserver("obs-a")
        val a2 = RecordingObserver("obs-a")

        assertFailsWith<IllegalArgumentException> {
            SynchronizationObserverRegistry(listOf(a1, a2))
        }
    }

    @Test
    fun `registry defensively copies caller collection`() {
        val mutableList = mutableListOf<SynchronizationObserver>(RecordingObserver("obs-a"))
        val registry = SynchronizationObserverRegistry(mutableList)

        mutableList.add(RecordingObserver("obs-b"))

        assertEquals(1, registry.size)
    }

    @Test
    fun `registry observers property returns snapshot immune to mutation`() {
        val registry = SynchronizationObserverRegistry(
            listOf(RecordingObserver("obs-a"), RecordingObserver("obs-b")),
        )

        val snapshot = registry.observers
        // The returned list is read-only; modifying the registry state externally is impossible.
        assertEquals(2, snapshot.size)
        // Call again to get fresh snapshot
        assertEquals(2, registry.observers.size)
    }

    @Test
    fun `registry construction does not invoke any observer`() {
        val observer = RecordingObserver("obs-a")
        SynchronizationObserverRegistry(listOf(observer))

        assertEquals(0, observer.callCount)
    }

    // =========================================================================
    // SynchronizationEventDispatchSummary tests
    // =========================================================================

    @Test
    fun `zero summary has all counts zero`() {
        val summary = SynchronizationEventDispatchSummary.Zero
        assertEquals(0, summary.registeredObserverCount)
        assertEquals(0, summary.attemptedObserverCount)
        assertEquals(0, summary.deliveredObserverCount)
        assertEquals(0, summary.failedObserverCount)
    }

    @Test
    fun `valid delivered summary is accepted`() {
        val summary = SynchronizationEventDispatchSummary(
            registeredObserverCount = 3,
            attemptedObserverCount = 3,
            deliveredObserverCount = 3,
            failedObserverCount = 0,
        )
        assertEquals(3, summary.deliveredObserverCount)
        assertEquals(0, summary.failedObserverCount)
    }

    @Test
    fun `valid partial summary is accepted`() {
        val summary = SynchronizationEventDispatchSummary(
            registeredObserverCount = 3,
            attemptedObserverCount = 3,
            deliveredObserverCount = 2,
            failedObserverCount = 1,
        )
        assertEquals(2, summary.deliveredObserverCount)
        assertEquals(1, summary.failedObserverCount)
    }

    @Test
    fun `negative registeredObserverCount is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            SynchronizationEventDispatchSummary(
                registeredObserverCount = -1,
                attemptedObserverCount = 0,
                deliveredObserverCount = 0,
                failedObserverCount = 0,
            )
        }
    }

    @Test
    fun `negative attemptedObserverCount is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            SynchronizationEventDispatchSummary(
                registeredObserverCount = 1,
                attemptedObserverCount = -1,
                deliveredObserverCount = 0,
                failedObserverCount = 0,
            )
        }
    }

    @Test
    fun `negative deliveredObserverCount is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            SynchronizationEventDispatchSummary(
                registeredObserverCount = 1,
                attemptedObserverCount = 1,
                deliveredObserverCount = -1,
                failedObserverCount = 0,
            )
        }
    }

    @Test
    fun `negative failedObserverCount is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            SynchronizationEventDispatchSummary(
                registeredObserverCount = 1,
                attemptedObserverCount = 1,
                deliveredObserverCount = 0,
                failedObserverCount = -1,
            )
        }
    }

    @Test
    fun `attempted exceeding registered is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            SynchronizationEventDispatchSummary(
                registeredObserverCount = 2,
                attemptedObserverCount = 3,
                deliveredObserverCount = 3,
                failedObserverCount = 0,
            )
        }
    }

    @Test
    fun `delivered exceeding attempted is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            SynchronizationEventDispatchSummary(
                registeredObserverCount = 3,
                attemptedObserverCount = 2,
                deliveredObserverCount = 3,
                failedObserverCount = 0,
            )
        }
    }

    @Test
    fun `failed exceeding attempted is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            SynchronizationEventDispatchSummary(
                registeredObserverCount = 3,
                attemptedObserverCount = 2,
                deliveredObserverCount = 0,
                failedObserverCount = 3,
            )
        }
    }

    @Test
    fun `delivered plus failed equals attempted for completed dispatch`() {
        val summary = SynchronizationEventDispatchSummary(
            registeredObserverCount = 5,
            attemptedObserverCount = 5,
            deliveredObserverCount = 3,
            failedObserverCount = 2,
        )
        assertEquals(
            summary.deliveredObserverCount + summary.failedObserverCount,
            summary.attemptedObserverCount,
        )
    }

    @Test
    fun `summary value equality works`() {
        val a = SynchronizationEventDispatchSummary(2, 2, 1, 1)
        val b = SynchronizationEventDispatchSummary(2, 2, 1, 1)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    // =========================================================================
    // No observers dispatch tests
    // =========================================================================

    @Test
    fun `dispatch to empty registry returns NoObservers`() {
        val registry = SynchronizationObserverRegistry(emptyList())
        val dispatcher = SynchronizationEventDispatcher(registry)
        val event = startedEvent()

        val result = dispatcher.dispatch(event)

        assertIs<SynchronizationEventDispatchResult.NoObservers>(result)
    }

    @Test
    fun `NoObservers result preserves event ID`() {
        val registry = SynchronizationObserverRegistry(emptyList())
        val dispatcher = SynchronizationEventDispatcher(registry)
        val event = startedEvent("event-no-obs")

        val result = dispatcher.dispatch(event)

        assertIs<SynchronizationEventDispatchResult.NoObservers>(result)
        assertEquals(SynchronizationEventId("event-no-obs"), result.eventId)
    }

    @Test
    fun `NoObservers result has zero summary`() {
        val registry = SynchronizationObserverRegistry(emptyList())
        val dispatcher = SynchronizationEventDispatcher(registry)

        val result = dispatcher.dispatch(startedEvent())

        assertIs<SynchronizationEventDispatchResult.NoObservers>(result)
        assertEquals(SynchronizationEventDispatchSummary.Zero, result.summary)
    }

    @Test
    fun `NoObservers result has empty failures`() {
        val registry = SynchronizationObserverRegistry(emptyList())
        val dispatcher = SynchronizationEventDispatcher(registry)

        val result = dispatcher.dispatch(startedEvent())

        assertIs<SynchronizationEventDispatchResult.NoObservers>(result)
        assertTrue(result.failures.isEmpty())
    }

    @Test
    fun `NoObservers dispatch invokes no callback`() {
        val observer = RecordingObserver("obs-a")
        // Intentionally not adding to registry to test empty case
        val registry = SynchronizationObserverRegistry(emptyList())
        val dispatcher = SynchronizationEventDispatcher(registry)

        dispatcher.dispatch(startedEvent())

        assertEquals(0, observer.callCount)
    }

    // =========================================================================
    // Successful delivery tests
    // =========================================================================

    @Test
    fun `one observer receives exact event instance`() {
        val observer = RecordingObserver("obs-a")
        val registry = SynchronizationObserverRegistry(listOf(observer))
        val dispatcher = SynchronizationEventDispatcher(registry)
        val event = startedEvent()

        dispatcher.dispatch(event)

        assertEquals(1, observer.events.size)
        assertSame(event, observer.events[0])
    }

    @Test
    fun `multiple observers receive exact event in registration order`() {
        val a = RecordingObserver("obs-a")
        val b = RecordingObserver("obs-b")
        val c = RecordingObserver("obs-c")
        val registry = SynchronizationObserverRegistry(listOf(a, b, c))
        val dispatcher = SynchronizationEventDispatcher(registry)
        val event = startedEvent()

        dispatcher.dispatch(event)

        assertSame(event, a.events[0])
        assertSame(event, b.events[0])
        assertSame(event, c.events[0])
    }

    @Test
    fun `each observer executes exactly once per dispatch`() {
        val a = RecordingObserver("obs-a")
        val b = RecordingObserver("obs-b")
        val registry = SynchronizationObserverRegistry(listOf(a, b))
        val dispatcher = SynchronizationEventDispatcher(registry)

        dispatcher.dispatch(startedEvent())

        assertEquals(1, a.callCount)
        assertEquals(1, b.callCount)
    }

    @Test
    fun `successful dispatch returns Delivered`() {
        val registry = SynchronizationObserverRegistry(listOf(RecordingObserver("obs-a")))
        val dispatcher = SynchronizationEventDispatcher(registry)

        val result = dispatcher.dispatch(startedEvent())

        assertIs<SynchronizationEventDispatchResult.Delivered>(result)
    }

    @Test
    fun `Delivered result has correct summary counts`() {
        val registry = SynchronizationObserverRegistry(
            listOf(RecordingObserver("obs-a"), RecordingObserver("obs-b")),
        )
        val dispatcher = SynchronizationEventDispatcher(registry)

        val result = dispatcher.dispatch(startedEvent())

        assertIs<SynchronizationEventDispatchResult.Delivered>(result)
        assertEquals(2, result.summary.registeredObserverCount)
        assertEquals(2, result.summary.attemptedObserverCount)
        assertEquals(2, result.summary.deliveredObserverCount)
        assertEquals(0, result.summary.failedObserverCount)
    }

    @Test
    fun `Delivered result has empty failures list`() {
        val registry = SynchronizationObserverRegistry(listOf(RecordingObserver("obs-a")))
        val dispatcher = SynchronizationEventDispatcher(registry)

        val result = dispatcher.dispatch(startedEvent())

        assertIs<SynchronizationEventDispatchResult.Delivered>(result)
        assertTrue(result.failures.isEmpty())
    }

    @Test
    fun `Delivered result preserves event ID`() {
        val registry = SynchronizationObserverRegistry(listOf(RecordingObserver("obs-a")))
        val dispatcher = SynchronizationEventDispatcher(registry)
        val event = startedEvent("event-delivered")

        val result = dispatcher.dispatch(event)

        assertIs<SynchronizationEventDispatchResult.Delivered>(result)
        assertEquals(SynchronizationEventId("event-delivered"), result.eventId)
    }

    // =========================================================================
    // Event variant delivery tests
    // =========================================================================

    @Test
    fun `Started event is delivered unchanged`() {
        val observer = RecordingObserver("obs-a")
        val registry = SynchronizationObserverRegistry(listOf(observer))
        val dispatcher = SynchronizationEventDispatcher(registry)
        val event = startedEvent()

        dispatcher.dispatch(event)

        val received = observer.events.first()
        assertIs<SynchronizationEvent.Started>(received)
        assertSame(event, received)
    }

    @Test
    fun `PhaseChanged event is delivered unchanged`() {
        val observer = RecordingObserver("obs-a")
        val registry = SynchronizationObserverRegistry(listOf(observer))
        val dispatcher = SynchronizationEventDispatcher(registry)
        val event = phaseChangedEvent()

        dispatcher.dispatch(event)

        val received = observer.events.first()
        assertIs<SynchronizationEvent.PhaseChanged>(received)
        assertSame(event, received)
    }

    @Test
    fun `ProgressUpdated event is delivered unchanged`() {
        val observer = RecordingObserver("obs-a")
        val registry = SynchronizationObserverRegistry(listOf(observer))
        val dispatcher = SynchronizationEventDispatcher(registry)
        val event = progressUpdatedEvent()

        dispatcher.dispatch(event)

        val received = observer.events.first()
        assertIs<SynchronizationEvent.ProgressUpdated>(received)
        assertSame(event, received)
    }

    @Test
    fun `RetryScheduled event is delivered unchanged`() {
        val observer = RecordingObserver("obs-a")
        val registry = SynchronizationObserverRegistry(listOf(observer))
        val dispatcher = SynchronizationEventDispatcher(registry)
        val event = retryScheduledEvent()

        dispatcher.dispatch(event)

        val received = observer.events.first()
        assertIs<SynchronizationEvent.RetryScheduled>(received)
        assertSame(event, received)
    }

    @Test
    fun `ConflictDetected event is delivered unchanged`() {
        val observer = RecordingObserver("obs-a")
        val registry = SynchronizationObserverRegistry(listOf(observer))
        val dispatcher = SynchronizationEventDispatcher(registry)
        val event = conflictDetectedEvent()

        dispatcher.dispatch(event)

        val received = observer.events.first()
        assertIs<SynchronizationEvent.ConflictDetected>(received)
        assertSame(event, received)
    }

    @Test
    fun `Completed event is delivered unchanged`() {
        val observer = RecordingObserver("obs-a")
        val registry = SynchronizationObserverRegistry(listOf(observer))
        val dispatcher = SynchronizationEventDispatcher(registry)
        val event = completedEvent()

        dispatcher.dispatch(event)

        val received = observer.events.first()
        assertIs<SynchronizationEvent.Completed>(received)
        assertSame(event, received)
    }

    // =========================================================================
    // Partial delivery tests
    // =========================================================================

    @Test
    fun `first succeeds second fails third still executes returns PartiallyDelivered`() {
        val a = RecordingObserver("obs-a")
        val b = FailingObserver("obs-b")
        val c = RecordingObserver("obs-c")
        val registry = SynchronizationObserverRegistry(listOf(a, b, c))
        val dispatcher = SynchronizationEventDispatcher(registry)

        val result = dispatcher.dispatch(startedEvent())

        assertIs<SynchronizationEventDispatchResult.PartiallyDelivered>(result)
        assertEquals(1, a.callCount)
        assertEquals(1, b.callCount)
        assertEquals(1, c.callCount)
    }

    @Test
    fun `PartiallyDelivered has correct delivery and failure counts`() {
        val registry = SynchronizationObserverRegistry(
            listOf(
                RecordingObserver("obs-a"),
                FailingObserver("obs-b"),
                RecordingObserver("obs-c"),
            ),
        )
        val dispatcher = SynchronizationEventDispatcher(registry)

        val result = dispatcher.dispatch(startedEvent())

        assertIs<SynchronizationEventDispatchResult.PartiallyDelivered>(result)
        assertEquals(2, result.summary.deliveredObserverCount)
        assertEquals(1, result.summary.failedObserverCount)
        assertEquals(3, result.summary.attemptedObserverCount)
    }

    @Test
    fun `PartiallyDelivered failure identifies exact observer and event IDs`() {
        val failing = FailingObserver("obs-failing")
        val registry = SynchronizationObserverRegistry(
            listOf(RecordingObserver("obs-a"), failing),
        )
        val dispatcher = SynchronizationEventDispatcher(registry)
        val event = startedEvent("event-partial")

        val result = dispatcher.dispatch(event)

        assertIs<SynchronizationEventDispatchResult.PartiallyDelivered>(result)
        val failure = result.failures.first()
        assertEquals(SynchronizationObserverId("obs-failing"), failure.observerId)
        assertEquals(SynchronizationEventId("event-partial"), failure.eventId)
    }

    @Test
    fun `PartiallyDelivered failure has OBSERVER_CALLBACK_FAILED reason`() {
        val registry = SynchronizationObserverRegistry(
            listOf(FailingObserver("obs-b"), RecordingObserver("obs-a")),
        )
        val dispatcher = SynchronizationEventDispatcher(registry)

        val result = dispatcher.dispatch(startedEvent())

        assertIs<SynchronizationEventDispatchResult.PartiallyDelivered>(result)
        assertEquals(
            SynchronizationObserverDispatchFailureReason.OBSERVER_CALLBACK_FAILED,
            result.failures.first().reason,
        )
    }

    @Test
    fun `failure does not expose exception message`() {
        val registry = SynchronizationObserverRegistry(
            listOf(FailingObserver("obs-b"), RecordingObserver("obs-a")),
        )
        val dispatcher = SynchronizationEventDispatcher(registry)

        val result = dispatcher.dispatch(startedEvent())

        assertIs<SynchronizationEventDispatchResult.PartiallyDelivered>(result)
        val failure = result.failures.first()
        assertFalse(
            failure.error.message.contains("intentionally failed"),
            "Error message must not include the observer exception message.",
        )
    }

    @Test
    fun `failure order follows observer invocation order`() {
        val a = FailingObserver("obs-a")
        val b = RecordingObserver("obs-b")
        val c = FailingObserver("obs-c")
        val registry = SynchronizationObserverRegistry(listOf(a, b, c))
        val dispatcher = SynchronizationEventDispatcher(registry)

        val result = dispatcher.dispatch(startedEvent())

        assertIs<SynchronizationEventDispatchResult.PartiallyDelivered>(result)
        assertEquals(SynchronizationObserverId("obs-a"), result.failures[0].observerId)
        assertEquals(SynchronizationObserverId("obs-c"), result.failures[1].observerId)
    }

    // =========================================================================
    // Complete delivery failure tests
    // =========================================================================

    @Test
    fun `all observers failing returns DeliveryFailed`() {
        val registry = SynchronizationObserverRegistry(
            listOf(FailingObserver("obs-a"), FailingObserver("obs-b")),
        )
        val dispatcher = SynchronizationEventDispatcher(registry)

        val result = dispatcher.dispatch(startedEvent())

        assertIs<SynchronizationEventDispatchResult.DeliveryFailed>(result)
    }

    @Test
    fun `DeliveryFailed all observers are still attempted`() {
        val a = FailingObserver("obs-a")
        val b = FailingObserver("obs-b")
        val registry = SynchronizationObserverRegistry(listOf(a, b))
        val dispatcher = SynchronizationEventDispatcher(registry)

        val result = dispatcher.dispatch(startedEvent())

        assertIs<SynchronizationEventDispatchResult.DeliveryFailed>(result)
        assertEquals(1, a.callCount)
        assertEquals(1, b.callCount)
    }

    @Test
    fun `DeliveryFailed failure count matches attempted count`() {
        val registry = SynchronizationObserverRegistry(
            listOf(FailingObserver("obs-a"), FailingObserver("obs-b"), FailingObserver("obs-c")),
        )
        val dispatcher = SynchronizationEventDispatcher(registry)

        val result = dispatcher.dispatch(startedEvent())

        assertIs<SynchronizationEventDispatchResult.DeliveryFailed>(result)
        assertEquals(3, result.summary.failedObserverCount)
        assertEquals(3, result.summary.attemptedObserverCount)
        assertEquals(0, result.summary.deliveredObserverCount)
    }

    @Test
    fun `DeliveryFailed preserves ordered failures`() {
        val registry = SynchronizationObserverRegistry(
            listOf(FailingObserver("obs-a"), FailingObserver("obs-b")),
        )
        val dispatcher = SynchronizationEventDispatcher(registry)

        val result = dispatcher.dispatch(startedEvent())

        assertIs<SynchronizationEventDispatchResult.DeliveryFailed>(result)
        assertEquals(2, result.failures.size)
        assertEquals(SynchronizationObserverId("obs-a"), result.failures[0].observerId)
        assertEquals(SynchronizationObserverId("obs-b"), result.failures[1].observerId)
    }

    // =========================================================================
    // Failure isolation tests
    // =========================================================================

    @Test
    fun `observer failure does not stop later observers`() {
        val later = RecordingObserver("obs-later")
        val registry = SynchronizationObserverRegistry(
            listOf(FailingObserver("obs-failing"), later),
        )
        val dispatcher = SynchronizationEventDispatcher(registry)

        dispatcher.dispatch(startedEvent())

        assertEquals(1, later.callCount)
    }

    @Test
    fun `each observer executes at most once per dispatch even after failure`() {
        val failing = FailingObserver("obs-a")
        val recording = RecordingObserver("obs-b")
        val registry = SynchronizationObserverRegistry(listOf(failing, recording))
        val dispatcher = SynchronizationEventDispatcher(registry)

        dispatcher.dispatch(startedEvent())

        assertEquals(1, failing.callCount)
        assertEquals(1, recording.callCount)
    }

    // =========================================================================
    // Cancellation tests
    // =========================================================================

    @Test
    fun `CancellationException from first observer propagates`() {
        val cancelling = CancellingObserver("obs-cancelling")
        val later = RecordingObserver("obs-later")
        val registry = SynchronizationObserverRegistry(listOf(cancelling, later))
        val dispatcher = SynchronizationEventDispatcher(registry)

        assertFailsWith<CancellationException> {
            dispatcher.dispatch(startedEvent())
        }
    }

    @Test
    fun `later observers are not invoked after CancellationException`() {
        val cancelling = CancellingObserver("obs-cancelling")
        val later = RecordingObserver("obs-later")
        val registry = SynchronizationObserverRegistry(listOf(cancelling, later))
        val dispatcher = SynchronizationEventDispatcher(registry)

        try {
            dispatcher.dispatch(startedEvent())
        } catch (_: CancellationException) {
            // expected
        }

        assertEquals(0, later.callCount)
    }

    @Test
    fun `CancellationException from middle observer propagates without later delivery`() {
        val a = RecordingObserver("obs-a")
        val b = CancellingObserver("obs-b")
        val c = RecordingObserver("obs-c")
        val registry = SynchronizationObserverRegistry(listOf(a, b, c))
        val dispatcher = SynchronizationEventDispatcher(registry)

        assertFailsWith<CancellationException> {
            dispatcher.dispatch(startedEvent())
        }

        assertEquals(1, a.callCount)
        assertEquals(1, b.callCount)
        assertEquals(0, c.callCount)
    }

    @Test
    fun `CancellationException is not recorded as dispatch failure`() {
        // The exception propagates, so no result is returned — we verify only that
        // it was not swallowed or converted to a failure result.
        val cancelling = CancellingObserver("obs-cancelling")
        val registry = SynchronizationObserverRegistry(listOf(cancelling))
        val dispatcher = SynchronizationEventDispatcher(registry)

        var propagated = false
        try {
            dispatcher.dispatch(startedEvent())
        } catch (_: CancellationException) {
            propagated = true
        }
        assertTrue(propagated, "CancellationException must propagate out of dispatch.")
    }

    // =========================================================================
    // Fatal error propagation tests
    // =========================================================================

    /** Observer that throws an Error (fatal). */
    private class FatalErrorObserver(idValue: String) : SynchronizationObserver {
        override val id: SynchronizationObserverId = SynchronizationObserverId(idValue)
        var callCount: Int = 0

        override fun onEvent(event: SynchronizationEvent) {
            callCount++
            throw OutOfMemoryError("Fatal error from test observer.")
        }
    }

    @Test
    fun `fatal Error from observer propagates out of dispatch`() {
        val fatal = FatalErrorObserver("obs-fatal")
        val registry = SynchronizationObserverRegistry(listOf(fatal))
        val dispatcher = SynchronizationEventDispatcher(registry)

        assertFailsWith<OutOfMemoryError> {
            dispatcher.dispatch(startedEvent())
        }
    }

    @Test
    fun `later observer is not invoked after fatal Error`() {
        val fatal = FatalErrorObserver("obs-fatal")
        val later = RecordingObserver("obs-later")
        val registry = SynchronizationObserverRegistry(listOf(fatal, later))
        val dispatcher = SynchronizationEventDispatcher(registry)

        try {
            dispatcher.dispatch(startedEvent())
        } catch (_: OutOfMemoryError) {
            // expected
        }

        assertEquals(0, later.callCount)
    }

    // =========================================================================
    // Exact event preservation tests
    // =========================================================================

    @Test
    fun `event ID is unchanged when received by observers`() {
        val a = RecordingObserver("obs-a")
        val b = RecordingObserver("obs-b")
        val registry = SynchronizationObserverRegistry(listOf(a, b))
        val dispatcher = SynchronizationEventDispatcher(registry)
        val event = startedEvent("event-id-check")

        dispatcher.dispatch(event)

        assertEquals(SynchronizationEventId("event-id-check"), a.events.first().id)
        assertEquals(SynchronizationEventId("event-id-check"), b.events.first().id)
    }

    @Test
    fun `request identity is unchanged when received by observers`() {
        val observer = RecordingObserver("obs-a")
        val registry = SynchronizationObserverRegistry(listOf(observer))
        val dispatcher = SynchronizationEventDispatcher(registry)
        val event = startedEvent()

        dispatcher.dispatch(event)

        assertSame(event.request, observer.events.first().request)
    }

    @Test
    fun `timestamp is unchanged when received by observers`() {
        val observer = RecordingObserver("obs-a")
        val registry = SynchronizationObserverRegistry(listOf(observer))
        val dispatcher = SynchronizationEventDispatcher(registry)
        val event = startedEvent()

        dispatcher.dispatch(event)

        assertEquals(event.occurredAt, observer.events.first().occurredAt)
    }

    @Test
    fun `progress is unchanged in ProgressUpdated event received by observers`() {
        val observer = RecordingObserver("obs-a")
        val registry = SynchronizationObserverRegistry(listOf(observer))
        val dispatcher = SynchronizationEventDispatcher(registry)
        val event = progressUpdatedEvent()

        dispatcher.dispatch(event)

        val received = assertIs<SynchronizationEvent.ProgressUpdated>(observer.events.first())
        assertSame(event.progress, received.progress)
    }

    @Test
    fun `result is unchanged in Completed event received by observers`() {
        val observer = RecordingObserver("obs-a")
        val registry = SynchronizationObserverRegistry(listOf(observer))
        val dispatcher = SynchronizationEventDispatcher(registry)
        val event = completedEvent()

        dispatcher.dispatch(event)

        val received = assertIs<SynchronizationEvent.Completed>(observer.events.first())
        assertSame(event.result, received.result)
    }

    @Test
    fun `conflict is unchanged in ConflictDetected event received by observers`() {
        val observer = RecordingObserver("obs-a")
        val registry = SynchronizationObserverRegistry(listOf(observer))
        val dispatcher = SynchronizationEventDispatcher(registry)
        val event = conflictDetectedEvent()

        dispatcher.dispatch(event)

        val received = assertIs<SynchronizationEvent.ConflictDetected>(observer.events.first())
        assertSame(event.conflict, received.conflict)
    }

    // =========================================================================
    // Cross-call state boundary tests
    // =========================================================================

    @Test
    fun `dispatcher maintains no mutable cross-call event state`() {
        val observer = RecordingObserver("obs-a")
        val registry = SynchronizationObserverRegistry(listOf(observer))
        val dispatcher = SynchronizationEventDispatcher(registry)
        val event1 = startedEvent("event-001")
        val event2 = phaseChangedEvent("event-002")

        dispatcher.dispatch(event1)
        dispatcher.dispatch(event2)

        assertEquals(2, observer.callCount)
        assertSame(event1, observer.events[0])
        assertSame(event2, observer.events[1])
    }

    @Test
    fun `each dispatch call produces an independent result`() {
        val registry = SynchronizationObserverRegistry(listOf(RecordingObserver("obs-a")))
        val dispatcher = SynchronizationEventDispatcher(registry)

        val result1 = dispatcher.dispatch(startedEvent("event-001"))
        val result2 = dispatcher.dispatch(phaseChangedEvent("event-002"))

        assertIs<SynchronizationEventDispatchResult.Delivered>(result1)
        assertIs<SynchronizationEventDispatchResult.Delivered>(result2)
        assertEquals(SynchronizationEventId("event-001"), result1.eventId)
        assertEquals(SynchronizationEventId("event-002"), result2.eventId)
    }

    // =========================================================================
    // Security tests
    // =========================================================================

    @Test
    fun `dispatch failure error message does not contain exception text`() {
        val registry = SynchronizationObserverRegistry(
            listOf(FailingObserver("obs-fail"), RecordingObserver("obs-ok")),
        )
        val dispatcher = SynchronizationEventDispatcher(registry)

        val result = dispatcher.dispatch(startedEvent())

        assertIs<SynchronizationEventDispatchResult.PartiallyDelivered>(result)
        val failure = result.failures.first()
        assertFalse(
            failure.error.message.contains("intentionally failed"),
            "Failure error message must not include the observer exception message.",
        )
    }

    @Test
    fun `dispatch failure error code is canonical DL error code`() {
        val registry = SynchronizationObserverRegistry(
            listOf(FailingObserver("obs-fail"), RecordingObserver("obs-ok")),
        )
        val dispatcher = SynchronizationEventDispatcher(registry)

        val result = dispatcher.dispatch(startedEvent())

        assertIs<SynchronizationEventDispatchResult.PartiallyDelivered>(result)
        val failure = result.failures.first()
        assertTrue(
            failure.error.code.value.isNotBlank(),
            "Failure error code must be a non-blank canonical code.",
        )
    }

    @Test
    fun `dispatch result contains no Throwable`() {
        val registry = SynchronizationObserverRegistry(
            listOf(FailingObserver("obs-fail"), RecordingObserver("obs-ok")),
        )
        val dispatcher = SynchronizationEventDispatcher(registry)

        val result = dispatcher.dispatch(startedEvent())

        assertIs<SynchronizationEventDispatchResult.PartiallyDelivered>(result)
        // SynchronizationObserverDispatchFailure exposes no Throwable.
        val failure = result.failures.first()
        assertNull(failure.error.cause, "Canonical error cause must be null in dispatch failure.")
    }

    // =========================================================================
    // SynchronizationEventDispatchResult invariant tests
    // =========================================================================

    @Test
    fun `NoObservers rejects non-zero summary`() {
        assertFailsWith<IllegalArgumentException> {
            SynchronizationEventDispatchResult.NoObservers(
                eventId = SynchronizationEventId("event-001"),
                summary = SynchronizationEventDispatchSummary(1, 0, 0, 0),
            )
        }
    }

    @Test
    fun `Delivered rejects zero delivered count`() {
        assertFailsWith<IllegalArgumentException> {
            SynchronizationEventDispatchResult.Delivered(
                eventId = SynchronizationEventId("event-001"),
                summary = SynchronizationEventDispatchSummary(2, 2, 0, 0),
            )
        }
    }

    @Test
    fun `Delivered rejects non-zero failed count`() {
        assertFailsWith<IllegalArgumentException> {
            SynchronizationEventDispatchResult.Delivered(
                eventId = SynchronizationEventId("event-001"),
                summary = SynchronizationEventDispatchSummary(2, 2, 1, 1),
            )
        }
    }

    @Test
    fun `PartiallyDelivered rejects empty failures list`() {
        assertFailsWith<IllegalArgumentException> {
            SynchronizationEventDispatchResult.PartiallyDelivered(
                eventId = SynchronizationEventId("event-001"),
                summary = SynchronizationEventDispatchSummary(2, 2, 1, 1),
                failures = emptyList(),
            )
        }
    }

    @Test
    fun `PartiallyDelivered rejects zero delivered count`() {
        val failure = buildFakeFailure("obs-a", "event-001")
        assertFailsWith<IllegalArgumentException> {
            SynchronizationEventDispatchResult.PartiallyDelivered(
                eventId = SynchronizationEventId("event-001"),
                summary = SynchronizationEventDispatchSummary(2, 2, 0, 2),
                failures = listOf(failure, failure),
            )
        }
    }

    @Test
    fun `DeliveryFailed rejects empty failures list`() {
        assertFailsWith<IllegalArgumentException> {
            SynchronizationEventDispatchResult.DeliveryFailed(
                eventId = SynchronizationEventId("event-001"),
                summary = SynchronizationEventDispatchSummary(2, 2, 0, 2),
                failures = emptyList(),
            )
        }
    }

    @Test
    fun `DeliveryFailed rejects non-zero delivered count`() {
        val failure = buildFakeFailure("obs-a", "event-001")
        assertFailsWith<IllegalArgumentException> {
            SynchronizationEventDispatchResult.DeliveryFailed(
                eventId = SynchronizationEventId("event-001"),
                summary = SynchronizationEventDispatchSummary(2, 2, 1, 1),
                failures = listOf(failure),
            )
        }
    }

    @Test
    fun `PartiallyDelivered defensively copies failures list`() {
        val failure = buildFakeFailure("obs-a", "event-001")
        val mutableList = mutableListOf(failure)
        val result = SynchronizationEventDispatchResult.PartiallyDelivered(
            eventId = SynchronizationEventId("event-001"),
            summary = SynchronizationEventDispatchSummary(2, 2, 1, 1),
            failures = mutableList,
        )

        mutableList.add(buildFakeFailure("obs-b", "event-001"))

        assertEquals(1, result.failures.size)
    }

    @Test
    fun `DeliveryFailed defensively copies failures list`() {
        val failure = buildFakeFailure("obs-a", "event-001")
        val mutableList = mutableListOf(failure)
        val result = SynchronizationEventDispatchResult.DeliveryFailed(
            eventId = SynchronizationEventId("event-001"),
            summary = SynchronizationEventDispatchSummary(1, 1, 0, 1),
            failures = mutableList,
        )

        mutableList.add(buildFakeFailure("obs-b", "event-001"))

        assertEquals(1, result.failures.size)
    }

    // =========================================================================
    // SynchronizationObserverDispatchFailure tests
    // =========================================================================

    @Test
    fun `dispatch failure preserves observer ID and event ID`() {
        val failure = SynchronizationObserverDispatchFailure(
            observerId = SynchronizationObserverId("obs-test"),
            eventId = SynchronizationEventId("event-test"),
            reason = SynchronizationObserverDispatchFailureReason.OBSERVER_CALLBACK_FAILED,
            error = FakeDataLoomError(),
        )
        assertEquals(SynchronizationObserverId("obs-test"), failure.observerId)
        assertEquals(SynchronizationEventId("event-test"), failure.eventId)
    }

    @Test
    fun `dispatch failure value equality works`() {
        val a = buildFakeFailure("obs-a", "event-001")
        val b = buildFakeFailure("obs-a", "event-001")
        assertEquals(a, b)
    }

    // =========================================================================
    // KMP compatibility notes (no Android or JVM-only API usage)
    // =========================================================================

    @Test
    fun `dispatcher uses no platform specific API`() {
        // If this test compiles and runs in commonTest it confirms KMP compatibility.
        val registry = SynchronizationObserverRegistry(emptyList())
        val dispatcher = SynchronizationEventDispatcher(registry)
        assertNotNull(dispatcher)
    }

    @Test
    fun `registry uses no platform specific API`() {
        val registry = SynchronizationObserverRegistry(listOf(RecordingObserver("obs-a")))
        assertNotNull(registry)
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun buildFakeFailure(
        observerIdValue: String,
        eventIdValue: String,
    ): SynchronizationObserverDispatchFailure = SynchronizationObserverDispatchFailure(
        observerId = SynchronizationObserverId(observerIdValue),
        eventId = SynchronizationEventId(eventIdValue),
        reason = SynchronizationObserverDispatchFailureReason.OBSERVER_CALLBACK_FAILED,
        error = FakeDataLoomError(),
    )
}
