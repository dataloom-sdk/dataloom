package io.dataloom.testing.conflict

import io.dataloom.api.conflict.ConflictDetectionRequest
import io.dataloom.api.conflict.ConflictDetectionResult
import io.dataloom.api.conflict.ConflictDetector
import io.dataloom.api.identifier.ConflictDetectorId

/**
 * Script-driven [ConflictDetector] for deterministic tests.
 *
 * Results are dequeued in call order. When the script is exhausted, the
 * optional [fallback] is returned, otherwise detection fails fast.
 *
 * @param id stable conflict detector identifier.
 * @param results initial scripted results to dequeue in call order.
 * @param fallback optional fallback used after scripted results are exhausted.
 */
public class ScriptedConflictDetector(
    override val id: ConflictDetectorId,
    private val results: MutableList<ConflictDetectionResult> = mutableListOf(),
    private val fallback: ConflictDetectionResult? = null,
) : ConflictDetector {
    private val recordedRequests: MutableList<ConflictDetectionRequest> = mutableListOf()

    /** Recorded conflict-detection requests in call order. */
    public val detectionRequests: List<ConflictDetectionRequest>
        get() = recordedRequests.toList()

    /**
     * Appends a scripted detection result.
     *
     * @param result result to dequeue on a future [detect] call.
     */
    public fun enqueueResult(result: ConflictDetectionResult) {
        results += result
    }

    override fun detect(request: ConflictDetectionRequest): ConflictDetectionResult {
        recordedRequests += request
        return results.removeFirstOrNull()
            ?: fallback
            ?: throw IllegalStateException(
                "ScriptedConflictDetector: result script exhausted for detector ${id.value}.",
            )
    }

    /** Clears recorded requests without clearing scripted results. */
    public fun clearRecordings() {
        recordedRequests.clear()
    }

    /** Clears recorded requests and scripted results. */
    public fun resetState() {
        results.clear()
        clearRecordings()
    }
}

private fun <T> MutableList<T>.removeFirstOrNull(): T? = if (isEmpty()) null else removeAt(0)
