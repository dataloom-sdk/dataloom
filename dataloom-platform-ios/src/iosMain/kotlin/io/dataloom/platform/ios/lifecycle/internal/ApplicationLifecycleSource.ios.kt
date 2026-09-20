package io.dataloom.platform.ios.lifecycle.internal

import io.dataloom.api.lifecycle.AppLifecycleState
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSThread
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.UIKit.UIApplicationWillTerminateNotification
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_sync

/**
 * The files in this source set that touch UIKit, Foundation notifications, or
 * `dispatch` for lifecycle observation: this file only.
 */
internal actual fun defaultApplicationLifecycleSource(): ApplicationLifecycleSource =
    ApplicationLifecycleSource(
        mainThread = DispatchMainThreadExecutor,
        binder = NsNotificationBinder(NSNotificationCenter.defaultCenter),
        readApplicationState = ::readUiApplicationState,
    )

/** [MainThreadExecutor] over the main `dispatch` queue. */
@OptIn(ExperimentalForeignApi::class)
internal object DispatchMainThreadExecutor : MainThreadExecutor {
    override fun execute(block: () -> Unit) {
        if (NSThread.isMainThread) {
            block()
        } else {
            dispatch_async(dispatch_get_main_queue()) { block() }
        }
    }

    override fun <T> executeBlocking(block: () -> T): T {
        if (NSThread.isMainThread) return block()

        var outcome: Result<T>? = null
        dispatch_sync(dispatch_get_main_queue()) {
            outcome = runCatching(block)
        }
        return checkNotNull(outcome) { "Main queue did not run the block." }.getOrThrow()
    }
}

/**
 * [NotificationBinder] over an `NSNotificationCenter`.
 *
 * Observers are added without an `NSOperationQueue`, so each block runs
 * synchronously on the thread that posts the notification: the main thread
 * for UIKit, which needs no run-loop hop and keeps delivery order equal to
 * posting order. The sink only hands a value to a channel and never blocks.
 * [center] is a parameter so tests can use a private center.
 */
internal class NsNotificationBinder(
    private val center: NSNotificationCenter,
) : NotificationBinder {
    override fun bind(sink: (LifecycleNotification) -> Unit): Unbind {
        val observers = LifecycleNotification.entries.map { notification ->
            center.addObserverForName(
                name = notification.notificationName(),
                `object` = null,
                queue = null,
            ) { _ -> sink(notification) }
        }
        return Unbind {
            observers.forEach { observer -> center.removeObserver(observer) }
        }
    }
}

/** The `UIApplication` notification name each [LifecycleNotification] mirrors. */
internal fun LifecycleNotification.notificationName(): String? = when (this) {
    LifecycleNotification.DID_BECOME_ACTIVE -> UIApplicationDidBecomeActiveNotification
    LifecycleNotification.WILL_RESIGN_ACTIVE -> UIApplicationWillResignActiveNotification
    LifecycleNotification.DID_ENTER_BACKGROUND -> UIApplicationDidEnterBackgroundNotification
    LifecycleNotification.WILL_ENTER_FOREGROUND -> UIApplicationWillEnterForegroundNotification
    LifecycleNotification.WILL_TERMINATE -> UIApplicationWillTerminateNotification
}

/**
 * Reads `UIApplication.applicationState`. Main thread only; the caller
 * ([ApplicationLifecycleSource]) guarantees that and maps any failure (for
 * example a process with no application object) to background.
 */
internal fun readUiApplicationState(): AppLifecycleState =
    if (UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateBackground) {
        AppLifecycleState.BACKGROUND
    } else {
        AppLifecycleState.FOREGROUND
    }
