package io.dataloom.api.synchronization

/**
 * Unit of measurement for [SynchronizationProgress].
 *
 * Each value describes the kind of quantity represented by the
 * [SynchronizationProgress.completed] and [SynchronizationProgress.total]
 * counters. Not every workflow is expected to know its total in advance;
 * a `null` total represents indeterminate progress regardless of unit.
 *
 * Do not rely on enum ordinals for persistence or comparison.
 */
public enum class SynchronizationProgressUnit {

    /**
     * Synchronization change-event count.
     *
     * Progress is measured in individual change events processed or received.
     */
    EVENTS,

    /**
     * Payload or asset byte count.
     *
     * Progress is measured in bytes transferred or written.
     */
    BYTES,

    /**
     * Logical provider or synchronization operation count.
     *
     * Progress is measured in discrete provider or synchronization operations
     * performed.
     */
    OPERATIONS,

    /**
     * Application or runtime-defined execution-step count.
     *
     * Progress is measured in abstract steps defined by the application or
     * the runtime workflow.
     */
    STEPS,
}
