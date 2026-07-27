package io.dataloom.connectivity.android.internal

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability

/**
 * Canonical [DataLoomError] for Android connectivity provider failures.
 *
 * The public error intentionally retains no raw platform exception, message,
 * stack trace, network identifier, or other sensitive diagnostic data.
 */
internal class ConnectivityProviderError(
    override val code: ErrorCode,
    override val category: ErrorCategory,
    override val severity: ErrorSeverity,
    override val recoverability: Recoverability,
    override val message: String,
) : DataLoomError {
    override val cause: Throwable? = null

    internal companion object {
        fun platformFailure(): ConnectivityProviderError = ConnectivityProviderError(
            code = ErrorCode("CONNECTIVITY_PLATFORM_FAILURE"),
            category = ErrorCategory.PROVIDER,
            severity = ErrorSeverity.ERROR,
            recoverability = Recoverability.RECOVERABLE,
            message = "The connectivity platform reported a failure while querying network state.",
        )
    }
}
