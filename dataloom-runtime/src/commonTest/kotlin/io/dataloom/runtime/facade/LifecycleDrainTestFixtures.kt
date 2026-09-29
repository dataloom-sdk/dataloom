package io.dataloom.runtime.facade

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Minimal deterministic [AppLifecycleProvider] for this module's tests.
 *
 * `dataloom-runtime` must not depend on `dataloom-testing`, so this is a small
 * local stand-in for `MutableAppLifecycleProvider`. Every collection is seeded
 * with [current] and then receives each [set] value in order.
 *
 * @param streamFailure when non-null, thrown by each collection right after
 *   the seed value is emitted.
 */
internal class FakeAppLifecycleProvider(
    initial: AppLifecycleState = AppLifecycleState.FOREGROUND,
    private val streamFailure: Throwable? = null,
    private val completesAfterSeed: Boolean = false,
    type: ProviderType = ProviderType.APP_LIFECYCLE,
) : AppLifecycleProvider {
    private val changes = MutableSharedFlow<AppLifecycleState>(extraBufferCapacity = 64)

    override val descriptor: ProviderDescriptor = ProviderDescriptor(
        id = ProviderId("fake-app-lifecycle"),
        name = ProviderName("Fake App Lifecycle"),
        type = type,
        version = ProviderVersion("1.0.0"),
    )

    override var current: AppLifecycleState = initial
        private set

    /** How many times [states] was called: proves build and construction never collect. */
    var statesCallCount: Int = 0
        private set

    fun set(state: AppLifecycleState) {
        if (state != AppLifecycleState.TERMINATING_SOON) current = state
        check(changes.tryEmit(state)) { "fake lifecycle buffer overflow" }
    }

    override fun states(): Flow<AppLifecycleState> {
        statesCallCount++
        return flow {
            emit(current)
            streamFailure?.let { throw it }
            if (!completesAfterSeed) emitAll(changes)
        }
    }

    override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
        ProviderOperationResult.Success(Unit)

    override suspend fun health(): ProviderOperationResult<ProviderHealth> =
        ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

    override suspend fun close(): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)
}

/** Canonical test error whose message must never leak into a public result. */
internal data class LifecycleDrainTestError(
    override val code: ErrorCode = ErrorCode("DL-LIFECYCLE-DRAIN-TEST"),
    override val category: ErrorCategory = ErrorCategory.PROVIDER,
    override val severity: ErrorSeverity = ErrorSeverity.ERROR,
    override val recoverability: Recoverability = Recoverability.RECOVERABLE,
    override val message: String = "raw-sensitive-message",
    override val cause: Throwable? = null,
) : DataLoomError

internal fun observationFailure(error: DataLoomError = LifecycleDrainTestError()): Throwable =
    AppLifecycleObservationException(error)
