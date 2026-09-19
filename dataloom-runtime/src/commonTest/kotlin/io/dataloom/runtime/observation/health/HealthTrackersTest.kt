package io.dataloom.runtime.observation.health

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.QueueConsumerId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.operational.DurableOperationalEventOutbox
import io.dataloom.api.operational.OperationalEventCategory
import io.dataloom.api.operational.OperationalEventEnvelope
import io.dataloom.api.operational.OperationalEventId
import io.dataloom.api.operational.OperationalEventOutboxScope
import io.dataloom.api.operational.OperationalEventOutboxState
import io.dataloom.api.operational.OperationalEventOutboxStateObservation
import io.dataloom.api.operational.OperationalEventOutboxSummary
import io.dataloom.api.operational.OperationalEventSource
import io.dataloom.api.operational.OperationalEventType
import io.dataloom.api.operational.OperationalPayloadDescriptor
import io.dataloom.api.operational.OperationalPayloadEncoding
import io.dataloom.api.operational.OperationalPayloadType
import io.dataloom.api.operational.OperationalSchemaVersion
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.queue.QueueAcquireRequest
import io.dataloom.api.security.DataClassification
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateRecord
import io.dataloom.api.state.DurableStateStore
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.facade.DataLoomCircuitQueueWorker
import io.dataloom.runtime.facade.DataLoomQueueWorker
import io.dataloom.runtime.facade.withHealthTracking
import io.dataloom.runtime.operational.DurableOperationalEventOutboxProcessor
import io.dataloom.runtime.operational.OperationalEventOutboxEntryOutcome
import io.dataloom.runtime.queue.CircuitBreakerQueueProcessingResult
import io.dataloom.runtime.queue.QueueCircuitOperationRecord
import io.dataloom.runtime.queue.QueueCircuitProviderFailureDisposition
import io.dataloom.runtime.queue.QueueProcessingFailureStage
import io.dataloom.runtime.queue.QueueProcessingRequest
import io.dataloom.runtime.queue.QueueProcessingResult
import io.dataloom.runtime.queue.QueueProcessingSummary
import io.dataloom.runtime.retry.CircuitBreakerRecordResult
import io.dataloom.runtime.retry.QueueCircuitOperation
import io.dataloom.runtime.worker.CircuitBreakerQueueWorkerRecoveryResult
import io.dataloom.runtime.worker.CircuitBreakerQueueWorkerRunResult
import io.dataloom.runtime.worker.QueueWorkerRunRequest
import io.dataloom.runtime.worker.QueueWorkerRunResult
import io.dataloom.runtime.worker.QueueWorkerSchedulingResult
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

/**
 * Integration tests for the two trackers that give [dataLoomHealthSnapshot]
 * its synchronous read paths: the outbox tracker fed by a real
 * [DurableOperationalEventOutbox] and processor over an in-memory store, and
 * the queue-worker tracker fed by the `withHealthTracking` wrappers.
 */
class HealthTrackersTest {

    private val clock = MutableClock(1_000_000L)
    private val scope = OperationalEventOutboxScope("events")

    // ---- outbox tracker -------------------------------------------------------------------------

    @Test
    fun aFreshTrackerHasObservedNothingAndSnapshotIsSynchronous() {
        val tracker = OperationalEventOutboxHealthTracker(clock)

        // A plain (non-suspend) call: the read path cannot suspend or do I/O.
        assertEquals(emptyList(), tracker.snapshot())
    }

    @Test
    fun anObservingOutboxKeepsTheTrackersSnapshotCurrentWithoutAnyReadFromTheHealthCaller() = runTest {
        val tracker = OperationalEventOutboxHealthTracker(clock)
        val outbox = DurableOperationalEventOutbox(InMemoryStore(), clock, stateObserver = tracker)

        outbox.append(scope, envelope("e1", occurredAt = 500L))
        outbox.append(scope, envelope("e2", occurredAt = 200L))
        outbox.acknowledge(scope, OperationalEventId("e2"))

        val observed = tracker.snapshot().single()
        assertEquals(scope, observed.scope)
        assertEquals(OperationalEventOutboxSummary(1, DataLoomInstant(500L), 1), observed.stateObservation?.summary)
        assertEquals(2L, observed.stateObservation?.storeVersion)
        assertNull(observed.lastProcessingCycle)
    }

    @Test
    fun theTrackerIsNotAuthoritativeAnotherInstancesWritesAreInvisibleUntilTheObservingOutboxTouchesTheStore() = runTest {
        val store = InMemoryStore()
        val tracker = OperationalEventOutboxHealthTracker(clock)
        val observing = DurableOperationalEventOutbox(store, clock, stateObserver = tracker)
        val otherProcess = DurableOperationalEventOutbox(store, clock)
        observing.append(scope, envelope("e1"))
        (2..6).forEach { otherProcess.append(scope, envelope("e$it")) }
        val strict = DataLoomHealthThresholds(outboxPendingDegradedAt = 5, outboxPendingUnhealthyAt = 10)

        // The store now holds six pending entries but this process last saw one.
        val stale = dataLoomHealthSnapshot(outboxObservations = tracker.snapshot(), now = clock.now(), thresholds = strict)
        assertEquals(1, stale.outboxHealth.single().pendingCount)
        assertEquals(DataLoomHealthSeverity.HEALTHY, stale.severity)

        // Once the observing outbox reads, the next snapshot reflects the truth.
        observing.entries(scope)
        val refreshed = dataLoomHealthSnapshot(outboxObservations = tracker.snapshot(), now = clock.now(), thresholds = strict)
        assertEquals(6, refreshed.outboxHealth.single().pendingCount)
        assertEquals(DataLoomHealthSeverity.DEGRADED, refreshed.severity)
    }

    @Test
    fun anObservationAgesIntoStalenessAsTheCallerSuppliedNowAdvances() = runTest {
        val tracker = OperationalEventOutboxHealthTracker(clock)
        DurableOperationalEventOutbox(InMemoryStore(), clock, stateObserver = tracker).append(scope, envelope("e1", occurredAt = clock.now().epochMilliseconds))
        val observedAt = clock.now()

        fun health(ageSeconds: Long) = dataLoomHealthSnapshot(
            outboxObservations = tracker.snapshot(),
            now = DataLoomInstant(observedAt.epochMilliseconds + ageSeconds * 1_000L),
        ).outboxHealth.single()

        assertFalse(health(599).stale)
        assertTrue(health(600).stale)
        assertEquals(600.seconds, health(600).observationAge)
    }

    @Test
    fun aLateArrivingOlderVersionNeverReplacesANewerObservation() {
        val tracker = OperationalEventOutboxHealthTracker(clock)
        fun obs(version: Long?, pending: Int, at: Long) = OperationalEventOutboxStateObservation(
            scope, OperationalEventOutboxSummary(pending, null, 0), version, DataLoomInstant(at),
        )

        tracker.onStateObserved(obs(5L, pending = 50, at = 10L))
        tracker.onStateObserved(obs(3L, pending = 30, at = 20L)) // older store state, later wall clock
        assertEquals(50, tracker.snapshot().single().stateObservation?.summary?.pendingCount)

        tracker.onStateObserved(obs(5L, pending = 50, at = 30L)) // same version: newer observation wins
        assertEquals(DataLoomInstant(30L), tracker.snapshot().single().stateObservation?.observedAt)

        tracker.onStateObserved(obs(6L, pending = 60, at = 40L))
        assertEquals(60, tracker.snapshot().single().stateObservation?.summary?.pendingCount)

        // A scope with no record yet (null version) never replaces a real one.
        tracker.onStateObserved(obs(null, pending = 0, at = 50L))
        assertEquals(60, tracker.snapshot().single().stateObservation?.summary?.pendingCount)
    }

    @Test
    fun scopesAreTrackedIndependentlyAndReportedInScopeNameOrder() = runTest {
        val tracker = OperationalEventOutboxHealthTracker(clock)
        val outbox = DurableOperationalEventOutbox(InMemoryStore(), clock, stateObserver = tracker)
        outbox.append(OperationalEventOutboxScope("zeta"), envelope("z1"))
        outbox.append(OperationalEventOutboxScope("alpha"), envelope("a1"))
        outbox.append(OperationalEventOutboxScope("alpha"), envelope("a2"))

        assertEquals(listOf("alpha", "zeta"), tracker.snapshot().map { it.scope.value })
        assertEquals(listOf(2, 1), tracker.snapshot().map { it.stateObservation?.summary?.pendingCount })
    }

    @Test
    fun aTrackedProcessorReportsWhatEachCycleLeftPendingAndTheOutboxNeverGetsItFromAnywhereElse() = runTest {
        val tracker = OperationalEventOutboxHealthTracker(clock)
        val outbox = DurableOperationalEventOutbox(InMemoryStore(), clock, stateObserver = tracker)
        val processor = DurableOperationalEventOutboxProcessor(outbox, tracker)
        listOf("done", "skip", "fail").forEach { outbox.append(scope, envelope(it)) }

        processor.process(scope) { envelope ->
            when (envelope.id.value) {
                "done" -> OperationalEventOutboxEntryOutcome.Processed
                "skip" -> OperationalEventOutboxEntryOutcome.Skipped
                else -> OperationalEventOutboxEntryOutcome.Failed()
            }
        }

        val observed = tracker.snapshot().single()
        val cycle = checkNotNull(observed.lastProcessingCycle)
        assertEquals(OperationalEventOutboxProcessingCycleOutcome.PROCESSED, cycle.outcome)
        assertEquals(1, cycle.skipped)
        assertEquals(1, cycle.failed)
        assertEquals(2, cycle.entriesLeftPending)
        // The state observation reflects the acknowledgement the same cycle performed.
        assertEquals(2, observed.stateObservation?.summary?.pendingCount)
        assertEquals(1, observed.stateObservation?.summary?.acknowledgedRetainedCount)

        val snapshot = dataLoomHealthSnapshot(outboxObservations = tracker.snapshot(), now = clock.now())
        assertTrue(snapshot.outboxHealth.single().hasSkippedOrFailedEntries)
        assertEquals(DataLoomHealthSeverity.DEGRADED, snapshot.severity)

        // A later clean cycle clears the indicator.
        processor.process(scope) { OperationalEventOutboxEntryOutcome.Processed }
        val clean = dataLoomHealthSnapshot(outboxObservations = tracker.snapshot(), now = clock.now())
        assertFalse(clean.outboxHealth.single().hasSkippedOrFailedEntries)
        assertEquals(DataLoomHealthSeverity.HEALTHY, clean.severity)
    }

    @Test
    fun noWorkAndReadFailureCyclesAreRecordedToo() = runTest {
        val tracker = OperationalEventOutboxHealthTracker(clock)
        val empty = DurableOperationalEventOutboxProcessor(
            DurableOperationalEventOutbox(InMemoryStore(), clock, stateObserver = tracker),
            tracker,
        )
        empty.process(scope) { OperationalEventOutboxEntryOutcome.Processed }
        assertEquals(OperationalEventOutboxProcessingCycleOutcome.NO_WORK, tracker.snapshot().single().lastProcessingCycle?.outcome)

        val failingScope = OperationalEventOutboxScope("failing")
        val failing = DurableOperationalEventOutboxProcessor(
            DurableOperationalEventOutbox(FailingLoadStore(), clock, stateObserver = tracker),
            tracker,
        )
        failing.process(failingScope) { OperationalEventOutboxEntryOutcome.Processed }

        val failedScope = tracker.snapshot().single { it.scope == failingScope }
        assertEquals(OperationalEventOutboxProcessingCycleOutcome.READ_FAILURE, failedScope.lastProcessingCycle?.outcome)
        assertNull(failedScope.stateObservation) // the read failed, so nothing was ever observed
    }

    // ---- queue-worker tracker --------------------------------------------------------------------

    @Test
    fun aFreshWorkerTrackerReportsNeverRunNotEmpty() {
        val state = QueueWorkerHealthTracker(clock).snapshot()

        assertEquals(0, state.runsInFlight)
        assertNull(state.lastRunStartedAt)
        assertNull(state.lastRunOutcome)
        assertEquals(clock.now(), state.trackedSince)
    }

    @Test
    fun aRunIsVisibleAsInFlightWhileItExecutesAndIdleAfterwardsAndTheResultIsUnchanged() = runTest {
        val tracker = QueueWorkerHealthTracker(clock)
        val gate = CompletableDeferred<Unit>()
        val expected = completed(QueueProcessingResult.NoWork)
        val worker = FakeWorker { gate.await(); expected }.withHealthTracking(tracker)

        val run = async { worker.run(request()) }
        yield()
        assertEquals(1, tracker.snapshot().runsInFlight)
        assertEquals(clock.now(), tracker.snapshot().lastRunStartedAt)

        clock.advance(5_000L)
        gate.complete(Unit)
        assertSame(expected, run.await())

        val state = tracker.snapshot()
        assertEquals(0, state.runsInFlight)
        assertEquals(QueueWorkerRunOutcome.COMPLETED_NO_WORK, state.lastRunOutcome)
        assertEquals(clock.now(), state.lastCompletedRunAt)
        assertEquals(clock.now(), state.lastEmptyQueueAt)
        assertEquals(0, state.consecutiveFailedRuns)
    }

    private data class RunCase(
        val name: String,
        val result: QueueWorkerRunResult,
        val outcome: QueueWorkerRunOutcome,
        val failed: Boolean,
        val emptyQueue: Boolean,
        val errorCode: String?,
    )

    @Test
    fun everyDirectWorkerResultIsClassifiedIntoTheClosedOutcomeVocabulary() = runTest {
        val summary = QueueProcessingSummary(acquired = 1, executed = 1, completed = 1, rescheduled = 0, failed = 0, cancelled = 0)
        val stage = QueueProcessingFailureStage.entries.first()
        val cases = listOf(
            RunCase("no work", completed(QueueProcessingResult.NoWork), QueueWorkerRunOutcome.COMPLETED_NO_WORK, false, true, null),
            RunCase("processed", completed(QueueProcessingResult.Processed(summary)), QueueWorkerRunOutcome.COMPLETED_PROCESSED, false, false, null),
            RunCase("recovery failed", QueueWorkerRunResult.RecoveryFailed(fakeError("RECOVERY-DOWN")), QueueWorkerRunOutcome.RECOVERY_FAILED, true, false, "RECOVERY-DOWN"),
            RunCase(
                "provider failure",
                QueueWorkerRunResult.ProcessingFailed(null, QueueProcessingResult.QueueProviderFailure(fakeError("PROVIDER-DOWN"), stage, summary)),
                QueueWorkerRunOutcome.PROCESSING_FAILED, true, false, "PROVIDER-DOWN",
            ),
            RunCase(
                "contract violation",
                QueueWorkerRunResult.ProcessingFailed(null, QueueProcessingResult.QueueContractViolation(fakeError("CONTRACT"), summary)),
                QueueWorkerRunOutcome.PROCESSING_FAILED, true, false, "CONTRACT",
            ),
        )

        cases.forEach { case ->
            val tracker = QueueWorkerHealthTracker(clock)
            val returned = FakeWorker { case.result }.withHealthTracking(tracker).run(request())
            assertSame(case.result, returned, "result unchanged for '${case.name}'")
            val state = tracker.snapshot()
            assertEquals(case.outcome, state.lastRunOutcome, "outcome for '${case.name}'")
            assertEquals(if (case.failed) 1 else 0, state.consecutiveFailedRuns, "failure count for '${case.name}'")
            assertEquals(!case.failed, state.lastCompletedRunAt != null, "completed marker for '${case.name}'")
            assertEquals(case.emptyQueue, state.lastEmptyQueueAt != null, "empty-queue marker for '${case.name}'")
            assertEquals(case.errorCode, state.lastFailure?.code?.value, "error code for '${case.name}'")
        }
    }

    @Test
    fun theCircuitAwareWorkerIsClassifiedToo() = runTest {
        val recovery = CircuitBreakerQueueWorkerRecoveryResult.ProviderFailure(
            fakeError("CIRCUIT-RECOVERY"),
            QueueCircuitProviderFailureDisposition.CIRCUIT_FAILURE,
            CircuitBreakerRecordResult.Ignored,
        )
        val stopped = CircuitBreakerQueueWorkerRunResult.RecoveryStopped(recovery)
        val noWork = CircuitBreakerQueueWorkerRunResult.ProcessingCompleted(
            recoveryResult = CircuitBreakerQueueWorkerRecoveryResult.NotRequested,
            processingResult = CircuitBreakerQueueProcessingResult.NoWork(
                QueueCircuitOperationRecord(QueueCircuitOperation.entries.first(), null, CircuitBreakerRecordResult.Ignored),
            ),
            schedulingResult = QueueWorkerSchedulingResult.NotRequired,
        )
        val tracker = QueueWorkerHealthTracker(clock)

        val failing = FakeCircuitWorker { stopped }.withHealthTracking(tracker)
        assertSame(stopped, failing.run(request()))
        assertEquals(QueueWorkerRunOutcome.RECOVERY_FAILED, tracker.snapshot().lastRunOutcome)
        assertEquals("CIRCUIT-RECOVERY", tracker.snapshot().lastFailure?.code?.value)
        assertEquals(1, tracker.snapshot().consecutiveFailedRuns)

        val recovering = FakeCircuitWorker { noWork }.withHealthTracking(tracker)
        assertSame(noWork, recovering.run(request()))
        assertEquals(QueueWorkerRunOutcome.COMPLETED_NO_WORK, tracker.snapshot().lastRunOutcome)
        assertEquals(0, tracker.snapshot().consecutiveFailedRuns)
        assertNull(tracker.snapshot().lastFailure)
    }

    @Test
    fun consecutiveFailuresAccumulateAndAreResetByOneNormalCompletion() = runTest {
        val tracker = QueueWorkerHealthTracker(clock)
        val results = ArrayDeque(
            listOf(
                QueueWorkerRunResult.RecoveryFailed(fakeError("E1")),
                QueueWorkerRunResult.RecoveryFailed(fakeError("E2")),
                completed(QueueProcessingResult.NoWork),
            ),
        )
        val worker = FakeWorker { results.removeFirst() }.withHealthTracking(tracker)

        worker.run(request())
        worker.run(request())
        assertEquals(2, tracker.snapshot().consecutiveFailedRuns)
        assertEquals("E2", tracker.snapshot().lastFailure?.code?.value)

        worker.run(request())
        assertEquals(0, tracker.snapshot().consecutiveFailedRuns)
        assertNull(tracker.snapshot().lastFailure)
    }

    @Test
    fun anUnexpectedExceptionIsCountedAsAFailedRunAndRethrownUnchanged() = runTest {
        val tracker = QueueWorkerHealthTracker(clock)
        val boom = IllegalArgumentException("bad request")
        val worker = FakeWorker { throw boom }.withHealthTracking(tracker)

        val thrown = assertFailsWith<IllegalArgumentException> { worker.run(request()) }

        assertSame(boom, thrown)
        val state = tracker.snapshot()
        assertEquals(0, state.runsInFlight)
        assertEquals(QueueWorkerRunOutcome.UNEXPECTED_EXCEPTION, state.lastRunOutcome)
        assertEquals(1, state.consecutiveFailedRuns)
        assertNull(state.lastFailure) // no DataLoomError, and the exception message is never captured
    }

    @Test
    fun aCancelledRunIsNoLongerInFlightAndIsNotCountedAsAFailure() = runTest {
        val tracker = QueueWorkerHealthTracker(clock)
        val worker = FakeWorker { CompletableDeferred<Unit>().await(); completed(QueueProcessingResult.NoWork) }
            .withHealthTracking(tracker)

        val job = launch { worker.run(request()) }
        yield()
        assertEquals(1, tracker.snapshot().runsInFlight)
        job.cancelAndJoin()

        val state = tracker.snapshot()
        assertEquals(0, state.runsInFlight)
        assertEquals(0, state.consecutiveFailedRuns)
        assertNull(state.lastRunOutcome)
        assertTrue(job.isCancelled)
    }

    @Test
    fun aCancellationExceptionFromTheWorkerIsRethrownAndNotCountedEither() = runTest {
        val tracker = QueueWorkerHealthTracker(clock)
        val worker = FakeWorker { throw CancellationException("cancelled") }.withHealthTracking(tracker)

        assertFailsWith<CancellationException> { worker.run(request()) }

        assertEquals(0, tracker.snapshot().runsInFlight)
        assertEquals(0, tracker.snapshot().consecutiveFailedRuns)
    }

    @Test
    fun concurrentRunsAreCountedIndividually() = runTest {
        val tracker = QueueWorkerHealthTracker(clock)
        val gate = CompletableDeferred<Unit>()
        val worker = FakeWorker { gate.await(); completed(QueueProcessingResult.NoWork) }.withHealthTracking(tracker)

        val a = async { worker.run(request()) }
        val b = async { worker.run(request()) }
        yield()
        assertEquals(2, tracker.snapshot().runsInFlight)

        gate.complete(Unit)
        a.await()
        b.await()
        assertEquals(0, tracker.snapshot().runsInFlight)
    }

    @Test
    fun theSnapshotFunctionRollsTheTrackedWorkerUpEndToEnd() = runTest {
        val tracker = QueueWorkerHealthTracker(clock)
        val worker = FakeWorker { QueueWorkerRunResult.RecoveryFailed(fakeError("STORE-DOWN")) }.withHealthTracking(tracker)
        repeat(3) { worker.run(request()) }

        val snapshot = dataLoomHealthSnapshot(queueWorkerObservation = tracker.snapshot(), now = clock.now())

        assertEquals(DataLoomHealthSeverity.UNHEALTHY, snapshot.severity)
        val health = checkNotNull(snapshot.queueWorkerHealth)
        assertEquals(3, health.consecutiveFailedRuns)
        assertEquals(QueueWorkerActivity.IDLE, health.activity)
        assertEquals("STORE-DOWN", health.lastFailure?.get("code"))
        assertEquals(QueueWorkerRunOutcome.RECOVERY_FAILED, health.lastRunOutcome)
    }

    // ---- fixtures -----------------------------------------------------------------------------------

    private fun completed(processing: QueueProcessingResult): QueueWorkerRunResult =
        QueueWorkerRunResult.ProcessingCompleted(null, processing, QueueWorkerSchedulingResult.NotRequired)

    private fun request(): QueueWorkerRunRequest = QueueWorkerRunRequest(
        processingRequest = QueueProcessingRequest(
            acquireRequest = QueueAcquireRequest(
                consumerId = QueueConsumerId("consumer"),
                leaseId = QueueLeaseId("lease"),
                acquiredAt = DataLoomInstant(1_000_000L),
                leaseExpiresAt = DataLoomInstant(2_000_000L),
                maxEntries = 5,
            ),
        ),
        recoveryRequest = null,
    )

    private fun fakeError(code: String): DataLoomError = object : DataLoomError {
        override val code = ErrorCode(code)
        override val category = ErrorCategory.STORAGE
        override val severity = ErrorSeverity.ERROR
        override val recoverability = Recoverability.RECOVERABLE
        override val message = "sensitive raw message that must never be captured"
        override val cause: Throwable? = null
    }

    private fun envelope(id: String, occurredAt: Long = 1_000L): OperationalEventEnvelope = OperationalEventEnvelope(
        id = OperationalEventId(id),
        type = OperationalEventType("dataloom.test.event"),
        source = OperationalEventSource("dataloom.runtime.test"),
        category = OperationalEventCategory.TELEMETRY,
        schemaVersion = OperationalSchemaVersion(1),
        occurredAt = DataLoomInstant(occurredAt),
        correlationId = CorrelationId("correlation-1"),
        payload = OperationalPayloadDescriptor(
            type = OperationalPayloadType("dataloom.test.signal"),
            schemaVersion = OperationalSchemaVersion(1),
            encoding = OperationalPayloadEncoding("application/json"),
            classification = DataClassification.INTERNAL,
        ),
    )

    private class MutableClock(private var millis: Long) : DataLoomClock {
        override fun now(): DataLoomInstant = DataLoomInstant(millis)
        fun advance(byMillis: Long) {
            millis += byMillis
        }
    }

    private class FakeWorker(private val block: suspend () -> QueueWorkerRunResult) : DataLoomQueueWorker {
        override suspend fun run(request: QueueWorkerRunRequest): QueueWorkerRunResult = block()
    }

    private class FakeCircuitWorker(private val block: suspend () -> CircuitBreakerQueueWorkerRunResult) :
        DataLoomCircuitQueueWorker {
        override suspend fun run(request: QueueWorkerRunRequest): CircuitBreakerQueueWorkerRunResult = block()
    }

    private class InMemoryStore : DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState> {
        private val records = mutableMapOf<OperationalEventOutboxScope, DurableStateRecord<OperationalEventOutboxState>>()

        override suspend fun load(
            scope: OperationalEventOutboxScope,
        ): ProviderOperationResult<DurableStateLoadResult<OperationalEventOutboxState>> {
            val record = records[scope]
            return ProviderOperationResult.Success(
                if (record == null) DurableStateLoadResult.Missing else DurableStateLoadResult.Found(record),
            )
        }

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<OperationalEventOutboxScope, OperationalEventOutboxState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<OperationalEventOutboxState>> {
            val current = records[request.scope]
            if (current?.version != request.expectedVersion) {
                return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Conflict(current))
            }
            val updated = DurableStateRecord(
                state = request.nextState,
                version = (current?.version ?: -1L) + 1L,
                schemaVersion = request.nextSchemaVersion,
            )
            records[request.scope] = updated
            return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Updated(updated))
        }
    }

    private inner class FailingLoadStore : DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState> {
        override suspend fun load(
            scope: OperationalEventOutboxScope,
        ): ProviderOperationResult<DurableStateLoadResult<OperationalEventOutboxState>> =
            ProviderOperationResult.Failure(fakeError("LOAD-FAILED"))

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<OperationalEventOutboxScope, OperationalEventOutboxState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<OperationalEventOutboxState>> =
            error("must not be called when load already failed")
    }
}
