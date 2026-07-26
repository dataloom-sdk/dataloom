package io.dataloom.scheduler.workmanager

import android.content.Context
import androidx.work.WorkManager
import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.ScheduleId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.scheduling.ExistingSchedulePolicy
import io.dataloom.api.scheduling.ScheduleCancellationRequest
import io.dataloom.api.scheduling.ScheduleConstraints
import io.dataloom.api.scheduling.ScheduleReceipt
import io.dataloom.api.scheduling.ScheduleRequest
import io.dataloom.api.scheduling.SchedulingDelay
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkManagerSchedulerProviderTest {

    private val mockContext: Context = mock()
    private val mockWorkManager: WorkManager = mock()
    private val provider = WorkManagerSchedulerProvider(mockContext, mockWorkManager)

    private fun runSync(block: suspend () -> Unit) {
        var thrown: Throwable? = null
        block.startCoroutine(
            Continuation(EmptyCoroutineContext) { result ->
                result.onFailure { thrown = it }
            },
        )
        thrown?.let { throw it }
    }

    private fun makeScheduleRequest(
        idValue: String = "test-schedule-id",
        delay: SchedulingDelay = SchedulingDelay.ZERO,
        existingPolicy: ExistingSchedulePolicy = ExistingSchedulePolicy.KEEP,
    ) = ScheduleRequest(
        id = ScheduleId(idValue),
        delay = delay,
        constraints = ScheduleConstraints(),
        existingPolicy = existingPolicy,
    )

    private fun makeExecutionContext() = ExecutionContext(
        executionId = ExecutionId("test-exec-id"),
        correlationId = CorrelationId("test-corr-id"),
        metadata = DataLoomMetadata.Empty,
    )

    @Test
    fun `descriptor type is SCHEDULER`() {
        assertEquals(ProviderType.SCHEDULER, provider.descriptor.type)
    }

    @Test
    fun `descriptor provider id is non-blank`() {
        assertTrue(provider.descriptor.id.value.isNotBlank())
    }

    @Test
    fun `schedule returns Success with matching receipt id`() {
        val request = makeScheduleRequest(idValue = "my-schedule-id")

        var result: ProviderOperationResult<ScheduleReceipt>? = null
        runSync { result = provider.schedule(request) }

        val success = assertIs<ProviderOperationResult.Success<ScheduleReceipt>>(result)
        assertEquals(ScheduleId("my-schedule-id"), success.value.id)
    }

    @Test
    fun `schedule calls enqueueUniqueWork with schedule id as work name`() {
        val request = makeScheduleRequest(idValue = "queue-worker-wakeup")

        runSync { provider.schedule(request) }

        verify(mockWorkManager).enqueueUniqueWork(
            org.mockito.kotlin.eq("queue-worker-wakeup"),
            any(),
            any(),
        )
    }

    @Test
    fun `initialize returns Success`() {
        var result: ProviderOperationResult<Unit>? = null
        runSync { result = provider.initialize(mock()) }
        assertIs<ProviderOperationResult.Success<Unit>>(result)
    }

    @Test
    fun `health returns Success`() {
        var result: ProviderOperationResult<*>? = null
        runSync { result = provider.health() }
        assertIs<ProviderOperationResult.Success<*>>(result)
    }

    @Test
    fun `close returns Success`() {
        var result: ProviderOperationResult<Unit>? = null
        runSync { result = provider.close() }
        assertIs<ProviderOperationResult.Success<Unit>>(result)
    }

    @Test
    fun `cancel calls cancelUniqueWork with schedule id`() {
        val request = ScheduleCancellationRequest(
            id = ScheduleId("cancel-me"),
            context = makeExecutionContext(),
        )

        runSync { provider.cancel(request) }

        verify(mockWorkManager).cancelUniqueWork("cancel-me")
    }

    @Test
    fun `cancel returns Success`() {
        val request = ScheduleCancellationRequest(
            id = ScheduleId("cancel-me"),
            context = makeExecutionContext(),
        )

        var result: ProviderOperationResult<Unit>? = null
        runSync { result = provider.cancel(request) }
        assertIs<ProviderOperationResult.Success<Unit>>(result)
    }
}
