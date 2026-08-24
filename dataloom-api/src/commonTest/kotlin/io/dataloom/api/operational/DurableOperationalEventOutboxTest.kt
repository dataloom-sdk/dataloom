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
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.runTest

/**
 * Verifies [DurableOperationalEventOutbox]'s ordered-append, idempotent-retry,
 * conflict-detection, retention, and acknowledgement behavior -- the bounded
 * first slice of DL-042's durable-outbox requirement.
 */
class DurableOperationalEventOutboxTest {

    private val scope = OperationalEventOutboxScope("outbox-1")

    @Test
    fun keyEncoderEncodesEqualScopesIdenticallyAndDistinctScopesDifferently() {
        val encoder = OperationalEventOutboxScope.KeyEncoder
        assertEquals(
            encoder.encode(OperationalEventOutboxScope("s1")),
            encoder.encode(OperationalEventOutboxScope("s1")),
        )
        assertEquals("s1", encoder.encode(OperationalEventOutboxScope("s1")))
        assertTrue(
            encoder.encode(OperationalEventOutboxScope("s1")) !=
                encoder.encode(OperationalEventOutboxScope("s2")),
        )
    }

    @Test
    fun scopeRejectsBlankValue() {
        assertFailsWith<IllegalArgumentException> { OperationalEventOutboxScope(" ") }
    }

    @Test
    fun entriesAreEmptyBeforeAnySuccessfulAppend() = runTest {
        val outbox = DurableOperationalEventOutbox(InMemoryOperationalEventOutboxStore())
        val result = assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(outbox.entries(scope))
        assertEquals(emptyList(), result.value)
    }

    @Test
    fun firstAppendSucceedsAndSurvivesAReloadOfTheSameStore() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val outbox = DurableOperationalEventOutbox(store)
        val envelope = envelope("event-1")

        val outcome = outbox.append(scope, envelope)

        val appended = assertIs<DurableOperationalEventOutboxAppendOutcome.Appended>(outcome)
        assertEquals(envelope, appended.envelope)

        // A fresh DurableOperationalEventOutbox wrapping the same underlying
        // store simulates a process restart: no in-memory outbox state
        // survives, only what the store itself persisted.
        val reopened = DurableOperationalEventOutbox(store)
        val entries = assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(reopened.entries(scope))
        assertEquals(listOf(envelope), entries.value)
    }

    @Test
    fun secondAppendPreservesOrderOldestFirst() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val outbox = DurableOperationalEventOutbox(store)
        val first = envelope("event-1")
        val second = envelope("event-2")

        outbox.append(scope, first)
        outbox.append(scope, second)

        val entries = assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(outbox.entries(scope))
        assertEquals(listOf(first, second), entries.value)
    }

    @Test
    fun appendingTheSameEnvelopeAgainIsIdempotent() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val outbox = DurableOperationalEventOutbox(store)
        val envelope = envelope("event-1")
        outbox.append(scope, envelope)

        val outcome = outbox.append(scope, envelope)

        val already = assertIs<DurableOperationalEventOutboxAppendOutcome.AlreadyAppended>(outcome)
        assertEquals(envelope, already.envelope)
        val entries = assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(outbox.entries(scope))
        assertEquals(listOf(envelope), entries.value)
    }

    @Test
    fun appendingADifferentEnvelopeWithTheSameIdConflicts() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val outbox = DurableOperationalEventOutbox(store)
        val first = envelope("event-1", correlationId = "correlation-a")
        outbox.append(scope, first)
        val second = envelope("event-1", correlationId = "correlation-b")

        val outcome = outbox.append(scope, second)

        val conflict = assertIs<DurableOperationalEventOutboxAppendOutcome.Conflict>(outcome)
        assertEquals(first, conflict.existing)
        assertEquals(second, conflict.attempted)
        val entries = assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(outbox.entries(scope))
        assertEquals(listOf(first), entries.value)
    }

    @Test
    fun distinctScopesAreIndependent() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val outbox = DurableOperationalEventOutbox(store)
        val other = OperationalEventOutboxScope("outbox-2")
        val a = envelope("event-1")
        val b = envelope("event-2")

        outbox.append(scope, a)
        outbox.append(other, b)

        val scopeEntries = assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(outbox.entries(scope))
        val otherEntries = assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(outbox.entries(other))
        assertEquals(listOf(a), scopeEntries.value)
        assertEquals(listOf(b), otherEntries.value)
    }

    @Test
    fun maximumStateUpdateAttemptsBelowOneIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            DurableOperationalEventOutbox(InMemoryOperationalEventOutboxStore(), maximumStateUpdateAttempts = 0)
        }
    }

    @Test
    fun maximumRetainedEntriesBelowOneIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            DurableOperationalEventOutbox(InMemoryOperationalEventOutboxStore(), maximumRetainedEntries = 0)
        }
    }

    @Test
    fun maximumRetainedAgeOfZeroIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            DurableOperationalEventOutbox(
                InMemoryOperationalEventOutboxStore(),
                maximumRetainedAge = Duration.ZERO,
                clock = StubDataLoomClock(DataLoomInstant(0L)),
            )
        }
    }

    @Test
    fun maximumRetainedAgeWithoutAClockIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            DurableOperationalEventOutbox(
                InMemoryOperationalEventOutboxStore(),
                maximumRetainedAge = 1_000L.milliseconds,
            )
        }
    }

    @Test
    fun unconfiguredRetentionAccumulatesEveryAppendWithoutEviction() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val outbox = DurableOperationalEventOutbox(store)
        val envelopes = (1..5).map { envelope("event-$it") }

        envelopes.forEach { outbox.append(scope, it) }

        val entries = assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(outbox.entries(scope))
        assertEquals(envelopes, entries.value)
    }

    @Test
    fun appendingUnderTheRetentionCapDoesNotEvictAnything() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val outbox = DurableOperationalEventOutbox(store, maximumRetainedEntries = 3)
        val envelopes = (1..3).map { envelope("event-$it") }

        envelopes.forEach { outbox.append(scope, it) }

        val entries = assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(outbox.entries(scope))
        assertEquals(envelopes, entries.value)
    }

    @Test
    fun appendingAtTheRetentionCapDoesNotEvictAnything() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val outbox = DurableOperationalEventOutbox(store, maximumRetainedEntries = 3)
        val envelopes = (1..3).map { envelope("event-$it") }
        envelopes.dropLast(1).forEach { outbox.append(scope, it) }

        outbox.append(scope, envelopes.last())

        val entries = assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(outbox.entries(scope))
        assertEquals(envelopes, entries.value)
    }

    @Test
    fun appendingPastTheRetentionCapEvictsTheOldestEntriesFirst() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val outbox = DurableOperationalEventOutbox(store, maximumRetainedEntries = 3)
        val envelopes = (1..5).map { envelope("event-$it") }

        envelopes.forEach { outbox.append(scope, it) }

        val entries = assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(outbox.entries(scope))
        // Oldest-first list capped at 3: only the three most recently appended survive.
        assertEquals(envelopes.takeLast(3), entries.value)
    }

    @Test
    fun retentionCapOfOneKeepsOnlyTheMostRecentlyAppendedEntry() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val outbox = DurableOperationalEventOutbox(store, maximumRetainedEntries = 1)
        val first = envelope("event-1")
        val second = envelope("event-2")

        outbox.append(scope, first)
        outbox.append(scope, second)

        val entries = assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(outbox.entries(scope))
        assertEquals(listOf(second), entries.value)
    }

    @Test
    fun retentionEvictionSurvivesAReloadOfTheSameStore() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val outbox = DurableOperationalEventOutbox(store, maximumRetainedEntries = 2)
        val envelopes = (1..3).map { envelope("event-$it") }
        envelopes.forEach { outbox.append(scope, it) }

        // A fresh DurableOperationalEventOutbox wrapping the same underlying
        // store simulates a process restart: eviction already happened as
        // part of the persisted state, not something recomputed on read.
        val reopened = DurableOperationalEventOutbox(store, maximumRetainedEntries = 2)
        val entries = assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(reopened.entries(scope))
        assertEquals(envelopes.takeLast(2), entries.value)
    }

    @Test
    fun retentionEvictsIndependentlyPerScope() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val outbox = DurableOperationalEventOutbox(store, maximumRetainedEntries = 2)
        val other = OperationalEventOutboxScope("outbox-2")
        val scopeEnvelopes = (1..3).map { envelope("scope-event-$it") }
        val otherEnvelopes = (1..2).map { envelope("other-event-$it") }

        scopeEnvelopes.forEach { outbox.append(scope, it) }
        otherEnvelopes.forEach { outbox.append(other, it) }

        val scopeEntries = assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(outbox.entries(scope))
        val otherEntries = assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(outbox.entries(other))
        assertEquals(scopeEnvelopes.takeLast(2), scopeEntries.value)
        assertEquals(otherEnvelopes, otherEntries.value)
    }

    @Test
    fun appendingAnAlreadyEvictedIdIsTreatedAsANewAppendRatherThanIdempotentOrConflicting() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val outbox = DurableOperationalEventOutbox(store, maximumRetainedEntries = 1)
        val evicted = envelope("event-1")
        val displacing = envelope("event-2")
        outbox.append(scope, evicted)
        outbox.append(scope, displacing) // evicts "event-1"

        val outcome = outbox.append(scope, evicted)

        // The id has already been evicted, so it is no longer visible to the
        // duplicate-id check -- re-appending it is a fresh append, not
        // AlreadyAppended or Conflict.
        val appended = assertIs<DurableOperationalEventOutboxAppendOutcome.Appended>(outcome)
        assertEquals(evicted, appended.envelope)
        val entries = assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(outbox.entries(scope))
        assertEquals(listOf(evicted), entries.value)
    }

    @Test
    fun retentionEvictionDoesNotAffectContentionRetryDiscipline() = runTest {
        val envelope = envelope("event-1")
        val winningRecord = DurableStateRecord(
            state = OperationalEventOutboxState(listOf(envelope)),
            version = 0L,
            schemaVersion = 1,
        )
        val store = RaceThenConsistentStore(winningRecord)
        val outbox = DurableOperationalEventOutbox(store, maximumRetainedEntries = 5)

        val outcome = outbox.append(scope, envelope)

        assertIs<DurableOperationalEventOutboxAppendOutcome.AlreadyAppended>(outcome)
    }

    @Test
    fun ageBasedRetentionEvictsEntriesOlderThanTheConfiguredAge() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val clock = StubDataLoomClock(DataLoomInstant(0L))
        val outbox = DurableOperationalEventOutbox(store, maximumRetainedAge = 100L.milliseconds, clock = clock)
        val first = envelope("event-1", occurredAt = DataLoomInstant(0L))
        outbox.append(scope, first)

        clock.instant = DataLoomInstant(150L)
        val second = envelope("event-2", occurredAt = DataLoomInstant(150L))
        outbox.append(scope, second)

        // "event-1" is 150ms old relative to the clock's reading at the
        // second append -- older than the configured 100ms window -- so it
        // is evicted as part of that same compare-and-set write.
        val entries = assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(outbox.entries(scope))
        assertEquals(listOf(second), entries.value)
    }

    @Test
    fun ageBasedRetentionKeepsEntriesStillWithinTheWindow() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val clock = StubDataLoomClock(DataLoomInstant(0L))
        val outbox = DurableOperationalEventOutbox(store, maximumRetainedAge = 100L.milliseconds, clock = clock)
        val first = envelope("event-1", occurredAt = DataLoomInstant(0L))
        outbox.append(scope, first)

        clock.instant = DataLoomInstant(50L)
        val second = envelope("event-2", occurredAt = DataLoomInstant(50L))
        outbox.append(scope, second)

        // "event-1" is only 50ms old relative to the clock's reading at the
        // second append -- within the configured 100ms window -- so it
        // survives.
        val entries = assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(outbox.entries(scope))
        assertEquals(listOf(first, second), entries.value)
    }

    @Test
    fun ageBasedRetentionNeverEvictsTheEntryTheCurrentAppendIsItselfAddingEvenIfAlreadyOutsideTheWindow() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val clock = StubDataLoomClock(DataLoomInstant(1_000L))
        val outbox = DurableOperationalEventOutbox(store, maximumRetainedAge = 100L.milliseconds, clock = clock)
        val recent = envelope("event-recent", occurredAt = DataLoomInstant(1_000L))
        outbox.append(scope, recent)

        // A deliberate historical backfill: "event-backfill"'s own
        // occurredAt is already far outside the retention window at the
        // moment it is appended, but it is the entry this very append call
        // is adding, so it is never a candidate for age-based eviction on
        // this call.
        val backfill = envelope("event-backfill", occurredAt = DataLoomInstant(0L))
        val outcome = outbox.append(scope, backfill)

        assertIs<DurableOperationalEventOutboxAppendOutcome.Appended>(outcome)
        val entries = assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(outbox.entries(scope))
        assertEquals(listOf(recent, backfill), entries.value)

        // On the *next* append, "event-backfill" is no longer the entry
        // being added, so it becomes a candidate for age-based eviction and
        // is evicted.
        val third = envelope("event-3", occurredAt = DataLoomInstant(1_000L))
        outbox.append(scope, third)
        val laterEntries = assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(outbox.entries(scope))
        assertEquals(listOf(recent, third), laterEntries.value)
    }

    @Test
    fun ageBasedRetentionEvictionSurvivesAReloadOfTheSameStore() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val clock = StubDataLoomClock(DataLoomInstant(0L))
        val outbox = DurableOperationalEventOutbox(store, maximumRetainedAge = 100L.milliseconds, clock = clock)
        val first = envelope("event-1", occurredAt = DataLoomInstant(0L))
        outbox.append(scope, first)

        clock.instant = DataLoomInstant(200L)
        val second = envelope("event-2", occurredAt = DataLoomInstant(200L))
        outbox.append(scope, second)

        // A fresh DurableOperationalEventOutbox wrapping the same underlying
        // store simulates a process restart: eviction already happened as
        // part of the persisted state, not something recomputed on read.
        val reopened = DurableOperationalEventOutbox(
            store,
            maximumRetainedAge = 100L.milliseconds,
            clock = StubDataLoomClock(DataLoomInstant(200L)),
        )
        val entries = assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(reopened.entries(scope))
        assertEquals(listOf(second), entries.value)
    }

    @Test
    fun bothRetentionPoliciesComposeSoAnEntryEvictedByEitherPolicyIsEvicted() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val clock = StubDataLoomClock(DataLoomInstant(0L))
        val outbox = DurableOperationalEventOutbox(
            store,
            maximumRetainedEntries = 2,
            maximumRetainedAge = 100L.milliseconds,
            clock = clock,
        )
        val a = envelope("event-a", occurredAt = DataLoomInstant(0L))
        val b = envelope("event-b", occurredAt = DataLoomInstant(0L))
        outbox.append(scope, a)
        outbox.append(scope, b)
        val afterAb = assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(outbox.entries(scope))
        assertEquals(listOf(a, b), afterAb.value)

        // Advance well past the age window; appending "event-c" evicts both
        // "event-a" and "event-b" purely by age, even though the count cap
        // of 2 was not yet exceeded -- age-based eviction removing entries
        // count-based eviction alone would have kept.
        clock.instant = DataLoomInstant(200L)
        val c = envelope("event-c", occurredAt = DataLoomInstant(200L))
        outbox.append(scope, c)
        val afterC = assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(outbox.entries(scope))
        assertEquals(listOf(c), afterC.value)

        // Two more recent appends, all well within the age window, push the
        // retained count past the cap of 2 -- count-based eviction removing
        // an entry age-based eviction alone would have kept.
        val d = envelope("event-d", occurredAt = DataLoomInstant(200L))
        outbox.append(scope, d)
        val e = envelope("event-e", occurredAt = DataLoomInstant(200L))
        outbox.append(scope, e)

        val finalEntries = assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(outbox.entries(scope))
        assertEquals(listOf(d, e), finalEntries.value)
    }

    @Test
    fun acknowledgingAnExistingEntryRemovesItAndIsReflectedInASubsequentEntriesCall() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val outbox = DurableOperationalEventOutbox(store)
        val first = envelope("event-1")
        val second = envelope("event-2")
        outbox.append(scope, first)
        outbox.append(scope, second)

        val outcome = outbox.acknowledge(scope, first.id)

        val acknowledged = assertIs<DurableOperationalEventOutboxAcknowledgeOutcome.Acknowledged>(outcome)
        assertEquals(first, acknowledged.envelope)
        val entries = assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(outbox.entries(scope))
        assertEquals(listOf(second), entries.value)
    }

    @Test
    fun acknowledgementSurvivesAReloadOfTheSameStore() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val outbox = DurableOperationalEventOutbox(store)
        val envelope = envelope("event-1")
        outbox.append(scope, envelope)

        outbox.acknowledge(scope, envelope.id)

        // A fresh DurableOperationalEventOutbox wrapping the same underlying
        // store simulates a process restart: the removal already happened as
        // part of the persisted state, not something recomputed on read.
        val reopened = DurableOperationalEventOutbox(store)
        val entries = assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(reopened.entries(scope))
        assertEquals(emptyList(), entries.value)
    }

    @Test
    fun acknowledgingANonexistentIdIsAWellDefinedNoOp() = runTest {
        val outbox = DurableOperationalEventOutbox(InMemoryOperationalEventOutboxStore())

        val outcome = outbox.acknowledge(scope, OperationalEventId("never-appended"))

        assertIs<DurableOperationalEventOutboxAcknowledgeOutcome.NotFound>(outcome)
    }

    @Test
    fun acknowledgingTheSameEntryTwiceIsIdempotentAndTheSecondCallReportsNotFound() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val outbox = DurableOperationalEventOutbox(store)
        val envelope = envelope("event-1")
        outbox.append(scope, envelope)
        assertIs<DurableOperationalEventOutboxAcknowledgeOutcome.Acknowledged>(outbox.acknowledge(scope, envelope.id))

        val outcome = outbox.acknowledge(scope, envelope.id)

        assertIs<DurableOperationalEventOutboxAcknowledgeOutcome.NotFound>(outcome)
    }

    @Test
    fun acknowledgingAnEntryAlreadyEvictedByRetentionIsAWellDefinedNoOp() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val outbox = DurableOperationalEventOutbox(store, maximumRetainedEntries = 1)
        val evicted = envelope("event-1")
        val displacing = envelope("event-2")
        outbox.append(scope, evicted)
        outbox.append(scope, displacing) // evicts "event-1"

        val outcome = outbox.acknowledge(scope, evicted.id)

        assertIs<DurableOperationalEventOutboxAcknowledgeOutcome.NotFound>(outcome)
        val entries = assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(outbox.entries(scope))
        assertEquals(listOf(displacing), entries.value)
    }

    @Test
    fun acknowledgingOneEntryDoesNotDisturbRetentionForSubsequentAppends() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val outbox = DurableOperationalEventOutbox(store, maximumRetainedEntries = 2)
        val first = envelope("event-1")
        val second = envelope("event-2")
        outbox.append(scope, first)
        outbox.append(scope, second)

        outbox.acknowledge(scope, first.id)
        val third = envelope("event-3")
        outbox.append(scope, third)

        // Acknowledging "event-1" already dropped the retained count to 1;
        // the cap of 2 is not yet exceeded by appending "event-3", so no
        // further eviction happens.
        val entries = assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(outbox.entries(scope))
        assertEquals(listOf(second, third), entries.value)
    }

    @Test
    fun acknowledgeDistinctScopesAreIndependent() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val outbox = DurableOperationalEventOutbox(store)
        val other = OperationalEventOutboxScope("outbox-2")
        val a = envelope("event-1")
        val b = envelope("event-1") // same id, different scope -- distinct entries
        outbox.append(scope, a)
        outbox.append(other, b)

        outbox.acknowledge(scope, a.id)

        val scopeEntries = assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(outbox.entries(scope))
        val otherEntries = assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(outbox.entries(other))
        assertEquals(emptyList(), scopeEntries.value)
        assertEquals(listOf(b), otherEntries.value)
    }

    @Test
    fun acknowledgeReturnsPersistenceFailureWhenLoadFails() = runTest {
        val outbox = DurableOperationalEventOutbox(FailingLoadStore())
        val outcome = outbox.acknowledge(scope, OperationalEventId("event-1"))
        assertIs<DurableOperationalEventOutboxAcknowledgeOutcome.PersistenceFailure>(outcome)
    }

    @Test
    fun acknowledgeReturnsPersistenceFailureWhenCompareAndSetFails() = runTest {
        val record = DurableStateRecord(
            state = OperationalEventOutboxState(listOf(envelope("event-1"))),
            version = 0L,
            schemaVersion = 1,
        )
        val outbox = DurableOperationalEventOutbox(FoundStoreWithFailingCompareAndSet(record))

        val outcome = outbox.acknowledge(scope, OperationalEventId("event-1"))

        assertIs<DurableOperationalEventOutboxAcknowledgeOutcome.PersistenceFailure>(outcome)
    }

    @Test
    fun acknowledgeReturnsContentionLimitReachedWhenTheRemovalRaceIsAlwaysLost() = runTest {
        val record = DurableStateRecord(
            state = OperationalEventOutboxState(listOf(envelope("event-1"))),
            version = 0L,
            schemaVersion = 1,
        )
        val outbox = DurableOperationalEventOutbox(
            FoundStoreAlwaysConflicting(record),
            maximumStateUpdateAttempts = 3,
        )

        val outcome = outbox.acknowledge(scope, OperationalEventId("event-1"))

        assertIs<DurableOperationalEventOutboxAcknowledgeOutcome.ContentionLimitReached>(outcome)
    }

    @Test
    fun acknowledgeRetriesAfterLosingTheRemovalRaceAndThenSucceeds() = runTest {
        val envelope = envelope("event-1")
        val record = DurableStateRecord(
            state = OperationalEventOutboxState(listOf(envelope)),
            version = 0L,
            schemaVersion = 1,
        )
        val store = FoundStoreConflictOnceThenUpdates(record)
        val outbox = DurableOperationalEventOutbox(store)

        val outcome = outbox.acknowledge(scope, envelope.id)

        val acknowledged = assertIs<DurableOperationalEventOutboxAcknowledgeOutcome.Acknowledged>(outcome)
        assertEquals(envelope, acknowledged.envelope)
        assertEquals(2, store.compareAndSetCalls)
    }

    @Test
    fun appendReturnsPersistenceFailureWhenLoadFails() = runTest {
        val outbox = DurableOperationalEventOutbox(FailingLoadStore())
        val outcome = outbox.append(scope, envelope("event-1"))
        assertIs<DurableOperationalEventOutboxAppendOutcome.PersistenceFailure>(outcome)
    }

    @Test
    fun appendReturnsPersistenceFailureWhenCompareAndSetFails() = runTest {
        val outbox = DurableOperationalEventOutbox(FailingCompareAndSetStore())
        val outcome = outbox.append(scope, envelope("event-1"))
        assertIs<DurableOperationalEventOutboxAppendOutcome.PersistenceFailure>(outcome)
    }

    @Test
    fun appendReturnsContentionLimitReachedWhenTheInsertRaceIsAlwaysLost() = runTest {
        val outbox = DurableOperationalEventOutbox(AlwaysConflictStore(), maximumStateUpdateAttempts = 3)
        val outcome = outbox.append(scope, envelope("event-1"))
        assertIs<DurableOperationalEventOutboxAppendOutcome.ContentionLimitReached>(outcome)
    }

    @Test
    fun appendRetriesAfterLosingTheInsertRaceAndReportsAlreadyAppended() = runTest {
        val envelope = envelope("event-1")
        val winningRecord = DurableStateRecord(
            state = OperationalEventOutboxState(listOf(envelope)),
            version = 0L,
            schemaVersion = 1,
        )
        val store = RaceThenConsistentStore(winningRecord)
        val outbox = DurableOperationalEventOutbox(store)

        val outcome = outbox.append(scope, envelope)

        assertIs<DurableOperationalEventOutboxAppendOutcome.AlreadyAppended>(outcome)
    }

    private fun envelope(
        id: String,
        correlationId: String = "correlation-1",
        occurredAt: DataLoomInstant = DataLoomInstant(1_000L),
    ): OperationalEventEnvelope = OperationalEventEnvelope(
        id = OperationalEventId(id),
        type = OperationalEventType("dataloom.retry.scheduled"),
        source = OperationalEventSource("dataloom.runtime.retry"),
        category = OperationalEventCategory.TELEMETRY,
        schemaVersion = OperationalSchemaVersion(1),
        occurredAt = occurredAt,
        correlationId = CorrelationId(correlationId),
        payload = OperationalPayloadDescriptor(
            type = OperationalPayloadType("dataloom.retry.signal"),
            schemaVersion = OperationalSchemaVersion(1),
            encoding = OperationalPayloadEncoding("application/json"),
            classification = DataClassification.INTERNAL,
        ),
    )

    /** Reports whatever [DataLoomInstant] [instant] currently holds -- settable between calls to simulate the passage of time. */
    private class StubDataLoomClock(
        var instant: DataLoomInstant,
    ) : DataLoomClock {
        override fun now(): DataLoomInstant = instant
    }

    private class InMemoryOperationalEventOutboxStore :
        DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState> {
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

    private class FailingLoadStore : DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState> {
        override suspend fun load(
            scope: OperationalEventOutboxScope,
        ): ProviderOperationResult<DurableStateLoadResult<OperationalEventOutboxState>> =
            ProviderOperationResult.Failure(testError())

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<OperationalEventOutboxScope, OperationalEventOutboxState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<OperationalEventOutboxState>> =
            error("must not be called when load already failed")
    }

    private class FailingCompareAndSetStore : DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState> {
        override suspend fun load(
            scope: OperationalEventOutboxScope,
        ): ProviderOperationResult<DurableStateLoadResult<OperationalEventOutboxState>> =
            ProviderOperationResult.Success(DurableStateLoadResult.Missing)

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<OperationalEventOutboxScope, OperationalEventOutboxState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<OperationalEventOutboxState>> =
            ProviderOperationResult.Failure(testError())
    }

    private class AlwaysConflictStore : DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState> {
        override suspend fun load(
            scope: OperationalEventOutboxScope,
        ): ProviderOperationResult<DurableStateLoadResult<OperationalEventOutboxState>> =
            ProviderOperationResult.Success(DurableStateLoadResult.Missing)

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<OperationalEventOutboxScope, OperationalEventOutboxState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<OperationalEventOutboxState>> =
            ProviderOperationResult.Success(DurableStateCompareAndSetResult.Conflict(null))
    }

    /** Reports [DurableStateLoadResult.Found] with [record] on every [load], and always fails [compareAndSet]. */
    private class FoundStoreWithFailingCompareAndSet(
        private val record: DurableStateRecord<OperationalEventOutboxState>,
    ) : DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState> {
        override suspend fun load(
            scope: OperationalEventOutboxScope,
        ): ProviderOperationResult<DurableStateLoadResult<OperationalEventOutboxState>> =
            ProviderOperationResult.Success(DurableStateLoadResult.Found(record))

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<OperationalEventOutboxScope, OperationalEventOutboxState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<OperationalEventOutboxState>> =
            ProviderOperationResult.Failure(testError())
    }

    /**
     * Reports [DurableStateLoadResult.Found] with [record] on every [load],
     * and always loses [compareAndSet] with a
     * [DurableStateCompareAndSetResult.Conflict] against the same [record] --
     * simulating a removal race that is never won.
     */
    private class FoundStoreAlwaysConflicting(
        private val record: DurableStateRecord<OperationalEventOutboxState>,
    ) : DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState> {
        override suspend fun load(
            scope: OperationalEventOutboxScope,
        ): ProviderOperationResult<DurableStateLoadResult<OperationalEventOutboxState>> =
            ProviderOperationResult.Success(DurableStateLoadResult.Found(record))

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<OperationalEventOutboxScope, OperationalEventOutboxState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<OperationalEventOutboxState>> =
            ProviderOperationResult.Success(DurableStateCompareAndSetResult.Conflict(record))
    }

    /**
     * Reports [DurableStateLoadResult.Found] with the same [record] on every
     * [load] (the record itself never changes underneath this store), but
     * loses the first [compareAndSet] race with a
     * [DurableStateCompareAndSetResult.Conflict] before succeeding on the
     * second -- simulating a concurrent writer's compare-and-set landing
     * between this call's load and its own compareAndSet, without changing
     * what the next load sees.
     */
    private class FoundStoreConflictOnceThenUpdates(
        private val record: DurableStateRecord<OperationalEventOutboxState>,
    ) : DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState> {
        var compareAndSetCalls: Int = 0
            private set

        override suspend fun load(
            scope: OperationalEventOutboxScope,
        ): ProviderOperationResult<DurableStateLoadResult<OperationalEventOutboxState>> =
            ProviderOperationResult.Success(DurableStateLoadResult.Found(record))

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<OperationalEventOutboxScope, OperationalEventOutboxState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<OperationalEventOutboxState>> {
            compareAndSetCalls += 1
            return if (compareAndSetCalls == 1) {
                ProviderOperationResult.Success(DurableStateCompareAndSetResult.Conflict(record))
            } else {
                val updated = DurableStateRecord(
                    state = request.nextState,
                    version = record.version + 1L,
                    schemaVersion = request.nextSchemaVersion,
                )
                ProviderOperationResult.Success(DurableStateCompareAndSetResult.Updated(updated))
            }
        }
    }

    /**
     * Reports [DurableStateLoadResult.Missing] on its first [load], then a
     * losing [DurableStateCompareAndSetResult.Conflict] against
     * [winningRecord] on its first [compareAndSet] -- simulating a
     * concurrent appender's insert landing between this call's load and its
     * own compareAndSet.
     */
    private class RaceThenConsistentStore(
        private val winningRecord: DurableStateRecord<OperationalEventOutboxState>,
    ) : DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState> {
        private var loadCalls = 0
        private var compareAndSetCalls = 0

        override suspend fun load(
            scope: OperationalEventOutboxScope,
        ): ProviderOperationResult<DurableStateLoadResult<OperationalEventOutboxState>> {
            loadCalls += 1
            return ProviderOperationResult.Success(
                if (loadCalls == 1) DurableStateLoadResult.Missing else DurableStateLoadResult.Found(winningRecord),
            )
        }

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<OperationalEventOutboxScope, OperationalEventOutboxState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<OperationalEventOutboxState>> {
            compareAndSetCalls += 1
            check(compareAndSetCalls == 1) { "must not be called again once load() reports Found" }
            return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Conflict(winningRecord))
        }
    }
}

private fun testError(): DataLoomError = DurableOperationalEventOutboxTestError(
    code = ErrorCode("DURABLE_OPERATIONAL_EVENT_OUTBOX_TEST_FAILURE"),
    category = ErrorCategory.STORAGE,
    severity = ErrorSeverity.ERROR,
    recoverability = Recoverability.RECOVERABLE,
    message = "Simulated store failure.",
)

private data class DurableOperationalEventOutboxTestError(
    override val code: ErrorCode,
    override val category: ErrorCategory,
    override val severity: ErrorSeverity,
    override val recoverability: Recoverability,
    override val message: String,
    override val cause: Throwable? = null,
) : DataLoomError
