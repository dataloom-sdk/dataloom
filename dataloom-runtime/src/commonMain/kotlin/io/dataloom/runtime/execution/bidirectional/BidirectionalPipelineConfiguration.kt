package io.dataloom.runtime.execution.bidirectional

/**
 * Immutable configuration for [BidirectionalSynchronizationPipeline].
 *
 * ## Purpose
 *
 * [BidirectionalPipelineConfiguration] selects the sequential execution order
 * of the outbound push and inbound pull child pipelines within
 * [BidirectionalSynchronizationPipeline].
 *
 * ## Execution order
 *
 * [executionOrder] determines which direction runs first. The default is
 * [BidirectionalExecutionOrder.OUTBOUND_THEN_INBOUND], which pushes local
 * changes before pulling remote changes. Applications that require
 * server-first synchronization may supply
 * [BidirectionalExecutionOrder.INBOUND_THEN_OUTBOUND] explicitly.
 *
 * ## Immutability
 *
 * This is a value class. All properties are immutable. Equality is value-based.
 *
 * ## Construction restrictions
 *
 * Construction performs no pipeline execution, no clock read, and generates
 * no identifiers. It does not resolve or access any provider, registry,
 * coordinator, or scheduler.
 *
 * ## Scope
 *
 * This configuration covers execution order only. Retry, queue, scheduling,
 * concurrency, and provider-selection configuration are out of scope for
 * DL-023.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom runtime types only. Safe for use
 * in Kotlin Multiplatform common code.
 *
 * @param executionOrder the sequential order in which the outbound and inbound
 *   child pipelines are executed. Defaults to
 *   [BidirectionalExecutionOrder.OUTBOUND_THEN_INBOUND].
 */
public data class BidirectionalPipelineConfiguration(
    public val executionOrder: BidirectionalExecutionOrder =
        BidirectionalExecutionOrder.OUTBOUND_THEN_INBOUND,
)
