package io.dataloom.runtime.observation

/**
 * Immutable summary of observer delivery counts for a single
 * [SynchronizationEventDispatcher.dispatch] invocation.
 *
 * ## Purpose
 *
 * [SynchronizationEventDispatchSummary] captures delivery statistics without
 * exposing event payload, observer instances, or exception state. It provides
 * counts at a structural level for diagnostics and result classification.
 *
 * ## Invariants
 *
 * All counts must be zero or positive:
 * - [attemptedObserverCount] cannot exceed [registeredObserverCount].
 * - [deliveredObserverCount] cannot exceed [attemptedObserverCount].
 * - [failedObserverCount] cannot exceed [attemptedObserverCount].
 * - For a completed, non-cancelled dispatch:
 *   [deliveredObserverCount] + [failedObserverCount] must equal
 *   [attemptedObserverCount].
 *
 * ## Zero summary
 *
 * A zero summary (all counts zero) represents a registry with no observers.
 * [SynchronizationEventDispatchResult.NoObservers] uses this summary.
 *
 * ## Value semantics
 *
 * Implements structural equality via `data class`.
 *
 * ## Construction restrictions
 *
 * Construction performs no delivery, invokes no observer, and dispatches no
 * event.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library types only. Safe for use in Kotlin
 * Multiplatform common code.
 *
 * @param registeredObserverCount Total number of observers in the registry at
 *   dispatch time. Must be zero or positive.
 * @param attemptedObserverCount Number of observers for which delivery was
 *   attempted. Must be in `[0, registeredObserverCount]`.
 * @param deliveredObserverCount Number of observers that received the event
 *   successfully. Must be in `[0, attemptedObserverCount]`.
 * @param failedObserverCount Number of observers whose callback threw an
 *   ordinary exception. Must be in `[0, attemptedObserverCount]`.
 * @throws IllegalArgumentException if any invariant is violated.
 */
public data class SynchronizationEventDispatchSummary(
    /** Total number of observers registered at dispatch time. */
    public val registeredObserverCount: Int,

    /** Number of observers for which delivery was attempted. */
    public val attemptedObserverCount: Int,

    /** Number of observers that received the event successfully. */
    public val deliveredObserverCount: Int,

    /** Number of observers whose callback threw an ordinary exception. */
    public val failedObserverCount: Int,
) {
    init {
        require(registeredObserverCount >= 0) {
            "registeredObserverCount must be zero or positive, was $registeredObserverCount."
        }
        require(attemptedObserverCount >= 0) {
            "attemptedObserverCount must be zero or positive, was $attemptedObserverCount."
        }
        require(deliveredObserverCount >= 0) {
            "deliveredObserverCount must be zero or positive, was $deliveredObserverCount."
        }
        require(failedObserverCount >= 0) {
            "failedObserverCount must be zero or positive, was $failedObserverCount."
        }
        require(attemptedObserverCount <= registeredObserverCount) {
            "attemptedObserverCount ($attemptedObserverCount) cannot exceed " +
                "registeredObserverCount ($registeredObserverCount)."
        }
        require(deliveredObserverCount <= attemptedObserverCount) {
            "deliveredObserverCount ($deliveredObserverCount) cannot exceed " +
                "attemptedObserverCount ($attemptedObserverCount)."
        }
        require(failedObserverCount <= attemptedObserverCount) {
            "failedObserverCount ($failedObserverCount) cannot exceed " +
                "attemptedObserverCount ($attemptedObserverCount)."
        }
    }

    public companion object {
        /**
         * A zero summary representing a registry with no observers or no
         * delivery attempted.
         */
        public val Zero: SynchronizationEventDispatchSummary =
            SynchronizationEventDispatchSummary(
                registeredObserverCount = 0,
                attemptedObserverCount = 0,
                deliveredObserverCount = 0,
                failedObserverCount = 0,
            )
    }
}
