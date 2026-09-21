package io.dataloom.platform.ios.lifecycle.internal

import io.dataloom.api.lifecycle.AppLifecycleState

/**
 * Platform-independent mirror of the five `UIApplication` lifecycle
 * notifications this module observes, each mapped to the coarse
 * [AppLifecycleState] it implies.
 *
 * `UIApplicationState.inactive` (reached through
 * `UIApplicationWillResignActiveNotification`, and again while the app is
 * moving to the foreground) still means the app is on screen, so every
 * notification other than `DidEnterBackground` and `WillTerminate` maps to
 * [AppLifecycleState.FOREGROUND]. Repeated mappings to the same state are
 * absorbed by the provider's distinct-until-changed stream, not here.
 */
internal enum class LifecycleNotification(val state: AppLifecycleState) {
    /** `UIApplicationDidBecomeActiveNotification`. */
    DID_BECOME_ACTIVE(AppLifecycleState.FOREGROUND),

    /** `UIApplicationWillResignActiveNotification`: inactive, still visible. */
    WILL_RESIGN_ACTIVE(AppLifecycleState.FOREGROUND),

    /** `UIApplicationDidEnterBackgroundNotification`. */
    DID_ENTER_BACKGROUND(AppLifecycleState.BACKGROUND),

    /** `UIApplicationWillEnterForegroundNotification`. */
    WILL_ENTER_FOREGROUND(AppLifecycleState.FOREGROUND),

    /** `UIApplicationWillTerminateNotification`: best effort, see [AppLifecycleState.TERMINATING_SOON]. */
    WILL_TERMINATE(AppLifecycleState.TERMINATING_SOON),
}
