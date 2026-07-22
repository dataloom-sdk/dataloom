package io.dataloom.api.scheduling

import io.dataloom.api.provider.DataLoomProvider
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType

/**
 * Platform-independent provider contract for scheduling deferred
 * synchronization execution.
 *
 * [SchedulerProvider] is the adapter boundary between the DataLoom runtime and
 * a platform-specific background execution mechanism such as WorkManager,
 * Apple background tasks, AlarmManager, or a custom scheduler.
 *
 * ```text
 * DataLoom Runtime
 *       ↓
 * SchedulerProvider
 *       ↓
 * Platform scheduler implementation
 *       ↓
 * WorkManager / Apple scheduler / custom scheduler
 * ```
 *
 * ## Responsibilities
 *
 * Implementations adapt DataLoom scheduling requests to concrete platform
 * mechanisms while keeping platform-specific APIs outside the shared DataLoom
 * public surface. The provider accepts or rejects scheduling requests and
 * communicates the outcome through [ProviderOperationResult].
 *
 * ## What a successful schedule result means
 *
 * [ProviderOperationResult.Success] from [schedule] means the scheduler
 * accepted the request. It does not mean the workflow has started, that
 * execution will succeed, or that the platform will trigger the workflow under
 * every operating-system condition.
 *
 * ## What this provider must not do
 *
 * Implementations must not:
 * - execute synchronization directly
 * - perform storage or transport operations
 * - implement retry policy
 * - select threads or dispatchers
 * - expose WorkManager, Worker, AlarmManager, JobScheduler, Apple
 *   background-task, or other platform-specific types through the public API
 * - expose [kotlinx.coroutines.CoroutineScope] or dispatcher types
 * - automatically initialize or register themselves
 *
 * ## Thread safety
 *
 * Implementations are responsible for documenting and enforcing their own
 * thread-safety guarantees and additional concurrency constraints.
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
 *   [ProviderType.SCHEDULER]
 * - map platform failures to canonical [io.dataloom.api.error.DataLoomError]
 *   values
 * - return a canonical configuration or provider error when a constraint from
 *   [ScheduleConstraints] is not supported by the platform
 * - not allow platform exceptions to escape through the public contract
 */
public interface SchedulerProvider : DataLoomProvider {

    /**
     * Immutable descriptor for this scheduler provider.
     *
     * [ProviderDescriptor.type] must be [ProviderType.SCHEDULER].
     */
    override public val descriptor: ProviderDescriptor

    /**
     * Requests deferred execution of the synchronization described by
     * [request] using the platform scheduler.
     *
     * A successful result returns a [ScheduleReceipt] confirming that the
     * provider accepted the request. A receipt does not guarantee that
     * synchronization has started, completed, or succeeded, or that the
     * platform will trigger the workflow under every operating-system
     * condition.
     *
     * Implementations must:
     * - apply [ScheduleRequest.existingPolicy] when a schedule with the same
     *   [io.dataloom.api.identifier.ScheduleId] already exists
     * - map unsupported [ScheduleRequest.constraints] to a canonical
     *   [io.dataloom.api.error.DataLoomError]
     * - not execute synchronization directly
     * - preserve coroutine cancellation
     *
     * @param request immutable scheduling intent including the identifier,
     *   synchronization request, delay, constraints, and existing-schedule
     *   policy.
     * @return [ProviderOperationResult.Success] with a [ScheduleReceipt] when
     *   the provider accepted the request, or [ProviderOperationResult.Failure]
     *   with a canonical DataLoom error.
     */
    public suspend fun schedule(
        request: ScheduleRequest,
    ): ProviderOperationResult<ScheduleReceipt>

    /**
     * Requests cancellation of a previously scheduled synchronization.
     *
     * A successful result means the provider processed the cancellation
     * request. If no schedule exists for the given
     * [ScheduleCancellationRequest.id], implementations may return success
     * or a canonical failure according to their platform semantics.
     *
     * Cancellation of a scheduled entry does not automatically cancel a
     * synchronization workflow that has already started. Runtime workflow
     * cancellation semantics are deferred to a future issue.
     *
     * Implementations must preserve coroutine cancellation.
     *
     * @param request immutable cancellation request identifying the schedule
     *   entry to remove and carrying the associated execution context.
     * @return [ProviderOperationResult.Success] when the cancellation was
     *   processed, or [ProviderOperationResult.Failure] with a canonical
     *   DataLoom error.
     */
    public suspend fun cancel(
        request: ScheduleCancellationRequest,
    ): ProviderOperationResult<Unit>
}
