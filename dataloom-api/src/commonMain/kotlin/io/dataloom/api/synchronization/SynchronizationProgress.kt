package io.dataloom.api.synchronization

import io.dataloom.api.context.DataLoomMetadata

/**
 * Immutable snapshot of synchronization execution progress at a point in time.
 *
 * [SynchronizationProgress] captures how far the current execution phase has
 * advanced. It does not accumulate state over time; each instance is an
 * independent snapshot supplied by the future runtime.
 *
 * ## Constraints
 *
 * - [completed] must be zero or greater.
 * - [total] is optional. When provided it must be zero or greater.
 * - When [total] is provided, [completed] must not exceed [total].
 * - A `null` [total] represents indeterminate progress.
 *
 * ## Usage notes
 *
 * - Construction does not perform synchronization.
 * - Construction does not calculate progress by reading providers.
 * - Construction does not log.
 * - Percentage may be calculated by consumers when [total] is non-null and
 *   greater than zero: `completed.toDouble() / total.toDouble() * 100`.
 * - Do not add a mutable progress counter to this type.
 *
 * @param phase Current synchronization phase.
 * @param completed Non-negative count of completed units so far.
 * @param total Optional non-negative total unit count. `null` indicates the
 *   total is not known.
 * @param unit The unit of measurement for [completed] and [total].
 * @param metadata Optional caller-supplied context. Defaults to
 *   [DataLoomMetadata.Empty].
 */
public data class SynchronizationProgress(
    /** Current synchronization phase. */
    public val phase: SynchronizationPhase,

    /** Non-negative count of units completed so far in the current phase. */
    public val completed: Long,

    /**
     * Optional total unit count for the current phase.
     *
     * `null` indicates that the total is not known and progress is
     * indeterminate.
     */
    public val total: Long?,

    /** Unit of measurement for [completed] and [total]. */
    public val unit: SynchronizationProgressUnit,

    /**
     * Optional caller-supplied context.
     *
     * Must not contain credentials, tokens, encryption keys, or personal data.
     * Defaults to [DataLoomMetadata.Empty].
     */
    public val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
) {
    init {
        require(completed >= 0L) {
            "SynchronizationProgress completed must be zero or greater, but was $completed."
        }
        if (total != null) {
            require(total >= 0L) {
                "SynchronizationProgress total must be zero or greater when provided, but was $total."
            }
            require(completed <= total) {
                "SynchronizationProgress completed ($completed) must not exceed total ($total)."
            }
        }
    }
}
