package io.dataloom.runtime.execution

/**
 * Closed set of reasons why the [SynchronizationExecutionCoordinator] rejected
 * a synchronization execution request.
 *
 * ## Purpose
 *
 * [SynchronizationExecutionRejectionReason] classifies the structural reason
 * why execution was rejected before a [SynchronizationPipeline] was invoked.
 * It is used in [SynchronizationExecutionResult.Rejected] to communicate the
 * specific pre-condition that was not satisfied.
 *
 * ## Enum ordinals
 *
 * Enum ordinals are not a compatibility contract and must not be persisted or
 * compared by ordinal.
 *
 * ## Coroutine cancellation
 *
 * Coroutine cancellation is not represented here. A
 * [kotlinx.coroutines.CancellationException] from the pipeline propagates
 * normally and is never converted to a rejection reason.
 */
public enum class SynchronizationExecutionRejectionReason {

    /**
     * The [io.dataloom.core.provider.ProviderLifecycleCoordinator] is not in
     * the [io.dataloom.core.provider.ProviderLifecycleCoordinatorState.INITIALIZED]
     * state.
     *
     * Applications must call
     * [io.dataloom.core.provider.ProviderLifecycleCoordinator.initialize]
     * and confirm a successful result before calling
     * [SynchronizationExecutionCoordinator.execute].
     */
    PROVIDERS_NOT_INITIALIZED,

    /**
     * The [io.dataloom.core.provider.SynchronizationProviderResolver] returned
     * one or more [io.dataloom.core.provider.ProviderBindingFailure] records.
     *
     * Provider resolution is attempted only after lifecycle initialization is
     * confirmed. When resolution fails, a
     * [SynchronizationExecutionResult.Rejected] with this reason and the
     * ordered binding failures is returned.
     */
    PROVIDER_RESOLUTION_FAILED,

    /**
     * No [SynchronizationPipeline] is registered in the
     * [SynchronizationPipelineRegistry] for the requested
     * [io.dataloom.api.model.SynchronizationDirection].
     *
     * Pipeline lookup is attempted only after provider resolution succeeds.
     * When no pipeline exists for the requested direction, a
     * [SynchronizationExecutionResult.Rejected] with this reason is returned.
     */
    PIPELINE_NOT_FOUND,
}
