package io.dataloom.testing.scheduling

import io.dataloom.api.provider.ProviderCapability
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.scheduling.ScheduleCancellationRequest
import io.dataloom.api.scheduling.ScheduleReceipt
import io.dataloom.api.scheduling.ScheduleRequest
import io.dataloom.api.scheduling.SchedulerProvider
import io.dataloom.testing.provider.TestProviderLifecycleController

/**
 * Recording [SchedulerProvider] for deterministic tests.
 *
 * Scheduling requests are recorded but never executed. The provider can either
 * return a constant [scheduleResult] for every call or dequeue scripted results
 * when [scheduleResult] is `null`.
 *
 * @param descriptor provider descriptor exposed through [SchedulerProvider.descriptor].
 * @param scheduleResult constant result returned from every [schedule] call, or `null` to use scripting.
 * @param cancelResult result returned from every [cancel] call.
 * @param lifecycleController shared lifecycle controller used by provider tests.
 */
public class RecordingSchedulerProvider(
    override val descriptor: ProviderDescriptor = defaultDescriptor(),
    private val scheduleResult: ProviderOperationResult<ScheduleReceipt>? = null,
    private val cancelResult: ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit),
    private val lifecycleController: TestProviderLifecycleController = TestProviderLifecycleController(),
) : SchedulerProvider {
    private val scriptedScheduleResults: MutableList<ProviderOperationResult<ScheduleReceipt>> = mutableListOf()
    private val recordedScheduleRequests: MutableList<ScheduleRequest> = mutableListOf()
    private val recordedCancellationRequests: MutableList<ScheduleCancellationRequest> = mutableListOf()

    /** Recorded schedule requests in call order. */
    public val scheduleRequests: List<ScheduleRequest>
        get() = recordedScheduleRequests.toList()

    /** Recorded cancellation requests in call order. */
    public val cancellationRequests: List<ScheduleCancellationRequest>
        get() = recordedCancellationRequests.toList()

    /**
     * Queues a scripted result for [schedule] when the provider is script-driven.
     *
     * @param result result to dequeue on a future [schedule] call.
     */
    public fun enqueueScheduleResult(result: ProviderOperationResult<ScheduleReceipt>) {
        scriptedScheduleResults += result
    }

    override suspend fun initialize(
        context: ProviderInitializationContext,
    ): ProviderOperationResult<Unit> = lifecycleController.initialize(context)

    override suspend fun health(): ProviderOperationResult<ProviderHealth> = lifecycleController.health()

    override suspend fun close(): ProviderOperationResult<Unit> = lifecycleController.close()

    override suspend fun schedule(
        request: ScheduleRequest,
    ): ProviderOperationResult<ScheduleReceipt> {
        recordedScheduleRequests += request
        return scheduleResult
            ?: scriptedScheduleResults.removeFirstOrNull()
            ?: throw IllegalStateException(
                "RecordingSchedulerProvider: schedule script exhausted. Enqueue a schedule result before calling schedule.",
            )
    }

    override suspend fun cancel(
        request: ScheduleCancellationRequest,
    ): ProviderOperationResult<Unit> {
        recordedCancellationRequests += request
        return cancelResult
    }

    /** Clears recorded requests and lifecycle recordings without clearing scripted results. */
    public fun clearRecordings() {
        recordedScheduleRequests.clear()
        recordedCancellationRequests.clear()
        lifecycleController.clearRecordings()
    }

    /** Clears scripted schedule results and recorded requests. */
    public fun resetState() {
        scriptedScheduleResults.clear()
        clearRecordings()
    }
}

private fun <T> MutableList<T>.removeFirstOrNull(): T? = if (isEmpty()) null else removeAt(0)

private fun defaultDescriptor(): ProviderDescriptor = ProviderDescriptor(
    id = ProviderId("testing.scheduler.recording"),
    name = ProviderName("RecordingSchedulerProvider"),
    type = ProviderType.SCHEDULER,
    version = ProviderVersion("1.0.0"),
    capabilities = setOf(
        ProviderCapability("testing"),
        ProviderCapability("recording"),
    ),
)
