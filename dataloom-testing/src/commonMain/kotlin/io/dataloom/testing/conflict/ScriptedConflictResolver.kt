package io.dataloom.testing.conflict

import io.dataloom.api.conflict.ConflictResolutionDecision
import io.dataloom.api.conflict.ConflictResolutionRequest
import io.dataloom.api.conflict.ConflictResolver
import io.dataloom.api.identifier.ConflictResolverId

/**
 * Script-driven [ConflictResolver] for deterministic tests.
 *
 * Decisions are dequeued in call order. When the script is exhausted, the
 * optional [fallback] is returned, otherwise resolution fails fast.
 *
 * @param id stable conflict resolver identifier.
 * @param decisions initial scripted decisions to dequeue in call order.
 * @param fallback optional fallback used after scripted decisions are exhausted.
 */
public class ScriptedConflictResolver(
    override val id: ConflictResolverId,
    private val decisions: MutableList<ConflictResolutionDecision> = mutableListOf(),
    private val fallback: ConflictResolutionDecision? = null,
) : ConflictResolver {
    private val recordedRequests: MutableList<ConflictResolutionRequest> = mutableListOf()

    /** Recorded conflict-resolution requests in call order. */
    public val resolutionRequests: List<ConflictResolutionRequest>
        get() = recordedRequests.toList()

    /**
     * Appends a scripted resolution decision.
     *
     * @param decision decision to dequeue on a future [resolve] call.
     */
    public fun enqueueDecision(decision: ConflictResolutionDecision) {
        decisions += decision
    }

    override fun resolve(request: ConflictResolutionRequest): ConflictResolutionDecision {
        recordedRequests += request
        return decisions.removeFirstOrNull()
            ?: fallback
            ?: throw IllegalStateException(
                "ScriptedConflictResolver: decision script exhausted for resolver ${id.value}.",
            )
    }

    /** Clears recorded requests without clearing scripted decisions. */
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
