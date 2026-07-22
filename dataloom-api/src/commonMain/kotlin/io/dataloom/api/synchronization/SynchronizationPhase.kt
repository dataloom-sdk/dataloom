package io.dataloom.api.synchronization

/**
 * Current execution phase within an active synchronization workflow.
 *
 * [SynchronizationPhase] describes the granular operation the DataLoom runtime
 * is performing at a given moment. It is distinct from
 * [io.dataloom.api.model.WorkflowLifecycleState], which represents the
 * high-level lifecycle of the workflow as a whole.
 *
 * ## Boundary with WorkflowLifecycleState
 *
 * | Type                    | Meaning                                          |
 * |-------------------------|--------------------------------------------------|
 * | `WorkflowLifecycleState`| High-level workflow state (QUEUED, RUNNING, …)   |
 * | `SynchronizationPhase`  | Current operation within an executing workflow   |
 *
 * A workflow may be in state [io.dataloom.api.model.WorkflowLifecycleState.RUNNING]
 * while cycling through multiple [SynchronizationPhase] values.
 *
 * ## Usage notes
 *
 * - Phase values represent lifecycle state only; they do not trigger runtime
 *   actions.
 * - Do not rely on enum ordinals for persistence or comparison.
 * - Phase transition logic is defined by the future runtime engine and is not
 *   enforced by this type.
 */
public enum class SynchronizationPhase {

    /**
     * The runtime is validating configuration, providers, and request
     * requirements.
     *
     * This phase occurs before any provider operations are executed.
     */
    VALIDATING,

    /**
     * Execution cannot continue until connectivity requirements are satisfied.
     *
     * The runtime is waiting for the required network or connectivity
     * conditions to be met before proceeding.
     */
    WAITING_FOR_CONNECTIVITY,

    /**
     * The runtime is requesting outbound changes from
     * [io.dataloom.api.storage.StorageProvider].
     *
     * During this phase the runtime reads locally staged changes that need to
     * be pushed to the remote endpoint.
     */
    READING_OUTBOUND,

    /**
     * Outbound changes are being sent through
     * [io.dataloom.api.transport.TransportProvider].
     *
     * During this phase the runtime transmits the outbound change set to the
     * remote endpoint.
     */
    PUSHING,

    /**
     * Remote acknowledgement results are being applied through
     * [io.dataloom.api.storage.StorageProvider].
     *
     * During this phase the runtime persists the remote acknowledgements
     * received after a push operation.
     */
    ACKNOWLEDGING_OUTBOUND,

    /**
     * The runtime is requesting inbound changes through
     * [io.dataloom.api.transport.TransportProvider].
     *
     * During this phase the runtime fetches remotely available changes that
     * need to be applied to local storage.
     */
    PULLING,

    /**
     * Inbound changes are being applied through
     * [io.dataloom.api.storage.StorageProvider].
     *
     * During this phase the runtime writes the fetched remote changes into
     * local storage.
     */
    APPLYING_INBOUND,

    /**
     * A successfully applied inbound checkpoint is being persisted.
     *
     * During this phase the runtime stores the synchronization checkpoint that
     * marks progress for a pull stream.
     */
    WRITING_CHECKPOINT,

    /**
     * One or more conflicts are being evaluated or resolved.
     *
     * During this phase the runtime invokes conflict detection and resolution
     * logic to reconcile divergent changes.
     */
    RESOLVING_CONFLICTS,

    /**
     * Execution is waiting for a future retry opportunity.
     *
     * During this phase the runtime has encountered a recoverable failure and
     * is waiting for the next scheduled retry attempt.
     */
    WAITING_FOR_RETRY,

    /**
     * The runtime is preparing the terminal synchronization result.
     *
     * This is the last phase before a [SynchronizationResult] is produced and
     * a [io.dataloom.api.synchronization.SynchronizationEvent.Completed] event
     * is emitted.
     */
    FINALIZING,
}
