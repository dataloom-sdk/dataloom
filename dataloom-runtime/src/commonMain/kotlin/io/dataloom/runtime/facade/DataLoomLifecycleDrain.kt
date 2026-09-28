package io.dataloom.runtime.facade

import io.dataloom.api.error.DataLoomError

/**
 * Opt-in runtime consumer that turns application-lifecycle transitions into
 * bounded queue drains (ADR-0013, D23).
 *
 * ## Purpose
 *
 * [run] collects the configured
 * [io.dataloom.api.lifecycle.AppLifecycleProvider] stream and, on a transition
 * that [LifecycleDrainPolicy] selects, performs at most one bounded drain: one
 * [DataLoomQueueWorker.run] (or [DataLoomCircuitQueueWorker.run]) call,
 * exactly the entry point [DataLoom.queueWorker] exposes. There is no second
 * worker path, so every drain is health-tracked and bridged exactly as a
 * direct call to that worker is.
 *
 * ## Who runs it
 *
 * The runtime owns no scope and selects no dispatcher. The host launches [run]
 * in a scope it owns, for example `scope.launch { dataLoom.lifecycleDrain?.run() }`,
 * and cancels that scope to stop it.
 *
 * ## Guarantees
 *
 * - Drains never overlap. A qualifying transition that arrives while a drain
 *   is in flight is dropped (coalesced into the drain already running), and
 *   this holds across concurrent [run] callers on the same instance.
 * - A transition inside the policy's minimum interval is dropped, not deferred.
 * - Cancelling the collecting coroutine cancels the in-flight drain and
 *   [run] rethrows the cancellation. A cancelled drain is reported to the
 *   worker's health tracker as a cancelled run.
 * - A drain's failure (a failed run result, an exception from the worker, an
 *   invalid request) never ends the collection. Failures of the run itself
 *   are recorded by the worker's own health and event wrappers; nothing is
 *   rethrown.
 * - A failure of the lifecycle stream ends [run] normally with
 *   [LifecycleDrainEnd.ObservationFailed]. The runtime does not restart the
 *   stream: the host decides whether and when to call [run] again.
 *
 * ## Best effort
 *
 * A drain that starts as the app enters the background runs with whatever
 * execution time the OS grants and may be suspended or killed part-way. The
 * lease expiry and expired-lease recovery are what make an interrupted drain
 * safe; a drain is never a delivery guarantee.
 */
public interface DataLoomLifecycleDrain {

    /**
     * Collects lifecycle transitions and drains the queue on qualifying ones
     * until the stream ends or fails, or the calling coroutine is cancelled.
     *
     * An in-flight drain is awaited before [run] returns a [LifecycleDrainEnd].
     * [kotlinx.coroutines.CancellationException] propagates normally.
     */
    public suspend fun run(): LifecycleDrainEnd
}

/** Why [DataLoomLifecycleDrain.run] returned. */
public sealed interface LifecycleDrainEnd {

    /** The lifecycle stream completed. The contract says it never does; a provider may still end it. */
    public data object StreamCompleted : LifecycleDrainEnd

    /**
     * The lifecycle stream failed. [error] is the canonical error carried by
     * [io.dataloom.api.lifecycle.AppLifecycleObservationException], or a
     * sanitized internal error when the provider failed with anything else.
     */
    public data class ObservationFailed(public val error: DataLoomError) : LifecycleDrainEnd
}
