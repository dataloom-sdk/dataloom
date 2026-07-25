package io.dataloom.testing.retry

import io.dataloom.api.identifier.RetryPolicyId
import io.dataloom.api.retry.RetryDecision
import io.dataloom.api.retry.RetryEvaluationRequest
import io.dataloom.api.retry.RetryPolicy

/**
 * Script-driven [RetryPolicy] for deterministic tests.
 *
 * Decisions are dequeued in call order. When the script is exhausted, the
 * optional [fallback] is returned, otherwise evaluation fails fast.
 *
 * @param id stable retry policy identifier.
 * @param decisions initial scripted decisions to dequeue in call order.
 * @param fallback optional fallback used after scripted decisions are exhausted.
 */
public class ScriptedRetryPolicy(
    override val id: RetryPolicyId,
    private val decisions: MutableList<RetryDecision> = mutableListOf(),
    private val fallback: RetryDecision? = null,
) : RetryPolicy {
    private val recordedRequests: MutableList<RetryEvaluationRequest> = mutableListOf()

    /** Recorded retry-evaluation requests in call order. */
    public val evaluationRequests: List<RetryEvaluationRequest>
        get() = recordedRequests.toList()

    /**
     * Appends a scripted decision to the evaluation queue.
     *
     * @param decision decision to dequeue on a future [evaluate] call.
     */
    public fun enqueueDecision(decision: RetryDecision) {
        decisions += decision
    }

    override fun evaluate(request: RetryEvaluationRequest): RetryDecision {
        recordedRequests += request
        return decisions.removeFirstOrNull()
            ?: fallback
            ?: throw IllegalStateException(
                "ScriptedRetryPolicy: decision script exhausted for policy ${id.value}.",
            )
    }

    /** Clears recorded evaluation requests without clearing the scripted decisions. */
    public fun clearRecordings() {
        recordedRequests.clear()
    }

    /** Clears recorded requests and scripted decisions. */
    public fun resetState() {
        decisions.clear()
        clearRecordings()
    }
}

private fun <T> MutableList<T>.removeFirstOrNull(): T? = if (isEmpty()) null else removeAt(0)
