package io.dataloom.platform.ios.lifecycle.internal

import io.dataloom.api.lifecycle.AppLifecycleState
import kotlin.concurrent.Volatile

/**
 * Runs work on the application's main thread, the only thread `UIApplication`
 * may be read from.
 */
internal interface MainThreadExecutor {
    /**
     * Runs [block] on the main thread without blocking the caller: inline when
     * the caller already is the main thread, otherwise asynchronously on the
     * main queue. Blocks queued from other threads run in submission order.
     */
    fun execute(block: () -> Unit)

    /**
     * Runs [block] on the main thread and returns its result, blocking the
     * caller when it is not the main thread.
     *
     * Blocking hands the work to the main queue and waits for it, so it
     * deadlocks if the main thread is itself blocked waiting for the caller.
     */
    fun <T> executeBlocking(block: () -> T): T
}

/** Removes whatever [NotificationBinder.bind] registered. Safe to call more than once. */
internal fun interface Unbind {
    fun unbind()
}

/**
 * The one platform-specific piece of lifecycle observation: registering for the
 * five [LifecycleNotification]s and delivering each as it is posted.
 */
internal fun interface NotificationBinder {
    /**
     * Starts delivering notifications to [sink] and returns the handle that
     * stops delivery. [sink] is invoked on whichever thread posts the
     * notification (the main thread for UIKit) and must not block.
     */
    fun bind(sink: (LifecycleNotification) -> Unit): Unbind
}

/** Receives one observation's signals. Invoked serially, never after [LifecycleObservation.cancel] returns. */
internal interface LifecycleSignalSink {
    /** The application state read at the moment the observation started. */
    fun onSeed(state: AppLifecycleState)

    /** A lifecycle notification was posted after the seed. */
    fun onNotification(notification: LifecycleNotification)

    /** The observation could not be established. No further signal follows. */
    fun onFailure()
}

/** Handle for one running observation. */
internal class LifecycleObservation internal constructor(
    private val mainThread: MainThreadExecutor,
) {
    @Volatile
    internal var cancelled: Boolean = false
        private set

    // Only touched on the main thread.
    private var unbind: Unbind? = null

    internal fun attach(unbind: Unbind) {
        this.unbind = unbind
    }

    /**
     * Stops the observation and removes its platform observer. Callable from
     * any thread and idempotent. Removal is queued behind a registration that
     * has not run yet, and a registration that has not run yet is skipped, so
     * a cancellation can never leave an observer behind.
     */
    fun cancel() {
        cancelled = true
        mainThread.execute {
            unbind?.unbind()
            unbind = null
        }
    }
}

/**
 * Platform-independent core of iOS lifecycle observation: reads the
 * application state on the main thread and binds the lifecycle notifications
 * so that the seed and every later notification are ordered.
 *
 * Registration and the seed read run together in one main-thread block. UIKit
 * posts lifecycle notifications on the main thread too, so a state change
 * cannot fall between the two and a stale seed can never overtake a newer
 * notification.
 *
 * @param mainThread executor for the main thread.
 * @param binder platform notification registration.
 * @param readApplicationState reads the application's visible state; only ever
 *   called on the main thread and only ever returns
 *   [AppLifecycleState.FOREGROUND] or [AppLifecycleState.BACKGROUND].
 */
internal class ApplicationLifecycleSource(
    private val mainThread: MainThreadExecutor,
    private val binder: NotificationBinder,
    private val readApplicationState: () -> AppLifecycleState,
) {
    /**
     * The application's visible state right now. Never throws: when the state
     * cannot be read (for example there is no `UIApplication` in this
     * process) the answer is [AppLifecycleState.BACKGROUND].
     */
    fun readCurrent(): AppLifecycleState = try {
        mainThread.executeBlocking(readApplicationState)
    } catch (_: Exception) {
        AppLifecycleState.BACKGROUND
    }

    /**
     * Starts one observation. Returns immediately; registration happens on
     * the main thread and reports through [sink].
     */
    fun observe(sink: LifecycleSignalSink): LifecycleObservation {
        val observation = LifecycleObservation(mainThread)
        mainThread.execute {
            if (observation.cancelled) return@execute
            try {
                observation.attach(
                    binder.bind { notification ->
                        if (!observation.cancelled) sink.onNotification(notification)
                    },
                )
                sink.onSeed(
                    try {
                        readApplicationState()
                    } catch (_: Exception) {
                        AppLifecycleState.BACKGROUND
                    },
                )
            } catch (_: Exception) {
                observation.cancel()
                sink.onFailure()
            }
        }
        return observation
    }
}
