package io.dataloom.platform.ios.lifecycle.internal

import platform.Foundation.NSDate
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSRunLoop
import platform.Foundation.NSThread
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runMode
import platform.Foundation.timeIntervalSinceNow
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Exercises the real `dispatch` main-queue hop of [DispatchMainThreadExecutor].
 *
 * The Kotlin/Native test runner is expected to run test bodies on the main
 * thread; the test then plays the part of the app's main run loop by pumping
 * it. If a runner ever executes tests off the main thread the hop cannot be
 * observed this way and each test returns without asserting, so a pass alone
 * is not evidence that the hop ran: [runsInlineOnTheMainThread] asserts the
 * precondition explicitly.
 */
class DispatchMainThreadExecutorTest {

    private fun pumpMainRunLoopUntil(timeoutSeconds: Double = 10.0, condition: () -> Boolean) {
        val deadline = NSDate.dateWithTimeIntervalSinceNow(timeoutSeconds)
        while (!condition() && deadline.timeIntervalSinceNow > 0.0) {
            NSRunLoop.mainRunLoop.runMode(
                NSDefaultRunLoopMode,
                beforeDate = NSDate.dateWithTimeIntervalSinceNow(0.05),
            )
        }
    }

    @Test
    fun runsInlineOnTheMainThread() {
        assertEquals(true, NSThread.isMainThread, "Kotlin/Native test bodies were expected to run on the main thread.")

        var ranOnMainThread: Boolean? = null
        DispatchMainThreadExecutor.execute { ranOnMainThread = NSThread.isMainThread }

        assertEquals(true, ranOnMainThread)
        assertEquals(7, DispatchMainThreadExecutor.executeBlocking { 7 })
    }

    @Test
    fun hopsToTheMainQueueFromAnotherThread() {
        if (!NSThread.isMainThread) return

        var ranOnMainThread: Boolean? = null
        dispatch_async(dispatch_get_global_queue(0, 0u)) {
            DispatchMainThreadExecutor.execute { ranOnMainThread = NSThread.isMainThread }
        }

        pumpMainRunLoopUntil { ranOnMainThread != null }

        assertEquals(true, ranOnMainThread)
    }

    @Test
    fun blockingCallFromAnotherThreadReturnsTheMainThreadResult() {
        if (!NSThread.isMainThread) return

        var result: Boolean? = null
        dispatch_async(dispatch_get_global_queue(0, 0u)) {
            result = DispatchMainThreadExecutor.executeBlocking { NSThread.isMainThread }
        }

        pumpMainRunLoopUntil { result != null }

        assertEquals(true, result)
    }
}
