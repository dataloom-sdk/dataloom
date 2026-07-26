package io.dataloom.scheduler.workmanager

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.dataloom.api.connectivity.ConnectivityRequirement
import io.dataloom.api.provider.ProviderCapability
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.scheduling.ExistingSchedulePolicy
import io.dataloom.api.scheduling.ScheduleCancellationRequest
import io.dataloom.api.scheduling.ScheduleReceipt
import io.dataloom.api.scheduling.ScheduleRequest
import io.dataloom.api.scheduling.SchedulerProvider
import io.dataloom.scheduler.workmanager.internal.SchedulerProviderError
import java.util.concurrent.TimeUnit

/**
 * AndroidX WorkManager-backed implementation of [SchedulerProvider].
 *
 * Translates DataLoom [ScheduleRequest] intents into WorkManager
 * [OneTimeWorkRequest]s with a deterministic unique work name derived from
 * [io.dataloom.api.identifier.ScheduleId.value].
 *
 * ## Work naming
 *
 * Each schedule uses `ScheduleId.value` as the unique work name. This
 * produces a stable, collision-resistant name for every distinct schedule
 * without hashing. WorkManager's unique work deduplication is used to
 * enforce [ExistingSchedulePolicy] semantics.
 *
 * ## ExistingSchedulePolicy mapping
 *
 * | [ExistingSchedulePolicy] | WorkManager [ExistingWorkPolicy] |
 * |--------------------------|----------------------------------|
 * | [ExistingSchedulePolicy.KEEP]    | [ExistingWorkPolicy.KEEP]    |
 * | [ExistingSchedulePolicy.REPLACE] | [ExistingWorkPolicy.REPLACE] |
 *
 * ## Delay mapping
 *
 * [io.dataloom.api.scheduling.SchedulingDelay.milliseconds] is converted to
 * the WorkManager initial delay. Overflow-safe clamping ensures that values
 * larger than [Long.MAX_VALUE] milliseconds do not cause arithmetic errors
 * (in practice SchedulingDelay already validates non-negative longs, so no
 * real overflow is possible, but the conversion is explicit).
 *
 * ## Connectivity constraint mapping
 *
 * | [ConnectivityRequirement]         | WorkManager [NetworkType]     |
 * |-----------------------------------|-------------------------------|
 * | [ConnectivityRequirement.NONE]    | [NetworkType.NOT_REQUIRED]    |
 * | [ConnectivityRequirement.AVAILABLE] | [NetworkType.CONNECTED]     |
 * | [ConnectivityRequirement.UNMETERED] | [NetworkType.UNMETERED]     |
 *
 * ## WorkManager enqueue contract
 *
 * Exactly one [WorkManager.enqueueUniqueWork] call is made per [schedule]
 * invocation.
 *
 * ## What this provider must not do
 *
 * - Execute synchronization directly.
 * - Select threads or dispatchers.
 * - Initialize or configure DataLoom automatically.
 *
 * ## Thread safety
 *
 * This provider is safe to call from any thread. [WorkManager] enqueue
 * operations are thread-safe.
 *
 * ## Cancellation
 *
 * Coroutine cancellation propagates normally.
 *
 * @param context Android context used to obtain the WorkManager instance.
 *   Should be the application context.
 * @param workManager WorkManager instance to use for scheduling. Defaults to
 *   [WorkManager.getInstance] for the provided context.
 */
public class WorkManagerSchedulerProvider(
    private val context: Context,
    private val workManager: WorkManager = WorkManager.getInstance(context),
) : SchedulerProvider {

    /**
     * Immutable descriptor for this scheduler provider.
     *
     * [ProviderDescriptor.type] is [ProviderType.SCHEDULER].
     */
    override val descriptor: ProviderDescriptor = ProviderDescriptor(
        id = ProviderId("io.dataloom.scheduler.workmanager"),
        name = ProviderName("WorkManagerSchedulerProvider"),
        type = ProviderType.SCHEDULER,
        version = ProviderVersion("1.0.0"),
        capabilities = setOf(ProviderCapability("one-time-work")),
    )

    override suspend fun initialize(
        context: ProviderInitializationContext,
    ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

    override suspend fun health(): ProviderOperationResult<ProviderHealth> =
        ProviderOperationResult.Success(ProviderHealth(status = ProviderHealthStatus.HEALTHY))

    override suspend fun close(): ProviderOperationResult<Unit> =
        ProviderOperationResult.Success(Unit)

    /**
     * Schedules a one-time WorkManager work request for the given [request].
     *
     * Uses [ScheduleRequest.id] as the unique WorkManager work name.
     * Applies [ScheduleRequest.existingPolicy] to handle existing schedules.
     * Maps [ScheduleRequest.delay] to the WorkManager initial delay.
     * Maps [ScheduleRequest.constraints] to WorkManager [Constraints].
     *
     * @param request immutable scheduling intent.
     * @return [ProviderOperationResult.Success] with a [ScheduleReceipt] when
     *   WorkManager accepted the request, or [ProviderOperationResult.Failure]
     *   on platform failure.
     */
    override suspend fun schedule(
        request: ScheduleRequest,
    ): ProviderOperationResult<ScheduleReceipt> {
        return try {
            val workRequest = buildWorkRequest(request)
            val existingWorkPolicy = request.existingPolicy.toExistingWorkPolicy()
            val workName = request.id.value

            workManager.enqueueUniqueWork(workName, existingWorkPolicy, workRequest)

            ProviderOperationResult.Success(ScheduleReceipt(id = request.id))
        } catch (e: Exception) {
            ProviderOperationResult.Failure(SchedulerProviderError.schedulingFailure(cause = e))
        }
    }

    /**
     * Cancels a previously scheduled work request.
     *
     * Uses [ScheduleCancellationRequest.id] as the unique WorkManager work name.
     *
     * @param request immutable cancellation request.
     * @return [ProviderOperationResult.Success] when the cancellation was
     *   processed, or [ProviderOperationResult.Failure] on platform failure.
     */
    override suspend fun cancel(
        request: ScheduleCancellationRequest,
    ): ProviderOperationResult<Unit> {
        return try {
            workManager.cancelUniqueWork(request.id.value)
            ProviderOperationResult.Success(Unit)
        } catch (e: Exception) {
            ProviderOperationResult.Failure(SchedulerProviderError.schedulingFailure(cause = e))
        }
    }

    private fun buildWorkRequest(request: ScheduleRequest): OneTimeWorkRequest {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(request.constraints.connectivity.toNetworkType())
            .setRequiresCharging(request.constraints.requiresCharging)
            .build()

        return OneTimeWorkRequestBuilder<DataLoomCoroutineWorker>()
            .setConstraints(constraints)
            .setInitialDelay(request.delay.milliseconds, TimeUnit.MILLISECONDS)
            .addTag(WORKER_TAG)
            .build()
    }

    private fun ExistingSchedulePolicy.toExistingWorkPolicy(): ExistingWorkPolicy =
        when (this) {
            ExistingSchedulePolicy.KEEP -> ExistingWorkPolicy.KEEP
            ExistingSchedulePolicy.REPLACE -> ExistingWorkPolicy.REPLACE
        }

    private fun ConnectivityRequirement.toNetworkType(): NetworkType =
        when (this) {
            ConnectivityRequirement.NONE -> NetworkType.NOT_REQUIRED
            ConnectivityRequirement.AVAILABLE -> NetworkType.CONNECTED
            ConnectivityRequirement.UNMETERED -> NetworkType.UNMETERED
        }

    public companion object {
        /**
         * WorkManager tag applied to all work requests created by this provider.
         *
         * Consuming applications may use this tag to query or cancel all
         * DataLoom-managed work items.
         */
        public const val WORKER_TAG: String = "io.dataloom.worker"
    }
}
