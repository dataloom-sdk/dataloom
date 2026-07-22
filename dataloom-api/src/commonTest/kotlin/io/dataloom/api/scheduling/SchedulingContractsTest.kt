package io.dataloom.api.scheduling

import io.dataloom.api.connectivity.ConnectivityRequirement
import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.ScheduleId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderVersion
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SchedulingContractsTest {

    // -------------------------------------------------------------------------
    // ScheduleId tests
    // -------------------------------------------------------------------------

    @Test
    fun `schedule id accepts valid value`() {
        val id: ScheduleId = ScheduleId("sync-schedule-001")
        assertEquals("sync-schedule-001", id.value)
    }

    @Test
    fun `schedule id rejects blank value`() {
        assertFailsWith<IllegalArgumentException> {
            ScheduleId("")
        }
    }

    @Test
    fun `schedule id rejects whitespace-only value`() {
        assertFailsWith<IllegalArgumentException> {
            ScheduleId("   ")
        }
    }

    @Test
    fun `schedule id preserves exact value`() {
        val id: ScheduleId = ScheduleId("workflow-daily-customers")
        assertEquals("workflow-daily-customers", id.value)
    }

    @Test
    fun `schedule id value equality`() {
        val a: ScheduleId = ScheduleId("schedule-abc")
        val b: ScheduleId = ScheduleId("schedule-abc")
        assertEquals(a, b)
    }

    @Test
    fun `schedule id inequality for different values`() {
        val a: ScheduleId = ScheduleId("schedule-abc")
        val b: ScheduleId = ScheduleId("schedule-xyz")
        assertNotEquals(a, b)
    }

    @Test
    fun `schedule id toString returns wrapped value`() {
        val id: ScheduleId = ScheduleId("tenant-example-push")
        assertEquals("tenant-example-push", id.toString())
    }

    // -------------------------------------------------------------------------
    // SchedulingDelay tests
    // -------------------------------------------------------------------------

    @Test
    fun `scheduling delay accepts zero`() {
        val delay: SchedulingDelay = SchedulingDelay(0L)
        assertEquals(0L, delay.milliseconds)
    }

    @Test
    fun `scheduling delay ZERO constant is zero`() {
        assertEquals(0L, SchedulingDelay.ZERO.milliseconds)
    }

    @Test
    fun `scheduling delay accepts positive value`() {
        val delay: SchedulingDelay = SchedulingDelay(30_000L)
        assertEquals(30_000L, delay.milliseconds)
    }

    @Test
    fun `scheduling delay rejects negative value`() {
        assertFailsWith<IllegalArgumentException> {
            SchedulingDelay(-1L)
        }
    }

    @Test
    fun `scheduling delay value equality`() {
        val a: SchedulingDelay = SchedulingDelay(5_000L)
        val b: SchedulingDelay = SchedulingDelay(5_000L)
        assertEquals(a, b)
    }

    @Test
    fun `scheduling delay inequality for different values`() {
        val a: SchedulingDelay = SchedulingDelay(1_000L)
        val b: SchedulingDelay = SchedulingDelay(2_000L)
        assertNotEquals(a, b)
    }

    // -------------------------------------------------------------------------
    // ExistingSchedulePolicy tests
    // -------------------------------------------------------------------------

    @Test
    fun `existing schedule policy contains KEEP`() {
        val policy: ExistingSchedulePolicy = ExistingSchedulePolicy.KEEP
        assertEquals("KEEP", policy.name)
    }

    @Test
    fun `existing schedule policy contains REPLACE`() {
        val policy: ExistingSchedulePolicy = ExistingSchedulePolicy.REPLACE
        assertEquals("REPLACE", policy.name)
    }

    @Test
    fun `existing schedule policy exposes all required values`() {
        val names: Set<String> = ExistingSchedulePolicy.entries.map { it.name }.toSet()
        assertTrue("KEEP" in names)
        assertTrue("REPLACE" in names)
    }

    // -------------------------------------------------------------------------
    // ScheduleConstraints tests
    // -------------------------------------------------------------------------

    @Test
    fun `schedule constraints default connectivity is NONE`() {
        val constraints: ScheduleConstraints = ScheduleConstraints()
        assertEquals(ConnectivityRequirement.NONE, constraints.connectivity)
    }

    @Test
    fun `schedule constraints charging is not required by default`() {
        val constraints: ScheduleConstraints = ScheduleConstraints()
        assertEquals(false, constraints.requiresCharging)
    }

    @Test
    fun `schedule constraints metadata defaults to empty`() {
        val constraints: ScheduleConstraints = ScheduleConstraints()
        assertEquals(DataLoomMetadata.Empty, constraints.metadata)
        assertTrue(constraints.metadata.isEmpty())
    }

    @Test
    fun `schedule constraints preserves explicit connectivity requirement`() {
        val constraints: ScheduleConstraints = ScheduleConstraints(
            connectivity = ConnectivityRequirement.UNMETERED,
        )
        assertEquals(ConnectivityRequirement.UNMETERED, constraints.connectivity)
    }

    @Test
    fun `schedule constraints preserves explicit requiresCharging`() {
        val constraints: ScheduleConstraints = ScheduleConstraints(requiresCharging = true)
        assertEquals(true, constraints.requiresCharging)
    }

    @Test
    fun `schedule constraints preserves explicit metadata`() {
        val metadata: DataLoomMetadata = DataLoomMetadata.of(mapOf("source" to "test"))
        val constraints: ScheduleConstraints = ScheduleConstraints(metadata = metadata)
        assertEquals(metadata, constraints.metadata)
    }

    @Test
    fun `equal schedule constraints compare as equal`() {
        val a: ScheduleConstraints = ScheduleConstraints(
            connectivity = ConnectivityRequirement.AVAILABLE,
            requiresCharging = true,
        )
        val b: ScheduleConstraints = ScheduleConstraints(
            connectivity = ConnectivityRequirement.AVAILABLE,
            requiresCharging = true,
        )
        assertEquals(a, b)
    }

    @Test
    fun `different schedule constraints compare as not equal`() {
        val a: ScheduleConstraints = ScheduleConstraints(
            connectivity = ConnectivityRequirement.AVAILABLE,
        )
        val b: ScheduleConstraints = ScheduleConstraints(
            connectivity = ConnectivityRequirement.NONE,
        )
        assertNotEquals(a, b)
    }

    // -------------------------------------------------------------------------
    // ScheduleRequest tests
    // -------------------------------------------------------------------------

    @Test
    fun `schedule request preserves schedule id`() {
        val id: ScheduleId = ScheduleId("sync-schedule-001")
        val request: ScheduleRequest = ScheduleRequest(
            id = id,
            synchronizationRequest = sampleSynchronizationRequest(),
        )
        assertEquals(id, request.id)
    }

    @Test
    fun `schedule request preserves synchronization request`() {
        val syncRequest: SynchronizationRequest = sampleSynchronizationRequest()
        val request: ScheduleRequest = ScheduleRequest(
            id = ScheduleId("schedule-001"),
            synchronizationRequest = syncRequest,
        )
        assertEquals(syncRequest, request.synchronizationRequest)
    }

    @Test
    fun `schedule request default delay is zero`() {
        val request: ScheduleRequest = ScheduleRequest(
            id = ScheduleId("schedule-001"),
            synchronizationRequest = sampleSynchronizationRequest(),
        )
        assertEquals(SchedulingDelay.ZERO, request.delay)
    }

    @Test
    fun `schedule request default constraints apply`() {
        val request: ScheduleRequest = ScheduleRequest(
            id = ScheduleId("schedule-001"),
            synchronizationRequest = sampleSynchronizationRequest(),
        )
        assertEquals(ScheduleConstraints(), request.constraints)
    }

    @Test
    fun `schedule request default existing policy is KEEP`() {
        val request: ScheduleRequest = ScheduleRequest(
            id = ScheduleId("schedule-001"),
            synchronizationRequest = sampleSynchronizationRequest(),
        )
        assertEquals(ExistingSchedulePolicy.KEEP, request.existingPolicy)
    }

    @Test
    fun `schedule request preserves explicit delay`() {
        val delay: SchedulingDelay = SchedulingDelay(15_000L)
        val request: ScheduleRequest = ScheduleRequest(
            id = ScheduleId("schedule-001"),
            synchronizationRequest = sampleSynchronizationRequest(),
            delay = delay,
        )
        assertEquals(delay, request.delay)
    }

    @Test
    fun `schedule request preserves explicit constraints`() {
        val constraints: ScheduleConstraints = ScheduleConstraints(
            connectivity = ConnectivityRequirement.AVAILABLE,
            requiresCharging = true,
        )
        val request: ScheduleRequest = ScheduleRequest(
            id = ScheduleId("schedule-001"),
            synchronizationRequest = sampleSynchronizationRequest(),
            constraints = constraints,
        )
        assertEquals(constraints, request.constraints)
    }

    @Test
    fun `schedule request preserves explicit existing policy`() {
        val request: ScheduleRequest = ScheduleRequest(
            id = ScheduleId("schedule-001"),
            synchronizationRequest = sampleSynchronizationRequest(),
            existingPolicy = ExistingSchedulePolicy.REPLACE,
        )
        assertEquals(ExistingSchedulePolicy.REPLACE, request.existingPolicy)
    }

    @Test
    fun `equal schedule requests compare as equal`() {
        val syncRequest: SynchronizationRequest = sampleSynchronizationRequest()
        val id: ScheduleId = ScheduleId("schedule-001")
        val a: ScheduleRequest = ScheduleRequest(
            id = id,
            synchronizationRequest = syncRequest,
        )
        val b: ScheduleRequest = ScheduleRequest(
            id = id,
            synchronizationRequest = syncRequest,
        )
        assertEquals(a, b)
    }

    // -------------------------------------------------------------------------
    // ScheduleReceipt tests
    // -------------------------------------------------------------------------

    @Test
    fun `schedule receipt preserves schedule id`() {
        val id: ScheduleId = ScheduleId("schedule-001")
        val receipt: ScheduleReceipt = ScheduleReceipt(id = id)
        assertEquals(id, receipt.id)
    }

    @Test
    fun `schedule receipt metadata defaults to empty`() {
        val receipt: ScheduleReceipt = ScheduleReceipt(id = ScheduleId("schedule-001"))
        assertEquals(DataLoomMetadata.Empty, receipt.metadata)
        assertTrue(receipt.metadata.isEmpty())
    }

    @Test
    fun `schedule receipt preserves explicit metadata`() {
        val metadata: DataLoomMetadata = DataLoomMetadata.of(mapOf("region" to "us-east"))
        val receipt: ScheduleReceipt = ScheduleReceipt(
            id = ScheduleId("schedule-001"),
            metadata = metadata,
        )
        assertEquals(metadata, receipt.metadata)
    }

    @Test
    fun `equal schedule receipts compare as equal`() {
        val id: ScheduleId = ScheduleId("schedule-001")
        val a: ScheduleReceipt = ScheduleReceipt(id = id)
        val b: ScheduleReceipt = ScheduleReceipt(id = id)
        assertEquals(a, b)
    }

    @Test
    fun `different schedule receipts compare as not equal`() {
        val a: ScheduleReceipt = ScheduleReceipt(id = ScheduleId("schedule-001"))
        val b: ScheduleReceipt = ScheduleReceipt(id = ScheduleId("schedule-002"))
        assertNotEquals(a, b)
    }

    // -------------------------------------------------------------------------
    // ScheduleCancellationRequest tests
    // -------------------------------------------------------------------------

    @Test
    fun `schedule cancellation request preserves id`() {
        val id: ScheduleId = ScheduleId("schedule-001")
        val request: ScheduleCancellationRequest = ScheduleCancellationRequest(
            id = id,
            context = sampleExecutionContext(),
        )
        assertEquals(id, request.id)
    }

    @Test
    fun `schedule cancellation request preserves execution context`() {
        val context: ExecutionContext = sampleExecutionContext()
        val request: ScheduleCancellationRequest = ScheduleCancellationRequest(
            id = ScheduleId("schedule-001"),
            context = context,
        )
        assertEquals(context, request.context)
    }

    @Test
    fun `equal cancellation requests compare as equal`() {
        val id: ScheduleId = ScheduleId("schedule-001")
        val context: ExecutionContext = sampleExecutionContext()
        val a: ScheduleCancellationRequest = ScheduleCancellationRequest(id = id, context = context)
        val b: ScheduleCancellationRequest = ScheduleCancellationRequest(id = id, context = context)
        assertEquals(a, b)
    }

    // -------------------------------------------------------------------------
    // SchedulerProvider tests (fake implementation)
    // -------------------------------------------------------------------------

    @Test
    fun `scheduler provider descriptor uses SCHEDULER type`() {
        val provider: SchedulerProvider = FakeSchedulerProvider()
        assertEquals(ProviderType.SCHEDULER, provider.descriptor.type)
    }

    @Test
    fun `scheduler provider schedule returns receipt`() {
        val provider: SchedulerProvider = FakeSchedulerProvider(
            scheduleResult = ProviderOperationResult.Success(
                ScheduleReceipt(id = ScheduleId("schedule-001")),
            ),
        )
        var result: ProviderOperationResult<ScheduleReceipt>? = null
        val request: ScheduleRequest = ScheduleRequest(
            id = ScheduleId("schedule-001"),
            synchronizationRequest = sampleSynchronizationRequest(),
        )
        runSync {
            result = provider.schedule(request)
        }
        val success: ProviderOperationResult.Success<ScheduleReceipt> =
            assertIs(result)
        assertEquals(ScheduleId("schedule-001"), success.value.id)
    }

    @Test
    fun `scheduler provider cancel returns success`() {
        val provider: SchedulerProvider = FakeSchedulerProvider(
            cancelResult = ProviderOperationResult.Success(Unit),
        )
        var result: ProviderOperationResult<Unit>? = null
        val request: ScheduleCancellationRequest = ScheduleCancellationRequest(
            id = ScheduleId("schedule-001"),
            context = sampleExecutionContext(),
        )
        runSync {
            result = provider.cancel(request)
        }
        assertIs<ProviderOperationResult.Success<Unit>>(result)
    }

    @Test
    fun `scheduler provider schedule can return canonical failure`() {
        val error: DataLoomError = TestDataLoomError(
            code = ErrorCode("SCHEDULER_REJECTED"),
            category = ErrorCategory.SCHEDULER,
            severity = ErrorSeverity.ERROR,
            recoverability = Recoverability.RECOVERABLE,
            message = "Scheduling rejected by platform.",
            cause = null,
        )
        val provider: SchedulerProvider = FakeSchedulerProvider(
            scheduleResult = ProviderOperationResult.Failure(error),
        )
        var result: ProviderOperationResult<ScheduleReceipt>? = null
        runSync {
            result = provider.schedule(
                ScheduleRequest(
                    id = ScheduleId("schedule-002"),
                    synchronizationRequest = sampleSynchronizationRequest(),
                ),
            )
        }
        val failure: ProviderOperationResult.Failure = assertIs(result)
        assertEquals(ErrorCategory.SCHEDULER, failure.error.category)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun sampleExecutionContext(): ExecutionContext = ExecutionContext(
        executionId = ExecutionId("execution-001"),
        correlationId = CorrelationId("corr-001"),
    )

    private fun sampleSynchronizationRequest(): SynchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("workflow-001"),
        sessionId = SynchronizationSessionId("session-001"),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        context = sampleExecutionContext(),
    )

    private fun runSync(block: suspend () -> Unit) {
        var exception: Throwable? = null
        val completed = arrayOf(false)
        block.startCoroutine(
            object : kotlin.coroutines.Continuation<Unit> {
                override val context: kotlin.coroutines.CoroutineContext =
                    kotlin.coroutines.EmptyCoroutineContext

                override fun resumeWith(result: Result<Unit>) {
                    result.onFailure { exception = it }
                    completed[0] = true
                }
            },
        )
        assertTrue(completed[0], "Coroutine did not complete synchronously")
        exception?.let { throw it }
    }

    private class FakeSchedulerProvider(
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("scheduler.fake"),
            name = ProviderName("Fake Scheduler Provider"),
            type = ProviderType.SCHEDULER,
            version = ProviderVersion("1.0.0"),
        ),
        private val scheduleResult: ProviderOperationResult<ScheduleReceipt> =
            ProviderOperationResult.Success(ScheduleReceipt(id = ScheduleId("default-schedule"))),
        private val cancelResult: ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit),
    ) : SchedulerProvider {

        override suspend fun schedule(
            request: ScheduleRequest,
        ): ProviderOperationResult<ScheduleReceipt> = scheduleResult

        override suspend fun cancel(
            request: ScheduleCancellationRequest,
        ): ProviderOperationResult<Unit> = cancelResult

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(status = ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)
    }

    private data class TestDataLoomError(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val severity: ErrorSeverity,
        override val recoverability: Recoverability,
        override val message: String,
        override val cause: Throwable?,
    ) : DataLoomError
}
