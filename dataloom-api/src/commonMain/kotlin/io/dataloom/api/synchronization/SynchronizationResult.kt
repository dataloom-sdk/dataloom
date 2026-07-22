package io.dataloom.api.synchronization

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.time.DataLoomInstant

/**
 * Terminal outcome of a synchronization workflow execution.
 *
 * [SynchronizationResult] is a sealed contract with one variant per
 * possible terminal state. The future DataLoom runtime produces and supplies
 * the appropriate variant; applications consume it through
 * [SynchronizationEvent.Completed] or a direct result callback.
 *
 * ## Variants
 *
 * | Variant              | Meaning                                              |
 * |----------------------|------------------------------------------------------|
 * | [Succeeded]          | Workflow completed successfully.                     |
 * | [PartiallySucceeded] | Workflow completed with one or more non-fatal errors.|
 * | [Failed]             | Workflow failed with a canonical error.              |
 * | [Cancelled]          | Workflow was cancelled before completion.            |
 * | [Skipped]            | Execution did not proceed for the stated reason.     |
 *
 * ## Common requirements
 *
 * - Results are immutable.
 * - Construction does not change workflow state.
 * - Construction does not write queue records.
 * - Construction does not log.
 * - Payload contents are not exposed.
 */
public sealed interface SynchronizationResult {

    /**
     * The synchronization request that produced this result.
     *
     * Must not expose credential, token, or payload content.
     */
    public val request: SynchronizationRequest

    /**
     * The instant at which the workflow reached its terminal state.
     *
     * Supplied by the future runtime; construction does not read the clock.
     */
    public val completedAt: DataLoomInstant

    /** Statistical summary of the workflow execution. */
    public val summary: SynchronizationSummary

    /**
     * Optional caller-supplied context attached to this result.
     *
     * Must not contain credentials, tokens, encryption keys, or personal data.
     */
    public val metadata: DataLoomMetadata

    /**
     * The synchronization workflow completed successfully.
     *
     * This result does not imply that remote business processing beyond the
     * configured transport contract is complete.
     *
     * @param request The synchronization request that produced this result.
     * @param completedAt The instant at which the workflow completed.
     * @param summary Statistical summary of the workflow execution.
     * @param metadata Optional caller-supplied context. Defaults to
     *   [DataLoomMetadata.Empty].
     */
    public data class Succeeded(
        override val request: SynchronizationRequest,
        override val completedAt: DataLoomInstant,
        override val summary: SynchronizationSummary,
        override val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
    ) : SynchronizationResult

    /**
     * Some work succeeded and one or more canonical errors remain.
     *
     * The workflow processed part of the available work but encountered
     * non-fatal errors that prevented complete success.
     *
     * The supplied [errors] list is defensively copied so that mutating a
     * caller-supplied mutable list after construction does not affect the
     * result. The exposed [errors] collection is read-only.
     *
     * ## Constraints
     *
     * - [errors] must contain at least one item.
     * - Empty error collections are rejected at construction.
     * - Provider-specific exception types must not be exposed through [errors].
     *
     * @param request The synchronization request that produced this result.
     * @param completedAt The instant at which the workflow completed.
     * @param summary Statistical summary of the workflow execution.
     * @param errors Non-empty list of canonical errors encountered during
     *   partial execution.
     * @param metadata Optional caller-supplied context. Defaults to
     *   [DataLoomMetadata.Empty].
     */
    public class PartiallySucceeded(
        override val request: SynchronizationRequest,
        override val completedAt: DataLoomInstant,
        override val summary: SynchronizationSummary,
        errors: List<DataLoomError>,
        override val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
    ) : SynchronizationResult {
        private val _errors: List<DataLoomError> = errors.toList()

        init {
            require(_errors.isNotEmpty()) {
                "SynchronizationResult.PartiallySucceeded errors must contain at least one item."
            }
        }

        /**
         * Read-only list of canonical errors encountered during partial
         * execution.
         *
         * The collection is a defensive copy of the caller-supplied list.
         */
        public val errors: List<DataLoomError>
            get() = _errors

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PartiallySucceeded) return false
            return request == other.request &&
                completedAt == other.completedAt &&
                summary == other.summary &&
                _errors == other._errors &&
                metadata == other.metadata
        }

        override fun hashCode(): Int {
            var result: Int = request.hashCode()
            result = 31 * result + completedAt.hashCode()
            result = 31 * result + summary.hashCode()
            result = 31 * result + _errors.hashCode()
            result = 31 * result + metadata.hashCode()
            return result
        }

        override fun toString(): String =
            "SynchronizationResult.PartiallySucceeded(" +
                "request=$request, " +
                "completedAt=$completedAt, " +
                "summary=$summary, " +
                "errorCount=${_errors.size}, " +
                "metadata=$metadata)"
    }

    /**
     * The workflow failed with a canonical [DataLoomError].
     *
     * The error describes the terminal failure. Stack traces, credentials, and
     * payload content must not be exposed through the error message.
     *
     * @param request The synchronization request that produced this result.
     * @param completedAt The instant at which the workflow failed.
     * @param summary Statistical summary of the execution up to the point of
     *   failure.
     * @param error The canonical error that caused the workflow to fail.
     * @param metadata Optional caller-supplied context. Defaults to
     *   [DataLoomMetadata.Empty].
     */
    public data class Failed(
        override val request: SynchronizationRequest,
        override val completedAt: DataLoomInstant,
        override val summary: SynchronizationSummary,
        public val error: DataLoomError,
        override val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
    ) : SynchronizationResult

    /**
     * Execution was cancelled before successful completion.
     *
     * Creating this result does not cancel any running work. Cancellation is
     * represented as an explicit terminal state by the future runtime or host
     * integration.
     *
     * @param request The synchronization request that produced this result.
     * @param completedAt The instant at which the workflow was cancelled.
     * @param summary Statistical summary of the execution up to the point of
     *   cancellation.
     * @param metadata Optional caller-supplied context. Defaults to
     *   [DataLoomMetadata.Empty].
     */
    public data class Cancelled(
        override val request: SynchronizationRequest,
        override val completedAt: DataLoomInstant,
        override val summary: SynchronizationSummary,
        override val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
    ) : SynchronizationResult

    /**
     * Execution did not proceed because of the supplied canonical skip reason.
     *
     * The workflow was evaluated but not executed. The skip reason indicates
     * why the runtime chose not to proceed.
     *
     * @param request The synchronization request that produced this result.
     * @param completedAt The instant at which the skip decision was made.
     * @param summary Statistical summary; typically all-zero for a skipped
     *   result.
     * @param reason The canonical reason why execution was skipped.
     * @param metadata Optional caller-supplied context. Defaults to
     *   [DataLoomMetadata.Empty].
     */
    public data class Skipped(
        override val request: SynchronizationRequest,
        override val completedAt: DataLoomInstant,
        override val summary: SynchronizationSummary,
        public val reason: SynchronizationSkipReason,
        override val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
    ) : SynchronizationResult
}
