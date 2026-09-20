package io.dataloom.platform.ios.lifecycle.internal

import io.dataloom.api.lifecycle.AppLifecycleState
import kotlin.test.Test
import kotlin.test.assertEquals

class LifecycleNotificationTest {

    @Test
    fun `active and foreground notifications map to FOREGROUND`() {
        assertEquals(AppLifecycleState.FOREGROUND, LifecycleNotification.DID_BECOME_ACTIVE.state)
        assertEquals(AppLifecycleState.FOREGROUND, LifecycleNotification.WILL_ENTER_FOREGROUND.state)
    }

    @Test
    fun `resigning active is still FOREGROUND because inactive is on screen`() {
        assertEquals(AppLifecycleState.FOREGROUND, LifecycleNotification.WILL_RESIGN_ACTIVE.state)
    }

    @Test
    fun `entering background maps to BACKGROUND`() {
        assertEquals(AppLifecycleState.BACKGROUND, LifecycleNotification.DID_ENTER_BACKGROUND.state)
    }

    @Test
    fun `will terminate maps to TERMINATING_SOON`() {
        assertEquals(AppLifecycleState.TERMINATING_SOON, LifecycleNotification.WILL_TERMINATE.state)
    }

    @Test
    fun `exactly the five UIApplication notifications are observed`() {
        assertEquals(5, LifecycleNotification.entries.size)
    }
}
