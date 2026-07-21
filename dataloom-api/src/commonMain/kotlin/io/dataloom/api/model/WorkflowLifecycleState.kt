package io.dataloom.api.model

/**
 * Canonical synchronization workflow lifecycle states.
 *
 * This type defines stable state names only. Transition logic is defined in
 * later issues.
 */
public enum class WorkflowLifecycleState {
    /** The workflow contract has been created but not yet validated. */
    CREATED,

    /** Initial validation completed successfully. */
    VALIDATED,

    /** The workflow has been accepted into a queue. */
    QUEUED,

    /** The workflow has been selected for future execution. */
    SCHEDULED,

    /** The workflow is currently executing. */
    RUNNING,

    /** The workflow completed successfully. */
    SUCCEEDED,

    /** The workflow completed with failure. */
    FAILED,

    /** The workflow was intentionally cancelled. */
    CANCELLED,
}
