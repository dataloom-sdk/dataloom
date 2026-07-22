package io.dataloom.runtime.execution

import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.synchronization.SynchronizationResult

/**
 * Contract for a single synchronization direction pipeline.
 *
 * ## Purpose
 *
 * [SynchronizationPipeline] represents one supported synchronization direction.
 * The [SynchronizationExecutionCoordinator] selects a pipeline by matching the
 * [direction] property against the direction in the incoming
 * [io.dataloom.api.model.SynchronizationRequest], then delegates execution
 * to [execute].
 *
 * ## Direction
 *
 * [direction] declares which [SynchronizationDirection] this pipeline handles.
 * A [SynchronizationPipelineRegistry] rejects duplicate direction registrations
 * so that direction-based selection is unambiguous.
 *
 * ## Execution
 *
 * [execute] receives an immutable [SynchronizationExecutionContext] and returns
 * a [SynchronizationResult]. The pipeline is responsible for all
 * synchronization work within the scope of its direction. The coordinator
 * returns the result unchanged.
 *
 * ## Cancellation
 *
 * Coroutine cancellation must propagate normally. [execute] must not catch
 * [kotlinx.coroutines.CancellationException].
 *
 * ## Threading
 *
 * This interface does not expose a [kotlinx.coroutines.CoroutineScope] and
 * does not select a dispatcher. Thread-safety responsibilities belong to
 * concrete implementations.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API and runtime types only.
 * Safe for use in Kotlin Multiplatform common code.
 *
 * ## Scope restrictions
 *
 * DL-020 does not provide any concrete pipeline implementation. Outbound push,
 * inbound pull, and bidirectional pipelines are out of scope for this issue.
 *
 * [io.dataloom.api.model.SynchronizationMode] may be interpreted by future
 * pipeline implementations. Direction-based selection in DL-020 uses
 * [SynchronizationDirection] only.
 */
public interface SynchronizationPipeline {

    /**
     * The [SynchronizationDirection] this pipeline handles.
     *
     * The [SynchronizationExecutionCoordinator] uses this value as the lookup
     * key when selecting a pipeline from the [SynchronizationPipelineRegistry].
     * A [SynchronizationPipelineRegistry] rejects pipelines that share the
     * same direction.
     */
    public val direction: SynchronizationDirection

    /**
     * Executes the synchronization pipeline for the supplied [context].
     *
     * Receives the immutable [SynchronizationExecutionContext] produced by the
     * coordinator and returns a [SynchronizationResult] representing the
     * terminal outcome. The coordinator returns this result unchanged.
     *
     * Coroutine cancellation propagates normally. Implementations must not
     * catch [kotlinx.coroutines.CancellationException].
     *
     * @param context the immutable execution context for this synchronization
     *   run, including the request, resolved providers, and runtime dependencies.
     * @return the [SynchronizationResult] representing the terminal outcome
     *   of this pipeline execution.
     */
    public suspend fun execute(context: SynchronizationExecutionContext): SynchronizationResult
}
