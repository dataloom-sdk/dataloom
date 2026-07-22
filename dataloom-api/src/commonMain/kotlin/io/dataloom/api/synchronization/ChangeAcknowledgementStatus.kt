package io.dataloom.api.synchronization

/**
 * Closed canonical classification for how a remote participant responded to
 * a single pushed [io.dataloom.api.change.ChangeEvent].
 *
 * This contract classifies event-level acceptance. It does not implement
 * retry timing, queue deletion, or map statuses directly to HTTP status
 * codes.
 */
public enum class ChangeAcknowledgementStatus {

    /**
     * The remote participant accepted the change for the synchronization
     * contract.
     *
     * Acceptance does not by itself imply that the accepting participant has
     * durably persisted, applied, or completed the associated business
     * process. It only means the event satisfied the synchronization
     * contract for this exchange.
     */
    ACCEPTED,

    /**
     * The change was not accepted, but a later attempt with the same event
     * may succeed.
     *
     * This status does not schedule or execute a retry. Retry timing and
     * execution are runtime responsibilities deferred to a later issue.
     */
    RETRY,

    /**
     * The change was not accepted, and repeating the same, unchanged event is
     * not expected to succeed.
     *
     * This status does not delete the event from any queue or dictate
     * application-defined rejection policy. Rejected-event policy is
     * application configurable in future work.
     */
    REJECTED,
}
