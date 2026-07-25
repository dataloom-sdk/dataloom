package io.dataloom.testing.scheduling

import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.testing.FakeDataLoomError
import io.dataloom.testing.sampleScheduleCancellationRequest
import io.dataloom.testing.sampleScheduleReceipt
import io.dataloom.testing.sampleScheduleRequest
import io.dataloom.testing.runSuspend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class RecordingSchedulerProviderTest {
    @Test
    fun `descriptor uses scheduler type`() {
        val provider = RecordingSchedulerProvider(scheduleResult = ProviderOperationResult.Success(sampleScheduleReceipt()))
        assertEquals(io.dataloom.api.provider.ProviderType.SCHEDULER, provider.descriptor.type)
    }

    @Test
    fun `constant schedule result is returned for every call`() {
        val receipt = sampleScheduleReceipt()
        val provider = RecordingSchedulerProvider(scheduleResult = ProviderOperationResult.Success(receipt))
        assertEquals(ProviderOperationResult.Success(receipt), runSuspend { provider.schedule(sampleScheduleRequest()) })
        assertEquals(ProviderOperationResult.Success(receipt), runSuspend { provider.schedule(sampleScheduleRequest("schedule-002")) })
    }

    @Test
    fun `scripted schedule result is returned when constant result is null`() {
        val provider = RecordingSchedulerProvider(scheduleResult = null)
        val scripted = ProviderOperationResult.Success(sampleScheduleReceipt())
        provider.enqueueScheduleResult(scripted)
        assertEquals(scripted, runSuspend { provider.schedule(sampleScheduleRequest()) })
    }

    @Test
    fun `schedule can return failure result`() {
        val provider = RecordingSchedulerProvider(scheduleResult = null)
        val scripted = ProviderOperationResult.Failure(FakeDataLoomError(message = "schedule failed"))
        provider.enqueueScheduleResult(scripted)
        val result = runSuspend { provider.schedule(sampleScheduleRequest()) }
        assertIs<ProviderOperationResult.Failure>(result)
    }

    @Test
    fun `schedule records requests`() {
        val provider = RecordingSchedulerProvider(scheduleResult = ProviderOperationResult.Success(sampleScheduleReceipt()))
        val first = sampleScheduleRequest()
        val second = sampleScheduleRequest("schedule-002")
        runSuspend { provider.schedule(first) }
        runSuspend { provider.schedule(second) }
        assertEquals(listOf(first, second), provider.scheduleRequests)
    }

    @Test
    fun `cancel returns configured result`() {
        val cancelResult = ProviderOperationResult.Failure(FakeDataLoomError(message = "cancel failed"))
        val provider = RecordingSchedulerProvider(
            scheduleResult = ProviderOperationResult.Success(sampleScheduleReceipt()),
            cancelResult = cancelResult,
        )
        assertEquals(cancelResult, runSuspend { provider.cancel(sampleScheduleCancellationRequest()) })
    }

    @Test
    fun `cancel records requests`() {
        val provider = RecordingSchedulerProvider(scheduleResult = ProviderOperationResult.Success(sampleScheduleReceipt()))
        val request = sampleScheduleCancellationRequest()
        runSuspend { provider.cancel(request) }
        assertEquals(listOf(request), provider.cancellationRequests)
    }

    @Test
    fun `schedule exhaustion throws when scripting is required`() {
        val provider = RecordingSchedulerProvider(scheduleResult = null)
        val error = assertFailsWith<IllegalStateException> {
            runSuspend { provider.schedule(sampleScheduleRequest()) }
        }
        assertEquals(true, error.message.orEmpty().contains("schedule script exhausted"))
    }

    @Test
    fun `requests are recorded before schedule exhaustion`() {
        val provider = RecordingSchedulerProvider(scheduleResult = null)
        val request = sampleScheduleRequest()
        assertFailsWith<IllegalStateException> { runSuspend { provider.schedule(request) } }
        assertEquals(listOf(request), provider.scheduleRequests)
    }

    @Test
    fun `clear recordings preserves scripted results`() {
        val provider = RecordingSchedulerProvider(scheduleResult = null)
        val scripted = ProviderOperationResult.Success(sampleScheduleReceipt())
        provider.enqueueScheduleResult(scripted)
        provider.clearRecordings()
        assertEquals(scripted, runSuspend { provider.schedule(sampleScheduleRequest()) })
    }

    @Test
    fun `clear recordings clears request lists`() {
        val provider = RecordingSchedulerProvider(scheduleResult = ProviderOperationResult.Success(sampleScheduleReceipt()))
        runSuspend { provider.schedule(sampleScheduleRequest()) }
        runSuspend { provider.cancel(sampleScheduleCancellationRequest()) }
        provider.clearRecordings()
        assertEquals(emptyList(), provider.scheduleRequests)
        assertEquals(emptyList(), provider.cancellationRequests)
    }

    @Test
    fun `reset state clears scripted results and recordings`() {
        val provider = RecordingSchedulerProvider(scheduleResult = null)
        provider.enqueueScheduleResult(ProviderOperationResult.Success(sampleScheduleReceipt()))
        runSuspend { provider.schedule(sampleScheduleRequest()) }
        provider.resetState()
        assertEquals(emptyList(), provider.scheduleRequests)
        assertFailsWith<IllegalStateException> { runSuspend { provider.schedule(sampleScheduleRequest()) } }
    }

    @Test
    fun `scripted results are consumed in order`() {
        val provider = RecordingSchedulerProvider(scheduleResult = null)
        val first = ProviderOperationResult.Success(sampleScheduleReceipt("schedule-001"))
        val second = ProviderOperationResult.Success(sampleScheduleReceipt("schedule-002"))
        provider.enqueueScheduleResult(first)
        provider.enqueueScheduleResult(second)
        assertEquals(first, runSuspend { provider.schedule(sampleScheduleRequest("schedule-001")) })
        assertEquals(second, runSuspend { provider.schedule(sampleScheduleRequest("schedule-002")) })
    }

    @Test
    fun `constant schedule result ignores scripted queue`() {
        val constant = ProviderOperationResult.Success(sampleScheduleReceipt("constant"))
        val provider = RecordingSchedulerProvider(scheduleResult = constant)
        provider.enqueueScheduleResult(ProviderOperationResult.Success(sampleScheduleReceipt("scripted")))
        assertEquals(constant, runSuspend { provider.schedule(sampleScheduleRequest()) })
    }

    @Test
    fun `cancel default result is success`() {
        val provider = RecordingSchedulerProvider(scheduleResult = ProviderOperationResult.Success(sampleScheduleReceipt()))
        assertEquals(ProviderOperationResult.Success(Unit), runSuspend { provider.cancel(sampleScheduleCancellationRequest()) })
    }
}
