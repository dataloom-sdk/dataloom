package io.dataloom.scheduler.bgtask

import io.dataloom.runtime.facade.DataLoomQueueWorker
import io.dataloom.scheduler.bgtask.internal.succeeded
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import platform.BackgroundTasks.BGTask

/**
 * Executes one [DataLoomQueueWorker] cycle in response to a real `BGTask`
 * launch, and reports the outcome back to `BGTaskScheduler`.
 *
 * ## Role
 *
 * This is the Apple counterpart to `dataloom-scheduler-workmanager`'s
 * `DataLoomCoroutineWorker`: that class runs inside AndroidX WorkManager's
 * own `CoroutineWorker.doWork()` hook; this class is called directly by the
 * host application from inside the closure it registers via
 * `BGTaskScheduler.shared.register(forTaskWithIdentifier:using:launchHandler:)`
 * -- `BGTaskScheduler` has no equivalent "just implement this interface"
 * hook of its own, so the host application must call [handle] itself. See
 * `docs/apple/background-task-handler.md` and
 * `docs/apple/scheduler-provider.md` (which documents the same Info.plist /
 * launch-time registration constraint this class does not change).
 *
 * ## What the host application must do
 *
 * ```
 * BGTaskScheduler.shared.register(forTaskWithIdentifier: "com.example.sync", using: nil) { task in
 *     backgroundTaskHandler.handle(task: task as! BGTask)
 * }
 * ```
 *
 * [handle] returns immediately; it does not block the calling thread. The
 * queue-worker cycle itself runs asynchronously on [scope], and [BGTask] is
 * completed exactly once, from that asynchronous cycle -- never
 * synchronously inside [handle] itself, matching how every real `BGTask`
 * launch handler must behave (the OS expects a launch handler to return
 * quickly and to call `setTaskCompleted` later, potentially after real
 * asynchronous work).
 *
 * ## Expiration
 *
 * [handle] sets [BGTask.expirationHandler] to cancel the in-flight cycle's
 * [Job]. [DataLoomQueueWorker.run]'s own [CancellationException] contract
 * propagates normally through that cancellation; this class only ever
 * reports `false` to [BGTask.setTaskCompletedWithSuccess] for a cancelled
 * cycle, and never lets the [CancellationException] escape [handle] itself
 * (which would otherwise crash the host application from inside platform
 * callback code that does not expect [handle] to throw).
 *
 * ## Exactly-once completion
 *
 * The `BGTask` passed to [handle] is completed exactly once per call: either
 * the cycle finishes on its own (successfully, cancelled, or with a provider
 * failure) or the platform calls [BGTask.expirationHandler] first, cancelling
 * the same in-flight [Job] whose own completion still performs the one
 * [BGTask.setTaskCompletedWithSuccess] call. Calling
 * `setTaskCompletedWithSuccess` more than once for the same `BGTask` is a
 * documented platform misuse; this class's structure (completion lives only
 * in the cycle's own `finally`, expiration only ever cancels that same
 * cycle) makes a double call structurally unreachable.
 *
 * ## No scheduling
 *
 * [DataLoomBackgroundTaskHandler] never calls
 * `BGTaskScheduler.shared.submitTaskRequest`,
 * `register(forTaskWithIdentifier:using:launchHandler:)`, or any other
 * scheduling API -- that remains
 * `io.dataloom.platform.ios.scheduling.AppleSchedulerProvider`'s
 * (`dataloom-platform-ios`) sole responsibility. This class owns only the
 * "what happens when the OS actually wakes the app up" half.
 *
 * @param queueWorker the exact [DataLoomQueueWorker] to run one cycle
 *   against, typically `DataLoom.queueWorker` from a configured
 *   `DataLoomBuilder` instance.
 * @param requestFactory creates one fresh
 *   [io.dataloom.runtime.worker.QueueWorkerRunRequest] per [handle] call.
 * @param scope the [CoroutineScope] each queue-worker cycle runs on. Defaults
 *   to a private scope backed by [Dispatchers.Default] and a
 *   [SupervisorJob], so one cycle's failure never cancels another
 *   concurrent [handle] call sharing the same handler instance. Inject a
 *   test scope (for example, a `TestScope`) to make [handle]'s asynchronous
 *   completion deterministic in tests.
 */
@OptIn(ExperimentalForeignApi::class)
public class DataLoomBackgroundTaskHandler(
    private val queueWorker: DataLoomQueueWorker,
    private val requestFactory: QueueWorkerRunRequestFactory,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    /**
     * Runs exactly one [DataLoomQueueWorker] cycle for [task] and reports its
     * outcome via [BGTask.setTaskCompletedWithSuccess].
     *
     * Returns immediately. See the class KDoc for the exactly-once
     * completion and expiration-cancellation contract.
     */
    public fun handle(task: BGTask) {
        val job: Job = scope.launch {
            var succeeded = false
            try {
                succeeded = queueWorker.run(requestFactory.create()).succeeded()
            } catch (exception: CancellationException) {
                succeeded = false
                throw exception
            } finally {
                // Deliberately not clearing task.expirationHandler here: by
                // the time this finally block runs, job has already reached
                // its own terminal state, so a late expiration firing this
                // same closure only calls Job.cancel() on an
                // already-completed job -- a documented no-op, not a second
                // setTaskCompletedWithSuccess call. Clearing it here would
                // race a scope whose dispatcher runs this coroutine body
                // synchronously inside the scope.launch call below, before
                // the assignment on the next line has happened.
                task.setTaskCompletedWithSuccess(succeeded)
            }
        }
        task.expirationHandler = { job.cancel() }
    }
}
