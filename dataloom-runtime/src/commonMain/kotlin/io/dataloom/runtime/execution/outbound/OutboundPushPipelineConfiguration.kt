package io.dataloom.runtime.execution.outbound

import io.dataloom.api.identifier.EntityType

/**
 * Immutable configuration for [OutboundPushSynchronizationPipeline].
 *
 * ## Purpose
 *
 * [OutboundPushPipelineConfiguration] bounds a single outbound push pipeline
 * execution. It selects the entity types read from storage and limits both
 * the size of each batch and the number of batches attempted during one
 * [OutboundPushSynchronizationPipeline.execute] call.
 *
 * ## Entity types
 *
 * The supplied [entityTypes] set is defensively copied at construction time
 * and exposed as a read-only snapshot. An empty set means that all supported
 * entity types are eligible; it is not interpreted as "no entity types".
 *
 * ## Batch limits
 *
 * [maxEventsPerBatch] bounds the number of events requested from storage for
 * a single [io.dataloom.api.storage.OutboundChangeReadRequest]. It must be
 * greater than zero.
 *
 * [maxBatchesPerExecution] bounds the number of batches
 * [OutboundPushSynchronizationPipeline] attempts during a single `execute`
 * call. It must be greater than zero.
 *
 * The conceptual maximum number of events attempted during one execution is
 * bounded by `maxEventsPerBatch × maxBatchesPerExecution`. This configuration
 * does not allocate any collection sized by that product; batches are
 * processed and released one at a time.
 *
 * ## Construction restrictions
 *
 * Construction performs no storage operation, no transport operation, no
 * clock read, and generates no identifiers.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 *
 * @param entityTypes optional restriction on the entity types read from
 *   storage. Defaults to an empty set, meaning all supported entity types are
 *   eligible. The supplied set is defensively copied.
 * @param maxEventsPerBatch maximum number of events requested per storage
 *   read. Must be greater than zero. Defaults to `100`.
 * @param maxBatchesPerExecution maximum number of batches attempted during a
 *   single pipeline execution. Must be greater than zero. Defaults to `100`.
 * @throws IllegalArgumentException when [maxEventsPerBatch] or
 *   [maxBatchesPerExecution] is zero or negative.
 */
public class OutboundPushPipelineConfiguration(
    entityTypes: Set<EntityType> = emptySet(),
    public val maxEventsPerBatch: Int = 100,
    public val maxBatchesPerExecution: Int = 100,
) {

    init {
        require(maxEventsPerBatch > 0) {
            "OutboundPushPipelineConfiguration maxEventsPerBatch must be greater than zero, " +
                "but was $maxEventsPerBatch."
        }
        require(maxBatchesPerExecution > 0) {
            "OutboundPushPipelineConfiguration maxBatchesPerExecution must be greater than zero, " +
                "but was $maxBatchesPerExecution."
        }
    }

    private val entityTypesSnapshot: Set<EntityType> = entityTypes.toSet()

    /**
     * Immutable snapshot of the configured entity-type restriction.
     *
     * An empty set means that all supported entity types are eligible for
     * outbound read.
     */
    public val entityTypes: Set<EntityType>
        get() = entityTypesSnapshot.toSet()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OutboundPushPipelineConfiguration) return false
        return entityTypesSnapshot == other.entityTypesSnapshot &&
            maxEventsPerBatch == other.maxEventsPerBatch &&
            maxBatchesPerExecution == other.maxBatchesPerExecution
    }

    override fun hashCode(): Int {
        var result: Int = entityTypesSnapshot.hashCode()
        result = 31 * result + maxEventsPerBatch
        result = 31 * result + maxBatchesPerExecution
        return result
    }

    override fun toString(): String =
        "OutboundPushPipelineConfiguration(" +
            "entityTypes=$entityTypesSnapshot, " +
            "maxEventsPerBatch=$maxEventsPerBatch, " +
            "maxBatchesPerExecution=$maxBatchesPerExecution" +
            ")"
}
