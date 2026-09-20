package io.dataloom.platform.ios.lifecycle.internal

import io.dataloom.api.lifecycle.AppLifecycleState

/** Runs everything inline, as if every caller were the main thread. */
internal object ImmediateMainThreadExecutor : MainThreadExecutor {
    override fun execute(block: () -> Unit) = block()

    override fun <T> executeBlocking(block: () -> T): T = block()
}

/**
 * Simulates callers that are not on the main thread: [execute] queues the work
 * until the test calls [runQueued], the way `dispatch_async` on the main queue
 * would run it later.
 */
internal class QueueingMainThreadExecutor : MainThreadExecutor {
    private val queue: ArrayDeque<() -> Unit> = ArrayDeque()

    val pendingCount: Int get() = queue.size

    override fun execute(block: () -> Unit) {
        queue.addLast(block)
    }

    override fun <T> executeBlocking(block: () -> T): T = block()

    fun runQueued() {
        while (queue.isNotEmpty()) queue.removeFirst()()
    }
}

/** In-memory [NotificationBinder] that records registrations and lets tests post notifications. */
internal class FakeNotificationBinder : NotificationBinder {
    private val sinks: MutableList<(LifecycleNotification) -> Unit> = mutableListOf()
    private val everBound: MutableList<(LifecycleNotification) -> Unit> = mutableListOf()

    var bindCount: Int = 0
        private set
    var failOnBind: Boolean = false

    val activeBindings: Int get() = sinks.size

    override fun bind(sink: (LifecycleNotification) -> Unit): Unbind {
        check(!failOnBind) { "secret-platform-detail" }
        bindCount++
        sinks += sink
        everBound += sink
        return Unbind { sinks.remove(sink) }
    }

    fun post(notification: LifecycleNotification) {
        sinks.toList().forEach { sink -> sink(notification) }
    }

    /** Delivers to every sink ever bound, as a notification already in flight during removal would be. */
    fun postToRemovedObservers(notification: LifecycleNotification) {
        everBound.toList().forEach { sink -> sink(notification) }
    }
}

/**
 * Translates a desired [AppLifecycleState] into the notification sequence UIKit
 * posts for it, and keeps the "application state" a reader would report in step.
 */
internal class NotificationDriver(
    private val post: (LifecycleNotification) -> Unit,
) {
    var platformState: AppLifecycleState = AppLifecycleState.BACKGROUND
        private set

    fun drive(state: AppLifecycleState) {
        when (state) {
            AppLifecycleState.FOREGROUND -> {
                platformState = AppLifecycleState.FOREGROUND
                post(LifecycleNotification.WILL_ENTER_FOREGROUND)
                post(LifecycleNotification.DID_BECOME_ACTIVE)
            }
            AppLifecycleState.BACKGROUND -> {
                // UIKit only resigns active while the app is on screen.
                if (platformState == AppLifecycleState.FOREGROUND) {
                    post(LifecycleNotification.WILL_RESIGN_ACTIVE)
                }
                platformState = AppLifecycleState.BACKGROUND
                post(LifecycleNotification.DID_ENTER_BACKGROUND)
            }
            AppLifecycleState.TERMINATING_SOON -> post(LifecycleNotification.WILL_TERMINATE)
        }
    }
}
