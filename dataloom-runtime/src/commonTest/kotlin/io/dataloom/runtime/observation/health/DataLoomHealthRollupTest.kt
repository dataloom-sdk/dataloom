package io.dataloom.runtime.observation.health

import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.operational.OperationalEventOutboxScope
import io.dataloom.api.operational.OperationalEventOutboxStateObservation
import io.dataloom.api.operational.OperationalEventOutboxSummary
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderLifecycleCoordinatorState
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.observation.retry.RetryCircuitExporterHealth
import io.dataloom.runtime.observation.retry.RetryCircuitExporterSnapshot
import io.dataloom.runtime.observation.retry.RetryCircuitTelemetryExporterId
import io.dataloom.runtime.observation.retry.RetryCircuitTelemetrySnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Table-driven verification of [dataLoomHealthSnapshot]'s severity roll-up
 * for the two subsystems it aggregates through synchronous read paths
 * (outbox and queue worker), the staleness rule, threshold configuration,
 * and absence-is-inert. Every case states its expected severity *and* the
 * exact finding codes, so a threshold drifting by one shows up as a named
 * failure.
 */
class DataLoomHealthRollupTest {

    private val now = DataLoomInstant(1_000_000_000L)
    private val scope = OperationalEventOutboxScope("events")

    private fun ago(duration: Duration): DataLoomInstant = DataLoomInstant(now.epochMilliseconds - duration.inWholeMilliseconds)

    // ---- absence is inert -------------------------------------------------------------------

    @Test
    fun aSnapshotBuiltWithNoCollaboratorsIsHealthyWithNoFindingsAndNoNewSections() {
        val snapshot = dataLoomHealthSnapshot()

        assertEquals(DataLoomHealthSeverity.HEALTHY, snapshot.severity)
        assertEquals(emptyList(), snapshot.findings)
        assertEquals(emptyList(), snapshot.outboxHealth)
        assertNull(snapshot.queueWorkerHealth)
        assertNull(snapshot.providerLifecycleState)
        assertNull(snapshot.retryCircuitTelemetry)
        assertTrue(snapshot.providerHealth.isEmpty())
    }

    @Test
    fun nowIsRequiredExactlyWhenObservationsAreSuppliedAndNotOtherwise() {
        dataLoomHealthSnapshot(providerLifecycleState = ProviderLifecycleCoordinatorState.INITIALIZED) // no now needed
        assertFailsWith<IllegalArgumentException> {
            dataLoomHealthSnapshot(outboxObservations = listOf(OperationalEventOutboxObservedState(scope, null, null)))
        }
        assertFailsWith<IllegalArgumentException> { dataLoomHealthSnapshot(queueWorkerObservation = workerState()) }
    }

    // ---- outbox roll-up ---------------------------------------------------------------------

    private data class OutboxCase(
        val name: String,
        val pending: Int,
        val oldestAge: Duration?,
        val expectedSeverity: DataLoomHealthSeverity,
        val expectedCodes: Set<DataLoomHealthFindingCode>,
    )

    @Test
    fun outboxDepthAndAgeRollUpAgainstTheDefaultThresholds() {
        val healthy = DataLoomHealthSeverity.HEALTHY
        val degraded = DataLoomHealthSeverity.DEGRADED
        val unhealthy = DataLoomHealthSeverity.UNHEALTHY
        val cases = listOf(
            OutboxCase("empty", 0, null, healthy, emptySet()),
            OutboxCase("just under the degraded depth", 999, null, healthy, emptySet()),
            OutboxCase("at the degraded depth", 1_000, null, degraded, setOf(DataLoomHealthFindingCode.OUTBOX_PENDING_HIGH)),
            OutboxCase("just under the unhealthy depth", 4_999, null, degraded, setOf(DataLoomHealthFindingCode.OUTBOX_PENDING_HIGH)),
            OutboxCase("at the unhealthy depth", 5_000, null, unhealthy, setOf(DataLoomHealthFindingCode.OUTBOX_PENDING_CRITICAL)),
            OutboxCase("far past the unhealthy depth", 9_999, null, unhealthy, setOf(DataLoomHealthFindingCode.OUTBOX_PENDING_CRITICAL)),
            OutboxCase("oldest just under the degraded age", 1, 1.hours - 1.milliseconds, healthy, emptySet()),
            OutboxCase("oldest at the degraded age", 1, 1.hours, degraded, setOf(DataLoomHealthFindingCode.OUTBOX_OLDEST_PENDING_OLD)),
            OutboxCase("oldest just under the unhealthy age", 1, 24.hours - 1.milliseconds, degraded, setOf(DataLoomHealthFindingCode.OUTBOX_OLDEST_PENDING_OLD)),
            OutboxCase("oldest at the unhealthy age", 1, 24.hours, unhealthy, setOf(DataLoomHealthFindingCode.OUTBOX_OLDEST_PENDING_VERY_OLD)),
            OutboxCase(
                "deep and old together report both",
                5_000,
                24.hours,
                unhealthy,
                setOf(DataLoomHealthFindingCode.OUTBOX_PENDING_CRITICAL, DataLoomHealthFindingCode.OUTBOX_OLDEST_PENDING_VERY_OLD),
            ),
            OutboxCase(
                "high and old at different levels roll up to the worst",
                1_000,
                24.hours,
                unhealthy,
                setOf(DataLoomHealthFindingCode.OUTBOX_PENDING_HIGH, DataLoomHealthFindingCode.OUTBOX_OLDEST_PENDING_VERY_OLD),
            ),
        )

        cases.forEach { case ->
            val health = outboxSnapshot(freshObservation(case.pending, case.oldestAge))
            assertEquals(case.expectedSeverity, health.severity, "severity for '${case.name}'")
            assertEquals(case.expectedCodes, health.findings.map { it.code }.toSet(), "codes for '${case.name}'")
            assertEquals(case.expectedSeverity, health.outboxHealth.single().severity, "section severity for '${case.name}'")
            health.findings.forEach { finding ->
                assertEquals(DataLoomHealthComponent.OPERATIONAL_EVENT_OUTBOX, finding.component)
                assertEquals("events", finding.subject)
            }
        }
    }

    @Test
    fun outboxSectionReportsCountsAndAgesAsOfNowAndTheObservation() {
        val observation = observation(pending = 7, oldest = ago(90.minutes), acknowledged = 3, observedAt = ago(2.minutes), version = 12L)

        val health = outboxSnapshot(OperationalEventOutboxObservedState(scope, observation, null)).outboxHealth.single()

        assertEquals(7, health.pendingCount)
        assertEquals(3, health.acknowledgedRetainedCount)
        assertEquals(90.minutes, health.oldestPendingAge)
        assertEquals(ago(2.minutes), health.observedAt)
        assertEquals(2.minutes, health.observationAge)
        assertFalse(health.stale)
    }

    private data class StaleCase(
        val name: String,
        val observationAge: Duration,
        val pending: Int,
        val expectedStale: Boolean,
        val expectedSeverity: DataLoomHealthSeverity,
    )

    @Test
    fun staleObservationsAreFlaggedAndOnlyDegradeWhenPendingEntriesWereLastSeen() {
        val cases = listOf(
            StaleCase("fresh with pending", 9.minutes + 59.seconds, 5, false, DataLoomHealthSeverity.HEALTHY),
            StaleCase("exactly at the stale age with pending", 10.minutes, 5, true, DataLoomHealthSeverity.DEGRADED),
            StaleCase("long stale with pending", 3.hours, 5, true, DataLoomHealthSeverity.DEGRADED),
            StaleCase("stale but empty when last seen", 3.hours, 0, true, DataLoomHealthSeverity.HEALTHY),
            StaleCase("fresh and empty", 1.seconds, 0, false, DataLoomHealthSeverity.HEALTHY),
        )

        cases.forEach { case ->
            val observed = OperationalEventOutboxObservedState(
                scope,
                observation(pending = case.pending, oldest = if (case.pending > 0) ago(case.observationAge) else null, observedAt = ago(case.observationAge)),
                null,
            )
            val snapshot = dataLoomHealthSnapshot(outboxObservations = listOf(observed), now = now)
            val health = snapshot.outboxHealth.single()
            assertEquals(case.expectedStale, health.stale, "stale for '${case.name}'")
            assertEquals(case.expectedSeverity, snapshot.severity, "severity for '${case.name}'")
            if (case.expectedStale && case.pending > 0) {
                assertTrue(
                    snapshot.findings.any { it.code == DataLoomHealthFindingCode.OUTBOX_OBSERVATION_STALE_WITH_PENDING },
                    "stale finding for '${case.name}'",
                )
            }
        }
    }

    @Test
    fun aScopeThatWasNeverObservedIsNeitherStaleNorHealthyByAssumptionJustUnknown() {
        val cycleOnly = OperationalEventOutboxObservedState(
            scope,
            stateObservation = null,
            lastProcessingCycle = cycle(OperationalEventOutboxProcessingCycleOutcome.NO_WORK),
        )

        val health = dataLoomHealthSnapshot(outboxObservations = listOf(cycleOnly), now = now).outboxHealth.single()

        assertNull(health.pendingCount)
        assertNull(health.observedAt)
        assertNull(health.observationAge)
        assertNull(health.oldestPendingAge)
        assertFalse(health.stale)
    }

    private data class CycleCase(
        val name: String,
        val outcome: OperationalEventOutboxProcessingCycleOutcome,
        val skipped: Int,
        val failed: Int,
        val acknowledgeFailed: Int,
        val expectedFlag: Boolean,
        val expectedSeverity: DataLoomHealthSeverity,
        val expectedCodes: Set<DataLoomHealthFindingCode>,
    )

    @Test
    fun theLastProcessingCycleRollsUpAsAFactAboutThatCycle() {
        val leftPending = setOf(DataLoomHealthFindingCode.OUTBOX_LAST_CYCLE_LEFT_ENTRIES_PENDING)
        val cases = listOf(
            CycleCase("clean cycle", OperationalEventOutboxProcessingCycleOutcome.PROCESSED, 0, 0, 0, false, DataLoomHealthSeverity.HEALTHY, emptySet()),
            CycleCase("no work", OperationalEventOutboxProcessingCycleOutcome.NO_WORK, 0, 0, 0, false, DataLoomHealthSeverity.HEALTHY, emptySet()),
            CycleCase("one skipped", OperationalEventOutboxProcessingCycleOutcome.PROCESSED, 1, 0, 0, true, DataLoomHealthSeverity.DEGRADED, leftPending),
            CycleCase("one failed", OperationalEventOutboxProcessingCycleOutcome.PROCESSED, 0, 1, 0, true, DataLoomHealthSeverity.DEGRADED, leftPending),
            CycleCase("acknowledge failed", OperationalEventOutboxProcessingCycleOutcome.PROCESSED, 0, 0, 2, true, DataLoomHealthSeverity.DEGRADED, leftPending),
            CycleCase(
                "read failure",
                OperationalEventOutboxProcessingCycleOutcome.READ_FAILURE,
                0, 0, 0,
                false,
                DataLoomHealthSeverity.DEGRADED,
                setOf(DataLoomHealthFindingCode.OUTBOX_LAST_CYCLE_READ_FAILED),
            ),
        )

        cases.forEach { case ->
            val observed = OperationalEventOutboxObservedState(
                scope,
                freshObservation(0, null),
                cycle(case.outcome, case.skipped, case.failed, case.acknowledgeFailed),
            )
            val snapshot = dataLoomHealthSnapshot(outboxObservations = listOf(observed), now = now)
            assertEquals(case.expectedFlag, snapshot.outboxHealth.single().hasSkippedOrFailedEntries, "flag for '${case.name}'")
            assertEquals(case.expectedSeverity, snapshot.severity, "severity for '${case.name}'")
            assertEquals(case.expectedCodes, snapshot.findings.map { it.code }.toSet(), "codes for '${case.name}'")
        }
    }

    @Test
    fun eachOutboxScopeGetsItsOwnHealthAndFindingsCarryTheirScope() {
        val quiet = OperationalEventOutboxScope("quiet")
        val deep = OperationalEventOutboxScope("deep")
        val snapshot = dataLoomHealthSnapshot(
            outboxObservations = listOf(
                OperationalEventOutboxObservedState(quiet, freshObservation(1, null, quiet), null),
                OperationalEventOutboxObservedState(deep, freshObservation(6_000, null, deep), null),
            ),
            now = now,
        )

        assertEquals(listOf(DataLoomHealthSeverity.HEALTHY, DataLoomHealthSeverity.UNHEALTHY), snapshot.outboxHealth.map { it.severity })
        assertEquals(listOf("deep"), snapshot.findings.map { it.subject })
        assertEquals(DataLoomHealthSeverity.UNHEALTHY, snapshot.severity)
    }

    // ---- queue worker roll-up ----------------------------------------------------------------

    private data class WorkerFailureCase(val consecutiveFailures: Int, val expected: DataLoomHealthSeverity, val code: DataLoomHealthFindingCode?)

    @Test
    fun consecutiveWorkerFailuresRollUpAgainstTheDefaultThresholds() {
        val cases = listOf(
            WorkerFailureCase(0, DataLoomHealthSeverity.HEALTHY, null),
            WorkerFailureCase(1, DataLoomHealthSeverity.DEGRADED, DataLoomHealthFindingCode.QUEUE_WORKER_RUNS_FAILING),
            WorkerFailureCase(2, DataLoomHealthSeverity.DEGRADED, DataLoomHealthFindingCode.QUEUE_WORKER_RUNS_FAILING),
            WorkerFailureCase(3, DataLoomHealthSeverity.UNHEALTHY, DataLoomHealthFindingCode.QUEUE_WORKER_RUNS_FAILING_REPEATEDLY),
            WorkerFailureCase(50, DataLoomHealthSeverity.UNHEALTHY, DataLoomHealthFindingCode.QUEUE_WORKER_RUNS_FAILING_REPEATEDLY),
        )

        cases.forEach { case ->
            val snapshot = dataLoomHealthSnapshot(
                queueWorkerObservation = workerState(consecutiveFailedRuns = case.consecutiveFailures, lastRunStartedAt = ago(1.minutes)),
                now = now,
            )
            assertEquals(case.expected, snapshot.severity, "severity for ${case.consecutiveFailures} failures")
            assertEquals(listOfNotNull(case.code), snapshot.findings.map { it.code }, "codes for ${case.consecutiveFailures} failures")
            assertEquals(case.expected, snapshot.queueWorkerHealth?.severity)
            snapshot.findings.forEach { assertEquals(DataLoomHealthComponent.QUEUE_WORKER, it.component) }
        }
    }

    private data class StuckCase(val name: String, val inFlight: Int, val startedAgo: Duration?, val stuck: Boolean)

    @Test
    fun aRunInFlightForTooLongIsStuckButAnOldFinishedRunIsNot() {
        val cases = listOf(
            StuckCase("running just under the limit", 1, 29.minutes + 59.seconds, false),
            StuckCase("running at the limit", 1, 30.minutes, true),
            StuckCase("several in flight, youngest already old", 3, 2.hours, true),
            StuckCase("idle with an old last start", 0, 5.hours, false),
            StuckCase("never started", 0, null, false),
        )

        cases.forEach { case ->
            val snapshot = dataLoomHealthSnapshot(
                queueWorkerObservation = workerState(runsInFlight = case.inFlight, lastRunStartedAt = case.startedAgo?.let { ago(it) }),
                now = now,
            )
            val codes = snapshot.findings.map { it.code }
            assertEquals(case.stuck, DataLoomHealthFindingCode.QUEUE_WORKER_RUN_STUCK in codes, "stuck for '${case.name}'")
            assertEquals(
                if (case.stuck) DataLoomHealthSeverity.DEGRADED else DataLoomHealthSeverity.HEALTHY,
                snapshot.severity,
                "severity for '${case.name}'",
            )
        }
    }

    @Test
    fun workerActivityDistinguishesNeverRunIdleAndRunning() {
        fun activity(state: QueueWorkerObservedState) =
            dataLoomHealthSnapshot(queueWorkerObservation = state, now = now).queueWorkerHealth?.activity

        assertEquals(QueueWorkerActivity.NEVER_RUN, activity(workerState()))
        assertEquals(QueueWorkerActivity.IDLE, activity(workerState(lastRunStartedAt = ago(1.minutes))))
        assertEquals(QueueWorkerActivity.RUNNING, activity(workerState(runsInFlight = 1, lastRunStartedAt = ago(1.minutes))))
    }

    @Test
    fun aQuietWorkerIsFlaggedStaleForInformationButNeverDegraded() {
        val quiet = workerState(
            lastRunStartedAt = ago(30.hours),
            lastEventAt = ago(6.hours),
            lastEmptyQueueAt = ago(6.hours),
            lastCompletedRunAt = ago(6.hours),
        )

        val snapshot = dataLoomHealthSnapshot(queueWorkerObservation = quiet, now = now)

        val health = checkNotNull(snapshot.queueWorkerHealth)
        assertTrue(health.stale)
        assertEquals(6.hours, health.observationAge)
        assertEquals(ago(6.hours), health.lastEmptyQueueAt)
        assertEquals(DataLoomHealthSeverity.HEALTHY, snapshot.severity)
        assertFalse(dataLoomHealthSnapshot(queueWorkerObservation = workerState(lastEventAt = ago(5.hours)), now = now).queueWorkerHealth!!.stale)
    }

    @Test
    fun aNeverRunWorkerReportsWhenTrackingBeganAsItsObservationTime() {
        val health = dataLoomHealthSnapshot(queueWorkerObservation = workerState(trackedSince = ago(10.minutes)), now = now)
            .queueWorkerHealth!!

        assertEquals(ago(10.minutes), health.observedAt)
        assertEquals(10.minutes, health.observationAge)
        assertNull(health.lastRunStartedAt)
    }

    @Test
    fun workerFailureIsReportedAsRedactedClosedVocabularyOnlyAndOnlyWhileFailing() {
        val failure = QueueWorkerFailureDescriptor(
            ErrorCode("QUEUE-STORE-DOWN"),
            ErrorCategory.STORAGE,
            ErrorSeverity.ERROR,
            Recoverability.RECOVERABLE,
        )

        val failing = dataLoomHealthSnapshot(
            queueWorkerObservation = workerState(consecutiveFailedRuns = 1, lastFailure = failure, lastRunStartedAt = ago(1.minutes)),
            now = now,
        ).queueWorkerHealth!!.lastFailure!!
        assertEquals(setOf("code", "category", "severity", "recoverability"), failing.entries.keys)
        assertEquals("QUEUE-STORE-DOWN", failing["code"])
        assertEquals("STORAGE", failing["category"])

        val recovered = dataLoomHealthSnapshot(
            queueWorkerObservation = workerState(consecutiveFailedRuns = 0, lastFailure = failure, lastRunStartedAt = ago(1.minutes)),
            now = now,
        ).queueWorkerHealth!!
        assertNull(recovered.lastFailure)
    }

    // ---- overall roll-up ----------------------------------------------------------------------

    @Test
    fun overallSeverityIsTheWorstFindingAcrossEverySuppliedSectionInAFixedOrder() {
        val telemetry = RetryCircuitTelemetrySnapshot(
            metricCounts = emptyMap(),
            exporters = listOf(exporter("primary", RetryCircuitExporterHealth.STOPPED), exporter("fine", RetryCircuitExporterHealth.HEALTHY)),
        )

        val snapshot = dataLoomHealthSnapshot(
            providerLifecycleState = ProviderLifecycleCoordinatorState.NOT_INITIALIZED,
            retryCircuitTelemetry = telemetry,
            providerHealth = mapOf(
                ProviderId("b-storage") to ProviderHealth(ProviderHealthStatus.DEGRADED),
                ProviderId("a-transport") to ProviderHealth(ProviderHealthStatus.UNKNOWN),
                ProviderId("c-queue") to ProviderHealth(ProviderHealthStatus.HEALTHY),
            ),
            outboxObservations = listOf(OperationalEventOutboxObservedState(scope, freshObservation(1, null), null)),
            queueWorkerObservation = workerState(consecutiveFailedRuns = 3, lastRunStartedAt = ago(1.minutes)),
            now = now,
        )

        assertEquals(
            listOf(
                DataLoomHealthComponent.PROVIDER to DataLoomHealthFindingCode.PROVIDER_DEGRADED,
                DataLoomHealthComponent.TELEMETRY_EXPORTER to DataLoomHealthFindingCode.EXPORTER_STOPPED,
                DataLoomHealthComponent.QUEUE_WORKER to DataLoomHealthFindingCode.QUEUE_WORKER_RUNS_FAILING_REPEATEDLY,
            ),
            snapshot.findings.map { it.component to it.code },
        )
        assertEquals(listOf("b-storage", "primary", null), snapshot.findings.map { it.subject })
        assertEquals(DataLoomHealthSeverity.UNHEALTHY, snapshot.severity)
        // The lifecycle phase is reported but is not a health verdict.
        assertEquals(ProviderLifecycleCoordinatorState.NOT_INITIALIZED, snapshot.providerLifecycleState)
    }

    @Test
    fun providerAndExporterStatusesMapToSeveritiesAsDocumented() {
        val providerCases = mapOf(
            ProviderHealthStatus.HEALTHY to DataLoomHealthSeverity.HEALTHY,
            ProviderHealthStatus.UNKNOWN to DataLoomHealthSeverity.HEALTHY,
            ProviderHealthStatus.DEGRADED to DataLoomHealthSeverity.DEGRADED,
            ProviderHealthStatus.UNHEALTHY to DataLoomHealthSeverity.UNHEALTHY,
        )
        providerCases.forEach { (status, expected) ->
            val snapshot = dataLoomHealthSnapshot(providerHealth = mapOf(ProviderId("p") to ProviderHealth(status)))
            assertEquals(expected, snapshot.severity, "provider $status")
        }
        val exporterCases = mapOf(
            RetryCircuitExporterHealth.HEALTHY to DataLoomHealthSeverity.HEALTHY,
            RetryCircuitExporterHealth.DEGRADED to DataLoomHealthSeverity.DEGRADED,
            RetryCircuitExporterHealth.STOPPED to DataLoomHealthSeverity.DEGRADED,
        )
        exporterCases.forEach { (health, expected) ->
            val snapshot = dataLoomHealthSnapshot(
                retryCircuitTelemetry = RetryCircuitTelemetrySnapshot(emptyMap(), listOf(exporter("e", health))),
            )
            assertEquals(expected, snapshot.severity, "exporter $health")
        }
    }

    // ---- configurable thresholds --------------------------------------------------------------

    @Test
    fun customThresholdsMoveTheBoundariesTheyName() {
        val strict = DataLoomHealthThresholds(
            outboxPendingDegradedAt = 3,
            outboxPendingUnhealthyAt = 6,
            outboxOldestPendingDegradedAfter = 1.minutes,
            outboxOldestPendingUnhealthyAfter = 2.minutes,
            outboxObservationStaleAfter = 30.seconds,
            queueWorkerFailedRunsDegradedAt = 2,
            queueWorkerFailedRunsUnhealthyAt = 4,
            queueWorkerRunStuckAfter = 1.minutes,
            queueWorkerObservationStaleAfter = 1.hours,
        )

        fun outbox(pending: Int, oldestAge: Duration? = null, observationAge: Duration = 1.seconds) = dataLoomHealthSnapshot(
            outboxObservations = listOf(
                OperationalEventOutboxObservedState(
                    scope,
                    observation(pending, oldestAge?.let { ago(it) }, observedAt = ago(observationAge)),
                    null,
                ),
            ),
            now = now,
            thresholds = strict,
        ).severity

        assertEquals(DataLoomHealthSeverity.HEALTHY, outbox(2))
        assertEquals(DataLoomHealthSeverity.DEGRADED, outbox(3))
        assertEquals(DataLoomHealthSeverity.UNHEALTHY, outbox(6))
        assertEquals(DataLoomHealthSeverity.DEGRADED, outbox(1, oldestAge = 1.minutes))
        assertEquals(DataLoomHealthSeverity.UNHEALTHY, outbox(1, oldestAge = 2.minutes))
        assertEquals(DataLoomHealthSeverity.DEGRADED, outbox(1, observationAge = 30.seconds))

        fun worker(failures: Int) = dataLoomHealthSnapshot(
            queueWorkerObservation = workerState(consecutiveFailedRuns = failures, lastRunStartedAt = ago(1.seconds)),
            now = now,
            thresholds = strict,
        ).severity

        assertEquals(DataLoomHealthSeverity.HEALTHY, worker(1))
        assertEquals(DataLoomHealthSeverity.DEGRADED, worker(2))
        assertEquals(DataLoomHealthSeverity.UNHEALTHY, worker(4))
    }

    @Test
    fun thresholdsRejectInconsistentValues() {
        assertFailsWith<IllegalArgumentException> { DataLoomHealthThresholds(outboxPendingDegradedAt = 0) }
        assertFailsWith<IllegalArgumentException> {
            DataLoomHealthThresholds(outboxPendingDegradedAt = 10, outboxPendingUnhealthyAt = 9)
        }
        assertFailsWith<IllegalArgumentException> {
            DataLoomHealthThresholds(outboxOldestPendingDegradedAfter = 2.hours, outboxOldestPendingUnhealthyAfter = 1.hours)
        }
        assertFailsWith<IllegalArgumentException> { DataLoomHealthThresholds(outboxObservationStaleAfter = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { DataLoomHealthThresholds(queueWorkerFailedRunsDegradedAt = 0) }
        assertFailsWith<IllegalArgumentException> {
            DataLoomHealthThresholds(queueWorkerFailedRunsDegradedAt = 3, queueWorkerFailedRunsUnhealthyAt = 2)
        }
        assertFailsWith<IllegalArgumentException> { DataLoomHealthThresholds(queueWorkerRunStuckAfter = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { DataLoomHealthThresholds(queueWorkerObservationStaleAfter = Duration.ZERO) }
        // The defaults themselves must be valid.
        DataLoomHealthThresholds()
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun observation(
        pending: Int,
        oldest: DataLoomInstant?,
        acknowledged: Int = 0,
        observedAt: DataLoomInstant = ago(1.seconds),
        version: Long? = 1L,
        scope: OperationalEventOutboxScope = this.scope,
    ) = OperationalEventOutboxStateObservation(scope, OperationalEventOutboxSummary(pending, oldest, acknowledged), version, observedAt)

    private fun freshObservation(
        pending: Int,
        oldestAge: Duration?,
        scope: OperationalEventOutboxScope = this.scope,
    ) = observation(pending, oldestAge?.let { ago(it) }, scope = scope)

    private fun outboxSnapshot(observation: OperationalEventOutboxStateObservation) =
        outboxSnapshot(OperationalEventOutboxObservedState(observation.scope, observation, null))

    private fun outboxSnapshot(observed: OperationalEventOutboxObservedState) =
        dataLoomHealthSnapshot(outboxObservations = listOf(observed), now = now)

    private fun cycle(
        outcome: OperationalEventOutboxProcessingCycleOutcome,
        skipped: Int = 0,
        failed: Int = 0,
        acknowledgeFailed: Int = 0,
    ) = OperationalEventOutboxProcessingCycleObservation(outcome, skipped, failed, acknowledgeFailed, ago(1.seconds))

    private fun workerState(
        runsInFlight: Int = 0,
        lastRunStartedAt: DataLoomInstant? = null,
        lastCompletedRunAt: DataLoomInstant? = null,
        lastEmptyQueueAt: DataLoomInstant? = null,
        consecutiveFailedRuns: Int = 0,
        lastFailure: QueueWorkerFailureDescriptor? = null,
        lastEventAt: DataLoomInstant? = lastRunStartedAt,
        trackedSince: DataLoomInstant = ago(100.hours),
    ) = QueueWorkerObservedState(
        runsInFlight = runsInFlight,
        lastRunStartedAt = lastRunStartedAt,
        lastCompletedRunAt = lastCompletedRunAt,
        lastEmptyQueueAt = lastEmptyQueueAt,
        lastRunOutcome = null,
        consecutiveFailedRuns = consecutiveFailedRuns,
        lastFailure = lastFailure,
        lastEventAt = lastEventAt,
        trackedSince = trackedSince,
    )

    private fun exporter(id: String, health: RetryCircuitExporterHealth) = RetryCircuitExporterSnapshot(
        exporterId = RetryCircuitTelemetryExporterId(id),
        health = health,
        acceptedCount = 0L,
        droppedCount = 0L,
        exportedCount = 0L,
        failureCount = 0L,
        timeoutCount = 0L,
        lastFailureReason = null,
    )
}
