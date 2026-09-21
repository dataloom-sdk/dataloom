package io.dataloom.platform.ios.lifecycle.internal

import platform.Foundation.NSNotificationCenter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Exercises [NsNotificationBinder] against a real, private
 * `NSNotificationCenter`: real observer registration, real posting under the
 * real `UIApplication*Notification` names, real removal.
 */
class NsNotificationBinderTest {

    private val center = NSNotificationCenter()
    private val received: MutableList<LifecycleNotification> = mutableListOf()

    private fun post(notification: LifecycleNotification) {
        center.postNotificationName(notification.notificationName(), `object` = null)
    }

    @Test
    fun everyLifecycleNotificationHasAPlatformName() {
        LifecycleNotification.entries.forEach { notification ->
            assertNotNull(notification.notificationName(), "no name for $notification")
        }
    }

    @Test
    fun deliversEachNotificationSynchronouslyOnThePostingThread() {
        NsNotificationBinder(center).bind { received += it }

        LifecycleNotification.entries.forEach(::post)

        assertEquals(LifecycleNotification.entries.toList(), received)
    }

    @Test
    fun stopsDeliveringAfterUnbind() {
        val unbind = NsNotificationBinder(center).bind { received += it }
        post(LifecycleNotification.DID_BECOME_ACTIVE)

        unbind.unbind()
        LifecycleNotification.entries.forEach(::post)

        assertEquals(listOf(LifecycleNotification.DID_BECOME_ACTIVE), received)
    }

    @Test
    fun unbindIsIdempotent() {
        val unbind = NsNotificationBinder(center).bind { received += it }

        unbind.unbind()
        unbind.unbind()
        post(LifecycleNotification.DID_ENTER_BACKGROUND)

        assertEquals(emptyList(), received)
    }

    @Test
    fun bindingsAreIndependent() {
        val first: MutableList<LifecycleNotification> = mutableListOf()
        val second: MutableList<LifecycleNotification> = mutableListOf()
        val binder = NsNotificationBinder(center)
        val firstUnbind = binder.bind { first += it }
        binder.bind { second += it }

        firstUnbind.unbind()
        post(LifecycleNotification.WILL_TERMINATE)

        assertEquals(emptyList(), first)
        assertEquals(listOf(LifecycleNotification.WILL_TERMINATE), second)
    }
}
