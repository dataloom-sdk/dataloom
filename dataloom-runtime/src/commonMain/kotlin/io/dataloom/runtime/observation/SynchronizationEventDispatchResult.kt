package io.dataloom.runtime.observation

import io.dataloom.api.identifier.SynchronizationEventId

/**
 * Sealed result of a single [SynchronizationEventDispatcher.dispatch]
 * invocation.
 *
 * ## Variants
 *
 * - [NoObservers]: The registry contained no observers; no callback was
 *   invoked.
 * - [Delivered]: All attempted observers received the event successfully.
 * - [PartiallyDelivered]: At least one observer succeeded and at least one
 *   failed with an ordinary exception.
 * - [DeliveryFailed]: At least one observer was attempted and every attempted
 *   observer failed with an ordinary exception.
 *
 * ## Sensitive-data restrictions
 *
 * - No observer instance is exposed.
 * - No complete event payload is exposed.
 * - No [Throwable] is exposed.
 * - No stack trace is exposed.
 * - Failure collections contain only [SynchronizationObserverDispatchFailure]
 *   records with safe canonical diagnostics.
 *
 * ## Failure ordering
 *
 * Failure order follows observer invocation order (registration order).
 * Failures are never sorted by observer ID, error code, error message, class
 * name, or hash order.
 *
 * ## Cancellation boundary
 *
 * A [kotlin.coroutines.cancellation.CancellationException] thrown by an
 * observer callback propagates normally without producing any result variant.
 * No result is created after thrown cancellation.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 */
public sealed interface SynchronizationEventDispatchResult {

    /**
     * The unique identifier of the dispatched event.
     *
     * Preserves the exact [SynchronizationEventId] of the event passed to
     * [SynchronizationEventDispatcher.dispatch]. Not modified or regenerated.
     */
    public val eventId: SynchronizationEventId

    /**
     * The dispatch summary containing delivery counts for this invocation.
     */
    public val summary: SynchronizationEventDispatchSummary

    /**
     * The registry contained no observers.
     *
     * No observer callback was invoked. The summary contains zero counts.
     * The failure list is empty.
     *
     * @param eventId The unique identifier of the dispatched event.
     * @param summary A zero-count summary. Must have all counts equal to zero.
     * @throws IllegalArgumentException if [summary] contains any non-zero count.
     */
    public data class NoObservers(
        override val eventId: SynchronizationEventId,
        override val summary: SynchronizationEventDispatchSummary,
    ) : SynchronizationEventDispatchResult {
        init {
            require(summary.registeredObserverCount == 0) {
                "NoObservers result must have registeredObserverCount == 0."
            }
            require(summary.attemptedObserverCount == 0) {
                "NoObservers result must have attemptedObserverCount == 0."
            }
            require(summary.deliveredObserverCount == 0) {
                "NoObservers result must have deliveredObserverCount == 0."
            }
            require(summary.failedObserverCount == 0) {
                "NoObservers result must have failedObserverCount == 0."
            }
        }

        /** Always an empty list for [NoObservers]. */
        public val failures: List<SynchronizationObserverDispatchFailure> get() = emptyList()
    }

    /**
     * Every attempted observer received the event successfully.
     *
     * At least one observer was attempted and all succeeded. The summary
     * contains no failures. The failure list is empty.
     *
     * @param eventId The unique identifier of the dispatched event.
     * @param summary A delivery summary with zero [SynchronizationEventDispatchSummary.failedObserverCount]
     *   and at least one [SynchronizationEventDispatchSummary.deliveredObserverCount].
     * @throws IllegalArgumentException if [summary] contains any failures or
     *   no delivered observers.
     */
    public data class Delivered(
        override val eventId: SynchronizationEventId,
        override val summary: SynchronizationEventDispatchSummary,
    ) : SynchronizationEventDispatchResult {
        init {
            require(summary.failedObserverCount == 0) {
                "Delivered result must have failedObserverCount == 0."
            }
            require(summary.deliveredObserverCount > 0) {
                "Delivered result must have at least one delivered observer."
            }
        }

        /** Always an empty list for [Delivered]. */
        public val failures: List<SynchronizationObserverDispatchFailure> get() = emptyList()
    }

    /**
     * At least one observer succeeded and at least one observer failed with an
     * ordinary exception.
     *
     * The [failures] collection is ordered by observer invocation order
     * (registration order). Failures are never sorted by ID, error code, or
     * any other attribute.
     *
     * @param eventId The unique identifier of the dispatched event.
     * @param summary A delivery summary with both non-zero
     *   [SynchronizationEventDispatchSummary.deliveredObserverCount] and
     *   non-zero [SynchronizationEventDispatchSummary.failedObserverCount].
     * @param failures Ordered [SynchronizationObserverDispatchFailure] records.
     *   Defensively copied at construction. Must not be empty.
     * @throws IllegalArgumentException if [summary] does not reflect partial
     *   delivery or [failures] is empty.
     */
    public class PartiallyDelivered(
        override val eventId: SynchronizationEventId,
        override val summary: SynchronizationEventDispatchSummary,
        failures: List<SynchronizationObserverDispatchFailure>,
    ) : SynchronizationEventDispatchResult {

        private val _failures: List<SynchronizationObserverDispatchFailure> = failures.toList()

        /**
         * Ordered [SynchronizationObserverDispatchFailure] records preserving
         * observer invocation order. Read-only; caller mutations have no effect.
         */
        public val failures: List<SynchronizationObserverDispatchFailure>
            get() = _failures

        init {
            require(summary.deliveredObserverCount > 0) {
                "PartiallyDelivered result must have at least one delivered observer."
            }
            require(summary.failedObserverCount > 0) {
                "PartiallyDelivered result must have at least one failed observer."
            }
            require(_failures.isNotEmpty()) {
                "PartiallyDelivered result must have a non-empty failures list."
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PartiallyDelivered) return false
            return eventId == other.eventId &&
                summary == other.summary &&
                _failures == other._failures
        }

        override fun hashCode(): Int {
            var result = eventId.hashCode()
            result = 31 * result + summary.hashCode()
            result = 31 * result + _failures.hashCode()
            return result
        }

        override fun toString(): String =
            "PartiallyDelivered(eventId=$eventId, summary=$summary, " +
                "failureCount=${_failures.size})"
    }

    /**
     * At least one observer was attempted and every attempted observer failed
     * with an ordinary exception.
     *
     * The [failures] collection is ordered by observer invocation order
     * (registration order). Failures are never sorted by ID, error code, or
     * any other attribute.
     *
     * @param eventId The unique identifier of the dispatched event.
     * @param summary A delivery summary with zero
     *   [SynchronizationEventDispatchSummary.deliveredObserverCount] and at
     *   least one [SynchronizationEventDispatchSummary.failedObserverCount].
     * @param failures Ordered [SynchronizationObserverDispatchFailure] records.
     *   Defensively copied at construction. Must not be empty.
     * @throws IllegalArgumentException if [summary] does not reflect total
     *   failure or [failures] is empty.
     */
    public class DeliveryFailed(
        override val eventId: SynchronizationEventId,
        override val summary: SynchronizationEventDispatchSummary,
        failures: List<SynchronizationObserverDispatchFailure>,
    ) : SynchronizationEventDispatchResult {

        private val _failures: List<SynchronizationObserverDispatchFailure> = failures.toList()

        /**
         * Ordered [SynchronizationObserverDispatchFailure] records preserving
         * observer invocation order. Read-only; caller mutations have no effect.
         */
        public val failures: List<SynchronizationObserverDispatchFailure>
            get() = _failures

        init {
            require(summary.deliveredObserverCount == 0) {
                "DeliveryFailed result must have deliveredObserverCount == 0."
            }
            require(summary.failedObserverCount > 0) {
                "DeliveryFailed result must have at least one failed observer."
            }
            require(_failures.isNotEmpty()) {
                "DeliveryFailed result must have a non-empty failures list."
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DeliveryFailed) return false
            return eventId == other.eventId &&
                summary == other.summary &&
                _failures == other._failures
        }

        override fun hashCode(): Int {
            var result = eventId.hashCode()
            result = 31 * result + summary.hashCode()
            result = 31 * result + _failures.hashCode()
            return result
        }

        override fun toString(): String =
            "DeliveryFailed(eventId=$eventId, summary=$summary, " +
                "failureCount=${_failures.size})"
    }
}
