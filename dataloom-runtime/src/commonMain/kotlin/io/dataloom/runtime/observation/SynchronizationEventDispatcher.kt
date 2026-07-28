package io.dataloom.runtime.observation

import io.dataloom.api.identifier.SynchronizationObserverId
import io.dataloom.api.observation.SynchronizationObserver
import io.dataloom.api.synchronization.SynchronizationEvent
import kotlin.coroutines.cancellation.CancellationException

/**
 * Platform-independent event dispatcher that delivers a
 * [SynchronizationEvent] to every registered
 * [io.dataloom.api.observation.SynchronizationObserver] in registration order.
 *
 * ## Purpose
 *
 * [SynchronizationEventDispatcher] connects the DataLoom runtime event
 * emission point to registered observers through a deterministic sequential
 * delivery sequence. It translates observer callback outcomes into a structured
 * [SynchronizationEventDispatchResult].
 *
 * ## Required delivery flow
 *
 * [dispatch] follows this exact sequence for every call:
 *
 * ```text
 * SynchronizationEventDispatcher.dispatch(event)
 *     → SynchronizationObserverRegistry
 *     → Observer A.onEvent(event)
 *     → Observer B.onEvent(event)
 *     → Observer C.onEvent(event)
 *     → SynchronizationEventDispatchResult
 * ```
 *
 * 1. Receive the exact [SynchronizationEvent] instance.
 * 2. Read observers from the registry in registration order.
 * 3. If no observers are registered, return
 *    [SynchronizationEventDispatchResult.NoObservers]; no callback is invoked.
 * 4. Invoke each observer sequentially in registration order.
 * 5. Pass the exact event instance to every observer.
 * 6. Count successful delivery only after [SynchronizationObserver.onEvent]
 *    returns normally.
 * 7. Isolate ordinary observer callback failures: catch ordinary exceptions,
 *    record a structured [SynchronizationObserverDispatchFailure], and
 *    continue to the next observer.
 * 8. Preserve failure order (invocation order).
 * 9. Return the correct [SynchronizationEventDispatchResult] variant.
 *
 * ## Exact event preservation
 *
 * The exact [SynchronizationEvent] instance is passed to every observer.
 * No event copy is created, no field is modified, and no variant is
 * reconstructed.
 *
 * ## Observer failure isolation
 *
 * An ordinary observer callback exception (any [Exception] that is not a
 * [CancellationException]) must not stop delivery to later observers. The
 * exception is never re-thrown; a [SynchronizationObserverDispatchFailure]
 * is recorded instead.
 *
 * ## Cancellation boundary
 *
 * A [CancellationException] thrown by [SynchronizationObserver.onEvent]
 * propagates normally. Later observers are not invoked. Cancellation is not
 * recorded as a [SynchronizationObserverDispatchFailure] and no normal
 * [SynchronizationEventDispatchResult] is returned.
 *
 * ## Fatal-error boundary
 *
 * A fatal [Error] thrown by [SynchronizationObserver.onEvent] propagates
 * normally. Later observers are not invoked. Fatal errors are not converted
 * into dispatch failures.
 *
 * ## Event-ordering boundary
 *
 * Within one [dispatch] call, observers execute in registry registration
 * order. Across multiple [dispatch] calls, ordering is the caller's
 * responsibility. This dispatcher maintains no global event queue and
 * performs no cross-call serialization. Concurrent callers may interleave
 * unless callers serialize them externally.
 *
 * ## Performance
 *
 * [dispatch] performs exactly one bounded pass through registered observers.
 * Observers are invoked sequentially. No event copy is created. No payload
 * copy is created. No serialization occurs. No reflection is used. Only
 * bounded failure information is allocated.
 *
 * ## Boundaries
 *
 * [dispatch] must not invoke:
 * - [io.dataloom.api.provider.StorageProvider]
 * - [io.dataloom.api.provider.TransportProvider]
 * - [io.dataloom.api.scheduling.SchedulerProvider]
 * - [io.dataloom.api.connectivity.ConnectivityProvider]
 * - [io.dataloom.api.queue.QueueProvider]
 * - Provider lifecycle coordination
 * - Provider binding resolution
 * - [io.dataloom.api.retry.RetryPolicy]
 * - [io.dataloom.api.conflict.ConflictDetector]
 * - [io.dataloom.api.conflict.ConflictResolver]
 * - [io.dataloom.runtime.execution.SynchronizationExecutionCoordinator]
 * - Any synchronization pipeline
 *
 * ## No owned CoroutineScope, Flow, or Channel
 *
 * This class owns no [kotlinx.coroutines.CoroutineScope], exposes no
 * [kotlinx.coroutines.flow.Flow], [kotlinx.coroutines.flow.StateFlow],
 * [kotlinx.coroutines.flow.SharedFlow], or [kotlinx.coroutines.channels.Channel].
 * No background delivery or parallel fan-out occurs.
 *
 * ## No global state
 *
 * Uses no global registry, no service locator, no reflection, and no
 * ServiceLoader.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 *
 * @param observerRegistry The immutable registry of observers to deliver
 *   events to. Observers are called in registration order on every [dispatch]
 *   invocation.
 */
public class SynchronizationEventDispatcher(
    private val observerRegistry: SynchronizationObserverRegistry,
) {

    /**
     * Delivers [event] to every registered observer in registration order and
     * returns a structured [SynchronizationEventDispatchResult].
     *
     * ## Delivery sequence
     *
     * 1. Read observers from the registry in registration order.
     * 2. If no observers are registered, return
     *    [SynchronizationEventDispatchResult.NoObservers].
     * 3. Invoke each observer's [SynchronizationObserver.onEvent] sequentially.
     * 4. Isolate ordinary observer callback exceptions; record a
     *    [SynchronizationObserverDispatchFailure] and continue.
     * 5. Allow [CancellationException] to propagate without recording a
     *    failure.
     * 6. Allow fatal [Error] to propagate without recording a failure.
     * 7. Return the appropriate [SynchronizationEventDispatchResult] variant.
     *
     * ## Event immutability
     *
     * The exact [event] instance is passed to every observer. No copy is
     * created and no field is modified.
     *
     * ## Cross-call ordering
     *
     * Within this call, observers execute in registration order. Ordering
     * across multiple [dispatch] calls is the caller's responsibility.
     * Concurrent callers may interleave unless serialized externally.
     *
     * @param event The immutable synchronization lifecycle event to deliver.
     *   Passed unchanged to every registered observer.
     * @return A [SynchronizationEventDispatchResult] describing the outcome.
     *   [CancellationException] and fatal [Error] propagate instead of
     *   producing a result.
     * @throws CancellationException if any observer callback throws
     *   [CancellationException].
     */
    public fun dispatch(event: SynchronizationEvent): SynchronizationEventDispatchResult {
        val observers = observerRegistry.observers
        val registeredCount = observers.size

        if (observers.isEmpty()) {
            return SynchronizationEventDispatchResult.NoObservers(
                eventId = event.id,
                summary = SynchronizationEventDispatchSummary.Zero,
            )
        }

        val failures = mutableListOf<SynchronizationObserverDispatchFailure>()
        var deliveredCount = 0

        for (observer in observers) {
            deliverToObserver(observer, event, failures)
                .also { success -> if (success) deliveredCount++ }
        }

        val attemptedCount = observers.size
        val failedCount = failures.size
        val summary = SynchronizationEventDispatchSummary(
            registeredObserverCount = registeredCount,
            attemptedObserverCount = attemptedCount,
            deliveredObserverCount = deliveredCount,
            failedObserverCount = failedCount,
        )

        return when {
            failedCount == 0 -> SynchronizationEventDispatchResult.Delivered(
                eventId = event.id,
                summary = summary,
            )
            deliveredCount == 0 -> SynchronizationEventDispatchResult.DeliveryFailed(
                eventId = event.id,
                summary = summary,
                failures = failures,
            )
            else -> SynchronizationEventDispatchResult.PartiallyDelivered(
                eventId = event.id,
                summary = summary,
                failures = failures,
            )
        }
    }

    /**
     * Invokes [observer.onEvent][SynchronizationObserver.onEvent] with the
     * exact [event] instance and returns `true` on success.
     *
     * Ordinary exceptions are isolated: a [SynchronizationObserverDispatchFailure]
     * is added to [failures] and `false` is returned.
     *
     * [CancellationException] propagates normally; it is never recorded as a
     * failure.
     *
     * Fatal [Error] propagates normally; it is never recorded as a failure.
     *
     * @param observer The observer to invoke.
     * @param event The event to deliver.
     * @param failures The mutable list to which a failure record is appended
     *   on ordinary callback exception.
     * @return `true` if delivery succeeded; `false` if an ordinary exception
     *   was isolated.
     */
    private fun deliverToObserver(
        observer: SynchronizationObserver,
        event: SynchronizationEvent,
        failures: MutableList<SynchronizationObserverDispatchFailure>,
    ): Boolean {
        try {
            observer.onEvent(event)
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failures.add(buildFailure(observer.id, event))
            return false
        }
    }

    /**
     * Builds a [SynchronizationObserverDispatchFailure] with safe diagnostic
     * information.
     *
     * The observer's exception message, class name, and stack trace are never
     * included. The event payload is never included. Only the observer ID,
     * event ID, and event variant name are captured.
     *
     * @param observerId The ID of the observer that failed.
     * @param event The event that could not be delivered.
     * @return A safe [SynchronizationObserverDispatchFailure].
     */
    private fun buildFailure(
        observerId: SynchronizationObserverId,
        event: SynchronizationEvent,
    ): SynchronizationObserverDispatchFailure {
        val variantName = eventVariantName(event)
        return SynchronizationObserverDispatchFailure(
            observerId = observerId,
            eventId = event.id,
            reason = SynchronizationObserverDispatchFailureReason.OBSERVER_CALLBACK_FAILED,
            error = ObserverDeliveryError(
                observerIdValue = observerId.value,
                eventIdValue = event.id.value,
                eventVariantName = variantName,
            ),
        )
    }

    /**
     * Returns the simple variant name of the [SynchronizationEvent] sealed
     * type for safe diagnostics without using reflection.
     *
     * The variant name is derived from the sealed-class structure and contains
     * no payload data, credentials, or sensitive metadata.
     *
     * @param event The event whose variant name is needed.
     * @return A safe string identifying the event variant.
     */
    private fun eventVariantName(event: SynchronizationEvent): String = when (event) {
        is SynchronizationEvent.Started -> "Started"
        is SynchronizationEvent.PhaseChanged -> "PhaseChanged"
        is SynchronizationEvent.ProgressUpdated -> "ProgressUpdated"
        is SynchronizationEvent.RetryScheduled -> "RetryScheduled"
        is SynchronizationEvent.ConflictDetected -> "ConflictDetected"
        is SynchronizationEvent.Completed -> "Completed"
    }

    /**
     * Returns a safe diagnostic string.
     *
     * Does not expose observer implementation state or event payload.
     */
    override fun toString(): String =
        "SynchronizationEventDispatcher(registeredObservers=${observerRegistry.size})"
}
