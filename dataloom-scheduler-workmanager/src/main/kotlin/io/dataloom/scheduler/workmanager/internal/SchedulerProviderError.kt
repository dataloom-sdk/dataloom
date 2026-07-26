package io.dataloom.scheduler.workmanager.internal

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability

/**
 * Canonical [DataLoomError] for WorkManager scheduler provider failures.
 *
 * Exposes only sanitized diagnostic messages. No raw exception message,
 * stack trace, platform type, or sensitive data is included in [message].
 */
internal class SchedulerProviderError(
    override val code: ErrorCode,
    override val category: ErrorCategory,
    override val severity: ErrorSeverity,
    override val recoverability: Recoverability,
    override val message: String,
    override val cause: Throwable? = null,
) : DataLoomError {

    internal companion object {

        /**
         * Returns an error representing a failure during WorkManager scheduling.
         * The raw [cause] is preserved for diagnostic purposes but must not be
         * exposed through the public contract.
         */
        fun schedulingFailure(cause: Throwable? = null): SchedulerProviderError =
            SchedulerProviderError(
                code = ErrorCode("SCHEDULER_WORKMANAGER_FAILURE"),
                category = ErrorCategory.SCHEDULER,
                severity = ErrorSeverity.ERROR,
                recoverability = Recoverability.RECOVERABLE,
                message = "WorkManager reported a failure while scheduling the requested work.",
                cause = cause,
            )

        /**
         * Returns an error representing an unsupported constraint configuration.
         */
        fun unsupportedConstraint(detail: String): SchedulerProviderError =
            SchedulerProviderError(
                code = ErrorCode("SCHEDULER_UNSUPPORTED_CONSTRAINT"),
                category = ErrorCategory.CONFIGURATION,
                severity = ErrorSeverity.ERROR,
                recoverability = Recoverability.NON_RECOVERABLE,
                message = "WorkManager does not support the requested constraint: $detail",
            )
    }
}
