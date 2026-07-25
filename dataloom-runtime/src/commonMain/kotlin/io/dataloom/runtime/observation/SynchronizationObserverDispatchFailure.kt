package io.dataloom.runtime.observation

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.identifier.SynchronizationEventId
import io.dataloom.api.identifier.SynchronizationObserverId

/**
 * Immutable structural record of a single observer delivery failure.
 *
 * ## Purpose
 *
 * [SynchronizationObserverDispatchFailure] preserves the identity of the
 * observer that failed, the event that could not be delivered, the failure
 * reason, and a safe canonical [DataLoomError] for diagnostics.
 *
 * ## Sensitive-data restrictions
 *
 * - The observer instance is not retained.
 * - The complete event is not retained.
 * - No [Throwable] is exposed.
 * - No stack trace is exposed.
 * - No observer implementation state is exposed.
 * - The [error] message must not contain the exception message, the exception
 *   class name, stack-trace content, or event payload data.
 *
 * Safe diagnostic information preserved in [error]:
 * - Observer ID (via [observerId])
 * - Event ID (via [eventId])
 * - Event variant name
 * - Canonical [io.dataloom.api.error.ErrorCode]
 *
 * ## Value semantics
 *
 * Implements structural equality via `data class`.
 *
 * ## Construction restrictions
 *
 * Construction performs no callback, invokes no observer, and dispatches no
 * event.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 *
 * @param observerId The unique identifier of the observer that failed to
 *   receive the event.
 * @param eventId The unique identifier of the event that could not be
 *   delivered to the observer.
 * @param reason The structural reason for the delivery failure.
 * @param error A safe canonical [DataLoomError] containing diagnostic
 *   information about the failure. Must not expose exception messages, class
 *   names, stack traces, or event payload content.
 */
public data class SynchronizationObserverDispatchFailure(
    /** The unique identifier of the observer that failed to receive the event. */
    public val observerId: SynchronizationObserverId,

    /** The unique identifier of the event that could not be delivered. */
    public val eventId: SynchronizationEventId,

    /** The structural reason for the delivery failure. */
    public val reason: SynchronizationObserverDispatchFailureReason,

    /**
     * A safe canonical [DataLoomError] for diagnostics.
     *
     * Must not contain exception messages, class names, stack traces, or event
     * payload data.
     */
    public val error: DataLoomError,
)
