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

class StandardRetryPolicyTest {

    private data class FakeError(
        override val code: ErrorCode = ErrorCode("DL-RETRY-TEST"),
        override val category: ErrorCategory = ErrorCategory.NETWORK,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Sanitized retry test failure.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private val synchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("retry-policy-workflow"),
        sessionId = SynchronizationSessionId("retry-policy-session"),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("retry-policy-execution"),
            correlationId = CorrelationId("retry-policy-correlation"),
        ),
    )

    private fun evaluationRequest(
        attempt: Int,
        error: DataLoomError = FakeError(),
    ): RetryEvaluationRequest = RetryEvaluationRequest(
        synchronizationRequest = synchronizationRequest,
        operation = RetryOperation("transport.push"),
        error = error,
        attempt = RetryAttempt(attempt),
        previousDelay = null,
        provider = null,
    )

    @Test
    fun `policy exposes exact immutable configuration`() {
        val id = RetryPolicyId("standard-properties")
        val strategy = RetryBackoffStrategy.Fixed(SchedulingDelay(50L))
        val policy = StandardRetryPolicy(
            id = id,
            strategy = strategy,
            maximumAttempts = 4,
        )

        assertEquals(id, policy.id)
        assertEquals(strategy, policy.strategy)
        assertEquals(4, policy.maximumAttempts)
    }

    @Test
    fun `immediate strategy returns zero delay`() {
        val policy = StandardRetryPolicy(
            id = RetryPolicyId("standard-immediate"),
            strategy = RetryBackoffStrategy.Immediate,
            maximumAttempts = 3,
        )

        val decision = assertIs<RetryDecision.Retry>(
            policy.evaluate(evaluationRequest(attempt = 1)),
        )

        assertEquals(SchedulingDelay.ZERO, decision.delay)
    }

    @Test
    fun `fixed strategy returns configured delay for every allowed attempt`() {
        val delay = SchedulingDelay(2_500L)
        val policy = StandardRetryPolicy(
            id = RetryPolicyId("standard-fixed"),
            strategy = RetryBackoffStrategy.Fixed(delay),
            maximumAttempts = 3,
        )

        assertEquals(
            delay,
            assertIs<RetryDecision.Retry>(policy.evaluate(evaluationRequest(1))).delay,
        )
        assertEquals(
            delay,
            assertIs<RetryDecision.Retry>(policy.evaluate(evaluationRequest(3))).delay,
        )
    }

    @Test
    fun `linear strategy uses attempt one as initial and clamps at maximum`() {
        val policy = StandardRetryPolicy(
            id = RetryPolicyId("standard-linear"),
            strategy = RetryBackoffStrategy.Linear(
                initialDelay = SchedulingDelay(1_000L),
                increment = SchedulingDelay(750L),
                maximumDelay = SchedulingDelay(2_000L),
            ),
            maximumAttempts = 5,
        )

        assertRetryDelay(policy, attempt = 1, expectedMilliseconds = 1_000L)
        assertRetryDelay(policy, attempt = 2, expectedMilliseconds = 1_750L)
        assertRetryDelay(policy, attempt = 3, expectedMilliseconds = 2_000L)
        assertRetryDelay(policy, attempt = 5, expectedMilliseconds = 2_000L)
    }

    @Test
    fun `linear zero increment remains at initial delay`() {
        val policy = StandardRetryPolicy(
            id = RetryPolicyId("standard-linear-zero-increment"),
            strategy = RetryBackoffStrategy.Linear(
                initialDelay = SchedulingDelay(123L),
                increment = SchedulingDelay.ZERO,
                maximumDelay = SchedulingDelay(Long.MAX_VALUE),
            ),
            maximumAttempts = Int.MAX_VALUE,
        )

        assertRetryDelay(policy, attempt = Int.MAX_VALUE, expectedMilliseconds = 123L)
    }

    @Test
    fun `linear strategy clamps before multiplication can overflow`() {
        val policy = StandardRetryPolicy(
            id = RetryPolicyId("standard-linear-overflow"),
            strategy = RetryBackoffStrategy.Linear(
                initialDelay = SchedulingDelay(Long.MAX_VALUE - 2L),
                increment = SchedulingDelay(10L),
                maximumDelay = SchedulingDelay(Long.MAX_VALUE),
            ),
            maximumAttempts = Int.MAX_VALUE,
        )

        assertRetryDelay(policy, attempt = 2, expectedMilliseconds = Long.MAX_VALUE)
        assertRetryDelay(
            policy,
            attempt = Int.MAX_VALUE,
            expectedMilliseconds = Long.MAX_VALUE,
        )
    }

    @Test
    fun `exponential strategy multiplies from initial and clamps at maximum`() {
        val policy = StandardRetryPolicy(
            id = RetryPolicyId("standard-exponential"),
            strategy = RetryBackoffStrategy.Exponential(
                initialDelay = SchedulingDelay(250L),
                multiplier = 2,
                maximumDelay = SchedulingDelay(1_500L),
            ),
            maximumAttempts = 6,
        )

        assertRetryDelay(policy, attempt = 1, expectedMilliseconds = 250L)
        assertRetryDelay(policy, attempt = 2, expectedMilliseconds = 500L)
        assertRetryDelay(policy, attempt = 3, expectedMilliseconds = 1_000L)
        assertRetryDelay(policy, attempt = 4, expectedMilliseconds = 1_500L)
        assertRetryDelay(policy, attempt = 6, expectedMilliseconds = 1_500L)
    }

    @Test
    fun `exponential strategy clamps before multiplication can overflow`() {
        val policy = StandardRetryPolicy(
            id = RetryPolicyId("standard-exponential-overflow"),
            strategy = RetryBackoffStrategy.Exponential(
                initialDelay = SchedulingDelay((Long.MAX_VALUE / 2L) + 1L),
                multiplier = 2,
                maximumDelay = SchedulingDelay(Long.MAX_VALUE),
            ),
            maximumAttempts = Int.MAX_VALUE,
        )

        assertRetryDelay(policy, attempt = 2, expectedMilliseconds = Long.MAX_VALUE)
        assertRetryDelay(
            policy,
            attempt = Int.MAX_VALUE,
            expectedMilliseconds = Long.MAX_VALUE,
        )
    }

    @Test
    fun `zero initial exponential delay remains zero for maximum attempt`() {
        val policy = StandardRetryPolicy(
            id = RetryPolicyId("standard-exponential-zero"),
            strategy = RetryBackoffStrategy.Exponential(
                initialDelay = SchedulingDelay.ZERO,
                multiplier = Int.MAX_VALUE,
                maximumDelay = SchedulingDelay(Long.MAX_VALUE),
            ),
            maximumAttempts = Int.MAX_VALUE,
        )

        assertRetryDelay(policy, attempt = Int.MAX_VALUE, expectedMilliseconds = 0L)
    }

    @Test
    fun `attempt at budget is allowed and attempt beyond budget stops`() {
        val policy = StandardRetryPolicy(
            id = RetryPolicyId("standard-budget"),
            strategy = RetryBackoffStrategy.Immediate,
            maximumAttempts = 2,
        )

        assertIs<RetryDecision.Retry>(policy.evaluate(evaluationRequest(2)))
        val stopped = assertIs<RetryDecision.Stop>(policy.evaluate(evaluationRequest(3)))
        assertEquals(RetryStopReason.ATTEMPT_LIMIT_REACHED, stopped.reason)
    }

    @Test
    fun `zero maximum attempts disables retry`() {
        val policy = StandardRetryPolicy(
            id = RetryPolicyId("standard-disabled"),
            strategy = RetryBackoffStrategy.Immediate,
            maximumAttempts = 0,
        )

        val stopped = assertIs<RetryDecision.Stop>(policy.evaluate(evaluationRequest(1)))
        assertEquals(RetryStopReason.ATTEMPT_LIMIT_REACHED, stopped.reason)
    }

    @Test
    fun `same request and configuration return identical decision`() {
        val policy = StandardRetryPolicy(
            id = RetryPolicyId("standard-deterministic"),
            strategy = RetryBackoffStrategy.Exponential(
                initialDelay = SchedulingDelay(100L),
                multiplier = 3,
                maximumDelay = SchedulingDelay(10_000L),
            ),
            maximumAttempts = 10,
        )
        val request = evaluationRequest(attempt = 4)

        assertEquals(policy.evaluate(request), policy.evaluate(request))
    }

    @Test
    fun `non recoverable error is centrally stopped`() {
        val policy = StandardRetryPolicy(
            id = RetryPolicyId("standard-non-recoverable"),
            strategy = RetryBackoffStrategy.Immediate,
            maximumAttempts = 3,
        )
        val error = FakeError(recoverability = Recoverability.NON_RECOVERABLE)

        val stopped = assertIs<RetryDecision.Stop>(
            policy.evaluate(evaluationRequest(1, error)),
        )

        assertEquals(RetryStopReason.NON_RECOVERABLE, stopped.reason)
    }

    @Test
    fun `unknown recoverability fails closed`() {
        val policy = StandardRetryPolicy(
            id = RetryPolicyId("standard-unknown"),
            strategy = RetryBackoffStrategy.Immediate,
            maximumAttempts = 3,
        )
        val error = FakeError(recoverability = Recoverability.UNKNOWN)

        val stopped = assertIs<RetryDecision.Stop>(
            policy.evaluate(evaluationRequest(1, error)),
        )

        assertEquals(RetryStopReason.POLICY_REJECTED, stopped.reason)
    }

    @Test
    fun `protected category fails closed even when marked recoverable`() {
        val policy = StandardRetryPolicy(
            id = RetryPolicyId("standard-protected"),
            strategy = RetryBackoffStrategy.Immediate,
            maximumAttempts = 3,
        )
        val error = FakeError(
            category = ErrorCategory.AUTHENTICATION,
            recoverability = Recoverability.RECOVERABLE,
        )

        val stopped = assertIs<RetryDecision.Stop>(
            policy.evaluate(evaluationRequest(1, error)),
        )

        assertEquals(RetryStopReason.POLICY_REJECTED, stopped.reason)
    }

    @Test
    fun `invalid policy and strategy configuration is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            StandardRetryPolicy(
                id = RetryPolicyId("negative-budget"),
                strategy = RetryBackoffStrategy.Immediate,
                maximumAttempts = -1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RetryBackoffStrategy.Linear(
                initialDelay = SchedulingDelay(2L),
                increment = SchedulingDelay(1L),
                maximumDelay = SchedulingDelay(1L),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RetryBackoffStrategy.Exponential(
                initialDelay = SchedulingDelay(1L),
                multiplier = 1,
                maximumDelay = SchedulingDelay(2L),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RetryBackoffStrategy.Exponential(
                initialDelay = SchedulingDelay(2L),
                multiplier = 2,
                maximumDelay = SchedulingDelay(1L),
            )
        }
    }

    private fun assertRetryDelay(
        policy: StandardRetryPolicy,
        attempt: Int,
        expectedMilliseconds: Long,
    ) {
        val decision = assertIs<RetryDecision.Retry>(
            policy.evaluate(evaluationRequest(attempt)),
        )
        assertEquals(expectedMilliseconds, decision.delay.milliseconds)
    }
}
