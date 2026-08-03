package io.dataloom.runtime.facade

import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.core.provider.ProviderLifecycleCoordinator
import io.dataloom.api.provider.ProviderLifecycleCoordinatorState
import io.dataloom.api.provider.ProviderLifecycleResult
import io.dataloom.api.provider.StrategyProviderBindings
import io.dataloom.api.provider.SynchronizationProviderBindings
import io.dataloom.api.strategy.StrategySynchronizationRequest
import io.dataloom.runtime.execution.SynchronizationExecutionCoordinator
import io.dataloom.runtime.execution.SynchronizationExecutionResult
import io.dataloom.runtime.execution.SynchronizationExecutionRejectionReason
import io.dataloom.runtime.strategy.StrategySynchronizationExecutionCoordinator
import io.dataloom.runtime.strategy.StrategySynchronizationExecutionResult
import io.dataloom.runtime.submission.DataLoomQueueSubmission

/**
 * Internal [DataLoom] implementation assembled by [DataLoomBuilder].
 *
 * ## Delegation contract
 *
 * Every operation delegates to an existing runtime component. No provider
 * operation, synchronization logic, queue processing, retry logic, or event
 * dispatch is duplicated in this class.
 *
 * ## Immutability
 *
 * All fields are immutable after construction. No provider registry, observer
 * registry, pipeline registry, or coordinator is replaceable after build.
 *
 * ## Concurrency
 *
 * Callers must serialize [initialize] and [shutdown]. Concurrent
 * [synchronize] calls follow existing [SynchronizationExecutionCoordinator]
 * limitations. This implementation owns no [kotlinx.coroutines.CoroutineScope]
 * and selects no dispatcher.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API and runtime types only. Safe
 * for use in Kotlin Multiplatform common code.
 *
 * @param lifecycleCoordinator the coordinator that manages provider lifecycle.
 * @param executionCoordinator the coordinator that executes synchronization.
 * @param defaultBindings the default provider bindings used by
 *   [synchronize] without explicit bindings.
 * @param queueWorker the optional direct queue-worker capability; `null`
 *   when not configured.
 * @param circuitQueueWorker the optional circuit-aware queue-worker capability;
 *   `null` when not configured.
 * @param queueSubmission the optional queue-submission capability; `null` when
 *   not configured.
 * @param retryAdministration the optional authorized operations capability;
 *   `null` when not configured.
 * @param circuitAdministration the optional authorized circuit operations
 *   capability; `null` when not configured.
 */
internal class DefaultDataLoom(
    private val lifecycleCoordinator: ProviderLifecycleCoordinator,
    private val executionCoordinator: SynchronizationExecutionCoordinator,
    private val strategyExecutionCoordinator: StrategySynchronizationExecutionCoordinator,
    private val defaultBindings: SynchronizationProviderBindings?,
    private val defaultStrategyBindings: StrategyProviderBindings,
    override val queueWorker: DataLoomQueueWorker?,
    override val circuitQueueWorker: DataLoomCircuitQueueWorker?,
    override val protectedSynchronization: DataLoomProtectedSynchronization?,
    override val protectedStrategySynchronization: DataLoomProtectedStrategySynchronization?,
    override val queueSubmission: DataLoomQueueSubmission?,
    override val retryAdministration: DataLoomRetryAdministration?,
    override val circuitAdministration: DataLoomCircuitAdministration?,
) : DataLoom {

    override val providerLifecycleState: ProviderLifecycleCoordinatorState
        get() = lifecycleCoordinator.state

    override suspend fun initialize(): ProviderLifecycleResult =
        lifecycleCoordinator.initialize()

    override suspend fun synchronize(
        request: SynchronizationRequest,
    ): SynchronizationExecutionResult {
        val bindings = defaultBindings
            ?: return SynchronizationExecutionResult.Rejected(
                reason = SynchronizationExecutionRejectionReason.DEFAULT_PROVIDER_BINDINGS_NOT_CONFIGURED,
            )
        return executionCoordinator.execute(request, bindings)
    }

    override suspend fun synchronize(
        request: SynchronizationRequest,
        bindings: SynchronizationProviderBindings,
    ): SynchronizationExecutionResult =
        executionCoordinator.execute(request, bindings)

    override suspend fun synchronize(
        request: StrategySynchronizationRequest,
    ): StrategySynchronizationExecutionResult =
        strategyExecutionCoordinator.execute(request, defaultStrategyBindings)

    override suspend fun synchronize(
        request: StrategySynchronizationRequest,
        bindings: StrategyProviderBindings,
    ): StrategySynchronizationExecutionResult =
        strategyExecutionCoordinator.execute(request, bindings)

    override suspend fun shutdown(): ProviderLifecycleResult =
        lifecycleCoordinator.shutdown()
}
