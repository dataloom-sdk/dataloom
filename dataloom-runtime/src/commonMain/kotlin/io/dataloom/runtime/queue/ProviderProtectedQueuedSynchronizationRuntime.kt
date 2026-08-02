package io.dataloom.runtime.queue

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.queue.QueueEntry
import io.dataloom.runtime.execution.SynchronizationExecutionResult
import io.dataloom.runtime.execution.protection.ProviderProtectedSynchronizationResult
import io.dataloom.runtime.facade.DataLoomProtectedSynchronization
import io.dataloom.runtime.facade.ProviderProtectedSynchronizationExecutionResult
import io.dataloom.runtime.retry.WorkflowTimeoutStateExecutor

/**
 * Exact result of resolving and invoking one queued workflow through the
 * provider-protected synchronization facade.
 *
 * This result intentionally does not collapse provider/circuit evidence into a
 * legacy queue transition. A later queue-processing adapter may map the
 * synchronization result to one transition while preserving [Executed.result]
 * unchanged.
 */
public sealed interface ProviderProtectedQueuedSynchronizationResult {

    /** Application-owned queue payload resolution failed before synchronization. */
    public data class ResolutionRejected(
        public val queueEntryId: QueueEntryId,
        public val error: DataLoomError,
    ) : ProviderProtectedQueuedSynchronizationResult

    /** Persisted workflow timeout evidence rejected execution before completion. */
    public data class WorkflowTimeoutRejected(
        public val queueEntryId: QueueEntryId,
        public val error: DataLoomError,
    ) : ProviderProtectedQueuedSynchronizationResult

    /** Lifecycle, provider binding, pipeline, or connectivity admission rejected execution. */
    public data class ExecutionRejected(
        public val queueEntryId: QueueEntryId,
        public val rejection: SynchronizationExecutionResult.Rejected,
    ) : ProviderProtectedQueuedSynchronizationResult

    /** A protected pipeline ran and returned exact synchronization/provider evidence. */
    public data class Executed(
        public val queueEntryId: QueueEntryId,
        public val result: ProviderProtectedSynchronizationResult,
    ) : ProviderProtectedQueuedSynchronizationResult
}

/**
 * Resolves one acquired queue entry and invokes protected synchronization once.
 *
 * The accepted durable workflow deadline is enforced before and during the
 * invocation. Resolution rejection, timeout rejection, structural admission,
 * and executed provider evidence remain distinct. Construction performs no
 * resolver, provider, state-store, clock, timeout, I/O, identifier, or coroutine
 * activity.
 */
public class ProviderProtectedQueuedSynchronizationRuntime(
    private val workResolver: QueuedSynchronizationWorkResolver,
    private val protectedSynchronization: DataLoomProtectedSynchronization,
    private val workflowTimeoutExecutor: WorkflowTimeoutStateExecutor? = null,
) {

    /** Resolves [entry] and invokes protected synchronization at most once. */
    public suspend fun execute(
        entry: QueueEntry,
    ): ProviderProtectedQueuedSynchronizationResult {
        val resolution = workResolver.resolve(entry)
        if (resolution is QueuedSynchronizationWorkResolution.Rejected) {
            return ProviderProtectedQueuedSynchronizationResult.ResolutionRejected(
                queueEntryId = entry.id,
                error = resolution.error,
            )
        }
        val work = (resolution as QueuedSynchronizationWorkResolution.Resolved).work

        val execution = when (
            val timed = executeQueuedWorkflowWithTimeout(
                entry = entry,
                timeoutExecutor = workflowTimeoutExecutor,
            ) {
                protectedSynchronization.synchronize(work.request, work.bindings)
            }
        ) {
            is QueuedWorkflowTimeoutExecution.Completed -> timed.value
            is QueuedWorkflowTimeoutExecution.Failed -> {
                return ProviderProtectedQueuedSynchronizationResult.WorkflowTimeoutRejected(
                    queueEntryId = entry.id,
                    error = timed.error,
                )
            }
        }

        return when (execution) {
            is ProviderProtectedSynchronizationExecutionResult.Rejected ->
                ProviderProtectedQueuedSynchronizationResult.ExecutionRejected(
                    queueEntryId = entry.id,
                    rejection = execution.rejection,
                )
            is ProviderProtectedSynchronizationExecutionResult.Executed ->
                ProviderProtectedQueuedSynchronizationResult.Executed(
                    queueEntryId = entry.id,
                    result = execution.result,
                )
        }
    }
}
