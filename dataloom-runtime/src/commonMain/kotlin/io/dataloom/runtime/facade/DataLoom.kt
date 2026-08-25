package io.dataloom.runtime.facade

import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.ProviderLifecycleCoordinatorState
import io.dataloom.api.provider.ProviderLifecycleResult
import io.dataloom.api.provider.StrategyProviderBindings
import io.dataloom.api.provider.SynchronizationProviderBindings
import io.dataloom.api.strategy.PersistedStrategyDecision
import io.dataloom.api.strategy.StrategyExecutionPlan
import io.dataloom.api.strategy.StrategySynchronizationRequest
import io.dataloom.runtime.execution.SynchronizationExecutionResult
import io.dataloom.runtime.strategy.StrategySynchronizationExecutionResult
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
 * Call [shutdown] when the runtime is no longer needed. Providers shut down
 * in reverse initialization order.
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
 * [synchronize], [queueWorker], or [circuitQueueWorker] access.
 * Synchronization before [initialize]
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
     * The optional circuit-aware queue-worker capability.
     *
     * `null` unless
     * [DataLoomBuilder.circuitQueueWorkerConfiguration] was the effective
     * queue-worker configuration at build time. Direct and circuit-aware worker
     * capabilities are mutually exclusive; the most recent builder method wins.
     *
     * A default getter preserves source compatibility for custom pre-V1
     * [DataLoom] implementations.
     */
    public val circuitQueueWorker: DataLoomCircuitQueueWorker?
        get() = null

    /**
     * The optional protected direct-synchronization capability.
     *
     * `null` unless
     * [DataLoomBuilder.providerProtectionConfiguration] was supplied during
     * build. The historical [synchronize] methods remain unchanged; callers
     * select provider timeout/circuit evidence explicitly through this property.
     *
     * A default getter preserves source compatibility for custom pre-V1
     * [DataLoom] implementations.
     */
    public val protectedSynchronization: DataLoomProtectedSynchronization?
        get() = null

    /**
     * Optional plan-aware provider protection for built-in strategy execution.
     *
     * `null` unless [DataLoomBuilder.strategyProviderProtectionConfiguration]
     * was supplied. Historical strategy synchronization remains unchanged.
     */
    public val protectedStrategySynchronization: DataLoomProtectedStrategySynchronization?
        get() = null

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
     * Optional authorized retry-administration operations capability.
     *
     * `null` unless [DataLoomBuilder.retryAdministrationConfiguration] was
     * supplied. The capability does not expose the configured authorizer,
     * durable state store, or platform queue executor. Access performs no I/O
     * and does not initialize providers automatically.
     *
     * A default getter preserves source compatibility for custom pre-V1
     * [DataLoom] implementations.
     */
    public val retryAdministration: DataLoomRetryAdministration?
        get() = null

    /**
     * Optional authorized circuit-administration operations capability.
     *
     * `null` unless [DataLoomBuilder.circuitAdministrationConfiguration] was
     * supplied. Property access performs no authorization, persistence,
     * execution, clock read, or provider initialization.
     *
     * A default getter preserves source compatibility for custom pre-V1
     * [DataLoom] implementations.
     */
    public val circuitAdministration: DataLoomCircuitAdministration?
        get() = null

    /**
     * Optional authorized manual conflict-resolution operations capability.
     *
     * `null` unless [DataLoomBuilder.conflictAdministrationConfiguration] was
     * supplied. Property access performs no authorization, persistence,
     * execution, clock read, or provider initialization.
     *
     * A default getter preserves source compatibility for custom pre-V1
     * [DataLoom] implementations.
     */
    public val conflictAdministration: DataLoomConflictAdministration?
        get() = null

    /**
     * Initializes all registered providers in registration order.
     *
     * Initializes the internal provider lifecycle coordinator and returns its
     * result unchanged.
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
     * Evaluates and executes a strategy request using the configured default
     * strategy provider bindings.
     *
     * Provider roles are resolved from the evaluated plan. A network-only
     * request therefore resolves transport without resolving storage or queue.
     */
    public suspend fun synchronize(
        request: StrategySynchronizationRequest,
    ): StrategySynchronizationExecutionResult

    /**
     * Evaluates and executes a strategy request using explicit plan-aware
     * provider bindings.
     */
    public suspend fun synchronize(
        request: StrategySynchronizationRequest,
        bindings: StrategyProviderBindings,
    ): StrategySynchronizationExecutionResult

    /**
     * Executes the immutable accepted strategy plan using default strategy
     * provider bindings. Current profiles and runtime evidence are not read.
     */
    public suspend fun synchronizeAcceptedPlan(
        request: SynchronizationRequest,
        decision: PersistedStrategyDecision,
        plan: StrategyExecutionPlan,
    ): StrategySynchronizationExecutionResult

    /**
     * Executes the immutable accepted strategy plan with exact caller-supplied
     * strategy bindings. No strategy policy evaluation occurs.
     */
    public suspend fun synchronizeAcceptedPlan(
        request: SynchronizationRequest,
        decision: PersistedStrategyDecision,
        plan: StrategyExecutionPlan,
        bindings: StrategyProviderBindings,
    ): StrategySynchronizationExecutionResult

    /**
     * Shuts down all successfully initialized providers in reverse
     * initialization order.
     *
     * Shuts down the internal provider lifecycle coordinator and returns its
     * result unchanged.
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
