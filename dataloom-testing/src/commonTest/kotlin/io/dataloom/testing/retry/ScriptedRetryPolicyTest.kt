package io.dataloom.testing.retry

import io.dataloom.api.retry.RetryDecision
import io.dataloom.api.retry.RetryStopReason
import io.dataloom.testing.retryPolicyId
import io.dataloom.testing.sampleRetryDecisionRetry
import io.dataloom.testing.sampleRetryDecisionStop
import io.dataloom.testing.sampleRetryEvaluationRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScriptedRetryPolicyTest {
    @Test
    fun `returns scripted decisions in order`() {
        val policy = ScriptedRetryPolicy(id = retryPolicyId())
        val first = sampleRetryDecisionRetry(1_000L)
        val second = sampleRetryDecisionStop(RetryStopReason.POLICY_REJECTED)
        policy.enqueueDecision(first)
        policy.enqueueDecision(second)
        assertEquals(first, policy.evaluate(sampleRetryEvaluationRequest("001")))
        assertEquals(second, policy.evaluate(sampleRetryEvaluationRequest("002")))
    }

    @Test
    fun `constructor decisions are used before fallback`() {
        val first = sampleRetryDecisionRetry(1_000L)
        val fallback = sampleRetryDecisionStop()
        val policy = ScriptedRetryPolicy(
            id = retryPolicyId(),
            decisions = mutableListOf(first),
            fallback = fallback,
        )
        assertEquals(first, policy.evaluate(sampleRetryEvaluationRequest("001")))
        assertEquals(fallback, policy.evaluate(sampleRetryEvaluationRequest("002")))
    }

    @Test
    fun `fallback is returned when script is exhausted`() {
        val fallback = sampleRetryDecisionStop(RetryStopReason.ATTEMPT_LIMIT_REACHED)
        val policy = ScriptedRetryPolicy(id = retryPolicyId(), fallback = fallback)
        assertEquals(fallback, policy.evaluate(sampleRetryEvaluationRequest()))
    }

    @Test
    fun `script exhaustion without fallback throws informative exception`() {
        val policy = ScriptedRetryPolicy(id = retryPolicyId("policy-empty"))
        val error = assertFailsWith<IllegalStateException> {
            policy.evaluate(sampleRetryEvaluationRequest())
        }
        assertEquals(true, error.message.orEmpty().contains("policy-empty"))
    }

    @Test
    fun `records evaluation requests`() {
        val policy = ScriptedRetryPolicy(id = retryPolicyId(), fallback = sampleRetryDecisionStop())
        val first = sampleRetryEvaluationRequest("001")
        val second = sampleRetryEvaluationRequest("002")
        policy.evaluate(first)
        policy.evaluate(second)
        assertEquals(listOf(first, second), policy.evaluationRequests)
    }

    @Test
    fun `clear recordings preserves scripted decisions`() {
        val policy = ScriptedRetryPolicy(id = retryPolicyId())
        val decision = sampleRetryDecisionRetry(1_000L)
        policy.enqueueDecision(decision)
        policy.clearRecordings()
        assertEquals(decision, policy.evaluate(sampleRetryEvaluationRequest()))
    }

    @Test
    fun `clear recordings empties request log`() {
        val policy = ScriptedRetryPolicy(id = retryPolicyId(), fallback = sampleRetryDecisionStop())
        policy.evaluate(sampleRetryEvaluationRequest())
        policy.clearRecordings()
        assertEquals(emptyList(), policy.evaluationRequests)
    }

    @Test
    fun `reset state clears decisions and recordings`() {
        val policy = ScriptedRetryPolicy(id = retryPolicyId())
        policy.enqueueDecision(sampleRetryDecisionRetry())
        policy.evaluate(sampleRetryEvaluationRequest())
        policy.resetState()
        assertEquals(emptyList(), policy.evaluationRequests)
        assertFailsWith<IllegalStateException> { policy.evaluate(sampleRetryEvaluationRequest("again")) }
    }

    @Test
    fun `id is exposed unchanged`() {
        val id = retryPolicyId("policy-123")
        val policy = ScriptedRetryPolicy(id = id, fallback = sampleRetryDecisionStop())
        assertEquals(id, policy.id)
    }

    @Test
    fun `fallback can be retry decision`() {
        val fallback = sampleRetryDecisionRetry(5_000L)
        val policy = ScriptedRetryPolicy(id = retryPolicyId(), fallback = fallback)
        assertEquals(fallback, policy.evaluate(sampleRetryEvaluationRequest()))
    }

    @Test
    fun `enqueue decision appends behind constructor decisions`() {
        val first = sampleRetryDecisionRetry(1_000L)
        val second = sampleRetryDecisionStop()
        val policy = ScriptedRetryPolicy(id = retryPolicyId(), decisions = mutableListOf(first))
        policy.enqueueDecision(second)
        assertEquals(first, policy.evaluate(sampleRetryEvaluationRequest("001")))
        assertEquals(second, policy.evaluate(sampleRetryEvaluationRequest("002")))
    }

    @Test
    fun `requests are recorded before fallback is used`() {
        val fallback = sampleRetryDecisionStop()
        val policy = ScriptedRetryPolicy(id = retryPolicyId(), fallback = fallback)
        val request = sampleRetryEvaluationRequest()
        policy.evaluate(request)
        assertEquals(listOf(request), policy.evaluationRequests)
    }

    @Test
    fun `requests are recorded before exhaustion failure`() {
        val policy = ScriptedRetryPolicy(id = retryPolicyId())
        val request = sampleRetryEvaluationRequest()
        assertFailsWith<IllegalStateException> { policy.evaluate(request) }
        assertEquals(listOf(request), policy.evaluationRequests)
    }

    @Test
    fun `supports stop decisions with explicit reason`() {
        val stop = RetryDecision.Stop(reason = RetryStopReason.NON_RECOVERABLE)
        val policy = ScriptedRetryPolicy(id = retryPolicyId(), decisions = mutableListOf(stop))
        assertEquals(stop, policy.evaluate(sampleRetryEvaluationRequest()))
    }

    @Test
    fun `supports retry decisions with zero delay`() {
        val retry = RetryDecision.Retry(delay = io.dataloom.api.scheduling.SchedulingDelay.ZERO)
        val policy = ScriptedRetryPolicy(id = retryPolicyId(), decisions = mutableListOf(retry))
        assertEquals(retry, policy.evaluate(sampleRetryEvaluationRequest()))
    }
}
