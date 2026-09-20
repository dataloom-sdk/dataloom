package io.dataloom.api.lifecycle

import io.dataloom.api.provider.DataLoomProvider
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderType
import kotlinx.coroutines.flow.Flow

/**
 * Platform-independent provider contract for observing the application's
 * coarse lifecycle.
 *
 * [AppLifecycleProvider] is the adapter boundary between the DataLoom runtime
 * and a platform-specific lifecycle mechanism such as the AndroidX process
 * lifecycle, iOS `UIApplication` notifications, or a custom implementation.
 *
 * ```text
 * ProcessLifecycleOwner / UIApplication notifications / custom implementation
 *       ↓
 * AppLifecycleProvider
 *       ↓
 * AppLifecycleState
 *       ↓
 * DataLoom Runtime
 * ```
 *
 * ## Responsibilities
 *
 * Implementations observe platform lifecycle changes and translate them into
 * the canonical [AppLifecycleState] model, keeping platform-specific APIs
 * outside the shared DataLoom public surface.
 *
 * ## Reading and streaming
 *
 * - [current] is a synchronous reading of the state at the moment of the call.
 * - [states] is a cold stream. Nothing is registered with the platform until
 *   the returned flow is collected, and every collection registers and
 *   releases its own platform observer.
 *
 * ## Stream semantics
 *
 * Every collection of [states]:
 * - starts by emitting the [current] state observed when collection begins,
 *   so a late collector never has to combine [current] with the stream to
 *   avoid a gap;
 * - then emits only actual changes: two consecutive emissions are never equal;
 * - is conflated: a collector slower than the platform observes the latest
 *   state, and may skip intermediate states, but the last value it receives
 *   always equals the latest platform state;
 * - never emits after [AppLifecycleState.TERMINATING_SOON], and never
 *   completes on its own;
 * - releases its platform observer when the collector is cancelled or the
 *   flow fails, including a cancellation that races with registration.
 *
 * If the platform lifecycle source cannot be observed, collection fails with
 * [AppLifecycleObservationException]. [current] never throws; when the
 * platform cannot report a state it returns [AppLifecycleState.BACKGROUND],
 * the conservative answer for a consumer deciding whether to start work.
 *
 * ## What this provider must not do
 *
 * Implementations must not:
 * - call back into application code other than through the returned flow
 * - expose Application, Activity, ProcessLifecycleOwner, Lifecycle,
 *   UIApplication, NSNotification, or other platform lifecycle types through
 *   the public API
 * - expose activity, scene, window, task, or process identifiers, or any
 *   personal data
 * - trigger synchronization, scheduling, or retry themselves
 * - expose [kotlinx.coroutines.CoroutineScope] or dispatcher types
 * - automatically register or initialize themselves
 * - log sensitive context automatically
 *
 * ## Thread safety
 *
 * [current] and [states] may be called from any thread. Implementations own
 * any hop to the platform thread their lifecycle source requires and document
 * any constraint that hop imposes.
 *
 * ## Cancellation
 *
 * Implementations must preserve coroutine cancellation and must not convert
 * cancellation exceptions into normal failures.
 *
 * ## Constraints
 *
 * Implementations must:
 * - expose a [descriptor] whose [ProviderDescriptor.type] is
 *   [ProviderType.APP_LIFECYCLE] and that declares
 *   [AppLifecycleCapabilities.STATE_STREAM]
 * - declare [AppLifecycleCapabilities.TERMINATING_SOON] if and only if the
 *   platform can deliver [AppLifecycleState.TERMINATING_SOON]
 * - map platform failures to canonical [io.dataloom.api.error.DataLoomError]
 *   values
 * - not allow platform exceptions to escape through the public contract
 */
public interface AppLifecycleProvider : DataLoomProvider {

    /**
     * Immutable descriptor for this lifecycle provider.
     *
     * [ProviderDescriptor.type] must be [ProviderType.APP_LIFECYCLE].
     */
    override public val descriptor: ProviderDescriptor

    /**
     * The application's lifecycle state at the moment of the call.
     *
     * Synchronous and side-effect free: reading it registers nothing. Never
     * throws; returns [AppLifecycleState.BACKGROUND] when the platform cannot
     * report a state.
     *
     * Only [AppLifecycleState.FOREGROUND] or [AppLifecycleState.BACKGROUND]
     * is ever returned: [AppLifecycleState.TERMINATING_SOON] is an event
     * delivered through [states], not a state a platform can be read for.
     */
    public val current: AppLifecycleState

    /**
     * Returns a cold stream of lifecycle states.
     *
     * See the type-level stream semantics. Creating the flow performs no
     * platform work; collecting it registers one platform observer that lives
     * exactly as long as the collection.
     */
    public fun states(): Flow<AppLifecycleState>
}
