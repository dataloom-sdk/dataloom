package io.dataloom.runtime.observation

/**
 * Reason classification for a single [SynchronizationObserverDispatchFailure].
 *
 * ## Purpose
 *
 * [SynchronizationObserverDispatchFailureReason] identifies the structural
 * cause of a failure to deliver a
 * [io.dataloom.api.synchronization.SynchronizationEvent] to a single
 * [io.dataloom.api.observation.SynchronizationObserver].
 *
 * ## Ordinal stability
 *
 * Enum ordinals must not be persisted or used as stable identifiers.
 * Always use the enum name or a canonical string representation.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library types only. Safe for use in Kotlin
 * Multiplatform common code.
 */
public enum class SynchronizationObserverDispatchFailureReason {

    /**
     * The observer's [io.dataloom.api.observation.SynchronizationObserver.onEvent]
     * callback threw an ordinary exception.
     *
     * [io.dataloom.api.observation.SynchronizationObserver.onEvent]
     * implementations must not throw; when they do, the runtime records this
     * failure and continues delivering the event to remaining observers.
     *
     * [kotlin.coroutines.cancellation.CancellationException] is never
     * classified under this reason; it propagates normally without being
     * recorded as a dispatch failure.
     */
    OBSERVER_CALLBACK_FAILED,
}
