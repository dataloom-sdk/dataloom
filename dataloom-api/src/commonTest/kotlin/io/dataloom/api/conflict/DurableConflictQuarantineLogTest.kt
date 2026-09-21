package io.dataloom.api.conflict

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.ConflictResolverId
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.state.DurableStateCodec
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateRecord
import io.dataloom.api.state.DurableStateStore
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

/**
 * Verifies [DurableConflictQuarantineLog]: threshold and window behaviour,
 * restart survival, lost-increment-free concurrent counting, and release.
 */
class DurableConflictQuarantineLogTest {

    private val scope = ConflictQuarantineScope(EntityType("invoice"), EntityId("inv-1"))
    private val otherScope = ConflictQuarantineScope(EntityType("invoice"), EntityId("inv-2"))
    private val resolver = ConflictResolverId("dataloom.builtin.server-wins")

    private fun at(millis: Long) = DataLoomInstant(millis)

    private suspend fun DurableConflictQuarantineLog.occur(
        atMillis: Long,
        policy: ConflictQuarantinePolicy,
        target: ConflictQuarantineScope = scope,
        conflict: String = "c-$atMillis",
        resolverId: ConflictResolverId? = resolver,
    ): ConflictQuarantineObservation =
        recordOccurrence(target, ConflictId(conflict), resolverId, at(atMillis), policy)

    private fun release(command: String = "cmd-1", atMillis: Long = 9_000L) = ConflictQuarantineRelease(
        commandId = ConflictAdministrationCommandId(command),
        principalId = ConflictAdministrationPrincipalId("operator"),
        authorizationId = ConflictAdministrationAuthorizationId("auth-$command"),
        reason = ConflictAdministrationReason("verified upstream fix"),
        releasedAt = at(atMillis),
    )

    // -------------------------------------------------------------------------
    // Policy / scope / record validation
    // -------------------------------------------------------------------------

    @Test
    fun policyDefaultsToFiveOccurrencesAndNoWindow() {
        assertEquals(ConflictQuarantinePolicy(occurrenceThreshold = 5, windowMillis = null), ConflictQuarantinePolicy())
    }

    @Test
    fun policyRejectsThresholdBelowTwoAndNonPositiveWindow() {
        assertFailsWith<IllegalArgumentException> { ConflictQuarantinePolicy(occurrenceThreshold = 1) }
        assertFailsWith<IllegalArgumentException> { ConflictQuarantinePolicy(occurrenceThreshold = 0) }
        assertFailsWith<IllegalArgumentException> { ConflictQuarantinePolicy(windowMillis = 0L) }
        assertFailsWith<IllegalArgumentException> { ConflictQuarantinePolicy(windowMillis = -1L) }
    }

    @Test
    fun keyEncoderIsInjectiveAcrossFieldBoundaries() {
        val encode = ConflictQuarantineScope.KeyEncoder::encode
        assertEquals(encode(scope), encode(ConflictQuarantineScope(EntityType("invoice"), EntityId("inv-1"))))
        assertNotEquals(encode(scope), encode(otherScope))
        // Same concatenation, different boundary.
        assertNotEquals(
            encode(ConflictQuarantineScope(EntityType("ab"), EntityId("c"))),
            encode(ConflictQuarantineScope(EntityType("a"), EntityId("bc"))),
        )
    }

    @Test
    fun recordInvariantsAreEnforced() {
        val base = ConflictQuarantineRecord(
            ConflictQuarantineStatus.COUNTING, 1, at(1), at(1), ConflictId("c"), null, null,
        )
        assertFailsWith<IllegalArgumentException> { base.copy(quarantinedAt = at(1)) }
        assertFailsWith<IllegalArgumentException> { base.copy(status = ConflictQuarantineStatus.QUARANTINED) }
        assertFailsWith<IllegalArgumentException> {
            base.copy(status = ConflictQuarantineStatus.QUARANTINED, occurrenceCount = 0, quarantinedAt = at(1))
        }
        assertFailsWith<IllegalArgumentException> { base.copy(occurrenceCount = -1) }
        assertFailsWith<IllegalArgumentException> { base.copy(releaseCount = 1) }
        assertFailsWith<IllegalArgumentException> { base.copy(lastRelease = release()) }
    }

    @Test
    fun maximumStateUpdateAttemptsBelowOneIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            DurableConflictQuarantineLog(InMemoryStore(), maximumStateUpdateAttempts = 0)
        }
    }

    // -------------------------------------------------------------------------
    // Threshold behaviour (table-driven)
    // -------------------------------------------------------------------------

    @Test
    fun theOccurrenceThatReachesTheThresholdQuarantines_forEachThreshold() = runTest {
        for (threshold in listOf(2, 3, 5, 8)) {
            val log = DurableConflictQuarantineLog(InMemoryStore())
            val policy = ConflictQuarantinePolicy(occurrenceThreshold = threshold)
            for (n in 1 until threshold) {
                val counted = assertIs<ConflictQuarantineObservation.Counted>(log.occur(n * 10L, policy), "threshold=$threshold n=$n")
                assertEquals(n, counted.record.occurrenceCount)
                assertEquals(ConflictQuarantineStatus.COUNTING, counted.record.status)
            }
            val reached = assertIs<ConflictQuarantineObservation.Quarantined>(
                log.occur(threshold * 10L, policy),
                "threshold=$threshold",
            )
            assertEquals(true, reached.newlyQuarantined)
            assertEquals(threshold, reached.record.occurrenceCount)
            assertEquals(at(threshold * 10L), reached.record.quarantinedAt)
        }
    }

    @Test
    fun defaultThresholdIsFive() = runTest {
        val log = DurableConflictQuarantineLog(InMemoryStore())
        val policy = ConflictQuarantinePolicy()
        repeat(4) { assertIs<ConflictQuarantineObservation.Counted>(log.occur(it * 10L, policy)) }
        assertIs<ConflictQuarantineObservation.Quarantined>(log.occur(40L, policy))
    }

    @Test
    fun onceQuarantined_furtherOccurrencesAreReadOnlyAndReportNotNew() = runTest {
        val store = InMemoryStore()
        val log = DurableConflictQuarantineLog(store)
        val policy = ConflictQuarantinePolicy(occurrenceThreshold = 2)
        log.occur(1L, policy)
        log.occur(2L, policy)
        val writesAtQuarantine = store.compareAndSetCalls
        val later = assertIs<ConflictQuarantineObservation.Quarantined>(log.occur(3L, policy, conflict = "later"))
        assertEquals(false, later.newlyQuarantined)
        assertEquals(2, later.record.occurrenceCount)
        assertEquals(ConflictId("c-2"), later.record.lastConflictId)
        assertEquals(writesAtQuarantine, store.compareAndSetCalls)
    }

    @Test
    fun entitiesAreCountedIndependently() = runTest {
        val log = DurableConflictQuarantineLog(InMemoryStore())
        val policy = ConflictQuarantinePolicy(occurrenceThreshold = 2)
        log.occur(1L, policy, target = scope)
        assertIs<ConflictQuarantineObservation.Counted>(log.occur(2L, policy, target = otherScope))
        assertIs<ConflictQuarantineObservation.Quarantined>(log.occur(3L, policy, target = scope))
    }

    @Test
    fun recordCarriesFirstAndLastSeenLastConflictAndLastResolver() = runTest {
        val log = DurableConflictQuarantineLog(InMemoryStore())
        val policy = ConflictQuarantinePolicy(occurrenceThreshold = 5)
        log.occur(100L, policy, conflict = "first", resolverId = resolver)
        val second = assertIs<ConflictQuarantineObservation.Counted>(
            log.occur(250L, policy, conflict = "second", resolverId = null),
        ).record
        assertEquals(at(100L), second.firstSeenAt)
        assertEquals(at(250L), second.lastSeenAt)
        assertEquals(ConflictId("second"), second.lastConflictId)
        assertNull(second.lastResolverId)
        assertEquals(2, second.occurrenceCount)
    }

    @Test
    fun lastSeenAtNeverMovesBackwardsWhenTheClockRegresses() = runTest {
        val log = DurableConflictQuarantineLog(InMemoryStore())
        val policy = ConflictQuarantinePolicy(occurrenceThreshold = 5, windowMillis = 1_000L)
        log.occur(500L, policy)
        val regressed = assertIs<ConflictQuarantineObservation.Counted>(log.occur(200L, policy)).record
        assertEquals(at(500L), regressed.lastSeenAt)
        // Regression is inside the window, so it counts.
        assertEquals(2, regressed.occurrenceCount)
    }

    // -------------------------------------------------------------------------
    // Window behaviour (table-driven)
    // -------------------------------------------------------------------------

    private class WindowCase(
        val name: String,
        val windowMillis: Long?,
        val occurrenceTimes: List<Long>,
        val expectedCounts: List<Int>,
    )

    @Test
    fun windowExpiryRestartsTheCount() = runTest {
        val cases = listOf(
            WindowCase("no window accumulates across any gap", null, listOf(0L, 10_000_000L, 90_000_000L), listOf(1, 2, 3)),
            WindowCase("inside window", 1_000L, listOf(0L, 400L, 900L), listOf(1, 2, 3)),
            WindowCase("exactly at window edge is inside", 1_000L, listOf(0L, 1_000L), listOf(1, 2)),
            WindowCase("one past the edge starts a new window", 1_000L, listOf(0L, 1_001L), listOf(1, 1)),
            WindowCase("window is measured from the first occurrence, not the last", 1_000L, listOf(0L, 600L, 1_200L), listOf(1, 2, 1)),
            WindowCase("new window then continues", 1_000L, listOf(0L, 2_000L, 2_500L, 2_900L), listOf(1, 1, 2, 3)),
        )
        for (case in cases) {
            val log = DurableConflictQuarantineLog(InMemoryStore())
            // Threshold high enough that quarantine never interferes with counting.
            val policy = ConflictQuarantinePolicy(occurrenceThreshold = 100, windowMillis = case.windowMillis)
            val counts = case.occurrenceTimes.map { time ->
                assertIs<ConflictQuarantineObservation.Counted>(log.occur(time, policy), case.name).record.occurrenceCount
            }
            assertEquals(case.expectedCounts, counts, case.name)
        }
    }

    @Test
    fun windowExpiryPreventsSlowOccurrencesFromEverQuarantining() = runTest {
        val log = DurableConflictQuarantineLog(InMemoryStore())
        val policy = ConflictQuarantinePolicy(occurrenceThreshold = 3, windowMillis = 1_000L)
        repeat(10) { assertIs<ConflictQuarantineObservation.Counted>(log.occur(it * 5_000L, policy)) }
    }

    @Test
    fun aBurstInsideTheWindowQuarantines() = runTest {
        val log = DurableConflictQuarantineLog(InMemoryStore())
        val policy = ConflictQuarantinePolicy(occurrenceThreshold = 3, windowMillis = 1_000L)
        log.occur(0L, policy)
        log.occur(300L, policy)
        val third = assertIs<ConflictQuarantineObservation.Quarantined>(log.occur(600L, policy))
        assertEquals(true, third.newlyQuarantined)
    }

    // -------------------------------------------------------------------------
    // Restart survival
    // -------------------------------------------------------------------------

    @Test
    fun countSurvivesARestart_freshLogAndFreshStoreOverTheSamePersistedBytes() = runTest {
        val persisted = mutableMapOf<String, Pair<Long, String>>()
        val policy = ConflictQuarantinePolicy(occurrenceThreshold = 3)

        // "Process 1": two occurrences, then everything is dropped.
        val first = DurableConflictQuarantineLog(EncodedStore(persisted))
        first.occur(1L, policy)
        first.occur(2L, policy)

        // "Process 2": brand-new store instance and log over the same persisted bytes.
        val second = DurableConflictQuarantineLog(EncodedStore(persisted))
        val current = assertIs<ProviderOperationResult.Success<ConflictQuarantineRecord?>>(second.current(scope)).value
        assertEquals(2, current?.occurrenceCount)
        val reached = assertIs<ConflictQuarantineObservation.Quarantined>(second.occur(3L, policy))
        assertEquals(true, reached.newlyQuarantined)

        // "Process 3": the quarantine itself survives.
        val third = DurableConflictQuarantineLog(EncodedStore(persisted))
        val still = assertIs<ConflictQuarantineObservation.Quarantined>(third.occur(4L, policy))
        assertEquals(false, still.newlyQuarantined)
        assertEquals(3, still.record.occurrenceCount)
    }

    @Test
    fun releaseEvidenceSurvivesARestart() = runTest {
        val persisted = mutableMapOf<String, Pair<Long, String>>()
        val policy = ConflictQuarantinePolicy(occurrenceThreshold = 2)
        val first = DurableConflictQuarantineLog(EncodedStore(persisted))
        first.occur(1L, policy)
        first.occur(2L, policy)
        first.release(scope, release())

        val second = DurableConflictQuarantineLog(EncodedStore(persisted))
        val record = assertIs<ProviderOperationResult.Success<ConflictQuarantineRecord?>>(second.current(scope)).value
        assertEquals(release(), record?.lastRelease)
        assertEquals(ConflictQuarantineStatus.COUNTING, record?.status)
    }

    // -------------------------------------------------------------------------
    // Concurrent counting: compare-and-set, no lost increments
    // -------------------------------------------------------------------------

    @Test
    fun aLostCompareAndSetReloadsAndIncrementsFromTheWinner() = runTest {
        val store = InMemoryStore()
        val log = DurableConflictQuarantineLog(store)
        val policy = ConflictQuarantinePolicy(occurrenceThreshold = 100)
        log.occur(1L, policy)
        // Another writer lands between this call's load and its compareAndSet.
        store.beforeNextCompareAndSet = { store.forceIncrement(scope, at(2L)) }

        val observed = assertIs<ConflictQuarantineObservation.Counted>(log.occur(3L, policy))
        assertEquals(3, observed.record.occurrenceCount)
        assertEquals(3, currentCount(store))
    }

    @Test
    fun concurrentOccurrencesAreAllCounted_noLostIncrements() = runTest {
        val store = InMemoryStore(yieldAfterLoad = true)
        val log = DurableConflictQuarantineLog(store, maximumStateUpdateAttempts = 64)
        val policy = ConflictQuarantinePolicy(occurrenceThreshold = 1_000)
        val racers = 25

        val outcomes = List(racers) { index ->
            async { log.occur(index.toLong(), policy, conflict = "c-$index") }
        }.awaitAll()

        outcomes.forEach { assertIs<ConflictQuarantineObservation.Counted>(it) }
        // The racers genuinely interleaved: losers had to retry, so there are more writes than racers.
        assertTrue(store.compareAndSetCalls > racers, "expected retries, got ${store.compareAndSetCalls} writes")
        assertEquals(racers, currentCount(store))
        // Every racer landed on a distinct count: none was overwritten or counted twice.
        val counts = outcomes.map { (it as ConflictQuarantineObservation.Counted).record.occurrenceCount }
        assertEquals((1..racers).toList(), counts.sorted())
    }

    @Test
    fun concurrentOccurrencesReachTheThresholdExactlyOnce() = runTest {
        val store = InMemoryStore(yieldAfterLoad = true)
        val log = DurableConflictQuarantineLog(store, maximumStateUpdateAttempts = 64)
        val policy = ConflictQuarantinePolicy(occurrenceThreshold = 10)

        val outcomes = List(20) { index -> async { log.occur(index.toLong(), policy) } }.awaitAll()

        val counted = outcomes.filterIsInstance<ConflictQuarantineObservation.Counted>()
        val quarantined = outcomes.filterIsInstance<ConflictQuarantineObservation.Quarantined>()
        assertEquals(9, counted.size)
        assertEquals(11, quarantined.size)
        // Exactly one racer crossed the threshold; the rest observed it already.
        assertEquals(1, quarantined.count { it.newlyQuarantined })
        assertEquals(10, currentCount(store))
    }

    @Test
    fun contentionBoundIsReportedWhenEveryCompareAndSetLoses() = runTest {
        val log = DurableConflictQuarantineLog(AlwaysConflictStore(), maximumStateUpdateAttempts = 3)
        assertEquals(
            ConflictQuarantineObservation.ContentionLimitReached,
            log.occur(1L, ConflictQuarantinePolicy()),
        )
    }

    // -------------------------------------------------------------------------
    // Store failures
    // -------------------------------------------------------------------------

    @Test
    fun loadFailureIsReportedAsPersistenceFailure() = runTest {
        val log = DurableConflictQuarantineLog(InMemoryStore().apply { failLoads = true })
        assertIs<ConflictQuarantineObservation.PersistenceFailure>(log.occur(1L, ConflictQuarantinePolicy()))
        assertIs<ConflictQuarantineReleaseOutcome.PersistenceFailure>(log.release(scope, release()))
        assertIs<ProviderOperationResult.Failure>(log.current(scope))
    }

    @Test
    fun compareAndSetFailureIsReportedAsPersistenceFailure() = runTest {
        val log = DurableConflictQuarantineLog(InMemoryStore().apply { failCompareAndSet = true })
        assertIs<ConflictQuarantineObservation.PersistenceFailure>(log.occur(1L, ConflictQuarantinePolicy()))
    }

    // -------------------------------------------------------------------------
    // Release
    // -------------------------------------------------------------------------

    @Test
    fun releaseClearsQuarantineRestartsCountAndRecordsEvidence() = runTest {
        val store = InMemoryStore()
        val log = DurableConflictQuarantineLog(store)
        val policy = ConflictQuarantinePolicy(occurrenceThreshold = 2)
        log.occur(1L, policy)
        log.occur(2L, policy)

        val released = assertIs<ConflictQuarantineReleaseOutcome.Released>(log.release(scope, release()))
        assertEquals(ConflictQuarantineStatus.COUNTING, released.record.status)
        assertEquals(0, released.record.occurrenceCount)
        assertNull(released.record.quarantinedAt)
        assertEquals(1, released.record.releaseCount)
        assertEquals(release(), released.record.lastRelease)

        // Counting restarts from one, and the threshold quarantines again.
        assertEquals(1, assertIs<ConflictQuarantineObservation.Counted>(log.occur(3L, policy)).record.occurrenceCount)
        val again = assertIs<ConflictQuarantineObservation.Quarantined>(log.occur(4L, policy))
        assertEquals(true, again.newlyQuarantined)
        // Prior release evidence is retained.
        assertEquals(release(), again.record.lastRelease)
        assertEquals(1, again.record.releaseCount)
    }

    @Test
    fun releaseIsIdempotentByCommandId() = runTest {
        val store = InMemoryStore()
        val log = DurableConflictQuarantineLog(store)
        val policy = ConflictQuarantinePolicy(occurrenceThreshold = 2)
        log.occur(1L, policy)
        log.occur(2L, policy)
        log.release(scope, release("cmd-1"))
        val writes = store.compareAndSetCalls

        val replay = assertIs<ConflictQuarantineReleaseOutcome.AlreadyReleased>(log.release(scope, release("cmd-1", atMillis = 99_999L)))
        assertEquals(1, replay.record.releaseCount)
        assertEquals(at(9_000L), replay.record.lastRelease?.releasedAt)
        assertEquals(writes, store.compareAndSetCalls)
    }

    @Test
    fun aDifferentCommandOnAnEntityThatIsNotQuarantinedIsRejected() = runTest {
        val log = DurableConflictQuarantineLog(InMemoryStore())
        val policy = ConflictQuarantinePolicy(occurrenceThreshold = 3)
        assertNull(assertIs<ConflictQuarantineReleaseOutcome.NotQuarantined>(log.release(scope, release())).record)

        log.occur(1L, policy)
        val counting = assertIs<ConflictQuarantineReleaseOutcome.NotQuarantined>(log.release(scope, release()))
        assertEquals(ConflictQuarantineStatus.COUNTING, counting.record?.status)
        assertEquals(1, counting.record?.occurrenceCount)
    }

    @Test
    fun aSecondCommandAfterReleaseFindsNothingToRelease() = runTest {
        val log = DurableConflictQuarantineLog(InMemoryStore())
        val policy = ConflictQuarantinePolicy(occurrenceThreshold = 2)
        log.occur(1L, policy)
        log.occur(2L, policy)
        log.release(scope, release("cmd-1"))
        assertIs<ConflictQuarantineReleaseOutcome.NotQuarantined>(log.release(scope, release("cmd-2")))
    }

    @Test
    fun releaseRetriesAfterLosingCompareAndSet() = runTest {
        val store = InMemoryStore()
        val log = DurableConflictQuarantineLog(store)
        val policy = ConflictQuarantinePolicy(occurrenceThreshold = 2)
        log.occur(1L, policy)
        log.occur(2L, policy)
        // Same command lands first from a concurrent caller; ours must converge to AlreadyReleased.
        store.beforeNextCompareAndSet = {
            val current = store.peek(scope)!!
            store.forceState(scope, current.state.copy(
                status = ConflictQuarantineStatus.COUNTING,
                occurrenceCount = 0,
                quarantinedAt = null,
                releaseCount = 1,
                lastRelease = release("cmd-1"),
            ))
        }
        assertIs<ConflictQuarantineReleaseOutcome.AlreadyReleased>(log.release(scope, release("cmd-1")))
    }

    @Test
    fun releaseReportsContentionWhenEveryCompareAndSetLoses() = runTest {
        val store = InMemoryStore()
        val log = DurableConflictQuarantineLog(store, maximumStateUpdateAttempts = 3)
        val policy = ConflictQuarantinePolicy(occurrenceThreshold = 2)
        log.occur(1L, policy)
        log.occur(2L, policy)
        store.alwaysLoseCompareAndSet = true
        assertEquals(ConflictQuarantineReleaseOutcome.ContentionLimitReached, log.release(scope, release()))
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private suspend fun currentCount(store: InMemoryStore): Int? = store.peek(scope)?.state?.occurrenceCount

    private class InMemoryStore(
        private val yieldAfterLoad: Boolean = false,
    ) : DurableStateStore<ConflictQuarantineScope, ConflictQuarantineRecord> {
        private val records = mutableMapOf<ConflictQuarantineScope, DurableStateRecord<ConflictQuarantineRecord>>()
        var compareAndSetCalls = 0
            private set
        var failLoads = false
        var failCompareAndSet = false
        var alwaysLoseCompareAndSet = false
        var beforeNextCompareAndSet: (() -> Unit)? = null

        fun peek(scope: ConflictQuarantineScope): DurableStateRecord<ConflictQuarantineRecord>? = records[scope]

        /** Simulates a concurrent writer: bumps the count and version behind the caller's back. */
        fun forceIncrement(scope: ConflictQuarantineScope, observedAt: DataLoomInstant) {
            val current = records.getValue(scope)
            forceState(
                scope,
                current.state.copy(
                    occurrenceCount = current.state.occurrenceCount + 1,
                    lastSeenAt = observedAt,
                ),
            )
        }

        fun forceState(scope: ConflictQuarantineScope, state: ConflictQuarantineRecord) {
            val current = records.getValue(scope)
            records[scope] = DurableStateRecord(state, current.version + 1L, current.schemaVersion)
        }

        override suspend fun load(
            scope: ConflictQuarantineScope,
        ): ProviderOperationResult<DurableStateLoadResult<ConflictQuarantineRecord>> {
            if (failLoads) return ProviderOperationResult.Failure(testError())
            val record = records[scope]
            val result = ProviderOperationResult.Success(
                if (record == null) DurableStateLoadResult.Missing else DurableStateLoadResult.Found(record),
            )
            // Lets other coroutines run between this load and the caller's compareAndSet.
            if (yieldAfterLoad) yield()
            return result
        }

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<ConflictQuarantineScope, ConflictQuarantineRecord>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<ConflictQuarantineRecord>> {
            compareAndSetCalls += 1
            if (failCompareAndSet) return ProviderOperationResult.Failure(testError())
            beforeNextCompareAndSet?.let {
                beforeNextCompareAndSet = null
                it()
            }
            val current = records[request.scope]
            if (alwaysLoseCompareAndSet || current?.version != request.expectedVersion) {
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

    /** Persists through [ConflictQuarantineRecordCodec] into a shared map, like a real string-payload store. */
    private class EncodedStore(
        private val persisted: MutableMap<String, Pair<Long, String>>,
    ) : DurableStateStore<ConflictQuarantineScope, ConflictQuarantineRecord> {
        private val codec: DurableStateCodec<ConflictQuarantineRecord> = ConflictQuarantineRecordCodec()

        override suspend fun load(
            scope: ConflictQuarantineScope,
        ): ProviderOperationResult<DurableStateLoadResult<ConflictQuarantineRecord>> {
            val stored = persisted[ConflictQuarantineScope.KeyEncoder.encode(scope)]
                ?: return ProviderOperationResult.Success(DurableStateLoadResult.Missing)
            return ProviderOperationResult.Success(
                DurableStateLoadResult.Found(DurableStateRecord(codec.decode(stored.second), stored.first, 1)),
            )
        }

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<ConflictQuarantineScope, ConflictQuarantineRecord>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<ConflictQuarantineRecord>> {
            val key = ConflictQuarantineScope.KeyEncoder.encode(request.scope)
            val current = persisted[key]
            if (current?.first != request.expectedVersion) {
                return ProviderOperationResult.Success(
                    DurableStateCompareAndSetResult.Conflict(
                        current?.let { DurableStateRecord(codec.decode(it.second), it.first, 1) },
                    ),
                )
            }
            val version = (current?.first ?: -1L) + 1L
            persisted[key] = version to codec.encode(request.nextState)
            return ProviderOperationResult.Success(
                DurableStateCompareAndSetResult.Updated(
                    DurableStateRecord(request.nextState, version, request.nextSchemaVersion),
                ),
            )
        }
    }

    private class AlwaysConflictStore : DurableStateStore<ConflictQuarantineScope, ConflictQuarantineRecord> {
        override suspend fun load(
            scope: ConflictQuarantineScope,
        ): ProviderOperationResult<DurableStateLoadResult<ConflictQuarantineRecord>> =
            ProviderOperationResult.Success(DurableStateLoadResult.Missing)

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<ConflictQuarantineScope, ConflictQuarantineRecord>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<ConflictQuarantineRecord>> =
            ProviderOperationResult.Success(DurableStateCompareAndSetResult.Conflict(null))
    }
}

private fun testError(): DataLoomError = DurableConflictQuarantineLogTestError(
    code = ErrorCode("DURABLE_CONFLICT_QUARANTINE_LOG_TEST_FAILURE"),
    category = ErrorCategory.STORAGE,
    severity = ErrorSeverity.ERROR,
    recoverability = Recoverability.RECOVERABLE,
    message = "Simulated store failure.",
)

private data class DurableConflictQuarantineLogTestError(
    override val code: ErrorCode,
    override val category: ErrorCategory,
    override val severity: ErrorSeverity,
    override val recoverability: Recoverability,
    override val message: String,
    override val cause: Throwable? = null,
) : DataLoomError
