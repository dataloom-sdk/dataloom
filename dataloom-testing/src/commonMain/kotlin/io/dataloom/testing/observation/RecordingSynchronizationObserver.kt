package io.dataloom.testing.observation

import io.dataloom.api.identifier.SynchronizationObserverId
import io.dataloom.api.observation.SynchronizationObserver
import io.dataloom.api.synchronization.SynchronizationEvent

/**
 * Recording [SynchronizationObserver] for deterministic tests.
 *
 * The observer records every event in order and optionally forwards the event
 * to [onEventCallback]. Callback exceptions are propagated to the caller after
 * the event is recorded.
 *
 * @param id stable synchronization observer identifier.
 * @param onEventCallback optional callback invoked after recording each event.
 */
public class RecordingSynchronizationObserver(
    override val id: SynchronizationObserverId,
    private val onEventCallback: ((SynchronizationEvent) -> Unit)? = null,
) : SynchronizationObserver {
    private val recordedEvents: MutableList<SynchronizationEvent> = mutableListOf()

    /** Snapshot of all recorded events in call order. */
    public val events: List<SynchronizationEvent>
        get() = recordedEvents.toList()

    /** Number of events recorded so far. */
    public val eventCount: Int
        get() = recordedEvents.size

    /** Most recently recorded event, or `null` when no event has been observed. */
    public val latestEvent: SynchronizationEvent?
        get() = recordedEvents.lastOrNull()

    override fun onEvent(event: SynchronizationEvent) {
        recordedEvents += event
        onEventCallback?.invoke(event)
    }

    /** Clears all recorded events. */
    public fun clearRecordings() {
        recordedEvents.clear()
    }
}
