package io.dataloom.core.provider

import io.dataloom.api.provider.DataLoomProvider
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderLifecycleCoordinatorState
import io.dataloom.api.provider.ProviderLifecycleFailure
import io.dataloom.api.provider.ProviderLifecycleOperation
import io.dataloom.api.provider.ProviderLifecycleResult
import io.dataloom.api.provider.ProviderOperationResult

/**
 * Coordinator responsible for initializing and shutting down all providers in
 * a [ProviderRegistry] in deterministic lifecycle order.
 *
 * ## Purpose
 *
 * [ProviderLifecycleCoordinator] receives a [ProviderRegistry] and a
 * [ProviderInitializationContext] at construction time. It initializes
 * providers in registration order and shuts them down in reverse
 * successful-initialization order.
 *
 * ## Explicit injection
 *
 * All dependencies are required at construction time. There are no default
 * values, no global state, and no service locators. Construction performs no
 * provider operation.
 *
 * ## Lifecycle states
 *
 * The coordinator transitions through [ProviderLifecycleCoordinatorState] as
 * follows:
 *
 * ```text
 * NOT_INITIALIZED
 *     ↓ initialize() called
 * INITIALIZING
 *     ↓ all providers succeed
 * INITIALIZED
 *     ↓ shutdown() called
 * SHUTTING_DOWN
 *     ↓ all providers succeed
 * SHUT_DOWN
 * ```
 *
 * Exceptional transitions:
 *
 * ```text
 * INITIALIZING → FAILED  (provider initialization failure after rollback)
 * SHUTTING_DOWN → FAILED  (one or more provider shutdown failures)
 * ```
 *
 * State never returns to an earlier lifecycle phase. Terminal states
 * ([SHUT_DOWN][ProviderLifecycleCoordinatorState.SHUT_DOWN] and
 * [FAILED][ProviderLifecycleCoordinatorState.FAILED]) are permanent.
 *
 * ## Initialization order
 *
 * Providers are initialized in the order they appear in the registry. This
 * order is determined solely by registration order, not by [ProviderType]
 * enum ordinal, provider ID sorting, class name, or hash-map iteration.
 *
 * ## Initialization failure and rollback
 *
 * When a provider returns [ProviderOperationResult.Failure] during
 * initialization:
 *
 * - Providers registered after the failed provider are not initialized.
 * - Providers that were already successfully initialized are shut down in
 *   reverse initialization order (rollback).
 * - The primary initialization failure is preserved in the result.
 * - Any rollback failures are preserved separately.
 * - The coordinator transitions to [ProviderLifecycleCoordinatorState.FAILED].
 *
 * ## Normal shutdown
 *
 * [shutdown] iterates all successfully initialized providers in reverse
 * initialization order. Shutdown continues past individual provider failures.
 * All failures are collected and returned in [ProviderLifecycleResult.ShutdownFailure].
 *
 * A provider that was not successfully initialized is never shut down.
 * A successfully initialized provider is never shut down more than once.
 *
 * ## Coroutine cancellation
 *
 * `CancellationException` thrown by provider operations propagates normally.
 * The coordinator does not catch, convert, or suppress `CancellationException`.
 * Cleanup behavior after external cancellation during a lifecycle operation is
 * not guaranteed — callers must not rely on rollback or partial shutdown
 * occurring after cancellation.
 *
 * ## Thread-safety boundary
 *
 * [ProviderLifecycleCoordinator] does not provide concurrency control. It does
 * not choose a dispatcher and does not expose a [kotlinx.coroutines.CoroutineScope].
 *
 * Callers must serialize [initialize] and [shutdown] calls. Concurrent
 * lifecycle calls without external coordination produce undefined behavior.
 *
 * ## KMP compatibility
 *
 * [ProviderLifecycleCoordinator] uses Kotlin standard-library and DataLoom
 * API types only. It does not require Android APIs, JVM-only types, or
 * third-party libraries.
 *
 * ## Scope restrictions
 *
 * This coordinator performs provider lifecycle operations only. It does not
 * implement synchronization orchestration, queue processing, retry execution,
 * conflict resolution, event dispatch, scheduling, or connectivity observation.
 *
 * @param registry the registry of providers to coordinate.
 * @param context the immutable initialization context passed to each provider
 *   during [initialize].
 */
public class ProviderLifecycleCoordinator(
    private val registry: ProviderRegistry,
    private val context: ProviderInitializationContext,
) {
    private var _state: ProviderLifecycleCoordinatorState =
        ProviderLifecycleCoordinatorState.NOT_INITIALIZED

    /**
     * The current coordinator lifecycle state.
     *
     * This property reflects the coordinator's most recent state transition.
     * It must not be used for concurrent-safe state observation without
     * external synchronization.
     */
    public val state: ProviderLifecycleCoordinatorState
        get() = _state

    /**
     * Providers that have been successfully initialized, in initialization
     * order. Used to determine shutdown order and rollback targets.
     */
    private val successfullyInitialized: MutableList<DataLoomProvider> = mutableListOf()

    /**
     * Initializes all registered providers in registration order.
     *
     * Returns [ProviderLifecycleResult.InvalidOperation] when the coordinator
     * is not in the [ProviderLifecycleCoordinatorState.NOT_INITIALIZED] state.
     *
     * When all providers initialize successfully, transitions to
     * [ProviderLifecycleCoordinatorState.INITIALIZED] and returns
     * [ProviderLifecycleResult.InitializeSuccess].
     *
     * When a provider returns [ProviderOperationResult.Failure]:
     * - stops initializing further providers
     * - shuts down already-initialized providers in reverse order (rollback)
     * - transitions to [ProviderLifecycleCoordinatorState.FAILED]
     * - returns [ProviderLifecycleResult.InitializeFailure]
     *
     * `CancellationException` propagates normally and is not converted to a
     * lifecycle failure. Post-cancellation coordinator state is undefined.
     *
     * Callers must serialize this call with [shutdown]. Concurrent invocation
     * without external coordination produces undefined behavior.
     *
     * @return [ProviderLifecycleResult] describing the outcome.
     */
    public suspend fun initialize(): ProviderLifecycleResult {
        if (_state != ProviderLifecycleCoordinatorState.NOT_INITIALIZED) {
            return ProviderLifecycleResult.InvalidOperation(
                state = _state,
                operation = ProviderLifecycleOperation.INITIALIZE,
            )
        }

        _state = ProviderLifecycleCoordinatorState.INITIALIZING

        for (provider in registry.providers) {
            // CancellationException propagates normally from here.
            val result = provider.initialize(context)

            when (result) {
                is ProviderOperationResult.Success -> {
                    successfullyInitialized.add(provider)
                }
                is ProviderOperationResult.Failure -> {
                    val primaryFailure = ProviderLifecycleFailure(
                        providerId = provider.descriptor.id,
                        operation = ProviderLifecycleOperation.INITIALIZE,
                        error = result.error,
                    )
                    // Rollback: shut down successfully initialized providers
                    // in reverse order. CancellationException from close()
                    // propagates normally.
                    val rollbackFailures = performRollback()
                    _state = ProviderLifecycleCoordinatorState.FAILED
                    return ProviderLifecycleResult.InitializeFailure(
                        primaryFailure = primaryFailure,
                        rollbackFailures = rollbackFailures,
                    )
                }
            }
        }

        _state = ProviderLifecycleCoordinatorState.INITIALIZED
        return ProviderLifecycleResult.InitializeSuccess
    }

    /**
     * Shuts down all successfully initialized providers in reverse
     * initialization order.
     *
     * Returns [ProviderLifecycleResult.InvalidOperation] when the coordinator
     * is not in the [ProviderLifecycleCoordinatorState.INITIALIZED] state.
     *
     * Shutdown continues past individual provider failures. All failures are
     * collected and returned in [ProviderLifecycleResult.ShutdownFailure].
     *
     * When all providers shut down successfully, transitions to
     * [ProviderLifecycleCoordinatorState.SHUT_DOWN] and returns
     * [ProviderLifecycleResult.ShutdownSuccess].
     *
     * When any provider returns [ProviderOperationResult.Failure], transitions
     * to [ProviderLifecycleCoordinatorState.FAILED] and returns
     * [ProviderLifecycleResult.ShutdownFailure].
     *
     * `CancellationException` propagates normally and is not converted to a
     * lifecycle failure. Post-cancellation coordinator state is undefined.
     *
     * Callers must serialize this call with [initialize]. Concurrent invocation
     * without external coordination produces undefined behavior.
     *
     * @return [ProviderLifecycleResult] describing the outcome.
     */
    public suspend fun shutdown(): ProviderLifecycleResult {
        if (_state != ProviderLifecycleCoordinatorState.INITIALIZED) {
            return ProviderLifecycleResult.InvalidOperation(
                state = _state,
                operation = ProviderLifecycleOperation.SHUTDOWN,
            )
        }

        _state = ProviderLifecycleCoordinatorState.SHUTTING_DOWN

        val failures = mutableListOf<ProviderLifecycleFailure>()

        for (provider in successfullyInitialized.reversed()) {
            // CancellationException propagates normally from here.
            val result = provider.close()

            if (result is ProviderOperationResult.Failure) {
                failures.add(
                    ProviderLifecycleFailure(
                        providerId = provider.descriptor.id,
                        operation = ProviderLifecycleOperation.SHUTDOWN,
                        error = result.error,
                    ),
                )
            }
        }

        return if (failures.isEmpty()) {
            _state = ProviderLifecycleCoordinatorState.SHUT_DOWN
            ProviderLifecycleResult.ShutdownSuccess
        } else {
            _state = ProviderLifecycleCoordinatorState.FAILED
            ProviderLifecycleResult.ShutdownFailure(failures)
        }
    }

    /**
     * Shuts down all successfully initialized providers in reverse order as
     * part of an initialization rollback. Returns any shutdown failures that
     * occurred during rollback. Does not modify [_state]; callers set the
     * terminal state.
     *
     * `CancellationException` from [DataLoomProvider.close] propagates normally.
     */
    private suspend fun performRollback(): List<ProviderLifecycleFailure> {
        val rollbackFailures = mutableListOf<ProviderLifecycleFailure>()

        for (provider in successfullyInitialized.reversed()) {
            // CancellationException propagates normally from here.
            val result = provider.close()

            if (result is ProviderOperationResult.Failure) {
                rollbackFailures.add(
                    ProviderLifecycleFailure(
                        providerId = provider.descriptor.id,
                        operation = ProviderLifecycleOperation.SHUTDOWN,
                        error = result.error,
                    ),
                )
            }
        }

        return rollbackFailures
    }
}
