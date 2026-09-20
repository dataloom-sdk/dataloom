package io.dataloom.runtime.observation.health

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.operational.OperationalEventOutboxScope
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderLifecycleCoordinatorState
import io.dataloom.api.security.ClassifiedData
import io.dataloom.api.security.ClassifiedDataValue
import io.dataloom.api.security.DataClassification
import io.dataloom.api.security.DataLoomRedactor
import io.dataloom.api.security.RedactedAttributes
import io.dataloom.api.security.StrictDataLoomRedactor
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.observation.retry.RetryCircuitExporterHealth
import io.dataloom.runtime.observation.retry.RetryCircuitTelemetrySnapshot
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Redacted, diagnostics-safe view of one provider's [ProviderHealth].
 *
 * [errorAttributes] is produced by the same code/category/severity/
 * recoverability-are-`PUBLIC`, message-is-`CONFIDENTIAL`,
 * cause-is-never-included convention [SynchronizationOperationalEventBridge]
 * already applies to every other [DataLoomError] this SDK exports, so under
 * the default [DataLoomRedactor] only the closed vocabularies survive and
 * [DataLoomError.message] is removed outright. [DataLoomError.cause] (a raw
 * [Throwable]) is never read.
 *
 * [detailFieldCount] discloses how many entries [ProviderHealth.details]
 * carried without disclosing their names or values -- the same bound
 * [io.dataloom.api.context.DataLoomMetadata]'s own redacted `toString()`
 * already exposes. Field-level redaction of arbitrary provider-supplied
 * metadata keys is deliberately out of scope: nothing in this codebase
 * today converts free-form [io.dataloom.api.context.DataLoomMetadata] keys
 * (which are only required to be non-blank) into the bounded ASCII tokens
 * [ClassifiedData] requires, and inventing that conversion is a separate,
 * larger concern than this bounded first slice.
 */
public data class RedactedProviderHealth(
    public val status: ProviderHealthStatus,
    public val errorAttributes: RedactedAttributes,
    public val detailFieldCount: Int,
)

/** What a queue worker is doing at the instant its state was last recorded. */
public enum class QueueWorkerActivity {
    /** No run has been recorded since the tracker was created -- not evidence the queue is empty. */
    NEVER_RUN,

    /** No run is in flight. */
    IDLE,

    /** At least one run is in flight. */
    RUNNING,
}

/** How the last processing cycle of one outbox scope ended, for [OperationalEventOutboxHealth]. */
public data class OperationalEventOutboxProcessingCycleHealth(
    public val outcome: OperationalEventOutboxProcessingCycleOutcome,
    public val entriesLeftPending: Int,
    public val observedAt: DataLoomInstant,
)

/**
 * Health of one durable outbox scope, derived from a *cached observation* --
 * see [OperationalEventOutboxHealthTracker] for exactly what that promises.
 *
 * @param pendingCount pending entries when last observed, `null` if the scope's
 *   state has never been observed (only a processing cycle has been recorded).
 * @param acknowledgedRetainedCount retained acknowledged tombstones when last observed.
 * @param oldestPendingAge the oldest pending entry's age as of the snapshot's
 *   `now`, from its caller-supplied `occurredAt`, **assuming it is still
 *   pending** -- the observation may be older than `now`.
 * @param observedAt when the cached state was observed; `null` if never.
 * @param observationAge `now - observedAt`; `null` if never observed.
 * @param stale whether [observationAge] reached
 *   [DataLoomHealthThresholds.outboxObservationStaleAfter]. Never set for a
 *   scope that was never observed (there is nothing to be stale).
 * @param lastProcessingCycle the most recent processing cycle, if a tracked
 *   processor ran one.
 * @param hasSkippedOrFailedEntries whether the last processing cycle left
 *   entries pending (skipped, failed, or failed to acknowledge). A fact about
 *   that cycle, not a live count.
 * @param severity roll-up of this scope's findings.
 */
public data class OperationalEventOutboxHealth(
    public val scope: OperationalEventOutboxScope,
    public val pendingCount: Int?,
    public val acknowledgedRetainedCount: Int?,
    public val oldestPendingAge: Duration?,
    public val observedAt: DataLoomInstant?,
    public val observationAge: Duration?,
    public val stale: Boolean,
    public val lastProcessingCycle: OperationalEventOutboxProcessingCycleHealth?,
    public val hasSkippedOrFailedEntries: Boolean,
    public val severity: DataLoomHealthSeverity,
)

/**
 * Health of the queue worker, derived from a [QueueWorkerHealthTracker].
 *
 * @param lastFailure the last failed run's closed error vocabulary (code,
 *   category, severity, recoverability -- never a message), redacted by the
 *   snapshot's redactor; present only while [consecutiveFailedRuns] is above zero.
 * @param observedAt when the last event was recorded, or when tracking began if none was.
 * @param stale whether nothing was recorded for
 *   [DataLoomHealthThresholds.queueWorkerObservationStaleAfter]. Informational:
 *   it never changes [severity], because a worker scheduled on demand is
 *   legitimately quiet.
 * @param severity roll-up of the worker's findings.
 */
public data class QueueWorkerHealth(
    public val activity: QueueWorkerActivity,
    public val runsInFlight: Int,
    public val lastRunStartedAt: DataLoomInstant?,
    public val lastCompletedRunAt: DataLoomInstant?,
    public val lastEmptyQueueAt: DataLoomInstant?,
    public val lastRunOutcome: QueueWorkerRunOutcome?,
    public val consecutiveFailedRuns: Int,
    public val lastFailure: RedactedAttributes?,
    public val observedAt: DataLoomInstant,
    public val observationAge: Duration,
    public val stale: Boolean,
    public val severity: DataLoomHealthSeverity,
)

/**
 * Point-in-time, redacted snapshot of already-queryable in-process
 * subsystem state, with a severity roll-up.
 *
 * ## Scope -- what this is
 * A bounded aggregation of whichever already-synchronously-queryable
 * subsystem state a caller supplies at the moment of the call:
 * - [providerLifecycleState] -- the current
 *   `ProviderLifecycleCoordinator.state`.
 * - [retryCircuitTelemetry] -- the current
 *   `BoundedRetryCircuitTelemetry.snapshot()`.
 * - [providerHealth] -- already-redacted results of `DataLoomProvider
 *   .health()` calls the caller performed itself, keyed by [ProviderId].
 * - [outboxHealth] -- one entry per durable outbox scope, from an
 *   [OperationalEventOutboxHealthTracker]'s *cached observations* (see that
 *   class: they are as-of facts with their own age and staleness, never the
 *   store's current state).
 * - [queueWorkerHealth] -- the queue worker, from a [QueueWorkerHealthTracker].
 *
 * ## The roll-up
 * [findings] lists every reason the snapshot is not clean, each as closed
 * codes (never free text), and [severity] is the maximum of their severities
 * (`HEALTHY` when there are none). Findings come from: provider health
 * status, telemetry exporter health, outbox depth/age/last cycle/staleness,
 * and worker failures/stuck runs, with thresholds from
 * [DataLoomHealthThresholds]. [providerLifecycleState] is reported but does
 * **not** influence [severity]: it is a lifecycle phase (for example
 * `NOT_INITIALIZED` before startup), not a health verdict.
 *
 * **[severity] speaks only for the evidence supplied.** A snapshot built with
 * nothing is `HEALTHY` with no findings; that means "nothing reported a
 * problem", not "everything was checked".
 *
 * ## Scope -- what this deliberately is not
 * - Not a live or continuous monitoring feed -- callers decide when to
 *   call [dataLoomHealthSnapshot] and get back exactly one instant.
 * - Not backed by any new durable storage -- nothing here is persisted.
 * - Not cross-process or cross-node aggregation -- one snapshot describes
 *   exactly one process's in-memory state.
 * - Not a historical or trend view -- only the instant of the call.
 * - Not a deployable service or dashboard -- that remains a separate,
 *   larger, still fully open gap.
 *
 * A section a caller has nothing to report for is `null` (or empty for the
 * collections), never a failure.
 */
public data class DataLoomHealthSnapshot(
    public val providerLifecycleState: ProviderLifecycleCoordinatorState?,
    public val retryCircuitTelemetry: RetryCircuitTelemetrySnapshot?,
    public val providerHealth: Map<ProviderId, RedactedProviderHealth>,
    public val outboxHealth: List<OperationalEventOutboxHealth>,
    public val queueWorkerHealth: QueueWorkerHealth?,
    public val severity: DataLoomHealthSeverity,
    public val findings: List<DataLoomHealthFinding>,
)

/**
 * Builds a [DataLoomHealthSnapshot] purely from already-available,
 * caller-supplied state.
 *
 * This function performs no I/O, never suspends, reads no clock, and calls no
 * provider or collaborator directly -- every argument reflects state the
 * caller already obtained on its own (e.g. reading `lifecycleCoordinator
 * .state`, calling `telemetry.snapshot()`, `outboxTracker.snapshot()` or
 * `workerTracker.snapshot()`, or awaiting `provider.health()` itself).
 * Omitting a collaborator entirely (leaving a parameter at its default) is
 * well-defined: the corresponding section is `null`/empty in the result,
 * never a crash, and a snapshot built as before these parameters existed is
 * unchanged apart from carrying `HEALTHY`/no findings unless a provider or
 * exporter it already contained is degraded.
 *
 * @param outboxObservations `OperationalEventOutboxHealthTracker.snapshot()`.
 * @param queueWorkerObservation `QueueWorkerHealthTracker.snapshot()`.
 * @param now the instant ages and staleness are measured against. Required
 *   whenever [outboxObservations] is non-empty or [queueWorkerObservation] is
 *   supplied -- this function never reads a clock itself, so the caller states
 *   what "now" is.
 * @param thresholds roll-up thresholds; defaults are safe (see
 *   [DataLoomHealthThresholds]).
 */
public fun dataLoomHealthSnapshot(
    providerLifecycleState: ProviderLifecycleCoordinatorState? = null,
    retryCircuitTelemetry: RetryCircuitTelemetrySnapshot? = null,
    providerHealth: Map<ProviderId, ProviderHealth> = emptyMap(),
    redactor: DataLoomRedactor = StrictDataLoomRedactor(),
    outboxObservations: List<OperationalEventOutboxObservedState> = emptyList(),
    queueWorkerObservation: QueueWorkerObservedState? = null,
    now: DataLoomInstant? = null,
    thresholds: DataLoomHealthThresholds = DataLoomHealthThresholds(),
): DataLoomHealthSnapshot {
    require(now != null || (outboxObservations.isEmpty() && queueWorkerObservation == null)) {
        "now must be supplied when outboxObservations or queueWorkerObservation is."
    }
    val redactedProviderHealth = providerHealth.mapValues { (_, health) -> redactProviderHealth(health, redactor) }
    val findings = mutableListOf<DataLoomHealthFinding>()

    redactedProviderHealth.entries.sortedBy { it.key.value }.forEach { (providerId, health) ->
        when (health.status) {
            ProviderHealthStatus.DEGRADED -> findings += DataLoomHealthFinding(
                DataLoomHealthComponent.PROVIDER, DataLoomHealthFindingCode.PROVIDER_DEGRADED,
                DataLoomHealthSeverity.DEGRADED, providerId.value,
            )
            ProviderHealthStatus.UNHEALTHY -> findings += DataLoomHealthFinding(
                DataLoomHealthComponent.PROVIDER, DataLoomHealthFindingCode.PROVIDER_UNHEALTHY,
                DataLoomHealthSeverity.UNHEALTHY, providerId.value,
            )
            ProviderHealthStatus.HEALTHY, ProviderHealthStatus.UNKNOWN -> Unit
        }
    }
    retryCircuitTelemetry?.exporters?.forEach { exporter ->
        when (exporter.health) {
            RetryCircuitExporterHealth.DEGRADED -> findings += DataLoomHealthFinding(
                DataLoomHealthComponent.TELEMETRY_EXPORTER, DataLoomHealthFindingCode.EXPORTER_DEGRADED,
                DataLoomHealthSeverity.DEGRADED, exporter.exporterId.value,
            )
            RetryCircuitExporterHealth.STOPPED -> findings += DataLoomHealthFinding(
                DataLoomHealthComponent.TELEMETRY_EXPORTER, DataLoomHealthFindingCode.EXPORTER_STOPPED,
                DataLoomHealthSeverity.DEGRADED, exporter.exporterId.value,
            )
            RetryCircuitExporterHealth.HEALTHY -> Unit
        }
    }

    val outboxHealth = outboxObservations.map { observed ->
        outboxHealthOf(observed, checkNotNull(now), thresholds, findings)
    }
    val queueWorkerHealth = queueWorkerObservation?.let { queueWorkerHealthOf(it, checkNotNull(now), thresholds, redactor, findings) }

    return DataLoomHealthSnapshot(
        providerLifecycleState = providerLifecycleState,
        retryCircuitTelemetry = retryCircuitTelemetry,
        providerHealth = redactedProviderHealth,
        outboxHealth = outboxHealth,
        queueWorkerHealth = queueWorkerHealth,
        severity = findings.maxOfOrNull { it.severity } ?: DataLoomHealthSeverity.HEALTHY,
        findings = findings.toList(),
    )
}

private fun ageBetween(from: DataLoomInstant, now: DataLoomInstant): Duration =
    (now.epochMilliseconds - from.epochMilliseconds).coerceAtLeast(0L).milliseconds

private fun outboxHealthOf(
    observed: OperationalEventOutboxObservedState,
    now: DataLoomInstant,
    thresholds: DataLoomHealthThresholds,
    findings: MutableList<DataLoomHealthFinding>,
): OperationalEventOutboxHealth {
    val subject = observed.scope.value
    val own = mutableListOf<DataLoomHealthFinding>()
    fun add(code: DataLoomHealthFindingCode, severity: DataLoomHealthSeverity) {
        own += DataLoomHealthFinding(DataLoomHealthComponent.OPERATIONAL_EVENT_OUTBOX, code, severity, subject)
    }

    val observation = observed.stateObservation
    val summary = observation?.summary
    val observationAge = observation?.let { ageBetween(it.observedAt, now) }
    val stale = observationAge != null && observationAge >= thresholds.outboxObservationStaleAfter
    val oldestPendingAge = summary?.oldestPendingOccurredAt?.let { ageBetween(it, now) }

    if (summary != null) {
        when {
            summary.pendingCount >= thresholds.outboxPendingUnhealthyAt ->
                add(DataLoomHealthFindingCode.OUTBOX_PENDING_CRITICAL, DataLoomHealthSeverity.UNHEALTHY)
            summary.pendingCount >= thresholds.outboxPendingDegradedAt ->
                add(DataLoomHealthFindingCode.OUTBOX_PENDING_HIGH, DataLoomHealthSeverity.DEGRADED)
        }
        if (oldestPendingAge != null) {
            when {
                oldestPendingAge >= thresholds.outboxOldestPendingUnhealthyAfter ->
                    add(DataLoomHealthFindingCode.OUTBOX_OLDEST_PENDING_VERY_OLD, DataLoomHealthSeverity.UNHEALTHY)
                oldestPendingAge >= thresholds.outboxOldestPendingDegradedAfter ->
                    add(DataLoomHealthFindingCode.OUTBOX_OLDEST_PENDING_OLD, DataLoomHealthSeverity.DEGRADED)
            }
        }
        if (stale && summary.pendingCount > 0) {
            add(DataLoomHealthFindingCode.OUTBOX_OBSERVATION_STALE_WITH_PENDING, DataLoomHealthSeverity.DEGRADED)
        }
    }

    val cycle = observed.lastProcessingCycle
    val hasSkippedOrFailed = cycle != null && cycle.entriesLeftPending > 0
    if (hasSkippedOrFailed) {
        add(DataLoomHealthFindingCode.OUTBOX_LAST_CYCLE_LEFT_ENTRIES_PENDING, DataLoomHealthSeverity.DEGRADED)
    }
    if (cycle != null && cycle.outcome == OperationalEventOutboxProcessingCycleOutcome.READ_FAILURE) {
        add(DataLoomHealthFindingCode.OUTBOX_LAST_CYCLE_READ_FAILED, DataLoomHealthSeverity.DEGRADED)
    }

    findings += own
    return OperationalEventOutboxHealth(
        scope = observed.scope,
        pendingCount = summary?.pendingCount,
        acknowledgedRetainedCount = summary?.acknowledgedRetainedCount,
        oldestPendingAge = oldestPendingAge,
        observedAt = observation?.observedAt,
        observationAge = observationAge,
        stale = stale,
        lastProcessingCycle = cycle?.let {
            OperationalEventOutboxProcessingCycleHealth(it.outcome, it.entriesLeftPending, it.observedAt)
        },
        hasSkippedOrFailedEntries = hasSkippedOrFailed,
        severity = own.maxOfOrNull { it.severity } ?: DataLoomHealthSeverity.HEALTHY,
    )
}

private fun queueWorkerHealthOf(
    observed: QueueWorkerObservedState,
    now: DataLoomInstant,
    thresholds: DataLoomHealthThresholds,
    redactor: DataLoomRedactor,
    findings: MutableList<DataLoomHealthFinding>,
): QueueWorkerHealth {
    val own = mutableListOf<DataLoomHealthFinding>()
    fun add(code: DataLoomHealthFindingCode, severity: DataLoomHealthSeverity) {
        own += DataLoomHealthFinding(DataLoomHealthComponent.QUEUE_WORKER, code, severity)
    }

    when {
        observed.consecutiveFailedRuns >= thresholds.queueWorkerFailedRunsUnhealthyAt ->
            add(DataLoomHealthFindingCode.QUEUE_WORKER_RUNS_FAILING_REPEATEDLY, DataLoomHealthSeverity.UNHEALTHY)
        observed.consecutiveFailedRuns >= thresholds.queueWorkerFailedRunsDegradedAt ->
            add(DataLoomHealthFindingCode.QUEUE_WORKER_RUNS_FAILING, DataLoomHealthSeverity.DEGRADED)
    }
    val runStartedAt = observed.lastRunStartedAt
    if (observed.runsInFlight > 0 && runStartedAt != null &&
        ageBetween(runStartedAt, now) >= thresholds.queueWorkerRunStuckAfter
    ) {
        // The most recent start is the *youngest* in-flight run, so if it is
        // already this old every in-flight run is at least this old.
        add(DataLoomHealthFindingCode.QUEUE_WORKER_RUN_STUCK, DataLoomHealthSeverity.DEGRADED)
    }

    findings += own
    val observedAt = observed.lastEventAt ?: observed.trackedSince
    val observationAge = ageBetween(observedAt, now)
    return QueueWorkerHealth(
        activity = when {
            observed.runsInFlight > 0 -> QueueWorkerActivity.RUNNING
            observed.lastRunStartedAt == null -> QueueWorkerActivity.NEVER_RUN
            else -> QueueWorkerActivity.IDLE
        },
        runsInFlight = observed.runsInFlight,
        lastRunStartedAt = observed.lastRunStartedAt,
        lastCompletedRunAt = observed.lastCompletedRunAt,
        lastEmptyQueueAt = observed.lastEmptyQueueAt,
        lastRunOutcome = observed.lastRunOutcome,
        consecutiveFailedRuns = observed.consecutiveFailedRuns,
        lastFailure = observed.lastFailure?.takeIf { observed.consecutiveFailedRuns > 0 }?.let { failure ->
            redactor.redact(
                ClassifiedData.of(
                    mapOf(
                        "code" to ClassifiedDataValue(failure.code.value, DataClassification.PUBLIC),
                        "category" to ClassifiedDataValue(failure.category.name, DataClassification.PUBLIC),
                        "severity" to ClassifiedDataValue(failure.severity.name, DataClassification.PUBLIC),
                        "recoverability" to ClassifiedDataValue(failure.recoverability.name, DataClassification.PUBLIC),
                    ),
                ),
            ).attributes
        },
        observedAt = observedAt,
        observationAge = observationAge,
        stale = observationAge >= thresholds.queueWorkerObservationStaleAfter,
        severity = own.maxOfOrNull { it.severity } ?: DataLoomHealthSeverity.HEALTHY,
    )
}

private fun redactProviderHealth(
    health: ProviderHealth,
    redactor: DataLoomRedactor,
): RedactedProviderHealth {
    val error = health.error
    val errorAttributes = if (error == null) {
        RedactedAttributes.Empty
    } else {
        redactor.redact(ClassifiedData.of(errorClassifiedAttributes(error))).attributes
    }
    return RedactedProviderHealth(
        status = health.status,
        errorAttributes = errorAttributes,
        detailFieldCount = health.details.entries.size,
    )
}

private fun errorClassifiedAttributes(error: DataLoomError): Map<String, ClassifiedDataValue> = mapOf(
    "code" to ClassifiedDataValue(error.code.value, DataClassification.PUBLIC),
    "category" to ClassifiedDataValue(error.category.name, DataClassification.PUBLIC),
    "severity" to ClassifiedDataValue(error.severity.name, DataClassification.PUBLIC),
    "recoverability" to ClassifiedDataValue(error.recoverability.name, DataClassification.PUBLIC),
    "message" to ClassifiedDataValue(
        error.message.take(MAX_ERROR_MESSAGE_LENGTH),
        DataClassification.CONFIDENTIAL,
    ),
)

private const val MAX_ERROR_MESSAGE_LENGTH: Int = 4_096
