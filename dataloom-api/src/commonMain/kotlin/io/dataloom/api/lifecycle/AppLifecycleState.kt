package io.dataloom.api.lifecycle

/**
 * Closed, coarse set of application lifecycle states.
 *
 * [AppLifecycleState] is deliberately coarser than any platform lifecycle: it
 * folds Android's process-level started/resumed states and iOS's
 * active/inactive application states into the three answers the DataLoom
 * runtime can act on. It carries no activity, scene, window, or process
 * identifiers.
 *
 * These values are not bound to AndroidX lifecycle constants, `UIApplication`
 * states, or any other platform representation.
 *
 * Enum ordinals are not a compatibility contract and must not be persisted.
 */
public enum class AppLifecycleState {
    /**
     * The application is visible to the user.
     *
     * On Android this means at least one activity of the process is started.
     * On iOS this covers both the active and the transient inactive
     * application states (for example, while an incoming-call banner or the
     * app switcher is shown), because the application is still on screen.
     */
    FOREGROUND,

    /**
     * The application is not visible to the user.
     *
     * The operating system may suspend or terminate the process without
     * further notice while it stays in this state.
     */
    BACKGROUND,

    /**
     * The platform has signalled that the process is about to terminate.
     *
     * Only platforms that can deliver such a signal ever report this state:
     * iOS reports it from `UIApplicationWillTerminateNotification` on a
     * best-effort basis (the platform does not deliver it when it kills a
     * suspended process), and Android never reports it because the platform
     * offers no termination signal. Providers declare the ability through
     * [AppLifecycleCapabilities.TERMINATING_SOON].
     *
     * This is a terminal state: no further transition follows it.
     */
    TERMINATING_SOON,
}
