package io.dataloom.scheduler.workmanager.internal

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability

/** Safe canonical scheduler error with no retained platform exception. */
internal class SchedulerProviderError(
    override val code: ErrorCode,
    override val category: ErrorCategory,
    override val severity: ErrorSeverity,
    override val recoverability: Recoverability,
    override val message: String,
) : DataLoomError {
    override val cause: Throwable? = null

    internal companion object {
        fun schedulingFailure(): SchedulerProviderError = SchedulerProviderError(
            code = ErrorCode("SCHEDULER_WORKMANAGER_FAILURE"),
            category = ErrorCategory.SCHEDULER,
            severity = ErrorSeverity.ERROR,
            recoverability = Recoverability.RECOVERABLE,
            message = "WorkManager reported a failure while scheduling the requested work.",
        )

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
