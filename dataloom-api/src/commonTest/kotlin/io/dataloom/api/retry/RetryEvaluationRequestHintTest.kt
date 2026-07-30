package io.dataloom.api.retry

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.error.RetryDelayHint
import io.dataloom.api.error.RetryDelayHintSource
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RetryEvaluationRequestHintTest {

    private data class TestError(
        override val code: ErrorCode = ErrorCode("DL-HINT-REQUEST"),
        override val category: ErrorCategory = ErrorCategory.NETWORK,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Sanitized retry request failure.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private val synchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("hint-request-workflow"),
        sessionId = SynchronizationSessionId("hint-request-session"),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("hint-request-execution"),
            correlationId = CorrelationId("hint-request-correlation"),
        ),
    )

    @Test
    fun `retry delay hint defaults to null`() {
        val request = request(retryDelayHint = null)
        assertNull(request.retryDelayHint)
    }

    @Test
    fun `retry delay hint is preserved by value`() {
        val hint = RetryDelayHint(
            delayMilliseconds = 5_000L,
            source = RetryDelayHintSource.SERVER,
        )
        val request = request(retryDelayHint = hint)

        assertEquals(hint, request.retryDelayHint)
        assertEquals(hint, request.copy().retryDelayHint)
    }

    private fun request(retryDelayHint: RetryDelayHint?): RetryEvaluationRequest =
        RetryEvaluationRequest(
            synchronizationRequest = synchronizationRequest,
            operation = RetryOperation("transport.push"),
            error = TestError(),
            attempt = RetryAttempt(1),
            previousDelay = null,
            provider = null,
            retryDelayHint = retryDelayHint,
        )
}
