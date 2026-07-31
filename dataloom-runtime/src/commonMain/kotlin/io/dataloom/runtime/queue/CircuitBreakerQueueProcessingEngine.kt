package io.dataloom.runtime.queue

/**
 * Narrow execution boundary for one circuit-aware bounded queue-processing
 * cycle.
 *
 * The production implementation is
 * [CircuitBreakerDurableQueueExecutionProcessor]. The interface allows worker
 * coordination to depend on the exact enriched result contract without
 * exposing queue-provider or circuit internals.
 */
internal fun interface CircuitBreakerQueueProcessingEngine {

    /** Executes one bounded acquisition and transition cycle. */
    public suspend fun process(
        request: QueueProcessingRequest,
    ): CircuitBreakerQueueProcessingResult
}
