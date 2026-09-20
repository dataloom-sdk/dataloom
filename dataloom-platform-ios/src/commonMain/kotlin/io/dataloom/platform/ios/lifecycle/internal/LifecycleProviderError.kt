package io.dataloom.platform.ios.lifecycle.internal

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability

/**
 * Canonical [DataLoomError] for iOS lifecycle provider failures.
 *
 * The public error intentionally retains no raw platform exception, message,
 * stack trace, or other sensitive diagnostic data.
 */
internal class LifecycleProviderError(
    override val code: ErrorCode,
    override val category: ErrorCategory,
    override val severity: ErrorSeverity,
    override val recoverability: Recoverability,
    override val message: String,
) : DataLoomError {
    override val cause: Throwable? = null

    internal companion object {
        fun platformFailure(): LifecycleProviderError = LifecycleProviderError(
            code = ErrorCode("LIFECYCLE_PLATFORM_FAILURE"),
            category = ErrorCategory.PROVIDER,
            severity = ErrorSeverity.ERROR,
            recoverability = Recoverability.RECOVERABLE,
            message = "The lifecycle platform reported a failure while observing application state.",
        )
    }
}
