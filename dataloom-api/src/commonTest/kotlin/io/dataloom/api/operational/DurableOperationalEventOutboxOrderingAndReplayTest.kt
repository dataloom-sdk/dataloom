package io.dataloom.api.operational

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.WorkflowId
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
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

/**
 * Verifies the two decided design points layered on
 * [DurableOperationalEventOutbox]: durable per-workflow sequence numbers
 * assigned inside the persisting compare-and-set (FR-EVENT-003), and
 * acknowledgement as a retained tombstone with an explicit [DurableOperationalEventOutbox.replay]
 * and deterministic pruning (FR-EVENT-004 replay).
 */
class DurableOperationalEventOutboxOrderingAndReplayTest {

    private val scope = OperationalEventOutboxScope("outbox-1")
    private val clock = StubClock(DataLoomInstant(1_000L))

    // ---- Ordering: sequence assignment ----------------------------------------------------

    @Test
    fun sequencesAreAssignedPerWorkflowStartingAtOneAcrossInterleavedWorkflows() = runTest {
        val outbox = DurableOperationalEventOutbox(InMemoryStore(), clock)
        val appended = listOf("a" to "a1", "b" to "b1", "a" to "a2", "b" to "b2", "a" to "a3").map { (workflow, id) ->
            assertIs<DurableOperationalEventOutboxAppendOutcome.Appended>(outbox.append(scope, envelope(id, workflow)))
        }

        assertEquals(listOf(1L, 1L, 2L, 2L, 3L), appended.map { it.sequence })
        val pending = pendingEntries(outbox)
        assertEquals(listOf("a1", "b1", "a2", "b2", "a3"), pending.map { it.envelope.id.value })
        assertEquals(listOf(1L, 2L, 3L), pending.filter { it.envelope.workflowId == WorkflowId("a") }.map { it.sequence })
        assertEquals(listOf(1L, 2L), pending.filter { it.envelope.workflowId == WorkflowId("b") }.map { it.sequence })
    }

    @Test
    fun envelopesWithoutAWorkflowShareTheDocumentedGlobalOrderingKey() = runTest {
        val outbox = DurableOperationalEventOutbox(InMemoryStore(), clock)

        val first = assertIs<DurableOperationalEventOutboxAppendOutcome.Appended>(outbox.append(scope, envelope("g1")))
        val workflow = assertIs<DurableOperationalEventOutboxAppendOutcome.Appended>(outbox.append(scope, envelope("w1", "w")))
        val second = assertIs<DurableOperationalEventOutboxAppendOutcome.Appended>(outbox.append(scope, envelope("g2")))

        assertEquals(1L, first.sequence)
        assertEquals(1L, workflow.sequence)
        assertEquals(2L, second.sequence)
        assertEquals(OperationalEventOrderingKey.Global, pendingEntries(outbox).first().orderingKey)
    }

    @Test
    fun aWorkflowIdSpellingGlobalDoesNotCollideWithTheGlobalKey() = runTest {
        val outbox = DurableOperationalEventOutbox(InMemoryStore(), clock)

        outbox.append(scope, envelope("g1"))
        val workflowNamedGlobal = assertIs<DurableOperationalEventOutboxAppendOutcome.Appended>(
            outbox.append(scope, envelope("w1", "global")),
        )

        assertEquals(1L, workflowNamedGlobal.sequence)
        assertNotEquals(OperationalEventOrderingKey.Global, OperationalEventOrderingKey.forWorkflow(WorkflowId("global")))
    }

    @Test
    fun sequencesSurviveAReloadOfTheSameStoreSoNothingLivesInMemory() = runTest {
        val store = InMemoryStore()
        DurableOperationalEventOutbox(store, clock).append(scope, envelope("a1", "a"))

        val reopened = DurableOperationalEventOutbox(store, clock)
        val next = assertIs<DurableOperationalEventOutboxAppendOutcome.Appended>(reopened.append(scope, envelope("a2", "a")))

        assertEquals(2L, next.sequence)
    }

    @Test
    fun aSequenceIsNeverReusedAfterTheNewestEntryOfAWorkflowIsPruned() = runTest {
        // No tombstone retention: acknowledging removes the entry outright, so
        // the workflow has no retained entry left to derive a sequence from.
        val outbox = DurableOperationalEventOutbox(InMemoryStore(), clock, maximumRetainedAcknowledgedEntries = 0)
        outbox.append(scope, envelope("a1", "a"))
        outbox.acknowledge(scope, OperationalEventId("a1"))
        assertEquals(emptyList(), pendingEntries(outbox))

        val next = assertIs<DurableOperationalEventOutboxAppendOutcome.Appended>(outbox.append(scope, envelope("a2", "a")))

        assertEquals(2L, next.sequence)
    }

    @Test
    fun aSequenceIsNeverReusedAfterCountRetentionEvictsTheNewestEntryOfAWorkflow() = runTest {
        val outbox = DurableOperationalEventOutbox(InMemoryStore(), clock, maximumRetainedEntries = 1)
        outbox.append(scope, envelope("a1", "a"))
        outbox.append(scope, envelope("b1", "b")) // evicts a1, workflow "a" has no retained entry now

        val next = assertIs<DurableOperationalEventOutboxAppendOutcome.Appended>(outbox.append(scope, envelope("a2", "a")))

        assertEquals(2L, next.sequence)
    }

    @Test
    fun gapsFromRemovedEntriesAreAllowedButNeverDuplicates() = runTest {
        val outbox = DurableOperationalEventOutbox(InMemoryStore(), clock, maximumRetainedEntries = 2)
        val sequences = (1..5).map {
            assertIs<DurableOperationalEventOutboxAppendOutcome.Appended>(outbox.append(scope, envelope("a$it", "a"))).sequence
        }

        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), sequences)
        // Retention evicted a1..a3: the retained sequences have a gap at the front only.
        assertEquals(listOf(4L, 5L), pendingEntries(outbox).map { it.sequence })
    }

    @Test
    fun appendingTheSameEnvelopeAgainReportsItsOriginalSequence() = runTest {
        val outbox = DurableOperationalEventOutbox(InMemoryStore(), clock)
        outbox.append(scope, envelope("a1", "a"))
        val second = envelope("a2", "a")
        outbox.append(scope, second)
        outbox.append(scope, envelope("a3", "a"))

        val retried = assertIs<DurableOperationalEventOutboxAppendOutcome.AlreadyAppended>(outbox.append(scope, second))

        assertEquals(2L, retried.sequence)
        assertEquals(listOf(1L, 2L, 3L), pendingEntries(outbox).map { it.sequence })
    }

    @Test
    fun aProducerRetryOfAnAlreadyAcknowledgedEntryDoesNotResurrectIt() = runTest {
        val outbox = DurableOperationalEventOutbox(InMemoryStore(), clock)
        val original = envelope("a1", "a")
        outbox.append(scope, original)
        outbox.acknowledge(scope, original.id)

        val retried = assertIs<DurableOperationalEventOutboxAppendOutcome.AlreadyAppended>(outbox.append(scope, original))

        assertEquals(1L, retried.sequence)
        assertEquals(emptyList(), pendingEntries(outbox))
    }

    @Test
    fun distinctScopesKeepIndependentSequences() = runTest {
        val outbox = DurableOperationalEventOutbox(InMemoryStore(), clock)
        outbox.append(scope, envelope("a1", "a"))
        outbox.append(scope, envelope("a2", "a"))

        val other = assertIs<DurableOperationalEventOutboxAppendOutcome.Appended>(
            outbox.append(OperationalEventOutboxScope("outbox-2"), envelope("a3", "a")),
        )

        assertEquals(1L, other.sequence)
    }

    // ---- Ordering: concurrent appenders can never assign the same sequence ------------------

    @Test
    fun aLosingConcurrentAppenderReloadsAndTakesTheNextSequenceInsteadOfDuplicatingTheWinners() = runTest {
        val backing = InMemoryStore()
        val interceptingStore = InterceptingStore(backing)
        val loser = DurableOperationalEventOutbox(interceptingStore, clock)
        val winner = DurableOperationalEventOutbox(backing, clock) // a second "process" on the same durable store
        // The winner commits its append after the loser has planned sequence 1
        // but before the loser's compare-and-set lands.
        interceptingStore.beforeFirstCompareAndSet = {
            val won = assertIs<DurableOperationalEventOutboxAppendOutcome.Appended>(winner.append(scope, envelope("won", "a")))
            assertEquals(1L, won.sequence)
        }

        val lost = assertIs<DurableOperationalEventOutboxAppendOutcome.Appended>(loser.append(scope, envelope("lost", "a")))

        assertEquals(2L, lost.sequence)
        assertEquals(2, interceptingStore.compareAndSetCalls)
        assertEquals(listOf("won" to 1L, "lost" to 2L), pendingEntries(winner).map { it.envelope.id.value to it.sequence })
    }

    @Test
    fun manyInterleavedAppendersAcrossTwoOutboxInstancesNeverProduceDuplicateOrOutOfOrderSequences() = runTest {
        val store = InMemoryStore(yieldOnAccess = true)
        val outboxes = listOf(
            DurableOperationalEventOutbox(store, clock, maximumStateUpdateAttempts = 500),
            DurableOperationalEventOutbox(store, clock, maximumStateUpdateAttempts = 500),
        )
        val appenders = 30
        val outcomes = (0 until appenders).map { index ->
            async {
                // Alternates instances and workflows so both same-key and cross-key races occur.
                outboxes[index % 2].append(scope, envelope("e$index", if (index % 3 == 0) null else "w${index % 3}"))
            }
        }.awaitAll()

        val appended = outcomes.map { assertIs<DurableOperationalEventOutboxAppendOutcome.Appended>(it) }
        assertTrue(store.conflicts > 0, "the interleaving must actually lose compare-and-set races")
        val pending = pendingEntries(outboxes[0])
        assertEquals(appenders, pending.size)
        pending.groupBy { it.orderingKey }.forEach { (_, entries) ->
            // Every key's sequences are exactly 1..n, with no duplicates, in list order.
            assertEquals((1L..entries.size.toLong()).toList(), entries.map { it.sequence })
        }
        // The sequence each appender was told matches what was durably persisted.
        val persistedById = pending.associate { it.envelope.id to it.sequence }
        appended.forEach { assertEquals(persistedById[it.envelope.id], it.sequence) }
    }

    // ---- Acknowledgement as a retained tombstone, and explicit replay ------------------------

    @Test
    fun acknowledgedEntriesAreRetainedAsTombstonesWithTheirAcknowledgementTime() = runTest {
        val outbox = DurableOperationalEventOutbox(InMemoryStore(), clock)
        val first = envelope("a1", "a")
        outbox.append(scope, first)
        outbox.append(scope, envelope("a2", "a"))
        clock.instant = DataLoomInstant(2_500L)

        val outcome = assertIs<DurableOperationalEventOutboxAcknowledgeOutcome.Acknowledged>(outbox.acknowledge(scope, first.id))

        assertEquals(DataLoomInstant(2_500L), outcome.acknowledgedAt)
        assertEquals(listOf("a2"), pendingEntries(outbox).map { it.envelope.id.value })
        val history = acknowledgedEntries(outbox)
        assertEquals(listOf(OperationalEventOutboxEntry(1L, first, DataLoomInstant(2_500L))), history)
        assertEquals(true, history.single().isAcknowledged)
    }

    @Test
    fun replayReopensAnAcknowledgedEntryAtItsOriginalPositionAheadOfLaterAppends() = runTest {
        val outbox = DurableOperationalEventOutbox(InMemoryStore(), clock)
        val first = envelope("a1", "a")
        outbox.append(scope, first)
        outbox.append(scope, envelope("b1", "b"))
        outbox.acknowledge(scope, first.id)
        outbox.append(scope, envelope("a2", "a"))
        assertEquals(listOf("b1", "a2"), pendingEntries(outbox).map { it.envelope.id.value })

        val replayed = assertIs<DurableOperationalEventOutboxReplayOutcome.Replayed>(outbox.replay(scope, first.id))

        assertEquals(OperationalEventOutboxEntry(1L, first, acknowledgedAt = null), replayed.entry)
        // Original position and sequence: a1 is presented first again, a2 keeps sequence 2.
        val pending = pendingEntries(outbox)
        assertEquals(listOf("a1", "b1", "a2"), pending.map { it.envelope.id.value })
        assertEquals(listOf(1L, 1L, 2L), pending.map { it.sequence })
        assertEquals(emptyList(), acknowledgedEntries(outbox))
    }

    @Test
    fun replayedEntryCanBeAcknowledgedAgain() = runTest {
        val outbox = DurableOperationalEventOutbox(InMemoryStore(), clock)
        val first = envelope("a1", "a")
        outbox.append(scope, first)
        outbox.acknowledge(scope, first.id)
        outbox.replay(scope, first.id)
        clock.instant = DataLoomInstant(9_000L)

        val again = assertIs<DurableOperationalEventOutboxAcknowledgeOutcome.Acknowledged>(outbox.acknowledge(scope, first.id))

        assertEquals(DataLoomInstant(9_000L), again.acknowledgedAt)
    }

    @Test
    fun replayingAPendingEntryIsAWellDefinedNoOp() = runTest {
        val outbox = DurableOperationalEventOutbox(InMemoryStore(), clock)
        val first = envelope("a1", "a")
        outbox.append(scope, first)

        val outcome = assertIs<DurableOperationalEventOutboxReplayOutcome.AlreadyPending>(outbox.replay(scope, first.id))

        assertEquals(OperationalEventOutboxEntry(1L, first), outcome.entry)
    }

    @Test
    fun replayingAnUnknownIdReportsNotFound() = runTest {
        val outbox = DurableOperationalEventOutbox(InMemoryStore(), clock)

        assertIs<DurableOperationalEventOutboxReplayOutcome.NotFound>(outbox.replay(scope, OperationalEventId("never")))
    }

    @Test
    fun acknowledgementAndReplaySurviveAReloadOfTheSameStore() = runTest {
        val store = InMemoryStore()
        val first = envelope("a1", "a")
        val outbox = DurableOperationalEventOutbox(store, clock)
        outbox.append(scope, first)
        outbox.acknowledge(scope, first.id)

        val reopened = DurableOperationalEventOutbox(store, clock)
        assertEquals(1, acknowledgedEntries(reopened).size)
        assertIs<DurableOperationalEventOutboxReplayOutcome.Replayed>(reopened.replay(scope, first.id))

        assertEquals(listOf("a1"), pendingEntries(DurableOperationalEventOutbox(store, clock)).map { it.envelope.id.value })
    }

    @Test
    fun replayReportsPersistenceFailureAndContentionLimit() = runTest {
        val tombstone = DurableStateRecord(
            state = OperationalEventOutboxState(
                listOf(OperationalEventOutboxEntry(1L, envelope("a1", "a"), DataLoomInstant(1L))),
            ),
            version = 0L,
            schemaVersion = 2,
        )

        val failing = DurableOperationalEventOutbox(FixedRecordStore(tombstone, casResult = { error("unused") }, casFails = true), clock)
        assertIs<DurableOperationalEventOutboxReplayOutcome.PersistenceFailure>(failing.replay(scope, OperationalEventId("a1")))

        val contended = DurableOperationalEventOutbox(
            FixedRecordStore(tombstone, casResult = { DurableStateCompareAndSetResult.Conflict(tombstone) }),
            clock,
            maximumStateUpdateAttempts = 3,
        )
        assertIs<DurableOperationalEventOutboxReplayOutcome.ContentionLimitReached>(contended.replay(scope, OperationalEventId("a1")))
    }

    @Test
    fun aPendingEntryOfTheSameWorkflowIsNotDisturbedByAcknowledgingAnother() = runTest {
        val outbox = DurableOperationalEventOutbox(InMemoryStore(), clock, maximumRetainedEntries = 2)
        outbox.append(scope, envelope("a1", "a"))
        outbox.append(scope, envelope("a2", "a"))
        outbox.acknowledge(scope, OperationalEventId("a1"))

        // Pending count is 1 (a2); the tombstone does not count against the pending cap.
        outbox.append(scope, envelope("a3", "a"))

        assertEquals(listOf("a2", "a3"), pendingEntries(outbox).map { it.envelope.id.value })
        assertEquals(listOf("a1"), acknowledgedEntries(outbox).map { it.envelope.id.value })
    }

    // ---- Acknowledged-history pruning -------------------------------------------------------

    @Test
    fun countRetentionPrunesTheEarliestAcknowledgedTombstonesFirst() = runTest {
        val outbox = DurableOperationalEventOutbox(InMemoryStore(), clock, maximumRetainedAcknowledgedEntries = 2)
        (1..4).forEach { outbox.append(scope, envelope("a$it", "a")) }
        // Acknowledge out of append order so "earliest acknowledged" differs from "earliest appended".
        clock.instant = DataLoomInstant(10L)
        outbox.acknowledge(scope, OperationalEventId("a3"))
        clock.instant = DataLoomInstant(20L)
        outbox.acknowledge(scope, OperationalEventId("a1"))
        clock.instant = DataLoomInstant(30L)
        outbox.acknowledge(scope, OperationalEventId("a2"))

        // a3 (acknowledged at 10) was pruned when a2's acknowledgement pushed the count past 2.
        assertEquals(listOf("a1", "a2"), acknowledgedEntries(outbox).map { it.envelope.id.value })
        assertIs<DurableOperationalEventOutboxReplayOutcome.NotFound>(outbox.replay(scope, OperationalEventId("a3")))
        assertEquals(listOf("a4"), pendingEntries(outbox).map { it.envelope.id.value })
    }

    @Test
    fun countRetentionTiesBreakByListPosition() = runTest {
        val outbox = DurableOperationalEventOutbox(InMemoryStore(), clock, maximumRetainedAcknowledgedEntries = 1)
        (1..3).forEach { outbox.append(scope, envelope("a$it", "a")) }
        // The clock never moves, so all three share one acknowledgedAt.
        outbox.acknowledge(scope, OperationalEventId("a2"))
        outbox.acknowledge(scope, OperationalEventId("a1"))

        // Same acknowledgedAt, so list position decides: a1 precedes a2 in the list and is
        // pruned even though a2 was acknowledged first.
        assertEquals(listOf("a2"), acknowledgedEntries(outbox).map { it.envelope.id.value })
    }

    @Test
    fun ageRetentionPrunesTombstonesPastTheWindowOnTheNextAppend() = runTest {
        val outbox = DurableOperationalEventOutbox(InMemoryStore(), clock, acknowledgedRetentionAge = 100.milliseconds)
        outbox.append(scope, envelope("a1", "a"))
        outbox.acknowledge(scope, OperationalEventId("a1")) // acknowledged at 1_000
        clock.instant = DataLoomInstant(1_050L)
        outbox.append(scope, envelope("a2", "a"))
        assertEquals(listOf("a1"), acknowledgedEntries(outbox).map { it.envelope.id.value }) // 50ms old: kept

        clock.instant = DataLoomInstant(1_101L)
        outbox.append(scope, envelope("a3", "a"))

        assertEquals(emptyList(), acknowledgedEntries(outbox)) // 101ms old: pruned
        assertIs<DurableOperationalEventOutboxReplayOutcome.NotFound>(outbox.replay(scope, OperationalEventId("a1")))
        // Pruning the tombstone did not free its sequence.
        assertEquals(3L, pendingEntries(outbox).last().sequence)
    }

    @Test
    fun ageRetentionPrunesOnAcknowledgeToo() = runTest {
        val outbox = DurableOperationalEventOutbox(InMemoryStore(), clock, acknowledgedRetentionAge = 100.milliseconds)
        outbox.append(scope, envelope("a1", "a"))
        outbox.append(scope, envelope("a2", "a"))
        outbox.acknowledge(scope, OperationalEventId("a1")) // at 1_000
        clock.instant = DataLoomInstant(1_500L)

        outbox.acknowledge(scope, OperationalEventId("a2"))

        assertEquals(listOf("a2"), acknowledgedEntries(outbox).map { it.envelope.id.value })
    }

    @Test
    fun tombstonePruningNeverTouchesPendingEntries() = runTest {
        val outbox = DurableOperationalEventOutbox(
            InMemoryStore(),
            clock,
            maximumRetainedAcknowledgedEntries = 0,
            acknowledgedRetentionAge = 1.milliseconds,
        )
        outbox.append(scope, envelope("a1", "a", occurredAt = DataLoomInstant(0L)))
        outbox.append(scope, envelope("a2", "a", occurredAt = DataLoomInstant(0L)))
        outbox.acknowledge(scope, OperationalEventId("a1"))
        clock.instant = DataLoomInstant(1_000_000L)

        outbox.append(scope, envelope("a3", "a"))

        assertEquals(listOf("a2", "a3"), pendingEntries(outbox).map { it.envelope.id.value })
    }

    @Test
    fun theClockIsNotReadOnAppendUnlessAnAgePolicyIsConfigured() = runTest {
        val explodingClock = object : DataLoomClock {
            override fun now(): DataLoomInstant = error("clock must not be read")
        }
        val outbox = DurableOperationalEventOutbox(InMemoryStore(), explodingClock)

        assertIs<DurableOperationalEventOutboxAppendOutcome.Appended>(outbox.append(scope, envelope("a1", "a")))
    }

    @Test
    fun aReplayedEntryIsSubjectToPendingCountRetentionOnTheNextAppendLikeAnyOtherPendingEntry() = runTest {
        val outbox = DurableOperationalEventOutbox(InMemoryStore(), clock, maximumRetainedEntries = 1)
        outbox.append(scope, envelope("a1", "a"))
        outbox.acknowledge(scope, OperationalEventId("a1"))
        outbox.append(scope, envelope("a2", "a"))
        outbox.replay(scope, OperationalEventId("a1")) // pending count is now 2, above the cap of 1

        outbox.append(scope, envelope("a3", "a"))

        // Documented consequence: the replayed entry sits at its old position, so it is the
        // first count-eviction candidate.
        assertEquals(listOf("a3"), pendingEntries(outbox).map { it.envelope.id.value })
    }

    // ---- Bounded sequence tracking --------------------------------------------------------------

    @Test
    fun boundedTrackingDropsTheLowestMarksOfUnretainedKeysAndRaisesTheFloorSoNoSequenceIsReused() {
        val a = OperationalEventOrderingKey.forWorkflow(WorkflowId("a"))
        val b = OperationalEventOrderingKey.forWorkflow(WorkflowId("b"))
        val c = OperationalEventOrderingKey.forWorkflow(WorkflowId("c"))
        val retained = OperationalEventOutboxEntry(4L, envelope("c1", "c"))
        val marks = mapOf(a to 7L, b to 3L, c to 4L)

        val (bounded, floor) = boundSequenceTracking(listOf(retained), marks, floor = 0L, maximumTrackedKeys = 2)

        assertEquals(mapOf(a to 7L, c to 4L), bounded) // b (lowest mark, no retained entry) dropped
        assertEquals(3L, floor)
        val state = OperationalEventOutboxState(listOf(retained), bounded, floor)
        assertEquals(4L, nextSequenceFor(state, b)) // the dropped key restarts above the floor, never at 1..3
        assertEquals(8L, nextSequenceFor(state, a))
        assertEquals(5L, nextSequenceFor(state, c))
    }

    @Test
    fun boundedTrackingNeverDropsAKeyThatStillHasARetainedEntry() {
        val a = OperationalEventOrderingKey.forWorkflow(WorkflowId("a"))
        val b = OperationalEventOrderingKey.forWorkflow(WorkflowId("b"))
        val entries = listOf(OperationalEventOutboxEntry(1L, envelope("a1", "a")), OperationalEventOutboxEntry(1L, envelope("b1", "b")))
        val marks = mapOf(a to 1L, b to 1L)

        assertEquals(marks to 0L, boundSequenceTracking(entries, marks, floor = 0L, maximumTrackedKeys = 1))
    }

    @Test
    fun boundedTrackingIsDeterministicWhenMarksTie() {
        val keys = listOf("x", "y", "z").map { OperationalEventOrderingKey.forWorkflow(WorkflowId(it)) }
        val marks = keys.associateWith { 2L }

        val (bounded, floor) = boundSequenceTracking(emptyList(), marks, floor = 0L, maximumTrackedKeys = 1)

        assertEquals(setOf(keys.last()), bounded.keys) // ties dropped in key order: x, y dropped, z kept
        assertEquals(2L, floor)
    }

    // ---- State invariants -----------------------------------------------------------------------

    @Test
    fun stateRejectsDuplicateEnvelopeIds() {
        assertFailsWith<IllegalArgumentException> {
            OperationalEventOutboxState(
                listOf(OperationalEventOutboxEntry(1L, envelope("a1", "a")), OperationalEventOutboxEntry(2L, envelope("a1", "a"))),
            )
        }
    }

    @Test
    fun stateRejectsNonIncreasingSequencesWithinOneWorkflow() {
        assertFailsWith<IllegalArgumentException> {
            OperationalEventOutboxState(
                listOf(OperationalEventOutboxEntry(2L, envelope("a1", "a")), OperationalEventOutboxEntry(2L, envelope("a2", "a"))),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            OperationalEventOutboxState(
                listOf(OperationalEventOutboxEntry(3L, envelope("a1", "a")), OperationalEventOutboxEntry(2L, envelope("a2", "a"))),
            )
        }
    }

    @Test
    fun stateAllowsEqualSequencesAcrossDifferentWorkflows() {
        OperationalEventOutboxState(
            listOf(OperationalEventOutboxEntry(1L, envelope("a1", "a")), OperationalEventOutboxEntry(1L, envelope("b1", "b"))),
        )
    }

    @Test
    fun stateRejectsAHighWaterMarkBelowARetainedSequence() {
        assertFailsWith<IllegalArgumentException> {
            OperationalEventOutboxState(
                entries = listOf(OperationalEventOutboxEntry(5L, envelope("a1", "a"))),
                sequenceHighWaterMarks = mapOf(OperationalEventOrderingKey.forWorkflow(WorkflowId("a")) to 4L),
            )
        }
    }

    @Test
    fun entryRejectsASequenceBelowOne() {
        assertFailsWith<IllegalArgumentException> { OperationalEventOutboxEntry(0L, envelope("a1", "a")) }
    }

    // ---- Helpers ---------------------------------------------------------------------------------

    private suspend fun pendingEntries(outbox: DurableOperationalEventOutbox): List<OperationalEventOutboxEntry> =
        assertIs<ProviderOperationResult.Success<List<OperationalEventOutboxEntry>>>(outbox.pendingEntries(scope)).value

    private suspend fun acknowledgedEntries(outbox: DurableOperationalEventOutbox): List<OperationalEventOutboxEntry> =
        assertIs<ProviderOperationResult.Success<List<OperationalEventOutboxEntry>>>(outbox.acknowledgedEntries(scope)).value

    private fun envelope(
        id: String,
        workflow: String? = null,
        occurredAt: DataLoomInstant = DataLoomInstant(1_000L),
    ): OperationalEventEnvelope = OperationalEventEnvelope(
        id = OperationalEventId(id),
        type = OperationalEventType("dataloom.retry.scheduled"),
        source = OperationalEventSource("dataloom.runtime.retry"),
        category = OperationalEventCategory.TELEMETRY,
        schemaVersion = OperationalSchemaVersion(1),
        occurredAt = occurredAt,
        correlationId = CorrelationId("correlation-1"),
        workflowId = workflow?.let { WorkflowId(it) },
        payload = OperationalPayloadDescriptor(
            type = OperationalPayloadType("dataloom.retry.signal"),
            schemaVersion = OperationalSchemaVersion(1),
            encoding = OperationalPayloadEncoding("application/json"),
            classification = DataClassification.INTERNAL,
        ),
    )

    private class StubClock(var instant: DataLoomInstant) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
    }

    /**
     * Version-checked in-memory [DurableStateStore]. With [yieldOnAccess] every
     * `load` and `compareAndSet` suspends first, so concurrently launched
     * appenders genuinely interleave between their load and their
     * compare-and-set and lose races -- the compare-and-set itself stays
     * atomic (no suspension between its version check and its write).
     */
    private class InMemoryStore(private val yieldOnAccess: Boolean = false) :
        DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState> {
        private val records = mutableMapOf<OperationalEventOutboxScope, DurableStateRecord<OperationalEventOutboxState>>()

        /** How many compare-and-set calls lost a race -- proves the interleaving really contended. */
        var conflicts: Int = 0
            private set

        override suspend fun load(
            scope: OperationalEventOutboxScope,
        ): ProviderOperationResult<DurableStateLoadResult<OperationalEventOutboxState>> {
            val record = records[scope]
            if (yieldOnAccess) yield()
            return ProviderOperationResult.Success(
                if (record == null) DurableStateLoadResult.Missing else DurableStateLoadResult.Found(record),
            )
        }

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<OperationalEventOutboxScope, OperationalEventOutboxState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<OperationalEventOutboxState>> {
            if (yieldOnAccess) yield()
            val current = records[request.scope]
            if (current?.version != request.expectedVersion) {
                conflicts += 1
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

    /** Runs [beforeFirstCompareAndSet] once, just before delegating the first compare-and-set -- a concurrent writer landing in the load-to-compare-and-set window. */
    private class InterceptingStore(private val delegate: InMemoryStore) :
        DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState> {
        var beforeFirstCompareAndSet: (suspend () -> Unit)? = null
        var compareAndSetCalls: Int = 0
            private set

        override suspend fun load(
            scope: OperationalEventOutboxScope,
        ): ProviderOperationResult<DurableStateLoadResult<OperationalEventOutboxState>> = delegate.load(scope)

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<OperationalEventOutboxScope, OperationalEventOutboxState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<OperationalEventOutboxState>> {
            compareAndSetCalls += 1
            beforeFirstCompareAndSet?.let {
                beforeFirstCompareAndSet = null
                it()
            }
            return delegate.compareAndSet(request)
        }
    }

    /** Always loads [record]; compare-and-set either fails or returns [casResult]. */
    private class FixedRecordStore(
        private val record: DurableStateRecord<OperationalEventOutboxState>,
        private val casResult: () -> DurableStateCompareAndSetResult<OperationalEventOutboxState>,
        private val casFails: Boolean = false,
    ) : DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState> {
        override suspend fun load(
            scope: OperationalEventOutboxScope,
        ): ProviderOperationResult<DurableStateLoadResult<OperationalEventOutboxState>> =
            ProviderOperationResult.Success(DurableStateLoadResult.Found(record))

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<OperationalEventOutboxScope, OperationalEventOutboxState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<OperationalEventOutboxState>> =
            if (casFails) ProviderOperationResult.Failure(storeError()) else ProviderOperationResult.Success(casResult())
    }
}

private fun storeError(): DataLoomError = OrderingTestError(
    code = ErrorCode("DURABLE_OPERATIONAL_EVENT_OUTBOX_ORDERING_TEST_FAILURE"),
    category = ErrorCategory.STORAGE,
    severity = ErrorSeverity.ERROR,
    recoverability = Recoverability.RECOVERABLE,
    message = "Simulated store failure.",
)

private data class OrderingTestError(
    override val code: ErrorCode,
    override val category: ErrorCategory,
    override val severity: ErrorSeverity,
    override val recoverability: Recoverability,
    override val message: String,
    override val cause: Throwable? = null,
) : DataLoomError
