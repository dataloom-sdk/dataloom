package io.dataloom.api.synchronization

import io.dataloom.api.context.DataLoomMetadata

/**
 * Immutable summary of synchronization execution statistics.
 *
 * [SynchronizationSummary] is produced by the future DataLoom runtime at the
 * end of a workflow and attached to every [SynchronizationResult]. Each
 * counter represents the number of units processed during the execution.
 *
 * ## Constraints
 *
 * - Every counter must be zero or greater.
 * - [outboundEventsAccepted], [outboundEventsMarkedForRetry], and
 *   [outboundEventsRejected] must each individually not exceed
 *   [outboundEventsRead].
 * - [inboundEventsApplied] must not exceed [inboundEventsReceived].
 *
 * ## Notes
 *
 * The sum of [outboundEventsAccepted], [outboundEventsMarkedForRetry], and
 * [outboundEventsRejected] is not required to equal [outboundEventsRead].
 * Some events may remain unprocessed when a workflow terminates early.
 *
 * Construction does not query providers, mutate state, or log.
 *
 * @param outboundEventsRead Number of outbound change events read from storage.
 * @param outboundEventsAccepted Number of outbound events accepted by the
 *   remote endpoint.
 * @param outboundEventsMarkedForRetry Number of outbound events scheduled for
 *   retry.
 * @param outboundEventsRejected Number of outbound events permanently rejected.
 * @param inboundEventsReceived Number of inbound change events received from
 *   the remote endpoint.
 * @param inboundEventsApplied Number of inbound events successfully applied to
 *   local storage.
 * @param conflictsDetected Number of conflicts detected during execution.
 * @param retryAttempts Number of retry attempts made during execution.
 * @param metadata Optional caller-supplied context. Defaults to
 *   [DataLoomMetadata.Empty].
 */
public data class SynchronizationSummary(
    /** Non-negative number of outbound change events read from storage. */
    public val outboundEventsRead: Long = 0L,

    /** Non-negative number of outbound events accepted by the remote endpoint. */
    public val outboundEventsAccepted: Long = 0L,

    /** Non-negative number of outbound events scheduled for retry. */
    public val outboundEventsMarkedForRetry: Long = 0L,

    /** Non-negative number of outbound events permanently rejected. */
    public val outboundEventsRejected: Long = 0L,

    /** Non-negative number of inbound change events received from the remote endpoint. */
    public val inboundEventsReceived: Long = 0L,

    /** Non-negative number of inbound events successfully applied to local storage. */
    public val inboundEventsApplied: Long = 0L,

    /** Non-negative number of conflicts detected during execution. */
    public val conflictsDetected: Long = 0L,

    /** Non-negative number of retry attempts made during execution. */
    public val retryAttempts: Int = 0,

    /**
     * Optional caller-supplied context.
     *
     * Must not contain credentials, tokens, encryption keys, or personal data.
     * Defaults to [DataLoomMetadata.Empty].
     */
    public val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
) {
    init {
        require(outboundEventsRead >= 0L) {
            "SynchronizationSummary outboundEventsRead must be zero or greater, but was $outboundEventsRead."
        }
        require(outboundEventsAccepted >= 0L) {
            "SynchronizationSummary outboundEventsAccepted must be zero or greater, but was $outboundEventsAccepted."
        }
        require(outboundEventsMarkedForRetry >= 0L) {
            "SynchronizationSummary outboundEventsMarkedForRetry must be zero or greater, " +
                "but was $outboundEventsMarkedForRetry."
        }
        require(outboundEventsRejected >= 0L) {
            "SynchronizationSummary outboundEventsRejected must be zero or greater, but was $outboundEventsRejected."
        }
        require(inboundEventsReceived >= 0L) {
            "SynchronizationSummary inboundEventsReceived must be zero or greater, but was $inboundEventsReceived."
        }
        require(inboundEventsApplied >= 0L) {
            "SynchronizationSummary inboundEventsApplied must be zero or greater, but was $inboundEventsApplied."
        }
        require(conflictsDetected >= 0L) {
            "SynchronizationSummary conflictsDetected must be zero or greater, but was $conflictsDetected."
        }
        require(retryAttempts >= 0) {
            "SynchronizationSummary retryAttempts must be zero or greater, but was $retryAttempts."
        }
        require(outboundEventsAccepted <= outboundEventsRead) {
            "SynchronizationSummary outboundEventsAccepted ($outboundEventsAccepted) must not exceed " +
                "outboundEventsRead ($outboundEventsRead)."
        }
        require(outboundEventsMarkedForRetry <= outboundEventsRead) {
            "SynchronizationSummary outboundEventsMarkedForRetry ($outboundEventsMarkedForRetry) must not exceed " +
                "outboundEventsRead ($outboundEventsRead)."
        }
        require(outboundEventsRejected <= outboundEventsRead) {
            "SynchronizationSummary outboundEventsRejected ($outboundEventsRejected) must not exceed " +
                "outboundEventsRead ($outboundEventsRead)."
        }
        require(inboundEventsApplied <= inboundEventsReceived) {
            "SynchronizationSummary inboundEventsApplied ($inboundEventsApplied) must not exceed " +
                "inboundEventsReceived ($inboundEventsReceived)."
        }
    }
}
