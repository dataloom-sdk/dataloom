package io.dataloom.api.synchronization

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SynchronizationResultContractsTest {

    // -------------------------------------------------------------------------
    // SynchronizationResult.Succeeded tests
    // -------------------------------------------------------------------------

    @Test
    fun succeededPreservesRequestCompletedAtAndSummary() {
        val request: SynchronizationRequest = sampleRequest()
        val completedAt: DataLoomInstant = sampleInstant()
        val summary: SynchronizationSummary = emptySummary()

        val result: SynchronizationResult = SynchronizationResult.Succeeded(
            request = request,
            completedAt = completedAt,
            summary = summary,
        )

        assertEquals(request, result.request)
        assertEquals(completedAt, result.completedAt)
        assertEquals(summary, result.summary)
    }

    @Test
    fun `succeeded defaults metadata to empty`() {
        val result: SynchronizationResult.Succeeded = SynchronizationResult.Succeeded(
            request = sampleRequest(),
            completedAt = sampleInstant(),
            summary = emptySummary(),
        )

        assertEquals(DataLoomMetadata.Empty, result.metadata)
        assertTrue(result.metadata.isEmpty())
    }

    @Test
    fun `succeeded preserves supplied metadata`() {
        val metadata: DataLoomMetadata = DataLoomMetadata.of(mapOf("source" to "example"))
        val result: SynchronizationResult.Succeeded = SynchronizationResult.Succeeded(
            request = sampleRequest(),
            completedAt = sampleInstant(),
            summary = emptySummary(),
            metadata = metadata,
        )

        assertEquals(metadata, result.metadata)
    }

    @Test
    fun `equal succeeded results compare as equal`() {
        val request: SynchronizationRequest = sampleRequest()
        val completedAt: DataLoomInstant = sampleInstant()
        val first: SynchronizationResult.Succeeded = SynchronizationResult.Succeeded(
            request = request,
            completedAt = completedAt,
            summary = emptySummary(),
        )
        val second: SynchronizationResult.Succeeded = SynchronizationResult.Succeeded(
            request = request,
            completedAt = completedAt,
            summary = emptySummary(),
        )

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun `succeeded can be used in when expression as sealed variant`() {
        val result: SynchronizationResult = SynchronizationResult.Succeeded(
            request = sampleRequest(),
            completedAt = sampleInstant(),
            summary = emptySummary(),
        )

        assertIs<SynchronizationResult.Succeeded>(result)
    }

    // -------------------------------------------------------------------------
    // SynchronizationResult.PartiallySucceeded tests
    // -------------------------------------------------------------------------

    @Test
    fun `partially succeeded preserves errors`() {
        val errors: List<DataLoomError> = listOf(sampleError("DL-001"), sampleError("DL-002"))
        val result: SynchronizationResult.PartiallySucceeded = SynchronizationResult.PartiallySucceeded(
            request = sampleRequest(),
            completedAt = sampleInstant(),
            summary = emptySummary(),
            errors = errors,
        )

        assertEquals(2, result.errors.size)
    }

    @Test
    fun `partially succeeded defaults metadata to empty`() {
        val result: SynchronizationResult.PartiallySucceeded = SynchronizationResult.PartiallySucceeded(
            request = sampleRequest(),
            completedAt = sampleInstant(),
            summary = emptySummary(),
            errors = listOf(sampleError()),
        )

        assertEquals(DataLoomMetadata.Empty, result.metadata)
    }

    @Test
    fun `partially succeeded rejects empty errors list`() {
        assertFailsWith<IllegalArgumentException> {
            SynchronizationResult.PartiallySucceeded(
                request = sampleRequest(),
                completedAt = sampleInstant(),
                summary = emptySummary(),
                errors = emptyList(),
            )
        }
    }

    @Test
    fun `partially succeeded defensively copies errors list`() {
        val mutableErrors: MutableList<DataLoomError> = mutableListOf(sampleError("DL-001"))
        val result: SynchronizationResult.PartiallySucceeded = SynchronizationResult.PartiallySucceeded(
            request = sampleRequest(),
            completedAt = sampleInstant(),
            summary = emptySummary(),
            errors = mutableErrors,
        )

        mutableErrors.add(sampleError("DL-002"))

        assertEquals(1, result.errors.size)
    }

    @Test
    fun `partially succeeded errors list is read-only`() {
        val result: SynchronizationResult.PartiallySucceeded = SynchronizationResult.PartiallySucceeded(
            request = sampleRequest(),
            completedAt = sampleInstant(),
            summary = emptySummary(),
            errors = listOf(sampleError()),
        )

        assertIs<List<DataLoomError>>(result.errors)
    }

    @Test
    fun `equal partially succeeded results compare as equal`() {
        val request: SynchronizationRequest = sampleRequest()
        val completedAt: DataLoomInstant = sampleInstant()
        val errors: List<DataLoomError> = listOf(sampleError())
        val first: SynchronizationResult.PartiallySucceeded = SynchronizationResult.PartiallySucceeded(
            request = request,
            completedAt = completedAt,
            summary = emptySummary(),
            errors = errors,
        )
        val second: SynchronizationResult.PartiallySucceeded = SynchronizationResult.PartiallySucceeded(
            request = request,
            completedAt = completedAt,
            summary = emptySummary(),
            errors = errors,
        )

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    // -------------------------------------------------------------------------
    // SynchronizationResult.Failed tests
    // -------------------------------------------------------------------------

    @Test
    fun `failed preserves error`() {
        val error: DataLoomError = sampleError()
        val result: SynchronizationResult.Failed = SynchronizationResult.Failed(
            request = sampleRequest(),
            completedAt = sampleInstant(),
            summary = emptySummary(),
            error = error,
        )

        assertEquals(error, result.error)
    }

    @Test
    fun `failed defaults metadata to empty`() {
        val result: SynchronizationResult.Failed = SynchronizationResult.Failed(
            request = sampleRequest(),
            completedAt = sampleInstant(),
            summary = emptySummary(),
            error = sampleError(),
        )

        assertEquals(DataLoomMetadata.Empty, result.metadata)
    }

    @Test
    fun `equal failed results compare as equal`() {
        val request: SynchronizationRequest = sampleRequest()
        val completedAt: DataLoomInstant = sampleInstant()
        val error: DataLoomError = sampleError()
        val first: SynchronizationResult.Failed = SynchronizationResult.Failed(
            request = request,
            completedAt = completedAt,
            summary = emptySummary(),
            error = error,
        )
        val second: SynchronizationResult.Failed = SynchronizationResult.Failed(
            request = request,
            completedAt = completedAt,
            summary = emptySummary(),
            error = error,
        )

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    // -------------------------------------------------------------------------
    // SynchronizationResult.Cancelled tests
    // -------------------------------------------------------------------------

    @Test
    fun cancelledPreservesRequestCompletedAtAndSummary() {
        val request: SynchronizationRequest = sampleRequest()
        val completedAt: DataLoomInstant = sampleInstant()
        val summary: SynchronizationSummary = emptySummary()

        val result: SynchronizationResult.Cancelled = SynchronizationResult.Cancelled(
            request = request,
            completedAt = completedAt,
            summary = summary,
        )

        assertEquals(request, result.request)
        assertEquals(completedAt, result.completedAt)
        assertEquals(summary, result.summary)
    }

    @Test
    fun `cancelled defaults metadata to empty`() {
        val result: SynchronizationResult.Cancelled = SynchronizationResult.Cancelled(
            request = sampleRequest(),
            completedAt = sampleInstant(),
            summary = emptySummary(),
        )

        assertEquals(DataLoomMetadata.Empty, result.metadata)
    }

    @Test
    fun `cancelled is distinct from failed`() {
        val request: SynchronizationRequest = sampleRequest()
        val completedAt: DataLoomInstant = sampleInstant()

        val cancelled: SynchronizationResult = SynchronizationResult.Cancelled(
            request = request,
            completedAt = completedAt,
            summary = emptySummary(),
        )
        val failed: SynchronizationResult = SynchronizationResult.Failed(
            request = request,
            completedAt = completedAt,
            summary = emptySummary(),
            error = sampleError(),
        )

        assertNotEquals(cancelled, failed)
    }

    // -------------------------------------------------------------------------
    // SynchronizationResult.Skipped tests
    // -------------------------------------------------------------------------

    @Test
    fun `skipped preserves skip reason`() {
        val result: SynchronizationResult.Skipped = SynchronizationResult.Skipped(
            request = sampleRequest(),
            completedAt = sampleInstant(),
            summary = emptySummary(),
            reason = SynchronizationSkipReason.NO_CHANGES,
        )

        assertEquals(SynchronizationSkipReason.NO_CHANGES, result.reason)
    }

    @Test
    fun `skipped defaults metadata to empty`() {
        val result: SynchronizationResult.Skipped = SynchronizationResult.Skipped(
            request = sampleRequest(),
            completedAt = sampleInstant(),
            summary = emptySummary(),
            reason = SynchronizationSkipReason.POLICY_REJECTED,
        )

        assertEquals(DataLoomMetadata.Empty, result.metadata)
    }

    @Test
    fun `skipped results with different reasons are not equal`() {
        val request: SynchronizationRequest = sampleRequest()
        val completedAt: DataLoomInstant = sampleInstant()
        val first: SynchronizationResult.Skipped = SynchronizationResult.Skipped(
            request = request,
            completedAt = completedAt,
            summary = emptySummary(),
            reason = SynchronizationSkipReason.NO_CHANGES,
        )
        val second: SynchronizationResult.Skipped = SynchronizationResult.Skipped(
            request = request,
            completedAt = completedAt,
            summary = emptySummary(),
            reason = SynchronizationSkipReason.DUPLICATE_REQUEST,
        )

        assertNotEquals(first, second)
    }

    // -------------------------------------------------------------------------
    // Sealed exhaustiveness
    // -------------------------------------------------------------------------

    @Test
    fun `all result variants are reachable through when expression`() {
        val variants: List<SynchronizationResult> = listOf(
            SynchronizationResult.Succeeded(
                request = sampleRequest(),
                completedAt = sampleInstant(),
                summary = emptySummary(),
            ),
            SynchronizationResult.PartiallySucceeded(
                request = sampleRequest(),
                completedAt = sampleInstant(),
                summary = emptySummary(),
                errors = listOf(sampleError()),
            ),
            SynchronizationResult.Failed(
                request = sampleRequest(),
                completedAt = sampleInstant(),
                summary = emptySummary(),
                error = sampleError(),
            ),
            SynchronizationResult.Cancelled(
                request = sampleRequest(),
                completedAt = sampleInstant(),
                summary = emptySummary(),
            ),
            SynchronizationResult.Skipped(
                request = sampleRequest(),
                completedAt = sampleInstant(),
                summary = emptySummary(),
                reason = SynchronizationSkipReason.NO_CHANGES,
            ),
        )

        val matched: List<String> = variants.map { result: SynchronizationResult ->
            when (result) {
                is SynchronizationResult.Succeeded -> "succeeded"
                is SynchronizationResult.PartiallySucceeded -> "partially-succeeded"
                is SynchronizationResult.Failed -> "failed"
                is SynchronizationResult.Cancelled -> "cancelled"
                is SynchronizationResult.Skipped -> "skipped"
            }
        }

        assertEquals(
            listOf("succeeded", "partially-succeeded", "failed", "cancelled", "skipped"),
            matched,
        )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun sampleRequest(): SynchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("workflow-001"),
        sessionId = SynchronizationSessionId("session-001"),
        direction = SynchronizationDirection.BIDIRECTIONAL,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("execution-001"),
            correlationId = CorrelationId("corr-001"),
        ),
    )

    private fun sampleInstant(ms: Long = 1_000_000L): DataLoomInstant = DataLoomInstant(ms)

    private fun emptySummary(): SynchronizationSummary = SynchronizationSummary()

    private fun sampleError(code: String = "DL-TEST-001"): DataLoomError = TestDataLoomError(
        code = ErrorCode(code),
        category = ErrorCategory.PROVIDER,
        severity = ErrorSeverity.ERROR,
        recoverability = Recoverability.RECOVERABLE,
        message = "Test error.",
        cause = null,
    )

    private data class TestDataLoomError(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val severity: ErrorSeverity,
        override val recoverability: Recoverability,
        override val message: String,
        override val cause: Throwable?,
    ) : DataLoomError
}
