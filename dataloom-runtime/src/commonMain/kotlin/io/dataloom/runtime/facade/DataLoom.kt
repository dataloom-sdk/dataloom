package io.dataloom.runtime.facade

import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.ProviderLifecycleCoordinatorState
import io.dataloom.api.provider.ProviderLifecycleResult
import io.dataloom.api.provider.SynchronizationProviderBindings
import io.dataloom.runtime.execution.SynchronizationExecutionResult
import io.dataloom.runtime.submission.DataLoomQueueSubmission

/**
 * Public facade for the DataLoom synchronization SDK.
 *
 * ## Purpose
 *
 * [DataLoom] is the single entry point for an application to interact with the
 * DataLoom runtime after assembly by [DataLoomBuilder]. It exposes provider
 * lifecycle management, direct synchronization, and an optional queue-worker
 * capability.
 *
 * ## Lifecycle ownership
 *
 * A [DataLoom] instance owns the lifecycle of the providers supplied to its
 * [DataLoomBuilder]. Providers must not normally be shared across multiple
 * independently built [DataLoom] instances.
 *
 * ## Initialization
 *
 * Providers are not initialized during [DataLoomBuilder.build]. Call
 * [initialize] explicitly before performing synchronization.
 *
 * ## Shutdown
 *
 * Call [shutdown] when the runtime is no longer needed. Shutdown uses
 * reverse initialization order as documented in [io.dataloom.core.provider.ProviderLifecycleCoordinator].
 *
 * ## Concurrency
 *
 * Callers must serialize [initialize] and [shutdown] calls. Concurrent
 * [synchronize] calls follow existing runtime execution limitations.
 * [DataLoom] owns no [kotlinx.coroutines.CoroutineScope] and selects no
 * dispatcher.
 *
 * ## Cancellation
 *
 * [kotlinx.coroutines.CancellationException] from any operation propagates
 * normally. Cancellation is never converted into a lifecycle or execution
 * result.
 *
 * ## No automatic initialization
 *
 * [DataLoom] does not initialize providers automatically during
 * [synchronize] or [queueWorker] access. Synchronization before [initialize]
 * returns a structured rejection result.
 *
 * ## No global singleton
 *
 * [DataLoom] instances are independently created by [DataLoomBuilder]. There
 * is no global runtime registry.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 */
public interface DataLoom {

    /**
     * The current lifecycle state of the provider coordinator.
     *
     * Reflects the truthful [ProviderLifecycleCoordinatorState] at the time of
     * access. Read after [initialize] to confirm the runtime is ready for
     * synchronization.
     */
    public val providerLifecycleState: ProviderLifecycleCoordinatorState

    /**
     * The optional queue-worker capability.
     *
     * `null` when [DataLoomBuilder.queueWorkerConfiguration] was not supplied
     * during build. Non-null when queue-worker configuration was valid and
     * complete.
     *
     * No background worker is started automatically. Callers must invoke
     * [DataLoomQueueWorker.run] explicitly.
     */
    public val queueWorker: DataLoomQueueWorker?

    /**
     * The optional queue-submission capability.
     *
     * `null` when [DataLoomBuilder.queueSubmissionEncoder] was not supplied
     * during build or when a valid [io.dataloom.api.queue.QueueProvider]
     * binding was absent. Non-null when a
     * [io.dataloom.runtime.submission.QueuedSynchronizationWorkEncoder] and a
     * valid queue provider binding were both configured.
     *
     * Queue submission and queue worker are independently configurable. Either,
     * both, or neither capability may be present.
     *
     * No provider is initialized automatically. Callers must invoke
     * [initialize] before submitting queue work.
     *
     * The [io.dataloom.api.queue.QueueProvider] is not exposed through this
     * property.
     */
    public val queueSubmission: DataLoomQueueSubmission?

    /**
     * Initializes all registered providers in registration order.
     *
     * Delegates to [io.dataloom.core.provider.ProviderLifecycleCoordinator.initialize]
     * and returns the result unchanged.
     *
     * Callers must serialize this call with [shutdown]. Concurrent lifecycle
     * calls produce undefined behavior.
     *
     * [kotlinx.coroutines.CancellationException] propagates normally.
     *
     * @return a [ProviderLifecycleResult] describing the outcome.
     */
    public suspend fun initialize(): ProviderLifecycleResult

    /**
     * Executes synchronization using the configured default provider bindings.
     *
     * Delegates to
     * [io.dataloom.runtime.execution.SynchronizationExecutionCoordinator.execute]
     * with the default bindings supplied to [DataLoomBuilder.defaultProviderBindings].
     * The execution result is returned unchanged.
     *
     * Synchronization before [initialize] returns
     * [io.dataloom.runtime.execution.SynchronizationExecutionResult.Rejected]
     * with [io.dataloom.runtime.execution.SynchronizationExecutionRejectionReason.PROVIDERS_NOT_INITIALIZED].
     *
     * [kotlinx.coroutines.CancellationException] propagates normally.
     *
     * @param request the synchronization request to execute.
     * @return a [SynchronizationExecutionResult] describing the outcome.
     */
    public suspend fun synchronize(
        request: SynchronizationRequest,
    ): SynchronizationExecutionResult

    /**
     * Executes synchronization using explicitly supplied provider bindings.
     *
     * Delegates to
     * [io.dataloom.runtime.execution.SynchronizationExecutionCoordinator.execute]
     * with the caller-supplied [bindings]. The exact supplied bindings are
     * forwarded unchanged. The execution result is returned unchanged.
     *
     * Synchronization before [initialize] returns
     * [io.dataloom.runtime.execution.SynchronizationExecutionResult.Rejected]
     * with [io.dataloom.runtime.execution.SynchronizationExecutionRejectionReason.PROVIDERS_NOT_INITIALIZED].
     *
     * [kotlinx.coroutines.CancellationException] propagates normally.
     *
     * @param request the synchronization request to execute.
     * @param bindings the explicit provider bindings to resolve for this request.
     * @return a [SynchronizationExecutionResult] describing the outcome.
     */
    public suspend fun synchronize(
        request: SynchronizationRequest,
        bindings: SynchronizationProviderBindings,
    ): SynchronizationExecutionResult

    /**
     * Shuts down all successfully initialized providers in reverse
     * initialization order.
     *
     * Delegates to [io.dataloom.core.provider.ProviderLifecycleCoordinator.shutdown]
     * and returns the result unchanged.
     *
     * Callers must serialize this call with [initialize]. Concurrent lifecycle
     * calls produce undefined behavior.
     *
     * [kotlinx.coroutines.CancellationException] propagates normally.
     *
     * @return a [ProviderLifecycleResult] describing the outcome.
     */
    public suspend fun shutdown(): ProviderLifecycleResult
}
