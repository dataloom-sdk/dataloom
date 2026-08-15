package io.dataloom.platform.ios.scheduling.internal

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.BackgroundTasks.BGTaskSchedulerErrorCodeNotPermitted
import platform.BackgroundTasks.BGTaskSchedulerErrorCodeTooManyPendingTaskRequests
import platform.BackgroundTasks.BGTaskSchedulerErrorCodeUnavailable
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.dateByAddingTimeInterval
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait

/**
 * The only file in this module that touches `platform.BackgroundTasks`.
 *
 * Every function here is a single bounded call into `BGTaskScheduler.shared`
 * -- no callback is retained past its single invocation, no queue or
 * semaphore outlives a single call, and nothing here polls or caches
 * platform state. See `internal/BackgroundTaskGateway.kt` (`expect`,
 * `commonMain`) for the documented contract each function fulfils.
 */

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal actual fun submitBackgroundTaskRequest(plan: SchedulePlan): SubmitTaskOutcome {
    val request: BGTaskRequest = when (plan.kind) {
        BGTaskRequestKind.APP_REFRESH ->
            BGAppRefreshTaskRequest(identifier = plan.identifier)

        BGTaskRequestKind.PROCESSING ->
            BGProcessingTaskRequest(identifier = plan.identifier).apply {
                requiresNetworkConnectivity = plan.requiresNetworkConnectivity
                requiresExternalPower = plan.requiresExternalPower
            }
    }
    request.earliestBeginDate = NSDate().dateByAddingTimeInterval(plan.delayMilliseconds / 1_000.0)

    return memScoped {
        val errorVar = alloc<ObjCObjectVar<NSError?>>()
        val accepted = BGTaskScheduler.sharedScheduler.submitTaskRequest(request, errorVar.ptr)
        if (accepted) {
            SubmitTaskOutcome.Submitted
        } else {
            SubmitTaskOutcome.Failed(errorVar.value.toSubmitFailureReason())
        }
    }
}

private fun NSError?.toSubmitFailureReason(): SubmitTaskFailureReason {
    return when (this?.code) {
        BGTaskSchedulerErrorCodeUnavailable -> SubmitTaskFailureReason.UNAVAILABLE
        BGTaskSchedulerErrorCodeTooManyPendingTaskRequests -> SubmitTaskFailureReason.TOO_MANY_PENDING_TASK_REQUESTS
        BGTaskSchedulerErrorCodeNotPermitted -> SubmitTaskFailureReason.NOT_PERMITTED
        else -> SubmitTaskFailureReason.UNKNOWN
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun cancelBackgroundTaskRequest(identifier: String) {
    BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(identifier)
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun pendingBackgroundTaskIdentifiers(): Set<String> {
    val semaphore = dispatch_semaphore_create(0)
    var identifiers: Set<String> = emptySet()

    BGTaskScheduler.sharedScheduler.getPendingTaskRequestsWithCompletionHandler { pending ->
        identifiers = pending.orEmpty()
            .filterIsInstance<BGTaskRequest>()
            .map { it.identifier }
            .toSet()
        dispatch_semaphore_signal(semaphore)
    }
    dispatch_semaphore_wait(semaphore, DISPATCH_TIME_FOREVER)

    return identifiers
}
