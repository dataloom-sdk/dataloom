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
import io.dataloom.api.strategy.StrategyTransportOutput
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.synchronization.SynchronizationSummary
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertFailsWith

class StrategyCacheInlineRefreshDirectionTest {

    @Test
    fun everyOutcomeRejectsNonPullCanonicalResults() {
        for (
            direction in listOf(
                SynchronizationDirection.PUSH,
                SynchronizationDirection.BIDIRECTIONAL,
            )
        ) {
            val request = request(direction)

            assertFailsWith<IllegalArgumentException> {
                StrategyCacheInlineRefreshResult.Completed(
                    completedOperations = listOf(StrategyOperation.PULL_REMOTE),
                    output = providerBacked(
                        SynchronizationResult.Succeeded(
                            request = request,
                            completedAt = completedAt,
                            summary = SynchronizationSummary(),
                        ),
                    ),
                )
            }

            assertFailsWith<IllegalArgumentException> {
                StrategyCacheInlineRefreshResult.PartiallySucceeded(
                    completedOperations = listOf(StrategyOperation.PULL_REMOTE),
                    output = providerBacked(
                        SynchronizationResult.PartiallySucceeded(
                            request = request,
                            completedAt = completedAt,
                            summary = SynchronizationSummary(),
                            errors = listOf(TestError),
                        ),
                    ),
                )
            }

            assertFailsWith<IllegalArgumentException> {
                StrategyCacheInlineRefreshResult.Failed(
                    transportAttempted = true,
                    output = providerBacked(
                        SynchronizationResult.Failed(
                            request = request,
                            completedAt = completedAt,
                            summary = SynchronizationSummary(),
                            error = TestError,
                        ),
                    ),
                )
            }

            assertFailsWith<IllegalArgumentException> {
                StrategyCacheInlineRefreshResult.Cancelled(
                    transportAttempted = true,
                    output = providerBacked(
                        SynchronizationResult.Cancelled(
                            request = request,
                            completedAt = completedAt,
                            summary = SynchronizationSummary(),
                        ),
                    ),
                )
            }
        }
    }

    private fun providerBacked(
        result: SynchronizationResult,
    ): StrategyTransportOutput.ProviderBacked =
        StrategyTransportOutput.ProviderBacked(result)

    private fun request(
        direction: SynchronizationDirection,
    ): SynchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("inline-refresh-direction-workflow"),
        sessionId = SynchronizationSessionId("inline-refresh-direction-session"),
        direction = direction,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("inline-refresh-direction-execution"),
            correlationId = CorrelationId("inline-refresh-direction-correlation"),
        ),
    )

    private object TestError : DataLoomError {
        override val code: ErrorCode = ErrorCode("INLINE_REFRESH_DIRECTION_TEST")
        override val category: ErrorCategory = ErrorCategory.STATE
        override val severity: ErrorSeverity = ErrorSeverity.ERROR
        override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE
        override val message: String = "Direction test failure."
        override val cause: Throwable? = null
    }

    private companion object {
        val completedAt: DataLoomInstant = DataLoomInstant(26_000L)
    }
}
