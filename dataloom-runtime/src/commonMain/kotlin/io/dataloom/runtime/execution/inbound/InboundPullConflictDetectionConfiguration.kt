package io.dataloom.runtime.execution.inbound

import io.dataloom.runtime.conflict.ConflictOrchestrationBindings
import io.dataloom.runtime.conflict.DurableConflictDetectionCoordinator

/**
 * Optional conflict-detection configuration for
 * [InboundPullSynchronizationPipeline].
 *
 * ## Opt-in
 *
 * Supplying this configuration is what turns on conflict detection during
 * inbound pull; a pipeline constructed without it behaves exactly as it did
 * before this capability existed. See
 * [InboundPullSynchronizationPipeline]'s own "Conflict detection" section
 * for the full behavior this configuration enables.
 *
 * @param coordinator the [DurableConflictDetectionCoordinator] to call for
 *   each incoming event that has a local counterpart.
 * @param bindings the [ConflictOrchestrationBindings] (detector, optional
 *   resolver) used for every detection call this pipeline execution makes.
 *   One binding applies to the whole pipeline instance — there is no
 *   per-entity-type binding.
 */
public class InboundPullConflictDetectionConfiguration(
    public val coordinator: DurableConflictDetectionCoordinator,
    public val bindings: ConflictOrchestrationBindings,
) {
    // Deliberately uses default (reference) equality: DurableConflictDetectionCoordinator
    // wraps stateful collaborators (a store, a clock) with no meaningful value equality
    // of its own, so a structural equals/hashCode here would be misleading.

    override fun toString(): String =
        "InboundPullConflictDetectionConfiguration(bindings=$bindings)"
}
