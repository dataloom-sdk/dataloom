package io.dataloom.runtime.strategy

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
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategyRemoteOutcome
import io.dataloom.api.strategy.StrategyTransportOutput
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.synchronization.SynchronizationSkipReason
import io.dataloom.api.synchronization.SynchronizationSummary
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StrategyCacheInlineRefreshResultTest {

    @Test
    fun completedAcceptsSucceededAndNoChangeOutputAndDerivesCompletionTime() {
        val skipped = StrategyCacheInlineRefreshResult.Completed(
            output = providerBacked(skipped()),
        )
        val succeeded = StrategyCacheInlineRefreshResult.Completed(
            output = providerBacked(succeeded()),
        )

        assertEquals(
            StrategyCacheInlineRefreshDisposition.COMPLETED,
            skipped.disposition,
        )
        assertEquals(completedAt, skipped.completedAt)
        assertEquals(completedAt, succeeded.completedAt)
        assertTrue(skipped.toString().contains("SKIPPED"))
        assertTrue(succeeded.toString().contains("SUCCEEDED"))
    }

    @Test
    fun completedRejectsPolicySkipPartialFailedAndCancelledOutputs() {
        assertFailsWith<IllegalArgumentException> {
            StrategyCacheInlineRefreshResult.Completed(
                output = providerBacked(
                    skipped(SynchronizationSkipReason.POLICY_REJECTED),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            StrategyCacheInlineRefreshResult.Completed(
                output = providerBacked(partiallySucceeded(TestError())),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            StrategyCacheInlineRefreshResult.Completed(
                output = providerBacked(failed(TestError())),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            StrategyCacheInlineRefreshResult.Completed(
                output = providerBacked(cancelled()),
            )
        }
    }

    @Test
    fun partialOutcomeRequiresCanonicalPartialResultAndUsesBoundedDiagnostics() {
        val error = TestError()
        val result = StrategyCacheInlineRefreshResult.PartiallySucceeded(
            output = providerBacked(partiallySucceeded(error)),
        )

        assertEquals(
            StrategyCacheInlineRefreshDisposition.PARTIALLY_SUCCEEDED,
            result.disposition,
        )
        assertEquals(completedAt, result.completedAt)
        assertTrue(result.toString().contains("errorCount=1"))
        assertFalse(result.toString().contains(error.message))
        assertFailsWith<IllegalArgumentException> {
            StrategyCacheInlineRefreshResult.PartiallySucceeded(
                output = providerBacked(skipped()),
            )
        }
    }

    @Test
    fun failedDerivesCanonicalErrorAndTimeAndCopiesCompletedOperations() {
        val error = TestError()
        val operations = mutableListOf(StrategyOperation.PULL_REMOTE)
        val output = providerBacked(failed(error))
        val result = StrategyCacheInlineRefreshResult.Failed(
            transportAttempted = true,
            completedOperations = operations,
            output = output,
            remoteOutcome = StrategyRemoteOutcome.UNAVAILABLE,
        )
        operations += StrategyOperation.PERSIST_REMOTE

        assertEquals(
            StrategyCacheInlineRefreshDisposition.FAILED,
            result.disposition,
        )
        assertEquals(completedAt, result.completedAt)
        assertSame(error, result.error)
        assertEquals(listOf(StrategyOperation.PULL_REMOTE), result.completedOperations)
        assertTrue(result.transportAttempted)
        assertEquals(StrategyRemoteOutcome.UNAVAILABLE, result.remoteOutcome)
        assertTrue(result.toString().contains(error.code.value))
        assertFalse(result.toString().contains(error.message))
    }

    @Test
    fun failedRequiresCanonicalFailureAndConsistentRemoteEvidence() {
        assertFailsWith<IllegalArgumentException> {
            StrategyCacheInlineRefreshResult.Failed(
                transportAttempted = false,
                output = providerBacked(skipped()),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            StrategyCacheInlineRefreshResult.Failed(
                transportAttempted = false,
                output = providerBacked(failed(TestError())),
                remoteOutcome = StrategyRemoteOutcome.UNAVAILABLE,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            StrategyCacheInlineRefreshResult.Failed(
                transportAttempted = false,
                completedOperations = listOf(StrategyOperation.PULL_REMOTE),
                output = providerBacked(failed(TestError())),
            )
        }
    }

    @Test
    fun cancelledRequiresCanonicalCancellationAndDerivesCompletionTime() {
        val result = StrategyCacheInlineRefreshResult.Cancelled(
            output = providerBacked(cancelled()),
        )

        assertEquals(
            StrategyCacheInlineRefreshDisposition.CANCELLED,
            result.disposition,
        )
        assertEquals(completedAt, result.completedAt)
        assertFailsWith<IllegalArgumentException> {
            StrategyCacheInlineRefreshResult.Cancelled(
                output = providerBacked(skipped()),
            )
        }
    }

    private fun providerBacked(
        result: SynchronizationResult,
    ): StrategyTransportOutput.ProviderBacked =
        StrategyTransportOutput.ProviderBacked(result)

    private fun succeeded(): SynchronizationResult.Succeeded =
        SynchronizationResult.Succeeded(
            request = request,
            completedAt = completedAt,
            summary = SynchronizationSummary(),
        )

    private fun skipped(
        reason: SynchronizationSkipReason = SynchronizationSkipReason.NO_CHANGES,
    ): SynchronizationResult.Skipped =
        SynchronizationResult.Skipped(
            request = request,
            completedAt = completedAt,
            summary = SynchronizationSummary(),
            reason = reason,
        )

    private fun partiallySucceeded(
        error: DataLoomError,
    ): SynchronizationResult.PartiallySucceeded =
        SynchronizationResult.PartiallySucceeded(
            request = request,
            completedAt = completedAt,
            summary = SynchronizationSummary(),
            errors = listOf(error),
        )

    private fun failed(error: DataLoomError): SynchronizationResult.Failed =
        SynchronizationResult.Failed(
            request = request,
            completedAt = completedAt,
            summary = SynchronizationSummary(),
            error = error,
        )

    private fun cancelled(): SynchronizationResult.Cancelled =
        SynchronizationResult.Cancelled(
            request = request,
            completedAt = completedAt,
            summary = SynchronizationSummary(),
        )

    private data class TestError(
        override val code: ErrorCode = ErrorCode("INLINE_REFRESH_FAILED"),
        override val category: ErrorCategory = ErrorCategory.NETWORK,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Sensitive provider failure detail.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private companion object {
        val completedAt = DataLoomInstant(25_000L)
        val request = SynchronizationRequest(
            workflowId = WorkflowId("inline-refresh-contract-workflow"),
            sessionId = SynchronizationSessionId("inline-refresh-contract-session"),
            direction = SynchronizationDirection.PULL,
            mode = SynchronizationMode.DELTA,
            context = ExecutionContext(
                executionId = ExecutionId("inline-refresh-contract-execution"),
                correlationId = CorrelationId("inline-refresh-contract-correlation"),
            ),
        )
    }
}
