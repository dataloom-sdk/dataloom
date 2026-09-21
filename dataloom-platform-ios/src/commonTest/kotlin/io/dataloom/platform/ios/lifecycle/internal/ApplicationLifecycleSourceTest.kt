package io.dataloom.platform.ios.lifecycle.internal

import io.dataloom.api.lifecycle.AppLifecycleState
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationLifecycleSourceTest {

    private class RecordingSink : LifecycleSignalSink {
        val signals: MutableList<String> = mutableListOf()

        override fun onSeed(state: AppLifecycleState) {
            signals += "seed:$state"
        }

        override fun onNotification(notification: LifecycleNotification) {
            signals += "notification:$notification"
        }

        override fun onFailure() {
            signals += "failure"
        }
    }

    private val binder = FakeNotificationBinder()

    private fun sourceOn(
        executor: MainThreadExecutor,
        read: () -> AppLifecycleState = { AppLifecycleState.FOREGROUND },
    ) = ApplicationLifecycleSource(executor, binder, read)

    @Test
    fun `observation registers then seeds then forwards notifications in order`() {
        val sink = RecordingSink()

        sourceOn(ImmediateMainThreadExecutor).observe(sink)
        binder.post(LifecycleNotification.DID_ENTER_BACKGROUND)
        binder.post(LifecycleNotification.WILL_ENTER_FOREGROUND)

        assertEquals(
            listOf(
                "seed:FOREGROUND",
                "notification:DID_ENTER_BACKGROUND",
                "notification:WILL_ENTER_FOREGROUND",
            ),
            sink.signals,
        )
        assertEquals(1, binder.activeBindings)
    }

    @Test
    fun `off-main observation defers registration until the main thread runs it`() {
        val executor = QueueingMainThreadExecutor()
        val sink = RecordingSink()

        sourceOn(executor).observe(sink)

        assertEquals(0, binder.bindCount)
        executor.runQueued()
        assertEquals(1, binder.bindCount)
        assertEquals(listOf("seed:FOREGROUND"), sink.signals)
    }

    @Test
    fun `cancelling before registration ran never registers an observer`() {
        val executor = QueueingMainThreadExecutor()
        val sink = RecordingSink()

        val observation = sourceOn(executor).observe(sink)
        observation.cancel()
        executor.runQueued()

        assertEquals(0, binder.bindCount)
        assertEquals(0, binder.activeBindings)
        assertEquals(emptyList(), sink.signals)
    }

    @Test
    fun `cancelling after registration removes the observer and silences the sink`() {
        val executor = QueueingMainThreadExecutor()
        val sink = RecordingSink()
        val observation = sourceOn(executor).observe(sink)
        executor.runQueued()

        observation.cancel()
        executor.runQueued()
        binder.post(LifecycleNotification.DID_ENTER_BACKGROUND)

        assertEquals(0, binder.activeBindings)
        assertEquals(listOf("seed:FOREGROUND"), sink.signals)
    }

    @Test
    fun `cancel is idempotent`() {
        val observation = sourceOn(ImmediateMainThreadExecutor).observe(RecordingSink())

        observation.cancel()
        observation.cancel()

        assertEquals(0, binder.activeBindings)
    }

    @Test
    fun `a notification already in flight when cancelled is dropped`() {
        val sink = RecordingSink()
        val observation = sourceOn(ImmediateMainThreadExecutor).observe(sink)

        observation.cancel()
        binder.postToRemovedObservers(LifecycleNotification.DID_BECOME_ACTIVE)

        assertEquals(listOf("seed:FOREGROUND"), sink.signals)
    }

    @Test
    fun `a failing state read seeds BACKGROUND instead of failing the observation`() {
        val sink = RecordingSink()

        sourceOn(ImmediateMainThreadExecutor) { error("no UIApplication") }.observe(sink)

        assertEquals(listOf("seed:BACKGROUND"), sink.signals)
        assertEquals(1, binder.activeBindings)
    }

    @Test
    fun `a registration failure reports failure and leaves no observer`() {
        val sink = RecordingSink()
        binder.failOnBind = true

        sourceOn(ImmediateMainThreadExecutor).observe(sink)

        assertEquals(listOf("failure"), sink.signals)
        assertEquals(0, binder.activeBindings)
    }

    @Test
    fun `readCurrent returns the application state`() {
        assertEquals(
            AppLifecycleState.BACKGROUND,
            sourceOn(ImmediateMainThreadExecutor) { AppLifecycleState.BACKGROUND }.readCurrent(),
        )
    }

    @Test
    fun `readCurrent never throws and falls back to BACKGROUND`() {
        assertEquals(
            AppLifecycleState.BACKGROUND,
            sourceOn(ImmediateMainThreadExecutor) { error("no UIApplication") }.readCurrent(),
        )
    }
}
