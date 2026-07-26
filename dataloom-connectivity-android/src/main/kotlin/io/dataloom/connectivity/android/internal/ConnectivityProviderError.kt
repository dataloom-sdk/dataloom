package io.dataloom.connectivity.android.internal

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability

/**
 * Canonical [DataLoomError] for Android connectivity provider failures.
 *
 * Exposes only sanitized diagnostic messages. No raw exception message,
 * stack trace, platform type, connection detail, or sensitive data is
 * included in [message].
 */
internal class ConnectivityProviderError(
    override val code: ErrorCode,
    override val category: ErrorCategory,
    override val severity: ErrorSeverity,
    override val recoverability: Recoverability,
    override val message: String,
    override val cause: Throwable? = null,
) : DataLoomError {

    internal companion object {

        /**
         * Returns an error representing a platform-level failure while querying
         * network state. The raw [cause] is preserved for diagnostic purposes but
         * must not be exposed through the public contract.
         */
        fun platformFailure(cause: Throwable? = null): ConnectivityProviderError =
            ConnectivityProviderError(
                code = ErrorCode("CONNECTIVITY_PLATFORM_FAILURE"),
                category = ErrorCategory.PROVIDER,
                severity = ErrorSeverity.ERROR,
                recoverability = Recoverability.RECOVERABLE,
                message = "The connectivity platform reported a failure while querying network state.",
                cause = cause,
            )
    }
}
