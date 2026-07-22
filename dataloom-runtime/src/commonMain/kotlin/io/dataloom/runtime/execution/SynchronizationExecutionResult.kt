package io.dataloom.runtime.execution

import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.core.provider.ProviderBindingFailure

/**
 * Sealed result of a synchronization execution attempt by the
 * [SynchronizationExecutionCoordinator].
 *
 * ## Variants
 *
 * | Variant    | Meaning                                                        |
 * |------------|----------------------------------------------------------------|
 * | [Executed] | A pipeline was selected and completed; result is the exact pipeline outcome. |
 * | [Rejected] | Execution was rejected before a pipeline was invoked.         |
 *
 * ## Relationship to [SynchronizationResult]
 *
 * [Executed] wraps a [SynchronizationResult] which is the terminal outcome
 * from the pipeline. [Rejected] is produced before any pipeline runs and
 * carries a [SynchronizationExecutionRejectionReason] instead.
 *
 * ## Security restrictions
 *
 * [Rejected] exposes only structural rejection reasons, provider IDs, provider
 * types, and binding failure reasons. It must not expose provider object
 * references, provider internal state, credentials, authorization headers,
 * payload bytes, checkpoint tokens, encryption keys, personal data, or stack
 * traces.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API and core types only. Safe for
 * use in Kotlin Multiplatform common code.
 */
public sealed interface SynchronizationExecutionResult {

    /**
     * A [SynchronizationPipeline] was selected and executed.
     *
     * [result] is the exact [SynchronizationResult] returned by the pipeline.
     * The coordinator does not transform, reinterpret, or modify the pipeline
     * result in any way.
     *
     * Any [SynchronizationResult] variant may appear here:
     * [SynchronizationResult.Succeeded], [SynchronizationResult.PartiallySucceeded],
     * [SynchronizationResult.Failed], [SynchronizationResult.Cancelled], or
     * [SynchronizationResult.Skipped].
     *
     * ## Construction restrictions
     *
     * Construction performs no lifecycle or provider action.
     *
     * @param result the exact [SynchronizationResult] returned by the pipeline.
     */
    public data class Executed(
        /** The exact [SynchronizationResult] returned by the selected pipeline. */
        public val result: SynchronizationResult,
    ) : SynchronizationExecutionResult

    /**
     * Execution was rejected before any pipeline was invoked.
     *
     * [reason] classifies why execution was rejected. [providerBindingFailures]
     * contains the ordered [ProviderBindingFailure] records produced by the
     * [io.dataloom.core.provider.SynchronizationProviderResolver] when
     * [reason] is [SynchronizationExecutionRejectionReason.PROVIDER_RESOLUTION_FAILED].
     * For all other rejection reasons, [providerBindingFailures] is empty.
     *
     * ## Constraints
     *
     * - When [reason] is [SynchronizationExecutionRejectionReason.PROVIDER_RESOLUTION_FAILED],
     *   [providerBindingFailures] must be non-empty.
     * - When [reason] is any other value, [providerBindingFailures] must be empty.
     *
     * Construction throws [IllegalArgumentException] when these constraints are
     * violated.
     *
     * ## Defensive copy
     *
     * The supplied [providerBindingFailures] collection is defensively copied.
     * Mutations to the original collection after construction have no effect.
     *
     * ## No partial provider set
     *
     * [Rejected] exposes no provider object references. Resolved provider
     * instances, if any, are not accessible through this result.
     *
     * ## No Throwable
     *
     * [Rejected] exposes no [Throwable] or stack trace.
     *
     * ## Value semantics
     *
     * [Rejected] provides value-based equality based on [reason] and the
     * ordered [providerBindingFailures] snapshot.
     *
     * @param reason the [SynchronizationExecutionRejectionReason] classifying
     *   why execution was rejected.
     * @param providerBindingFailures ordered [ProviderBindingFailure] records
     *   when [reason] is [SynchronizationExecutionRejectionReason.PROVIDER_RESOLUTION_FAILED].
     *   Empty for all other reasons. Defaults to empty.
     * @throws IllegalArgumentException when [reason] is
     *   [SynchronizationExecutionRejectionReason.PROVIDER_RESOLUTION_FAILED]
     *   and [providerBindingFailures] is empty, or when [reason] is any other
     *   value and [providerBindingFailures] is non-empty.
     */
    public class Rejected(
        /** The [SynchronizationExecutionRejectionReason] classifying this rejection. */
        public val reason: SynchronizationExecutionRejectionReason,
        providerBindingFailures: List<ProviderBindingFailure> = emptyList(),
    ) : SynchronizationExecutionResult {

        private val failuresSnapshot: List<ProviderBindingFailure> =
            providerBindingFailures.toList()

        init {
            if (reason == SynchronizationExecutionRejectionReason.PROVIDER_RESOLUTION_FAILED) {
                require(failuresSnapshot.isNotEmpty()) {
                    "SynchronizationExecutionResult.Rejected with PROVIDER_RESOLUTION_FAILED " +
                        "must supply a non-empty providerBindingFailures list."
                }
            } else {
                require(failuresSnapshot.isEmpty()) {
                    "SynchronizationExecutionResult.Rejected with reason=$reason " +
                        "must not supply providerBindingFailures (expected empty list)."
                }
            }
        }

        /**
         * The ordered [ProviderBindingFailure] records produced by the resolver.
         *
         * Non-empty only when [reason] is
         * [SynchronizationExecutionRejectionReason.PROVIDER_RESOLUTION_FAILED].
         * Empty for all other rejection reasons.
         *
         * The returned list is a defensive copy of the caller-supplied collection.
         */
        public val providerBindingFailures: List<ProviderBindingFailure>
            get() = failuresSnapshot

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Rejected) return false
            return reason == other.reason && failuresSnapshot == other.failuresSnapshot
        }

        override fun hashCode(): Int {
            var result = reason.hashCode()
            result = 31 * result + failuresSnapshot.hashCode()
            return result
        }

        /**
         * Returns a safe diagnostic representation.
         *
         * Includes rejection reason and the count and content of any binding
         * failures (by provider ID, expected type, and failure reason only).
         * Does not expose provider object references, credentials, payloads,
         * encryption keys, personal data, or stack traces.
         */
        override fun toString(): String =
            "SynchronizationExecutionResult.Rejected(" +
                "reason=$reason, " +
                "providerBindingFailures=$failuresSnapshot" +
                ")"
    }
}
