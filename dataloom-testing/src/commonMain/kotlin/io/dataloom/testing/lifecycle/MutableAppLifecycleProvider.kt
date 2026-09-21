package io.dataloom.testing.lifecycle

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.lifecycle.AppLifecycleCapabilities
import io.dataloom.api.lifecycle.AppLifecycleObservationException
import io.dataloom.api.lifecycle.AppLifecycleProvider
import io.dataloom.api.lifecycle.AppLifecycleState
import io.dataloom.api.provider.ProviderCapability
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.testing.provider.TestProviderLifecycleController
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Mutable in-memory [AppLifecycleProvider] for deterministic common tests.
 *
 * Tests drive the lifecycle with [setState]; delivery is synchronous with the
 * call, so no clock, dispatcher, or platform is involved. The fake honours the
 * full [AppLifecycleProvider] stream contract (cold, seeded with [current],
 * distinct, conflated, observer released on cancellation) and is exercised by
 * the same [AppLifecycleProviderContract] suite as the real implementations.
 *
 * Like the real providers, [current] only ever reports
 * [AppLifecycleState.FOREGROUND] or [AppLifecycleState.BACKGROUND]:
 * [AppLifecycleState.TERMINATING_SOON] is delivered to collectors that are
 * active when it is set, and is never replayed to a later collector.
 *
 * Collector bookkeeping is not thread-safe. Callers that share an instance
 * across threads must serialize mutation externally.
 *
 * @param initialState initial reading of [current]; must not be
 *   [AppLifecycleState.TERMINATING_SOON].
 * @param descriptor provider descriptor exposed through
 *   [AppLifecycleProvider.descriptor].
 * @param observationFailure when non-null, every collection of [states] fails
 *   with an [AppLifecycleObservationException] carrying this error.
 * @param lifecycleController shared lifecycle controller used by provider tests.
 */
public class MutableAppLifecycleProvider(
    initialState: AppLifecycleState = AppLifecycleState.FOREGROUND,
    override val descriptor: ProviderDescriptor = defaultDescriptor(),
    private val observationFailure: DataLoomError? = null,
    private val lifecycleController: TestProviderLifecycleController = TestProviderLifecycleController(),
) : AppLifecycleProvider {
    private val initialStateValue: AppLifecycleState = initialState
    private var currentValue: AppLifecycleState = initialState
    private val subscribers: MutableList<Channel<AppLifecycleState>> = mutableListOf()

    init {
        require(initialState != AppLifecycleState.TERMINATING_SOON) {
            "initialState must be FOREGROUND or BACKGROUND."
        }
    }

    override val current: AppLifecycleState
        get() = currentValue

    /** Number of [states] collections currently active. */
    public val activeCollectorCount: Int
        get() = subscribers.size

    /**
     * Reports [state] to [current] (unless it is
     * [AppLifecycleState.TERMINATING_SOON]) and to every active collector.
     */
    public fun setState(state: AppLifecycleState) {
        if (state != AppLifecycleState.TERMINATING_SOON) {
            currentValue = state
        }
        subscribers.toList().forEach { subscriber -> subscriber.trySend(state) }
    }

    override suspend fun initialize(
        context: ProviderInitializationContext,
    ): ProviderOperationResult<Unit> = lifecycleController.initialize(context)

    override suspend fun health(): ProviderOperationResult<ProviderHealth> = lifecycleController.health()

    override suspend fun close(): ProviderOperationResult<Unit> = lifecycleController.close()

    override fun states(): Flow<AppLifecycleState> = flow {
        observationFailure?.let { throw AppLifecycleObservationException(it) }

        val subscriber = Channel<AppLifecycleState>(Channel.CONFLATED)
        subscribers += subscriber
        try {
            var last: AppLifecycleState = currentValue
            emit(last)
            // TERMINATING_SOON is terminal: the collection (and its observer)
            // stays alive until cancelled, but never emits again.
            var terminated = false
            for (next in subscriber) {
                if (terminated || next == last) continue
                last = next
                emit(next)
                terminated = next == AppLifecycleState.TERMINATING_SOON
            }
        } finally {
            subscribers -= subscriber
        }
    }

    /** Resets [current] to the initial state without touching active collectors. */
    public fun resetState() {
        currentValue = initialStateValue
        lifecycleController.clearRecordings()
    }
}

private fun defaultDescriptor(): ProviderDescriptor = ProviderDescriptor(
    id = ProviderId("testing.lifecycle.mutable"),
    name = ProviderName("MutableAppLifecycleProvider"),
    type = ProviderType.APP_LIFECYCLE,
    version = ProviderVersion("1.0.0"),
    capabilities = setOf(
        AppLifecycleCapabilities.STATE_STREAM,
        AppLifecycleCapabilities.TERMINATING_SOON,
        ProviderCapability("testing"),
        ProviderCapability("mutable"),
    ),
)
