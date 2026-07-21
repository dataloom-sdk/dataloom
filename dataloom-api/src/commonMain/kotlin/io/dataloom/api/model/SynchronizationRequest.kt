package io.dataloom.api.model

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId

/**
 * Immutable synchronization intent contract.
 *
 * Creating a request does not execute synchronization, enqueue work, perform
 * persistence, evaluate connectivity, or trigger authentication.
 *
 * `toString()` is for diagnostics only and is not a serialization format.
 */
public data class SynchronizationRequest(
    /** Required workflow identifier for this synchronization request. */
    public val workflowId: WorkflowId,

    /** Required synchronization session identifier for this request. */
    public val sessionId: SynchronizationSessionId,

    /** Required synchronization direction. */
    public val direction: SynchronizationDirection,

    /** Required synchronization mode. */
    public val mode: SynchronizationMode,

    /** Optional workflow priority. Defaults to [WorkflowPriority.NORMAL]. */
    public val priority: WorkflowPriority = WorkflowPriority.NORMAL,

    /** Required immutable execution context associated with this request. */
    public val context: ExecutionContext,
)
