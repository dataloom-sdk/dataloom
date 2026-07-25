package io.dataloom.runtime.execution.lifecycle

import io.dataloom.api.conflict.SynchronizationConflict
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.identifier.IdentifierGenerator
import io.dataloom.api.identifier.SynchronizationEventId
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.synchronization.SynchronizationEvent
import io.dataloom.api.synchronization.SynchronizationPhase
import io.dataloom.api.synchronization.SynchronizationProgress
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.time.DataLoomClock
import io.dataloom.runtime.execution.SynchronizationExecutionContext
import io.dataloom.runtime.observation.SynchronizationEventDispatchResult
import io.dataloom.runtime.observation.SynchronizationEventDispatcher

/**
 * [SynchronizationLifecycleEventEmitter] implementation that constructs
 * lifecycle events and dispatches them through a
 * [SynchronizationEventDispatcher].
 *
 * ## Purpose
 *
 * [DispatchingSynchronizationLifecycleEventEmitter] translates lifecycle
 * boundaries into [io.dataloom.api.synchronization.SynchronizationEvent]
 * instances and delivers them to registered observers through the provided
 * [dispatcher]. Each emitted event receives a distinct identifier from
 * [eventIdGenerator] and a fresh timestamp from [clock].
 *
 * ## DL-030 operational events
 *
 * [DispatchingSynchronizationLifecycleEventEmitter] also implements
 * [SynchronizationRuntimeEventEmitter], adding three operational event
 * capabilities required by DL-030: [emitProgressUpdated],
 * [emitRetryScheduled], and [emitConflictDetected]. Each method reuses the
 * same [dispatcher], [clock], and [eventIdGenerator] without duplicating
 * dispatch logic.
 *
 * ## Explicit injection
 *
 * All dependencies are required at construction time:
 *
 * - [dispatcher]: delivers events to registered observers.
 * - [clock]: read exactly once per emitted event to produce the event
 *   timestamp.
 * - [eventIdGenerator]: invoked exactly once per emitted event to produce
 *   the event identifier.
 *
 * Construction performs no clock read, no identifier generation, no provider
 * call, and no event dispatch.
 *
 * ## Event-ID generation
 *
 * One fresh [SynchronizationEventId] is generated per emitted event. IDs
 * are not reused, derived from request IDs, or concatenated manually. No ID
 * is generated for events that are not emitted (e.g., rejected executions).
 * Generator exceptions propagate normally.
 *
 * ## Clock reads
 *
 * The clock is read exactly once per emitted event. Clock exceptions
 * propagate normally.
 *
 * ## Observer failure isolation
 *
 * Ordinary observer failures are isolated by the [dispatcher] and returned
 * as [SynchronizationEventDispatchResult.PartiallyDelivered] or
 * [SynchronizationEventDispatchResult.DeliveryFailed]. These results are
 * returned unchanged and must not stop synchronization.
 *
 * ## Cancellation boundary
 *
 * A [kotlin.coroutines.cancellation.CancellationException] thrown during
 * dispatch propagates normally. See [SynchronizationLifecycleEventEmitter]
 * for consequences at each emission boundary.
 *
 * ## No platform API
 *
 * Does not use System.currentTimeMillis(), java.time, kotlin.random,
 * UUID.randomUUID(), Android clocks, or any platform-specific date API.
 * All timestamps and identifiers are supplied through injected abstractions.
 *
 * ## No CoroutineScope
 *
 * This class owns no [kotlinx.coroutines.CoroutineScope], exposes no
 * [kotlinx.coroutines.flow.Flow] or [kotlinx.coroutines.channels.Channel],
 * selects no dispatcher, and performs no background fan-out.
 *
 * ## No global state
 *
 * Uses no global registry, no service locator, no reflection, and no
 * ServiceLoader.
 *
 * ## Security
 *
 * Safe diagnostics may include [SynchronizationEventId] values,
 * synchronization request IDs, [SynchronizationPhase] names, result
 * variant names, and dispatch-result counts. Payload bytes, checkpoint
 * tokens, credentials, and stack traces are never exposed.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API and runtime types only.
 * Safe for use in Kotlin Multiplatform common code.
 *
 * @param dispatcher the [SynchronizationEventDispatcher] used to deliver
 *   lifecycle events to registered observers.
 * @param clock the [DataLoomClock] read once per emitted event to supply
 *   the event occurrence timestamp.
 * @param eventIdGenerator the [IdentifierGenerator] invoked once per emitted
 *   event to produce a distinct [SynchronizationEventId].
 */
public class DispatchingSynchronizationLifecycleEventEmitter(
    private val dispatcher: SynchronizationEventDispatcher,
    private val clock: DataLoomClock,
    private val eventIdGenerator: IdentifierGenerator<SynchronizationEventId>,
) : SynchronizationRuntimeEventEmitter {

    /**
     * Constructs a [SynchronizationEvent.Started] event and dispatches it
     * through the configured [dispatcher].
     *
     * Generates one fresh [SynchronizationEventId] and reads the clock once.
     * The exact [SynchronizationExecutionContext.request] is preserved in the
     * emitted event.
     *
     * @param context the immutable execution context for the accepted execution.
     * @return the [SynchronizationEventDispatchResult] returned by the
     *   dispatcher.
     */
    override suspend fun emitStarted(
        context: SynchronizationExecutionContext,
    ): SynchronizationEventDispatchResult {
        val id = eventIdGenerator.generate()
        val occurredAt = clock.now()
        val event = SynchronizationEvent.Started(
            id = id,
            request = context.request,
            occurredAt = occurredAt,
        )
        return dispatcher.dispatch(event)
    }

    /**
     * Constructs a [SynchronizationEvent.PhaseChanged] event for the given
     * [phase] and dispatches it through the configured [dispatcher].
     *
     * Generates one fresh [SynchronizationEventId] and reads the clock once.
     * The exact [SynchronizationExecutionContext.request] and the exact
     * [phase] value are preserved in the emitted event.
     *
     * @param context the immutable execution context for the active execution.
     * @param phase the exact [SynchronizationPhase] being entered.
     * @return the [SynchronizationEventDispatchResult] returned by the
     *   dispatcher.
     */
    override suspend fun emitPhaseChanged(
        context: SynchronizationExecutionContext,
        phase: SynchronizationPhase,
    ): SynchronizationEventDispatchResult {
        val id = eventIdGenerator.generate()
        val occurredAt = clock.now()
        val event = SynchronizationEvent.PhaseChanged(
            id = id,
            request = context.request,
            occurredAt = occurredAt,
            phase = phase,
        )
        return dispatcher.dispatch(event)
    }

    /**
     * Constructs a [SynchronizationEvent.Completed] event carrying the exact
     * [result] and dispatches it through the configured [dispatcher].
     *
     * Generates one fresh [SynchronizationEventId] and reads the clock once.
     * The clock read must not be earlier than [SynchronizationResult.completedAt]
     * to satisfy [SynchronizationEvent.Completed]'s constraint.
     * The [result] instance is preserved exactly; its summary, errors, and
     * timestamps are not modified.
     *
     * @param context the immutable execution context for the completed execution.
     * @param result the exact terminal [SynchronizationResult] returned by the
     *   pipeline.
     * @return the [SynchronizationEventDispatchResult] returned by the
     *   dispatcher.
     */
    override suspend fun emitCompleted(
        context: SynchronizationExecutionContext,
        result: SynchronizationResult,
    ): SynchronizationEventDispatchResult {
        val id = eventIdGenerator.generate()
        val occurredAt = clock.now()
        val event = SynchronizationEvent.Completed(
            id = id,
            request = context.request,
            occurredAt = occurredAt,
            result = result,
        )
        return dispatcher.dispatch(event)
    }

    /**
     * Constructs a [SynchronizationEvent.ProgressUpdated] event and dispatches
     * it through the configured [dispatcher].
     *
     * Generates one fresh [SynchronizationEventId] and reads the clock once.
     * The exact [request] and [progress] values are preserved in the emitted
     * event.
     *
     * @param request the exact synchronization request for the active execution.
     * @param progress the current cumulative progress snapshot.
     * @return the [SynchronizationEventDispatchResult] returned by the
     *   dispatcher.
     */
    override suspend fun emitProgressUpdated(
        request: SynchronizationRequest,
        progress: SynchronizationProgress,
    ): SynchronizationEventDispatchResult {
        val id = eventIdGenerator.generate()
        val occurredAt = clock.now()
        val event = SynchronizationEvent.ProgressUpdated(
            id = id,
            request = request,
            occurredAt = occurredAt,
            progress = progress,
        )
        return dispatcher.dispatch(event)
    }

    /**
     * Constructs a [SynchronizationEvent.RetryScheduled] event and dispatches
     * it through the configured [dispatcher].
     *
     * Generates one fresh [SynchronizationEventId] and reads the clock once.
     * All parameters are preserved exactly in the emitted event.
     *
     * @param request the exact synchronization request being retried.
     * @param attempt the retry attempt descriptor for this scheduled retry.
     * @param delay the selected minimum scheduling delay.
     * @param error the canonical error that triggered the retry.
     * @return the [SynchronizationEventDispatchResult] returned by the
     *   dispatcher.
     */
    override suspend fun emitRetryScheduled(
        request: SynchronizationRequest,
        attempt: RetryAttempt,
        delay: SchedulingDelay,
        error: DataLoomError,
    ): SynchronizationEventDispatchResult {
        val id = eventIdGenerator.generate()
        val occurredAt = clock.now()
        val event = SynchronizationEvent.RetryScheduled(
            id = id,
            request = request,
            occurredAt = occurredAt,
            attempt = attempt,
            delay = delay,
            error = error,
        )
        return dispatcher.dispatch(event)
    }

    /**
     * Constructs a [SynchronizationEvent.ConflictDetected] event and dispatches
     * it through the configured [dispatcher].
     *
     * Generates one fresh [SynchronizationEventId] and reads the clock once.
     * The exact [request] and [conflict] references are preserved in the
     * emitted event. Payload bytes from [conflict] are not copied or inspected.
     *
     * @param request the exact synchronization request in which the conflict
     *   was detected.
     * @param conflict the exact [SynchronizationConflict] reported by the
     *   detector.
     * @return the [SynchronizationEventDispatchResult] returned by the
     *   dispatcher.
     */
    override suspend fun emitConflictDetected(
        request: SynchronizationRequest,
        conflict: SynchronizationConflict,
    ): SynchronizationEventDispatchResult {
        val id = eventIdGenerator.generate()
        val occurredAt = clock.now()
        val event = SynchronizationEvent.ConflictDetected(
            id = id,
            request = request,
            occurredAt = occurredAt,
            conflict = conflict,
        )
        return dispatcher.dispatch(event)
    }

    /**
     * Returns a safe diagnostic string.
     *
     * Does not expose dispatcher implementation state, observer details,
     * clock implementation, or identifier generator implementation.
     */
    override fun toString(): String =
        "DispatchingSynchronizationLifecycleEventEmitter(" +
            "dispatcher=$dispatcher" +
            ")"
}
