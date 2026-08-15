package io.dataloom.platform.ios.scheduling.internal

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability

/**
 * Safe canonical scheduler error for [io.dataloom.platform.ios.scheduling.AppleSchedulerProvider]
 * failures. Retains no raw `NSError`, message, or other platform diagnostic
 * data.
 */
internal class SchedulerProviderError(
    override val code: ErrorCode,
    override val category: ErrorCategory,
    override val severity: ErrorSeverity,
    override val recoverability: Recoverability,
    override val message: String,
) : DataLoomError {
    override val cause: Throwable? = null

    internal companion object {
        fun platformFailure(): SchedulerProviderError = SchedulerProviderError(
            code = ErrorCode("SCHEDULER_IOS_PLATFORM_FAILURE"),
            category = ErrorCategory.PROVIDER,
            severity = ErrorSeverity.ERROR,
            recoverability = Recoverability.RECOVERABLE,
            message = "BGTaskScheduler reported an unexpected failure while scheduling or cancelling the requested work.",
        )

        fun unsupportedUnmeteredConnectivity(): SchedulerProviderError = SchedulerProviderError(
            code = ErrorCode("SCHEDULER_IOS_UNSUPPORTED_CONSTRAINT"),
            category = ErrorCategory.CONFIGURATION,
            severity = ErrorSeverity.ERROR,
            recoverability = Recoverability.NON_RECOVERABLE,
            message = "BGTaskScheduler cannot guarantee unmetered-only connectivity: BGProcessingTaskRequest " +
                "exposes only a boolean requiresNetworkConnectivity flag, with no distinction between " +
                "metered and unmetered networks.",
        )

        fun identifierNotPreRegistered(): SchedulerProviderError = SchedulerProviderError(
            code = ErrorCode("SCHEDULER_IOS_IDENTIFIER_NOT_PREREGISTERED"),
            category = ErrorCategory.CONFIGURATION,
            severity = ErrorSeverity.ERROR,
            recoverability = Recoverability.NON_RECOVERABLE,
            message = "This schedule identifier was not declared as pre-registered when AppleSchedulerProvider " +
                "was constructed. BGTaskScheduler requires every task identifier to be listed in the host " +
                "app's Info.plist BGTaskSchedulerPermittedIdentifiers array and registered at app-launch time.",
        )

        fun notPermittedByPlatform(): SchedulerProviderError = SchedulerProviderError(
            code = ErrorCode("SCHEDULER_IOS_NOT_PERMITTED"),
            category = ErrorCategory.CONFIGURATION,
            severity = ErrorSeverity.ERROR,
            recoverability = Recoverability.NON_RECOVERABLE,
            message = "BGTaskScheduler rejected this identifier as not permitted. Confirm it is listed in the " +
                "host app's Info.plist BGTaskSchedulerPermittedIdentifiers array and was registered via " +
                "BGTaskScheduler.shared.register(forTaskWithIdentifier:using:launchHandler:) before " +
                "applicationDidFinishLaunching returned.",
        )

        fun tooManyPendingTaskRequests(): SchedulerProviderError = SchedulerProviderError(
            code = ErrorCode("SCHEDULER_IOS_TOO_MANY_PENDING"),
            category = ErrorCategory.SCHEDULER,
            severity = ErrorSeverity.ERROR,
            recoverability = Recoverability.RECOVERABLE,
            message = "BGTaskScheduler rejected this request: the platform's limit on simultaneously pending " +
                "task requests was reached.",
        )

        fun backgroundTasksUnavailable(): SchedulerProviderError = SchedulerProviderError(
            code = ErrorCode("SCHEDULER_IOS_UNAVAILABLE"),
            category = ErrorCategory.SCHEDULER,
            severity = ErrorSeverity.ERROR,
            recoverability = Recoverability.RECOVERABLE,
            message = "BGTaskScheduler reported background task submission as unavailable in this process.",
        )

        fun submissionFailure(): SchedulerProviderError = SchedulerProviderError(
            code = ErrorCode("SCHEDULER_IOS_SUBMIT_FAILURE"),
            category = ErrorCategory.SCHEDULER,
            severity = ErrorSeverity.ERROR,
            recoverability = Recoverability.RECOVERABLE,
            message = "BGTaskScheduler reported a failure while submitting the requested work.",
        )
    }
}
