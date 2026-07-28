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
     * The provider lifecycle is not in the
     * [io.dataloom.api.provider.ProviderLifecycleCoordinatorState.INITIALIZED]
     * state.
     *
     * Applications must call
     * [io.dataloom.runtime.facade.DataLoom.initialize] and confirm a successful
     * result before requesting synchronization.
     */
    PROVIDERS_NOT_INITIALIZED,

    /**
     * Internal provider resolution returned one or more
     * [io.dataloom.api.provider.ProviderBindingFailure] records.
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

    /**
     * The configured connectivity requirement cannot be evaluated because no
     * [io.dataloom.api.connectivity.ConnectivityProvider] is registered.
     *
     * The connectivity check is attempted only after pipeline lookup succeeds.
     * When a non-[io.dataloom.api.connectivity.ConnectivityRequirement.NONE]
     * requirement is configured but the resolved providers do not include a
     * connectivity provider, a [SynchronizationExecutionResult.Rejected] with
     * this reason is returned.
     *
     * No [io.dataloom.api.synchronization.SynchronizationEvent] is emitted and
     * no synchronization pipeline is invoked.
     */
    CONNECTIVITY_PROVIDER_NOT_CONFIGURED,

    /**
     * The current connectivity snapshot does not satisfy the configured
     * [io.dataloom.api.connectivity.ConnectivityRequirement].
     *
     * The connectivity check is attempted only after pipeline lookup succeeds.
     * When the provider reports a state that does not satisfy the requirement,
     * a [SynchronizationExecutionResult.Rejected] with this reason is returned.
     *
     * No [io.dataloom.api.synchronization.SynchronizationEvent] is emitted and
     * no synchronization pipeline is invoked.
     *
     * Queued execution maps this rejection to
     * [io.dataloom.runtime.queue.QueueEntryExecutionOutcome.Reschedule] using
     * the configured offline reschedule delay.
     */
    CONNECTIVITY_REQUIREMENT_NOT_MET,

    /**
     * The [io.dataloom.api.connectivity.ConnectivityProvider] returned a
     * canonical [io.dataloom.api.error.DataLoomError] during the preflight
     * check.
     *
     * The exact error is preserved in
     * [SynchronizationExecutionResult.Rejected.connectivityCheckError].
     *
     * No [io.dataloom.api.synchronization.SynchronizationEvent] is emitted and
     * no synchronization pipeline is invoked.
     */
    CONNECTIVITY_CHECK_FAILED,
}
