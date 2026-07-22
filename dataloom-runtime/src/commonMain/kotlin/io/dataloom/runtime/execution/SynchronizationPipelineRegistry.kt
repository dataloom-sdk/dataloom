package io.dataloom.runtime.execution

import io.dataloom.api.model.SynchronizationDirection

/**
 * Immutable registry of [SynchronizationPipeline] instances, keyed by
 * [SynchronizationDirection].
 *
 * ## Purpose
 *
 * [SynchronizationPipelineRegistry] holds all application-supplied
 * synchronization pipelines and provides direction-based lookup for the
 * [SynchronizationExecutionCoordinator]. Each registered direction maps to
 * exactly one pipeline.
 *
 * ## Defensive copy
 *
 * The supplier-provided collection is defensively copied at construction time.
 * Mutations to the original collection after construction have no effect on
 * this registry.
 *
 * ## Duplicate direction rejection
 *
 * Construction throws [IllegalArgumentException] when the supplied pipeline
 * collection contains more than one pipeline with the same
 * [SynchronizationDirection]. Direction uniqueness is required for unambiguous
 * pipeline selection.
 *
 * ## Lookup
 *
 * [lookup] returns the [SynchronizationPipeline] registered for the given
 * direction, or `null` when no pipeline is registered for that direction.
 *
 * ## Supplied order preservation
 *
 * Insertion order is preserved for diagnostic purposes. Pipelines are stored
 * in the order they appear in the supplied collection.
 *
 * ## No mutable collection exposure
 *
 * No mutable collection is exposed through any property or method.
 *
 * ## Construction restrictions
 *
 * Construction performs no pipeline execution, no provider operation, no
 * lifecycle operation, no automatic pipeline discovery, no reflection, and no
 * ServiceLoader usage.
 *
 * ## Selection key
 *
 * The explicit [SynchronizationPipeline.direction] property is the selection
 * key. Pipelines are never selected by class name, collection hash order,
 * `toString()`, [SynchronizationDirection] ordinal, or platform service
 * discovery.
 *
 * ## No global state
 *
 * The registry contains no global state and uses no service locator.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API and runtime types only.
 * Safe for use in Kotlin Multiplatform common code.
 *
 * @param pipelines the application-supplied [SynchronizationPipeline]
 *   instances to register. Each pipeline must have a unique
 *   [SynchronizationPipeline.direction]. The collection is defensively copied.
 * @throws IllegalArgumentException if [pipelines] contains duplicate
 *   [SynchronizationDirection] values.
 */
public class SynchronizationPipelineRegistry(
    pipelines: Collection<SynchronizationPipeline>,
) {

    private val pipelineMap: Map<SynchronizationDirection, SynchronizationPipeline>

    init {
        val snapshot = pipelines.toList()
        val map = LinkedHashMap<SynchronizationDirection, SynchronizationPipeline>(snapshot.size)
        for (pipeline in snapshot) {
            require(!map.containsKey(pipeline.direction)) {
                "SynchronizationPipelineRegistry: duplicate direction registration for " +
                    "${pipeline.direction}. Each direction must have at most one pipeline."
            }
            map[pipeline.direction] = pipeline
        }
        pipelineMap = map
    }

    /**
     * Returns the [SynchronizationPipeline] registered for [direction], or
     * `null` when no pipeline is registered for that direction.
     *
     * The lookup uses the explicit [SynchronizationPipeline.direction] property
     * as the key. It never uses class names, ordinals, or service discovery.
     *
     * @param direction the [SynchronizationDirection] to look up.
     * @return the registered [SynchronizationPipeline], or `null`.
     */
    public fun lookup(direction: SynchronizationDirection): SynchronizationPipeline? =
        pipelineMap[direction]

    /**
     * Returns an unmodifiable view of all registered pipelines in the order
     * they were supplied at construction time.
     *
     * The returned collection is read-only and reflects a defensive snapshot.
     * Modifications to the returned collection are not possible.
     */
    public val pipelines: List<SynchronizationPipeline>
        get() = pipelineMap.values.toList()

    /**
     * Returns a safe diagnostic string listing the registered directions.
     *
     * Does not invoke any pipeline's `toString()` method.
     */
    override fun toString(): String {
        val directions = pipelineMap.keys.joinToString()
        return "SynchronizationPipelineRegistry(directions=[$directions])"
    }
}
