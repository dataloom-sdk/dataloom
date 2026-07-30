package io.dataloom.runtime.retry

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.RetryPolicyId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.retry.RetryDecision
import io.dataloom.api.retry.RetryEvaluationRequest
import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.retry.RetryStopReason
import io.dataloom.api.scheduling.SchedulingDelay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class StandardRetryJitterTest {

    private data class FakeError(
        override val code: ErrorCode = ErrorCode("DL-JITTER-TEST"),
        override val category: ErrorCategory = ErrorCategory.NETWORK,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Sanitized jitter test failure.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private class FixedRandomSource(
        private val value: Long,
    ) : RetryRandomSource {
        var callCount: Int = 0
        var capturedRequest: RetryRandomRequest? = null

        override fun sample(request: RetryRandomRequest): Long {
            callCount++
            capturedRequest = request
            return value
        }
    }

    private class ThrowingRandomSource : RetryRandomSource {
        override fun sample(request: RetryRandomRequest): Long =
            error("Random source should not be called.")
    }

    private val synchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("jitter-workflow"),
        sessionId = SynchronizationSessionId("jitter-session"),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("jitter-execution"),
            correlationId = CorrelationId("jitter-correlation"),
        ),
    )

    private fun evaluationRequest(
        attempt: Int = 1,
        error: DataLoomError = FakeError(),
        request: SynchronizationRequest = synchronizationRequest,
    ): RetryEvaluationRequest = RetryEvaluationRequest(
        synchronizationRequest = request,
        operation = RetryOperation("transport.push"),
        error = error,
        attempt = RetryAttempt(attempt),
        previousDelay = null,
        provider = null,
    )

    @Test
    fun `legacy constructor preserves exact base delay with no jitter`() {
        val policy = StandardRetryPolicy(
            id = RetryPolicyId("jitter-none"),
            strategy = RetryBackoffStrategy.Fixed(SchedulingDelay(200L)),
            maximumAttempts = 2,
        )

        assertEquals(RetryJitterStrategy.None, policy.jitterStrategy)
        assertRetryDelay(policy, expectedMilliseconds = 200L)
    }

    @Test
    fun `full jitter supports zero and inclusive base-delay boundaries`() {
        val zeroSource = FixedRandomSource(0L)
        val maximumSource = FixedRandomSource(200L)

        val zeroPolicy = jitteredFixedPolicy(
            id = "jitter-full-zero",
            delay = 200L,
            jitter = RetryJitterStrategy.Full,
            source = zeroSource,
        )
        val maximumPolicy = jitteredFixedPolicy(
            id = "jitter-full-maximum",
            delay = 200L,
            jitter = RetryJitterStrategy.Full,
            source = maximumSource,
        )

        assertRetryDelay(zeroPolicy, expectedMilliseconds = 0L)
        assertRetryDelay(maximumPolicy, expectedMilliseconds = 200L)
        assertEquals(200L, zeroSource.capturedRequest?.maximumInclusive)
        assertEquals(200L, maximumSource.capturedRequest?.maximumInclusive)
    }

    @Test
    fun `equal jitter uses ceil half through inclusive base delay`() {
        val lowerSource = FixedRandomSource(0L)
        val upperSource = FixedRandomSource(50L)

        val lowerPolicy = jitteredFixedPolicy(
            id = "jitter-equal-lower",
            delay = 101L,
            jitter = RetryJitterStrategy.Equal,
            source = lowerSource,
        )
        val upperPolicy = jitteredFixedPolicy(
            id = "jitter-equal-upper",
            delay = 101L,
            jitter = RetryJitterStrategy.Equal,
            source = upperSource,
        )

        assertRetryDelay(lowerPolicy, expectedMilliseconds = 51L)
        assertRetryDelay(upperPolicy, expectedMilliseconds = 101L)
        assertEquals(50L, lowerSource.capturedRequest?.maximumInclusive)
        assertEquals(50L, upperSource.capturedRequest?.maximumInclusive)
    }

    @Test
    fun `equal jitter is overflow safe at Long MAX_VALUE`() {
        val source = FixedRandomSource(Long.MAX_VALUE / 2L)
        val policy = jitteredFixedPolicy(
            id = "jitter-equal-long-max",
            delay = Long.MAX_VALUE,
            jitter = RetryJitterStrategy.Equal,
            source = source,
        )

        assertRetryDelay(policy, expectedMilliseconds = Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE / 2L, source.capturedRequest?.maximumInclusive)
    }

    @Test
    fun `zero base delay bypasses random source`() {
        val policy = StandardRetryPolicy(
            id = RetryPolicyId("jitter-zero-base"),
            strategy = RetryBackoffStrategy.Immediate,
            maximumAttempts = 2,
            jitterStrategy = RetryJitterStrategy.Full,
            randomSource = ThrowingRandomSource(),
        )

        assertRetryDelay(policy, expectedMilliseconds = 0L)
    }

    @Test
    fun `equal jitter one millisecond has no random window`() {
        val source = FixedRandomSource(1L)
        val policy = jitteredFixedPolicy(
            id = "jitter-equal-one",
            delay = 1L,
            jitter = RetryJitterStrategy.Equal,
            source = source,
        )

        assertRetryDelay(policy, expectedMilliseconds = 1L)
        assertEquals(0, source.callCount)
    }

    @Test
    fun `jitter is applied after exponential maximum clamp`() {
        val source = FixedRandomSource(400L)
        val policy = StandardRetryPolicy(
            id = RetryPolicyId("jitter-after-clamp"),
            strategy = RetryBackoffStrategy.Exponential(
                initialDelay = SchedulingDelay(500L),
                multiplier = 4,
                maximumDelay = SchedulingDelay(1_000L),
            ),
            maximumAttempts = 4,
            jitterStrategy = RetryJitterStrategy.Full,
            randomSource = source,
        )

        assertRetryDelay(
            policy = policy,
            attempt = 4,
            expectedMilliseconds = 400L,
        )
        assertEquals(1_000L, source.capturedRequest?.maximumInclusive)
    }

    @Test
    fun `random request carries only stable bounded identity`() {
        val source = FixedRandomSource(5L)
        val policyId = RetryPolicyId("jitter-capture")
        val policy = StandardRetryPolicy(
            id = policyId,
            strategy = RetryBackoffStrategy.Fixed(SchedulingDelay(10L)),
            maximumAttempts = 3,
            jitterStrategy = RetryJitterStrategy.Full,
            randomSource = source,
        )
        val error = FakeError(code = ErrorCode("DL-JITTER-CAPTURE"))

        policy.evaluate(evaluationRequest(attempt = 2, error = error))

        val captured = requireNotNull(source.capturedRequest)
        assertEquals(policyId, captured.policyId)
        assertEquals(synchronizationRequest.workflowId, captured.workflowId)
        assertEquals(synchronizationRequest.sessionId, captured.sessionId)
        assertEquals(RetryOperation("transport.push"), captured.operation)
        assertEquals(error.code, captured.errorCode)
        assertEquals(RetryAttempt(2), captured.attempt)
        assertEquals(10L, captured.maximumInclusive)
    }

    @Test
    fun `out of range random source fails instead of clamping`() {
        val policy = jitteredFixedPolicy(
            id = "jitter-invalid-source",
            delay = 100L,
            jitter = RetryJitterStrategy.Full,
            source = FixedRandomSource(101L),
        )

        assertFailsWith<IllegalStateException> {
            policy.evaluate(evaluationRequest())
        }
    }

    @Test
    fun `attempt exhaustion bypasses random source`() {
        val policy = StandardRetryPolicy(
            id = RetryPolicyId("jitter-attempt-exhausted"),
            strategy = RetryBackoffStrategy.Fixed(SchedulingDelay(100L)),
            maximumAttempts = 1,
            jitterStrategy = RetryJitterStrategy.Full,
            randomSource = ThrowingRandomSource(),
        )

        val stopped = assertIs<RetryDecision.Stop>(
            policy.evaluate(evaluationRequest(attempt = 2)),
        )
        assertEquals(RetryStopReason.ATTEMPT_LIMIT_REACHED, stopped.reason)
    }

    @Test
    fun `protected error bypasses random source`() {
        val policy = StandardRetryPolicy(
            id = RetryPolicyId("jitter-protected"),
            strategy = RetryBackoffStrategy.Fixed(SchedulingDelay(100L)),
            maximumAttempts = 2,
            jitterStrategy = RetryJitterStrategy.Full,
            randomSource = ThrowingRandomSource(),
        )
        val protectedError = FakeError(
            category = ErrorCategory.AUTHENTICATION,
            recoverability = Recoverability.RECOVERABLE,
        )

        val stopped = assertIs<RetryDecision.Stop>(
            policy.evaluate(evaluationRequest(error = protectedError)),
        )
        assertEquals(RetryStopReason.POLICY_REJECTED, stopped.reason)
    }

    @Test
    fun `seeded random source is reproducible across instances`() {
        val request = RetryRandomRequest(
            policyId = RetryPolicyId("seeded-source"),
            workflowId = WorkflowId("seeded-workflow"),
            sessionId = SynchronizationSessionId("seeded-session"),
            operation = RetryOperation("transport.pull"),
            errorCode = ErrorCode("DL-SEEDED"),
            attempt = RetryAttempt(3),
            maximumInclusive = Long.MAX_VALUE,
        )

        val first = SeededRetryRandomSource(seed = 42L).sample(request)
        val second = SeededRetryRandomSource(seed = 42L).sample(request)

        assertEquals(first, second)
        assertEquals(true, first in 0L..Long.MAX_VALUE)
    }

    @Test
    fun `seeded random source varies stable identity dimensions`() {
        val source = SeededRetryRandomSource(seed = 77L)
        val request = RetryRandomRequest(
            policyId = RetryPolicyId("seeded-dimensions"),
            workflowId = WorkflowId("workflow-one"),
            sessionId = SynchronizationSessionId("session-one"),
            operation = RetryOperation("transport.push"),
            errorCode = ErrorCode("DL-DIMENSIONS"),
            attempt = RetryAttempt(1),
            maximumInclusive = Long.MAX_VALUE,
        )
        val original = source.sample(request)

        assertNotEquals(
            original,
            source.sample(request.copy(sessionId = SynchronizationSessionId("session-two"))),
        )
        assertNotEquals(
            original,
            source.sample(request.copy(attempt = RetryAttempt(2))),
        )
    }

    @Test
    fun `random request rejects negative upper bound`() {
        assertFailsWith<IllegalArgumentException> {
            RetryRandomRequest(
                policyId = RetryPolicyId("negative-bound"),
                workflowId = WorkflowId("workflow"),
                sessionId = SynchronizationSessionId("session"),
                operation = RetryOperation("transport.push"),
                errorCode = ErrorCode("DL-NEGATIVE"),
                attempt = RetryAttempt(1),
                maximumInclusive = -1L,
            )
        }
    }

    private fun jitteredFixedPolicy(
        id: String,
        delay: Long,
        jitter: RetryJitterStrategy,
        source: RetryRandomSource,
    ): StandardRetryPolicy = StandardRetryPolicy(
        id = RetryPolicyId(id),
        strategy = RetryBackoffStrategy.Fixed(SchedulingDelay(delay)),
        maximumAttempts = 3,
        jitterStrategy = jitter,
        randomSource = source,
    )

    private fun assertRetryDelay(
        policy: StandardRetryPolicy,
        attempt: Int = 1,
        expectedMilliseconds: Long,
    ) {
        val decision = assertIs<RetryDecision.Retry>(
            policy.evaluate(evaluationRequest(attempt = attempt)),
        )
        assertEquals(expectedMilliseconds, decision.delay.milliseconds)
    }
}
