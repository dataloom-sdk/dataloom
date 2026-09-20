package io.dataloom.runtime.observation.health

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Roll-up verdict of a [DataLoomHealthSnapshot], and of each finding that
 * contributed to it. Declared in increasing order of concern, so the roll-up
 * of several verdicts is simply the maximum.
 */
public enum class DataLoomHealthSeverity {
    /** Nothing supplied to the snapshot indicates a problem. */
    HEALTHY,

    /** Something needs attention but the subsystem is still doing its job. */
    DEGRADED,

    /** The subsystem is not doing its job, or is close to the bound that stops it doing so. */
    UNHEALTHY,
}

/** The subsystem a [DataLoomHealthFinding] is about. Closed vocabulary. */
public enum class DataLoomHealthComponent {
    PROVIDER,
    TELEMETRY_EXPORTER,
    OPERATIONAL_EVENT_OUTBOX,
    QUEUE_WORKER,
}

/**
 * Why a [DataLoomHealthFinding] was raised. A closed vocabulary -- a finding
 * never carries free text, an error message or any payload, so findings need
 * no redaction.
 */
public enum class DataLoomHealthFindingCode {
    /** A supplied provider health result reported `DEGRADED`. */
    PROVIDER_DEGRADED,

    /** A supplied provider health result reported `UNHEALTHY`. */
    PROVIDER_UNHEALTHY,

    /** A telemetry exporter reported `DEGRADED`. */
    EXPORTER_DEGRADED,

    /** A telemetry exporter reported `STOPPED` -- telemetry is being lost, work is unaffected. */
    EXPORTER_STOPPED,

    /** Pending outbox entries reached [DataLoomHealthThresholds.outboxPendingDegradedAt]. */
    OUTBOX_PENDING_HIGH,

    /** Pending outbox entries reached [DataLoomHealthThresholds.outboxPendingUnhealthyAt]. */
    OUTBOX_PENDING_CRITICAL,

    /** The oldest pending outbox entry is at least [DataLoomHealthThresholds.outboxOldestPendingDegradedAfter] old. */
    OUTBOX_OLDEST_PENDING_OLD,

    /** The oldest pending outbox entry is at least [DataLoomHealthThresholds.outboxOldestPendingUnhealthyAfter] old. */
    OUTBOX_OLDEST_PENDING_VERY_OLD,

    /** The last processing cycle presented entries it did not acknowledge (skipped, failed, or acknowledgement failed). */
    OUTBOX_LAST_CYCLE_LEFT_ENTRIES_PENDING,

    /** The last processing cycle could not even read the outbox. */
    OUTBOX_LAST_CYCLE_READ_FAILED,

    /** The outbox had pending entries when last observed and has not been observed since [DataLoomHealthThresholds.outboxObservationStaleAfter]. */
    OUTBOX_OBSERVATION_STALE_WITH_PENDING,

    /** The queue worker's consecutive failed runs reached [DataLoomHealthThresholds.queueWorkerFailedRunsDegradedAt]. */
    QUEUE_WORKER_RUNS_FAILING,

    /** The queue worker's consecutive failed runs reached [DataLoomHealthThresholds.queueWorkerFailedRunsUnhealthyAt]. */
    QUEUE_WORKER_RUNS_FAILING_REPEATEDLY,

    /** A queue worker run has been in flight for at least [DataLoomHealthThresholds.queueWorkerRunStuckAfter]. */
    QUEUE_WORKER_RUN_STUCK,
}

/**
 * One reason [DataLoomHealthSnapshot.severity] is not [DataLoomHealthSeverity.HEALTHY].
 *
 * @param subject a non-sensitive identifier of what the finding is about (a
 *   provider id, an exporter id or an outbox scope), or `null` for a
 *   singleton component such as the queue worker.
 */
public data class DataLoomHealthFinding(
    public val component: DataLoomHealthComponent,
    public val code: DataLoomHealthFindingCode,
    public val severity: DataLoomHealthSeverity,
    public val subject: String? = null,
)

/**
 * Tunable thresholds for [dataLoomHealthSnapshot]'s roll-up. Every field has
 * a safe default, so an application that configures nothing still gets a
 * meaningful verdict.
 *
 * The defaults are deliberately conservative for a *diagnostics* outbox and a
 * *scheduled* queue worker: an idle worker is normal, an outbox with a few
 * hundred pending diagnostics is normal, and a quiet process legitimately
 * goes a long time without touching the outbox. Thresholds are compared with
 * `>=`.
 *
 * @param outboxPendingDegradedAt pending entries in one outbox scope at which
 *   the scope is `DEGRADED`. The outbox codec bounds a scope to 10,000
 *   entries in total, so the defaults leave headroom.
 * @param outboxPendingUnhealthyAt pending entries at which the scope is
 *   `UNHEALTHY`. Must be at least [outboxPendingDegradedAt].
 * @param outboxOldestPendingDegradedAfter age of the oldest pending entry
 *   (measured from its caller-supplied `occurredAt`, as of the snapshot's
 *   `now`, assuming it is still pending) at which the scope is `DEGRADED`.
 * @param outboxOldestPendingUnhealthyAfter age at which the scope is
 *   `UNHEALTHY`. Must be at least [outboxOldestPendingDegradedAfter].
 * @param outboxObservationStaleAfter how old the last observation of a scope
 *   may be before it is flagged `stale`. A stale observation *with pending
 *   entries* is `DEGRADED` (entries nobody has looked at for a while); a
 *   stale observation of an empty outbox only sets the `stale` flag.
 * @param queueWorkerFailedRunsDegradedAt consecutive failed runs at which the
 *   worker is `DEGRADED`. At least `1`.
 * @param queueWorkerFailedRunsUnhealthyAt consecutive failed runs at which it
 *   is `UNHEALTHY`. Must be at least [queueWorkerFailedRunsDegradedAt].
 * @param queueWorkerRunStuckAfter how long a run may stay in flight before
 *   the worker is `DEGRADED`.
 * @param queueWorkerObservationStaleAfter how long since the worker's last
 *   recorded event before its `stale` flag is set. Informational only: a
 *   worker that is scheduled on demand is legitimately quiet for long
 *   periods, so staleness never changes the worker's severity.
 */
public data class DataLoomHealthThresholds(
    public val outboxPendingDegradedAt: Int = 1_000,
    public val outboxPendingUnhealthyAt: Int = 5_000,
    public val outboxOldestPendingDegradedAfter: Duration = 1.hours,
    public val outboxOldestPendingUnhealthyAfter: Duration = 24.hours,
    public val outboxObservationStaleAfter: Duration = 10.minutes,
    public val queueWorkerFailedRunsDegradedAt: Int = 1,
    public val queueWorkerFailedRunsUnhealthyAt: Int = 3,
    public val queueWorkerRunStuckAfter: Duration = 30.minutes,
    public val queueWorkerObservationStaleAfter: Duration = 6.hours,
) {
    init {
        require(outboxPendingDegradedAt >= 1) { "outboxPendingDegradedAt must be at least 1." }
        require(outboxPendingUnhealthyAt >= outboxPendingDegradedAt) {
            "outboxPendingUnhealthyAt must not be below outboxPendingDegradedAt."
        }
        require(outboxOldestPendingDegradedAfter > Duration.ZERO) {
            "outboxOldestPendingDegradedAfter must be greater than zero."
        }
        require(outboxOldestPendingUnhealthyAfter >= outboxOldestPendingDegradedAfter) {
            "outboxOldestPendingUnhealthyAfter must not be below outboxOldestPendingDegradedAfter."
        }
        require(outboxObservationStaleAfter > Duration.ZERO) { "outboxObservationStaleAfter must be greater than zero." }
        require(queueWorkerFailedRunsDegradedAt >= 1) { "queueWorkerFailedRunsDegradedAt must be at least 1." }
        require(queueWorkerFailedRunsUnhealthyAt >= queueWorkerFailedRunsDegradedAt) {
            "queueWorkerFailedRunsUnhealthyAt must not be below queueWorkerFailedRunsDegradedAt."
        }
        require(queueWorkerRunStuckAfter > Duration.ZERO) { "queueWorkerRunStuckAfter must be greater than zero." }
        require(queueWorkerObservationStaleAfter > Duration.ZERO) {
            "queueWorkerObservationStaleAfter must be greater than zero."
        }
    }
}
