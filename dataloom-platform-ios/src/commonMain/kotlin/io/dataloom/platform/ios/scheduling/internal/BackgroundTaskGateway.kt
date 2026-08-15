package io.dataloom.platform.ios.scheduling.internal

/** Outcome of one bounded `BGTaskScheduler.submitTaskRequest(_:error:)` call. */
internal sealed class SubmitTaskOutcome {
    internal object Submitted : SubmitTaskOutcome()
    internal data class Failed(val reason: SubmitTaskFailureReason) : SubmitTaskOutcome()
}

/**
 * Platform-independent mirror of the `BGTaskSchedulerErrorCode` values
 * `submitTaskRequest(_:error:)` can report through its `NSError` out
 * parameter, plus [UNKNOWN] for any failure this module cannot classify
 * without retaining the raw `NSError`.
 */
internal enum class SubmitTaskFailureReason {
    /**
     * `BGTaskSchedulerErrorCodeUnavailable` -- background task submission is
     * unavailable in this process (for example, an app extension, or the
     * app was launched directly by Xcode rather than by the system).
     */
    UNAVAILABLE,

    /**
     * `BGTaskSchedulerErrorCodeTooManyPendingTaskRequests` -- the platform's
     * limit on simultaneously pending task requests was reached.
     */
    TOO_MANY_PENDING_TASK_REQUESTS,

    /**
     * `BGTaskSchedulerErrorCodeNotPermitted` -- the identifier is not listed
     * in the host app's `Info.plist` `BGTaskSchedulerPermittedIdentifiers`
     * array, or was not registered via
     * `BGTaskScheduler.shared.register(forTaskWithIdentifier:using:launchHandler:)`
     * at app-launch time.
     */
    NOT_PERMITTED,

    /** Any other platform failure this module does not classify further. */
    UNKNOWN,
}

/**
 * Submits [plan] as a `BGProcessingTaskRequest` or `BGAppRefreshTaskRequest`
 * (per [SchedulePlan.kind]) to `BGTaskScheduler.shared`.
 *
 * This is one of three functions in this module that touch
 * `platform.BackgroundTasks` (`iosMain`); together they are the only files
 * that construct a `BGTaskRequest`, read an `NSError`, or otherwise reference
 * a `BackgroundTasks` platform type. Ordinary platform rejection (identifier
 * not permitted, too many pending requests, background tasks unavailable) is
 * reported as [SubmitTaskOutcome.Failed], not thrown. A thrown exception is
 * reserved for a genuine unexpected platform failure, which
 * [io.dataloom.platform.ios.scheduling.AppleSchedulerProvider] maps to a
 * canonical [io.dataloom.api.error.DataLoomError].
 *
 * Deliberately does not call
 * `BGTaskScheduler.shared.register(forTaskWithIdentifier:using:launchHandler:)`
 * -- registration must happen once, at app-launch time, before
 * `applicationDidFinishLaunching` returns, which is a lifecycle guarantee
 * this module cannot make on the host application's behalf. See
 * `docs/apple/scheduler-provider.md`.
 */
internal expect fun submitBackgroundTaskRequest(plan: SchedulePlan): SubmitTaskOutcome

/**
 * Cancels any pending `BGTaskRequest` registered under [identifier] via
 * `BGTaskScheduler.shared.cancelTaskRequestWithIdentifier(_:)`.
 *
 * A no-op, per Apple's documented behavior, when no pending request exists
 * for [identifier].
 */
internal expect fun cancelBackgroundTaskRequest(identifier: String)

/**
 * Performs one bounded synchronous query of every identifier currently
 * pending in `BGTaskScheduler.shared`, via
 * `getPendingTaskRequestsWithCompletionHandler(_:)`.
 *
 * Blocks the calling thread until the platform's completion handler fires,
 * matching the same bounded-query-via-semaphore pattern
 * [io.dataloom.platform.ios.connectivity.internal.currentNetworkPathObservation]
 * uses for `NWPathMonitor`. No callback or semaphore outlives this single
 * call -- there is no polling, caching, or long-lived observation.
 */
internal expect fun pendingBackgroundTaskIdentifiers(): Set<String>
