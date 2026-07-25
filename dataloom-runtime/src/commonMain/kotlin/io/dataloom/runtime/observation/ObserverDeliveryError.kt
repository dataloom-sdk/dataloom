package io.dataloom.runtime.observation

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability

/**
 * Internal [DataLoomError] implementation used to represent a safe diagnostic
 * record of an observer delivery failure.
 *
 * This implementation never exposes exception messages, class names,
 * stack-trace content, or event payload data. The message is a static
 * diagnostic string containing only the observer ID, event ID, and event
 * variant name.
 *
 * @param observerIdValue The string value of the failing observer's ID.
 * @param eventIdValue The string value of the event that failed to deliver.
 * @param eventVariantName The simple class name of the event variant for safe
 *   diagnostics (not derived from the exception).
 */
internal data class ObserverDeliveryError(
    private val observerIdValue: String,
    private val eventIdValue: String,
    private val eventVariantName: String,
) : DataLoomError {
    override val code: ErrorCode = ErrorCode("DL-OBSERVER-DELIVERY-FAILED")
    override val category: ErrorCategory = ErrorCategory.STATE
    override val severity: ErrorSeverity = ErrorSeverity.WARNING
    override val recoverability: Recoverability = Recoverability.RECOVERABLE
    override val message: String =
        "Observer callback failed: observerId='$observerIdValue', " +
            "eventId='$eventIdValue', eventVariant='$eventVariantName'."
    override val cause: Throwable? = null

    override fun toString(): String =
        "ObserverDeliveryError(code=$code, observerId='$observerIdValue', " +
            "eventId='$eventIdValue', eventVariant='$eventVariantName')"
}
