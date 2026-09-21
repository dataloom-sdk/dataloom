package io.dataloom.lifecycle.android

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
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
import io.dataloom.lifecycle.android.internal.LifecycleProviderError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

/**
 * Android [AppLifecycleProvider] backed by the AndroidX process lifecycle
 * ([ProcessLifecycleOwner]).
 *
 * The process lifecycle folds every activity of the process into one
 * lifecycle, so this provider reports [AppLifecycleState.FOREGROUND] while at
 * least one activity is started and [AppLifecycleState.BACKGROUND] otherwise.
 * It has no [AppLifecycleState.TERMINATING_SOON] signal to offer: Android
 * gives an application no termination callback, so the descriptor does not
 * declare [AppLifecycleCapabilities.TERMINATING_SOON].
 *
 * ## Background transition delay
 *
 * [ProcessLifecycleOwner] deliberately delays its stop event by roughly 700 ms
 * so that an activity restart (for example a configuration change) is not
 * reported as leaving the foreground. [AppLifecycleState.BACKGROUND] is
 * reported after that delay.
 *
 * ## Threading
 *
 * [Lifecycle] registration is main-thread-only. [states] may be collected
 * from any thread: it registers and removes its observer by switching to the
 * main dispatcher, and removal runs even when the collector is cancelled.
 * [current] reads the lifecycle state, which the AndroidX API allows from
 * any thread.
 *
 * ## Requirements
 *
 * The process lifecycle is started by `ProcessLifecycleInitializer`, which
 * `lifecycle-process` registers through `androidx.startup`. An application
 * that removes that initializer from its manifest leaves the lifecycle
 * un-started: [current] then reports [AppLifecycleState.BACKGROUND] and
 * [health] reports unhealthy.
 *
 * It does not poll, own a coroutine scope, register anything until [states]
 * is collected, or expose any AndroidX type through its public API.
 */
public class AndroidLifecycleProvider internal constructor(
    private val lifecycle: Lifecycle,
) : AppLifecycleProvider {

    public constructor() : this(ProcessLifecycleOwner.get().lifecycle)

    override val descriptor: ProviderDescriptor = ProviderDescriptor(
        id = ProviderId("io.dataloom.lifecycle.android"),
        name = ProviderName("AndroidLifecycleProvider"),
        type = ProviderType.APP_LIFECYCLE,
        version = ProviderVersion("1.0.0"),
        capabilities = setOf(AppLifecycleCapabilities.STATE_STREAM),
    )

    override val current: AppLifecycleState
        get() = lifecycle.currentState.toAppLifecycleState()

    override suspend fun initialize(
        context: ProviderInitializationContext,
    ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

    override suspend fun health(): ProviderOperationResult<ProviderHealth> =
        ProviderOperationResult.Success(
            if (lifecycle.currentState == Lifecycle.State.INITIALIZED) {
                ProviderHealth(
                    status = ProviderHealthStatus.UNHEALTHY,
                    error = LifecycleProviderError.processLifecycleNotStarted(),
                )
            } else {
                ProviderHealth(status = ProviderHealthStatus.HEALTHY)
            },
        )

    override suspend fun close(): ProviderOperationResult<Unit> =
        ProviderOperationResult.Success(Unit)

    override fun states(): Flow<AppLifecycleState> = callbackFlow {
        val observer = LifecycleEventObserver { _, _ ->
            trySend(lifecycle.currentState.toAppLifecycleState())
        }
        var registered = false
        try {
            withContext(Dispatchers.Main.immediate) {
                lifecycle.addObserver(observer)
                registered = true
                // Seed inside the same main-thread block so it cannot overtake
                // a newer event delivered to the observer.
                trySend(lifecycle.currentState.toAppLifecycleState())
            }
            awaitClose()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            throw AppLifecycleObservationException(LifecycleProviderError.platformFailure())
        } finally {
            // Also runs when cancellation lands right after registration,
            // which is why registration is tracked instead of assumed.
            if (registered) {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    lifecycle.removeObserver(observer)
                }
            }
        }
    }.conflate().distinctUntilChanged()
}

private fun Lifecycle.State.toAppLifecycleState(): AppLifecycleState =
    if (isAtLeast(Lifecycle.State.STARTED)) {
        AppLifecycleState.FOREGROUND
    } else {
        AppLifecycleState.BACKGROUND
    }
