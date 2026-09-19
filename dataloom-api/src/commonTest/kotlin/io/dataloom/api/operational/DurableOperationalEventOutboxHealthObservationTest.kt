package io.dataloom.api.operational

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.security.DataClassification
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateRecord
import io.dataloom.api.state.DurableStateStore
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/**
 * Verifies the outbox's optional `stateObserver` -- the push half of the
 * synchronous health read path -- reports bounded summaries of what the
 * outbox actually loaded or wrote, and is fully inert when absent.
 */
class DurableOperationalEventOutboxHealthObservationTest {

    private val scope = OperationalEventOutboxScope("health-scope")
    private val clock = MutableClock(DataLoomInstant(5_000L))
    private val observed = mutableListOf<OperationalEventOutboxStateObservation>()
    private val observer = OperationalEventOutboxStateObserver { observed += it }

    @Test
    fun withNoObserverTheOutboxNeverReadsTheClockOrBuildsASummary() = runTest {
        val explodingClock = object : DataLoomClock {
            override fun now(): DataLoomInstant = error("clock must not be read")
        }
        val outbox = DurableOperationalEventOutbox(InMemoryStore(), explodingClock)

        assertIs<DurableOperationalEventOutboxAppendOutcome.Appended>(outbox.append(scope, envelope("e1")))
        assertIs<ProviderOperationResult.Success<*>>(outbox.entries(scope))
        assertIs<ProviderOperationResult.Success<*>>(outbox.acknowledgedEntries(scope))
    }

    @Test
    fun appendReportsThePersistedStateAtItsNewVersionWithTheObservationTime() = runTest {
        val outbox = outbox()

        outbox.append(scope, envelope("e1", occurredAt = 300L))

        val observation = observed.single()
        assertEquals(scope, observation.scope)
        assertEquals(OperationalEventOutboxSummary(1, DataLoomInstant(300L), 0), observation.summary)
        assertEquals(0L, observation.storeVersion)
        assertEquals(DataLoomInstant(5_000L), observation.observedAt)
    }

    @Test
    fun oldestPendingIsTheMinimumOccurredAtNotTheFirstAppended() = runTest {
        val outbox = outbox()
        outbox.append(scope, envelope("e1", occurredAt = 900L))
        outbox.append(scope, envelope("e2", occurredAt = 100L))
        outbox.append(scope, envelope("e3", occurredAt = 500L))

        assertEquals(DataLoomInstant(100L), observed.last().summary.oldestPendingOccurredAt)
        assertEquals(3, observed.last().summary.pendingCount)
        assertEquals(2L, observed.last().storeVersion)
    }

    @Test
    fun acknowledgeAndReplayMoveEntriesBetweenPendingAndAcknowledgedCounts() = runTest {
        val outbox = outbox()
        outbox.append(scope, envelope("e1", occurredAt = 100L))
        outbox.append(scope, envelope("e2", occurredAt = 200L))

        outbox.acknowledge(scope, OperationalEventId("e1"))
        assertEquals(OperationalEventOutboxSummary(1, DataLoomInstant(200L), 1), observed.last().summary)

        outbox.replay(scope, OperationalEventId("e1"))
        assertEquals(OperationalEventOutboxSummary(2, DataLoomInstant(100L), 0), observed.last().summary)
        assertEquals(3L, observed.last().storeVersion)
    }

    @Test
    fun anEmptyOrMissingScopeReportsZerosWithNoVersionWhenRead() = runTest {
        val outbox = outbox()

        outbox.entries(scope)

        val observation = observed.single()
        assertEquals(OperationalEventOutboxSummary(0, null, 0), observation.summary)
        assertNull(observation.storeVersion)
    }

    @Test
    fun readsReportWhatTheyLoadWithoutChangingIt() = runTest {
        val outbox = outbox()
        outbox.append(scope, envelope("e1"))
        observed.clear()

        outbox.entries(scope)
        outbox.pendingEntries(scope)
        outbox.acknowledgedEntries(scope)

        assertEquals(3, observed.size)
        assertEquals(setOf(0L), observed.map { it.storeVersion }.toSet())
    }

    @Test
    fun anIdempotentAppendAndANoOpAcknowledgeReportTheLoadedStateWithoutABump() = runTest {
        val outbox = outbox()
        val e1 = envelope("e1")
        outbox.append(scope, e1)
        observed.clear()

        assertIs<DurableOperationalEventOutboxAppendOutcome.AlreadyAppended>(outbox.append(scope, e1))
        assertIs<DurableOperationalEventOutboxAcknowledgeOutcome.NotFound>(outbox.acknowledge(scope, OperationalEventId("nope")))

        assertEquals(listOf(0L, 0L), observed.map { it.storeVersion })
    }

    @Test
    fun aLostCompareAndSetRaceReportsNothingForTheLostAttemptOnlyForTheSuccessfulOne() = runTest {
        val backing = InMemoryStore()
        val intercepting = InterceptingStore(backing)
        val loser = DurableOperationalEventOutbox(intercepting, clock, stateObserver = observer)
        val winner = DurableOperationalEventOutbox(backing, clock) // no observer
        intercepting.beforeFirstCompareAndSet = { winner.append(scope, envelope("won")) }

        loser.append(scope, envelope("lost"))

        // Exactly one observation: the retry that won, showing both entries at version 1.
        val observation = observed.single()
        assertEquals(2, observation.summary.pendingCount)
        assertEquals(1L, observation.storeVersion)
    }

    @Test
    fun aFailedLoadOrFailedWriteReportsNothing() = runTest {
        val failingLoad = DurableOperationalEventOutbox(FailingStore(failLoad = true), clock, stateObserver = observer)
        assertIs<DurableOperationalEventOutboxAppendOutcome.PersistenceFailure>(failingLoad.append(scope, envelope("e1")))
        assertIs<ProviderOperationResult.Failure>(failingLoad.entries(scope))

        val failingWrite = DurableOperationalEventOutbox(FailingStore(failLoad = false), clock, stateObserver = observer)
        assertIs<DurableOperationalEventOutboxAppendOutcome.PersistenceFailure>(failingWrite.append(scope, envelope("e1")))

        assertEquals(emptyList(), observed)
    }

    @Test
    fun anObserverThatThrowsNeverFailsAnAppendAcknowledgeReplayOrRead() = runTest {
        val outbox = DurableOperationalEventOutbox(
            InMemoryStore(),
            clock,
            stateObserver = { error("observer bug") },
        )

        assertIs<DurableOperationalEventOutboxAppendOutcome.Appended>(outbox.append(scope, envelope("e1")))
        assertIs<DurableOperationalEventOutboxAcknowledgeOutcome.Acknowledged>(outbox.acknowledge(scope, OperationalEventId("e1")))
        assertIs<DurableOperationalEventOutboxReplayOutcome.Replayed>(outbox.replay(scope, OperationalEventId("e1")))
        assertIs<ProviderOperationResult.Success<*>>(outbox.entries(scope))
    }

    @Test
    fun theObservationIsASnapshotNotALiveViewSoAnotherInstancesWritesAreInvisibleUntilThisOneReads() = runTest {
        val store = InMemoryStore()
        val observing = DurableOperationalEventOutbox(store, clock, stateObserver = observer)
        val other = DurableOperationalEventOutbox(store, clock) // another process sharing the store
        observing.append(scope, envelope("e1"))
        other.append(scope, envelope("e2"))
        other.append(scope, envelope("e3"))

        // The observer still only knows what `observing` last saw.
        assertEquals(1, observed.last().summary.pendingCount)

        observing.entries(scope)
        assertEquals(3, observed.last().summary.pendingCount)
    }

    private fun outbox(): DurableOperationalEventOutbox =
        DurableOperationalEventOutbox(InMemoryStore(), clock, stateObserver = observer)

    private fun envelope(id: String, occurredAt: Long = 1_000L): OperationalEventEnvelope = OperationalEventEnvelope(
        id = OperationalEventId(id),
        type = OperationalEventType("dataloom.retry.scheduled"),
        source = OperationalEventSource("dataloom.runtime.retry"),
        category = OperationalEventCategory.TELEMETRY,
        schemaVersion = OperationalSchemaVersion(1),
        occurredAt = DataLoomInstant(occurredAt),
        correlationId = CorrelationId("correlation-1"),
        payload = OperationalPayloadDescriptor(
            type = OperationalPayloadType("dataloom.retry.signal"),
            schemaVersion = OperationalSchemaVersion(1),
            encoding = OperationalPayloadEncoding("application/json"),
            classification = DataClassification.INTERNAL,
        ),
    )

    private class MutableClock(var instant: DataLoomInstant) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
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

    /** Runs [beforeFirstCompareAndSet] once, just before delegating the first compare-and-set. */
    private class InterceptingStore(private val delegate: InMemoryStore) :
        DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState> {
        var beforeFirstCompareAndSet: (suspend () -> Unit)? = null

        override suspend fun load(
            scope: OperationalEventOutboxScope,
        ): ProviderOperationResult<DurableStateLoadResult<OperationalEventOutboxState>> = delegate.load(scope)

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<OperationalEventOutboxScope, OperationalEventOutboxState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<OperationalEventOutboxState>> {
            beforeFirstCompareAndSet?.let {
                beforeFirstCompareAndSet = null
                it()
            }
            return delegate.compareAndSet(request)
        }
    }

    private class FailingStore(private val failLoad: Boolean) :
        DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState> {
        override suspend fun load(
            scope: OperationalEventOutboxScope,
        ): ProviderOperationResult<DurableStateLoadResult<OperationalEventOutboxState>> =
            if (failLoad) ProviderOperationResult.Failure(storeError()) else ProviderOperationResult.Success(DurableStateLoadResult.Missing)

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<OperationalEventOutboxScope, OperationalEventOutboxState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<OperationalEventOutboxState>> =
            ProviderOperationResult.Failure(storeError())
    }
}

private fun storeError(): DataLoomError = HealthObservationTestError(
    code = ErrorCode("HEALTH_OBSERVATION_TEST_FAILURE"),
    category = ErrorCategory.STORAGE,
    severity = ErrorSeverity.ERROR,
    recoverability = Recoverability.RECOVERABLE,
    message = "Simulated store failure.",
)

private data class HealthObservationTestError(
    override val code: ErrorCode,
    override val category: ErrorCategory,
    override val severity: ErrorSeverity,
    override val recoverability: Recoverability,
    override val message: String,
    override val cause: Throwable? = null,
) : DataLoomError
