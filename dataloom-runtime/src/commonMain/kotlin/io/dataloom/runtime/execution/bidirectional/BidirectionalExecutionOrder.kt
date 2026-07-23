package io.dataloom.runtime.execution.bidirectional

/**
 * Configures the sequential execution order of outbound and inbound pipelines
 * inside [BidirectionalSynchronizationPipeline].
 *
 * ## Determinism
 *
 * The order is expressed by name only. Do not persist or compare enum ordinals.
 * Do not infer the order from pipeline registration order, provider type, or
 * any other implicit signal.
 *
 * ## Default
 *
 * The default order is [OUTBOUND_THEN_INBOUND]. Pushing local changes before
 * pulling remote changes reduces the chance that an immediately following
 * inbound pull overwrites or conflicts with unsent local work.
 *
 * Applications that require server-first synchronization may explicitly
 * configure [INBOUND_THEN_OUTBOUND].
 *
 * ## Construction
 *
 * Selecting an execution order performs no synchronization work, no clock
 * read, and no provider operation.
 */
public enum class BidirectionalExecutionOrder {

    /**
     * Execute the outbound push pipeline first, then the inbound pull
     * pipeline.
     *
     * This is the default order. Pushing local changes before pulling remote
     * changes reduces the chance that an immediately following inbound pull
     * overwrites or conflicts with unsent local work.
     */
    OUTBOUND_THEN_INBOUND,

    /**
     * Execute the inbound pull pipeline first, then the outbound push
     * pipeline.
     *
     * Use this order when the application requires server-authoritative
     * synchronization or when receiving the latest remote state before
     * sending local changes is necessary for correct conflict handling.
     */
    INBOUND_THEN_OUTBOUND,
}
