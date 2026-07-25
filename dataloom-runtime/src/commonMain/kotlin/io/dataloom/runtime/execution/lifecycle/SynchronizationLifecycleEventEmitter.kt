package io.dataloom.runtime.execution.lifecycle

import io.dataloom.api.synchronization.SynchronizationPhase
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.runtime.execution.SynchronizationExecutionContext
import io.dataloom.runtime.observation.SynchronizationEventDispatchResult

/**
 * Contract for emitting synchronization lifecycle events at deterministic
 * execution boundaries.
 *
 * ## Purpose
 *
 * [SynchronizationLifecycleEventEmitter] provides a narrow, pipeline-facing
 * capability for emitting [io.dataloom.api.synchronization.SynchronizationEvent]
 * variants at the three lifecycle boundaries defined by DL-029:
 *
 * - [emitStarted]: emitted immediately before pipeline execution begins.
 * - [emitPhaseChanged]: emitted immediately before a supported provider
 *   operation begins.
 * - [emitCompleted]: emitted immediately after a pipeline returns a terminal
 *   [SynchronizationResult].
 *
 * ## Emitter responsibilities
 *
 * The emitter:
 * - Constructs the exact existing event variant.
 * - Generates one fresh [io.dataloom.api.identifier.SynchronizationEventId]
 *   per emitted event.
 * - Reads the [io.dataloom.api.time.DataLoomClock] exactly once per emitted
 *   event.
 * - Delivers the event through the configured dispatcher.
 * - Returns the structured [SynchronizationEventDispatchResult] unchanged.
 *
 * ## Emitter non-responsibilities
 *
 * The emitter must not:
 * - Execute synchronization.
 * - Call any provider operation.
 * - Invoke retry policy.
 * - Schedule work.
 * - Modify queue state.
 * - Resolve conflicts.
 * - Modify [SynchronizationResult].
 * - Persist events.
 * - Generate event IDs for rejected execution.
 * - Read the clock for rejected execution.
 *
 * ## Observer failure isolation
 *
 * Ordinary observer callback failures are isolated by the dispatcher and
 * represented as [SynchronizationEventDispatchResult.PartiallyDelivered] or
 * [SynchronizationEventDispatchResult.DeliveryFailed]. These structured
 * results must not stop synchronization or alter [SynchronizationResult].
 *
 * ## Cancellation behavior
 *
 * A [kotlin.coroutines.cancellation.CancellationException] thrown during
 * event delivery propagates normally. When [emitStarted] is cancelled, the
 * pipeline must not execute. When [emitPhaseChanged] is cancelled, the
 * corresponding provider operation must not execute. When [emitCompleted] is
 * cancelled, the synchronization business work has already completed; the
 * caller may not receive the result.
 *
 * ## Event-generation failure boundary
 *
 * Unexpected failures from the clock, identifier generator, event
 * constructor, or dispatcher propagate unless the dispatcher contract
 * returns a structured ordinary observer failure.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API and runtime types only.
 * Safe for use in Kotlin Multiplatform common code.
 */
public interface SynchronizationLifecycleEventEmitter {

    /**
     * Emits a [io.dataloom.api.synchronization.SynchronizationEvent.Started]
     * event for the given execution context.
     *
     * Must be called after lifecycle validation, provider resolution, and
     * pipeline lookup, and immediately before pipeline execution begins.
     * Must be called exactly once per accepted execution.
     *
     * ## Event-ID and timestamp
     *
     * Generates one fresh [io.dataloom.api.identifier.SynchronizationEventId]
     * and reads the clock exactly once for this event.
     *
     * ## Cancellation
     *
     * A thrown [kotlin.coroutines.cancellation.CancellationException]
     * propagates normally. When cancelled here, the pipeline must not execute
     * and [emitCompleted] must not be called.
     *
     * @param context the immutable execution context for the accepted
     *   synchronization execution. The request is preserved exactly.
     * @return the [SynchronizationEventDispatchResult] from the dispatcher.
     *   Structured observer failures do not prevent pipeline execution.
     */
    public suspend fun emitStarted(
        context: SynchronizationExecutionContext,
    ): SynchronizationEventDispatchResult

    /**
     * Emits a [io.dataloom.api.synchronization.SynchronizationEvent.PhaseChanged]
     * event for the given execution context and phase.
     *
     * Must be called immediately before the corresponding provider operation
     * begins. Must not be called after the operation has completed.
     *
     * ## Supported phases
     *
     * Only existing [SynchronizationPhase] values are emitted. Internal
     * pipeline operations that are not represented by an existing phase
     * value do not produce a phase event.
     *
     * ## Event-ID and timestamp
     *
     * Generates one fresh [io.dataloom.api.identifier.SynchronizationEventId]
     * and reads the clock exactly once for this event.
     *
     * ## Cancellation
     *
     * A thrown [kotlin.coroutines.cancellation.CancellationException]
     * propagates normally. When cancelled here, the corresponding provider
     * operation must not execute.
     *
     * @param context the immutable execution context for the active
     *   synchronization execution.
     * @param phase the exact [SynchronizationPhase] value being entered.
     * @return the [SynchronizationEventDispatchResult] from the dispatcher.
     *   Structured observer failures do not prevent provider execution.
     */
    public suspend fun emitPhaseChanged(
        context: SynchronizationExecutionContext,
        phase: SynchronizationPhase,
    ): SynchronizationEventDispatchResult

    /**
     * Emits a [io.dataloom.api.synchronization.SynchronizationEvent.Completed]
     * event for the given execution context and terminal result.
     *
     * Must be called exactly once after the pipeline returns a normal
     * [SynchronizationResult]. Must be the last lifecycle event emitted for
     * the accepted execution.
     *
     * ## Applicable results
     *
     * Completed is dispatched for every normal [SynchronizationResult] variant:
     * [io.dataloom.api.synchronization.SynchronizationResult.Succeeded],
     * [io.dataloom.api.synchronization.SynchronizationResult.PartiallySucceeded],
     * [io.dataloom.api.synchronization.SynchronizationResult.Failed],
     * [io.dataloom.api.synchronization.SynchronizationResult.Cancelled], and
     * [io.dataloom.api.synchronization.SynchronizationResult.Skipped].
     *
     * A thrown [kotlin.coroutines.cancellation.CancellationException] from
     * the pipeline is not a normal result and must not receive Completed.
     *
     * ## Exact result preservation
     *
     * The [result] instance is preserved exactly. Its summary, errors, skip
     * or cancellation information, and terminal timestamps are not modified.
     *
     * ## Event-ID and timestamp
     *
     * Generates one fresh [io.dataloom.api.identifier.SynchronizationEventId]
     * and reads the clock exactly once for this event.
     *
     * ## Cancellation
     *
     * A thrown [kotlin.coroutines.cancellation.CancellationException]
     * propagates normally. Synchronization business work has already completed
     * at this point; the caller may not receive the result. No second Completed
     * event is attempted.
     *
     * @param context the immutable execution context for the completed
     *   synchronization execution.
     * @param result the exact terminal [SynchronizationResult] returned by
     *   the pipeline. Must not be modified.
     * @return the [SynchronizationEventDispatchResult] from the dispatcher.
     *   Structured observer failures do not alter the completed result.
     */
    public suspend fun emitCompleted(
        context: SynchronizationExecutionContext,
        result: SynchronizationResult,
    ): SynchronizationEventDispatchResult
}
