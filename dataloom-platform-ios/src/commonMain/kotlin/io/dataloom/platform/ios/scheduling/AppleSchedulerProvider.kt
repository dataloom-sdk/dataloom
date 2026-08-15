package io.dataloom.platform.ios.scheduling

import io.dataloom.api.identifier.ScheduleId
import io.dataloom.api.provider.ProviderCapability
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.scheduling.ExistingSchedulePolicy
import io.dataloom.api.scheduling.ScheduleCancellationRequest
import io.dataloom.api.scheduling.ScheduleReceipt
import io.dataloom.api.scheduling.ScheduleRequest
import io.dataloom.api.scheduling.SchedulerProvider
import io.dataloom.platform.ios.scheduling.internal.SchedulePlan
import io.dataloom.platform.ios.scheduling.internal.SchedulePlanRejection
import io.dataloom.platform.ios.scheduling.internal.SchedulePlanResult
import io.dataloom.platform.ios.scheduling.internal.SchedulerProviderError
import io.dataloom.platform.ios.scheduling.internal.SubmitTaskFailureReason
import io.dataloom.platform.ios.scheduling.internal.SubmitTaskOutcome
import io.dataloom.platform.ios.scheduling.internal.cancelBackgroundTaskRequest
import io.dataloom.platform.ios.scheduling.internal.pendingBackgroundTaskIdentifiers
import io.dataloom.platform.ios.scheduling.internal.planSchedule
import io.dataloom.platform.ios.scheduling.internal.submitBackgroundTaskRequest
import kotlin.coroutines.cancellation.CancellationException

/**
 * iOS [SchedulerProvider] backed by `BGTaskScheduler` (the BackgroundTasks
 * framework).
 *
 * ## The Info.plist / app-launch pre-registration constraint
 *
 * `BGTaskScheduler` requires every task identifier an app ever submits to be:
 *
 * 1. listed in the host app's `Info.plist`, under the
 *    `BGTaskSchedulerPermittedIdentifiers` array, and
 * 2. registered via
 *    `BGTaskScheduler.shared.register(forTaskWithIdentifier:using:launchHandler:)`
 *    once, before `applicationDidFinishLaunching` returns.
 *
 * DataLoom is a library, not the host application -- it cannot edit the host
 * app's `Info.plist`, and [schedule] is called at arbitrary times, not
 * guaranteed to be app-launch time. [SchedulerProvider] also explicitly
 * forbids implementations from automatically initializing or registering
 * themselves. Consequently, this provider never calls
 * `register(forTaskWithIdentifier:using:launchHandler:)`; the host
 * application is solely responsible for both Info.plist declaration and
 * launch-time registration.
 *
 * Instead, the host application declares the identifiers it has already
 * pre-registered via [preRegisteredIdentifiers] at construction time. A
 * [ScheduleRequest] whose [io.dataloom.api.identifier.ScheduleId] is not in
 * that set is rejected with a canonical configuration error before this
 * provider touches `BGTaskScheduler` at all -- see
 * `docs/apple/scheduler-provider.md` for the full explanation and required
 * host-app setup.
 *
 * ## Request-type selection
 *
 * [schedule] submits a `BGProcessingTaskRequest` when [request]'s
 * [io.dataloom.api.scheduling.ScheduleConstraints] declare a connectivity
 * requirement or require charging (the only `BGTaskRequest` subclass
 * exposing those constraints), and a `BGAppRefreshTaskRequest` otherwise
 * (Apple's lightweight, unconstrained background-refresh mechanism). See
 * [io.dataloom.platform.ios.scheduling.internal.planSchedule].
 *
 * [io.dataloom.api.connectivity.ConnectivityRequirement.UNMETERED] is
 * rejected with a canonical configuration error:
 * `BGProcessingTaskRequest.requiresNetworkConnectivity` is a single boolean
 * with no metered/unmetered distinction, so this constraint cannot be
 * honestly enforced.
 *
 * ## Existing-schedule policy
 *
 * `BGTaskScheduler` has no direct equivalent of WorkManager's
 * `ExistingWorkPolicy`, so [ExistingSchedulePolicy] is emulated:
 *
 * - [ExistingSchedulePolicy.REPLACE] unconditionally cancels any pending
 *   request for the same identifier (a documented no-op if none exists) and
 *   then submits the new request.
 * - [ExistingSchedulePolicy.KEEP] performs one bounded query of pending
 *   request identifiers via `getPendingTaskRequestsWithCompletionHandler`; if
 *   the identifier is already pending, the new request is not submitted and
 *   [schedule] still reports success. This query-then-decide sequence is not
 *   atomic the way WorkManager's `enqueueUniqueWork(KEEP)` is at the
 *   WorkManager database layer -- a concurrent submission for the same
 *   identifier between the query and this call returning could still race.
 *   This is a known, documented imperfect-parity limitation of
 *   `BGTaskScheduler`'s API, not a guarantee of atomicity.
 *
 * ## Platform types
 *
 * `BGTaskScheduler`, `BGTaskRequest`, and every other BackgroundTasks
 * platform type stay inside the `internal` `iosMain` functions in
 * `internal/BackgroundTaskGateway.ios.kt`. Nothing above that boundary,
 * including this class's public surface, references a platform type.
 *
 * @param preRegisteredIdentifiers the exact set of `BGTaskScheduler` task
 *   identifiers the host application has already listed in its `Info.plist`
 *   `BGTaskSchedulerPermittedIdentifiers` array and registered via
 *   `BGTaskScheduler.shared.register(forTaskWithIdentifier:using:launchHandler:)`
 *   at app-launch time. A [ScheduleRequest] whose
 *   [io.dataloom.api.identifier.ScheduleId] is outside this set is rejected.
 */
public class AppleSchedulerProvider(
    private val preRegisteredIdentifiers: Set<String>,
) : SchedulerProvider {

    override val descriptor: ProviderDescriptor = ProviderDescriptor(
        id = ProviderId("io.dataloom.scheduler.ios"),
        name = ProviderName("AppleSchedulerProvider"),
        type = ProviderType.SCHEDULER,
        version = ProviderVersion("1.0.0"),
        capabilities = setOf(ProviderCapability("bg-task-request")),
    )

    override suspend fun initialize(
        context: ProviderInitializationContext,
    ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

    override suspend fun health(): ProviderOperationResult<ProviderHealth> =
        ProviderOperationResult.Success(ProviderHealth(status = ProviderHealthStatus.HEALTHY))

    override suspend fun close(): ProviderOperationResult<Unit> =
        ProviderOperationResult.Success(Unit)

    override suspend fun schedule(
        request: ScheduleRequest,
    ): ProviderOperationResult<ScheduleReceipt> {
        return try {
            when (
                val planResult = planSchedule(
                    identifier = request.id.value,
                    constraints = request.constraints,
                    delay = request.delay,
                    preRegisteredIdentifiers = preRegisteredIdentifiers,
                )
            ) {
                is SchedulePlanResult.Rejected ->
                    ProviderOperationResult.Failure(planResult.reason.toDataLoomError())

                is SchedulePlanResult.Supported ->
                    submit(planResult.plan, request.existingPolicy, request.id)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            ProviderOperationResult.Failure(SchedulerProviderError.platformFailure())
        }
    }

    override suspend fun cancel(
        request: ScheduleCancellationRequest,
    ): ProviderOperationResult<Unit> {
        return try {
            cancelBackgroundTaskRequest(request.id.value)
            ProviderOperationResult.Success(Unit)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            ProviderOperationResult.Failure(SchedulerProviderError.platformFailure())
        }
    }

    private fun submit(
        plan: SchedulePlan,
        existingPolicy: ExistingSchedulePolicy,
        id: ScheduleId,
    ): ProviderOperationResult<ScheduleReceipt> {
        when (existingPolicy) {
            ExistingSchedulePolicy.REPLACE -> cancelBackgroundTaskRequest(plan.identifier)
            ExistingSchedulePolicy.KEEP -> {
                if (plan.identifier in pendingBackgroundTaskIdentifiers()) {
                    return ProviderOperationResult.Success(ScheduleReceipt(id = id))
                }
            }
        }

        return when (val outcome = submitBackgroundTaskRequest(plan)) {
            SubmitTaskOutcome.Submitted -> ProviderOperationResult.Success(ScheduleReceipt(id = id))

            is SubmitTaskOutcome.Failed ->
                ProviderOperationResult.Failure(outcome.reason.toDataLoomError())
        }
    }

    private fun SchedulePlanRejection.toDataLoomError(): SchedulerProviderError = when (this) {
        SchedulePlanRejection.UNSUPPORTED_UNMETERED_CONNECTIVITY ->
            SchedulerProviderError.unsupportedUnmeteredConnectivity()

        SchedulePlanRejection.IDENTIFIER_NOT_PREREGISTERED ->
            SchedulerProviderError.identifierNotPreRegistered()
    }

    private fun SubmitTaskFailureReason.toDataLoomError(): SchedulerProviderError = when (this) {
        SubmitTaskFailureReason.NOT_PERMITTED -> SchedulerProviderError.notPermittedByPlatform()
        SubmitTaskFailureReason.TOO_MANY_PENDING_TASK_REQUESTS -> SchedulerProviderError.tooManyPendingTaskRequests()
        SubmitTaskFailureReason.UNAVAILABLE -> SchedulerProviderError.backgroundTasksUnavailable()
        SubmitTaskFailureReason.UNKNOWN -> SchedulerProviderError.submissionFailure()
    }
}
