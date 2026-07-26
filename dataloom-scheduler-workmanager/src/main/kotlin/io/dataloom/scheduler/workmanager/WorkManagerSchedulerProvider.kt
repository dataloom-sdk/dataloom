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
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

/**
 * AndroidX WorkManager-backed implementation of [SchedulerProvider].
 *
 * Each call maps one immutable DataLoom schedule request to one unique
 * WorkManager request. Scheduling does not execute synchronization or inspect
 * queue payloads. WorkManager retry is not used as a second queue retry state
 * machine.
 */
public class WorkManagerSchedulerProvider(
    context: Context,
    private val workManager: WorkManager = WorkManager.getInstance(
        context.applicationContext ?: context,
    ),
) : SchedulerProvider {

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

    override suspend fun schedule(
        request: ScheduleRequest,
    ): ProviderOperationResult<ScheduleReceipt> {
        return try {
            val workRequest = buildWorkRequest(request)
            workManager.enqueueUniqueWork(
                request.id.value,
                request.existingPolicy.toExistingWorkPolicy(),
                workRequest,
            )
            ProviderOperationResult.Success(ScheduleReceipt(id = request.id))
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            ProviderOperationResult.Failure(SchedulerProviderError.schedulingFailure())
        }
    }

    override suspend fun cancel(
        request: ScheduleCancellationRequest,
    ): ProviderOperationResult<Unit> {
        return try {
            workManager.cancelUniqueWork(request.id.value)
            ProviderOperationResult.Success(Unit)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            ProviderOperationResult.Failure(SchedulerProviderError.schedulingFailure())
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
        public const val WORKER_TAG: String = "io.dataloom.worker"
    }
}
