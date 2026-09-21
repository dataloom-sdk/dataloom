package io.dataloom.platform.ios.lifecycle

import io.dataloom.api.lifecycle.AppLifecycleCapabilities
import io.dataloom.api.lifecycle.AppLifecycleObservationException
import io.dataloom.api.lifecycle.AppLifecycleProvider
import io.dataloom.api.lifecycle.AppLifecycleState
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.platform.ios.lifecycle.internal.ApplicationLifecycleSource
import io.dataloom.platform.ios.lifecycle.internal.LifecycleNotification
import io.dataloom.platform.ios.lifecycle.internal.LifecycleProviderError
import io.dataloom.platform.ios.lifecycle.internal.LifecycleSignalSink
import io.dataloom.platform.ios.lifecycle.internal.defaultApplicationLifecycleSource
import kotlin.concurrent.Volatile
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * iOS [AppLifecycleProvider] backed by `UIApplication` lifecycle notifications
 * observed through `NSNotificationCenter`.
 *
 * ## Mapping
 *
 * | Notification | State |
 * |---|---|
 * | `didBecomeActive`, `willEnterForeground` | [AppLifecycleState.FOREGROUND] |
 * | `willResignActive` | [AppLifecycleState.FOREGROUND] (inactive is still on screen) |
 * | `didEnterBackground` | [AppLifecycleState.BACKGROUND] |
 * | `willTerminate` | [AppLifecycleState.TERMINATING_SOON] |
 *
 * `willTerminate` is best effort: iOS does not deliver it when it terminates
 * an app that is suspended in the background, which is the common case. A
 * consumer must never rely on receiving it. The descriptor still declares
 * [AppLifecycleCapabilities.TERMINATING_SOON] because the platform can signal
 * it.
 *
 * ## Threading
 *
 * `UIApplication` may only be read on the main thread, and UIKit posts these
 * notifications on the main thread. [states] may be collected from any
 * thread: registration, the seed read, and removal all run on the main
 * thread, and collection never blocks a caller. Notification handling only
 * hands the mapped state to a conflated channel; it never blocks and never
 * touches UI state, so it is safe on the main thread.
 *
 * [current] reads the state on the main thread, so from any other thread it
 * blocks until the main queue services the read. Do not call it from a thread
 * the main thread is blocked waiting on.
 *
 * ## Limits
 *
 * `UIApplication.shared` is unavailable in app extensions, so this provider
 * must not be used there; [current] then reports
 * [AppLifecycleState.BACKGROUND]. It does not poll, own a coroutine scope, or
 * register anything until [states] is collected, and it exposes no UIKit or
 * Foundation type through its public API.
 */
public class AppleLifecycleProvider internal constructor(
    private val source: ApplicationLifecycleSource,
) : AppLifecycleProvider {

    public constructor() : this(source = defaultApplicationLifecycleSource())

    override val descriptor: ProviderDescriptor = ProviderDescriptor(
        id = ProviderId("io.dataloom.lifecycle.ios"),
        name = ProviderName("AppleLifecycleProvider"),
        type = ProviderType.APP_LIFECYCLE,
        version = ProviderVersion("1.0.0"),
        capabilities = setOf(
            AppLifecycleCapabilities.STATE_STREAM,
            AppLifecycleCapabilities.TERMINATING_SOON,
        ),
    )

    override val current: AppLifecycleState
        get() = source.readCurrent()

    override suspend fun initialize(
        context: ProviderInitializationContext,
    ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

    override suspend fun health(): ProviderOperationResult<ProviderHealth> =
        ProviderOperationResult.Success(ProviderHealth(status = ProviderHealthStatus.HEALTHY))

    override suspend fun close(): ProviderOperationResult<Unit> =
        ProviderOperationResult.Success(Unit)

    override fun states(): Flow<AppLifecycleState> = callbackFlow {
        val observation = source.observe(ChannelSink(this))
        try {
            awaitClose()
        } finally {
            // Runs on cancellation, on failure, and on normal close alike.
            observation.cancel()
        }
    }.conflate().distinctUntilChanged()

    /** Feeds one collection's channel and enforces that nothing follows TERMINATING_SOON. */
    private class ChannelSink(
        private val scope: ProducerScope<AppLifecycleState>,
    ) : LifecycleSignalSink {
        @Volatile
        private var terminated: Boolean = false

        override fun onSeed(state: AppLifecycleState) {
            scope.trySend(state)
        }

        override fun onNotification(notification: LifecycleNotification) {
            if (terminated) return
            terminated = notification.state == AppLifecycleState.TERMINATING_SOON
            scope.trySend(notification.state)
        }

        override fun onFailure() {
            scope.close(AppLifecycleObservationException(LifecycleProviderError.platformFailure()))
        }
    }
}
