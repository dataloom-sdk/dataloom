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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Verifies [DurableOperationalEventOutbox.enumerate] (bounded, cursor-paginated,
 * read-only fan-out over caller-named scopes) and
 * [DurableOperationalEventOutbox.replayBatch] (authorizer-gated, bounded batch
 * reopen) against a version-checked in-memory [DurableStateStore].
 */
class DurableOperationalEventOutboxEnumerationAndBatchReplayTest {

    private val alpha = OperationalEventOutboxScope("alpha")
    private val beta = OperationalEventOutboxScope("beta")
    private val gamma = OperationalEventOutboxScope("gamma")
    private val clock = StubClock(DataLoomInstant(1_000L))

    // ---- enumerate ------------------------------------------------------------------------

    @Test
    fun enumerateReturnsEntriesAcrossScopesInScopeThenKeyThenSequenceOrderRegardlessOfQueryOrder() = runTest {
        val outbox = DurableOperationalEventOutbox(InMemoryStore(), clock)
        outbox.append(beta, envelope("b1", "w1"))
        outbox.append(alpha, envelope("a-w2-1", "w2"))
        outbox.append(alpha, envelope("a-w1-1", "w1"))
        outbox.append(alpha, envelope("a-w2-2", "w2"))

        val page = enumerate(outbox, OperationalEventOutboxEntryQuery(listOf(beta, alpha)))

        assertEquals(listOf("a-w1-1", "a-w2-1", "a-w2-2", "b1"), page.entries.map { it.entry.envelope.id.value })
        assertEquals(listOf(alpha, alpha, alpha, beta), page.entries.map { it.scope })
        assertNull(page.nextCursor)
    }

    @Test
    fun enumerateFiltersByWorkflowAndStatus() = runTest {
        val outbox = DurableOperationalEventOutbox(InMemoryStore(), clock)
        outbox.append(alpha, envelope("a1", "w1"))
        outbox.append(alpha, envelope("a2", "w2"))
        outbox.append(beta, envelope("b1", "w1"))
        outbox.acknowledge(alpha, OperationalEventId("a1"))

        val w1 = enumerate(outbox, OperationalEventOutboxEntryQuery(listOf(alpha, beta), workflowId = WorkflowId("w1")))
        val pending = enumerate(
            outbox,
            OperationalEventOutboxEntryQuery(listOf(alpha, beta), status = OperationalEventOutboxEntryStatus.PENDING),
        )
        val acknowledged = enumerate(
            outbox,
            OperationalEventOutboxEntryQuery(listOf(alpha, beta), status = OperationalEventOutboxEntryStatus.ACKNOWLEDGED),
        )
        val acknowledgedW2 = enumerate(
            outbox,
            OperationalEventOutboxEntryQuery(
                listOf(alpha, beta),
                workflowId = WorkflowId("w2"),
                status = OperationalEventOutboxEntryStatus.ACKNOWLEDGED,
            ),
        )

        assertEquals(listOf("a1", "b1"), w1.entries.map { it.entry.envelope.id.value })
        assertEquals(listOf("a2", "b1"), pending.entries.map { it.entry.envelope.id.value })
        assertEquals(listOf("a1"), acknowledged.entries.map { it.entry.envelope.id.value })
        assertEquals(emptyList(), acknowledgedW2.entries)
    }

    @Test
    fun enumerateNeverReturnsMoreThanThePageSizeAndPagesConcatenateToEveryEntryExactlyOnce() = runTest {
        val outbox = DurableOperationalEventOutbox(InMemoryStore(), clock)
        val expected = mutableListOf<String>()
        for (scope in listOf(alpha, beta, gamma)) {
            for (i in 1..4) {
                outbox.append(scope, envelope("${scope.value}-$i", "w$i"))
            }
        }
        val query = OperationalEventOutboxEntryQuery(listOf(gamma, alpha, beta), pageSize = 5)

        var cursor: OperationalEventOutboxCursor? = null
        val seen = mutableListOf<String>()
        var pages = 0
        do {
            val page = enumerate(outbox, query, cursor)
            assertTrue(page.entries.size <= 5, "a page must never exceed the page size")
            seen += page.entries.map { it.entry.envelope.id.value }
            cursor = page.nextCursor
            pages++
        } while (cursor != null)

        for (scope in listOf(alpha, beta, gamma)) {
            for (i in 1..4) expected += "${scope.value}-$i"
        }
        assertEquals(expected, seen)
        assertEquals(3, pages) // 12 entries at 5 per page
    }

    @Test
    fun anExactlyFullLastPageHasNoNextCursor() = runTest {
        val outbox = DurableOperationalEventOutbox(InMemoryStore(), clock)
        outbox.append(alpha, envelope("a1"))
        outbox.append(alpha, envelope("a2"))

        val page = enumerate(outbox, OperationalEventOutboxEntryQuery(listOf(alpha), pageSize = 2))

        assertEquals(2, page.entries.size)
        assertNull(page.nextCursor)
    }

    @Test
    fun theCursorSurvivesTheOutboxChangingBetweenPagesWithoutSkippingOrRepeatingAnEntry() = runTest {
        // No tombstones kept: acknowledging removes the entry outright, so the
        // entry the cursor names can be removed entirely between pages.
        val outbox = DurableOperationalEventOutbox(InMemoryStore(), clock, maximumRetainedAcknowledgedEntries = 0)
        for (i in 1..3) outbox.append(alpha, envelope("a$i", "w"))
        outbox.append(beta, envelope("b1", "w"))
        val query = OperationalEventOutboxEntryQuery(listOf(alpha, beta), pageSize = 2)

        val first = enumerate(outbox, query)
        assertEquals(listOf("a1", "a2"), first.entries.map { it.entry.envelope.id.value })
        val cursor = assertIs<OperationalEventOutboxCursor>(first.nextCursor)

        // Between pages: remove the entry the cursor names and an earlier one, and append new work.
        outbox.acknowledge(alpha, OperationalEventId("a2"))
        outbox.acknowledge(alpha, OperationalEventId("a1"))
        outbox.append(alpha, envelope("a4", "w"))
        outbox.append(beta, envelope("b2", "w"))

        val second = enumerate(outbox, query, cursor)
        assertEquals(listOf("a3", "a4"), second.entries.map { it.entry.envelope.id.value })
        val third = enumerate(outbox, query, assertIs<OperationalEventOutboxCursor>(second.nextCursor))
        assertEquals(listOf("b1", "b2"), third.entries.map { it.entry.envelope.id.value })
        assertNull(third.nextCursor)
    }

    @Test
    fun theCursorSurvivesAnEntryBeingAcknowledgedAndReplayedBetweenPages() = runTest {
        val outbox = DurableOperationalEventOutbox(InMemoryStore(), clock)
        for (i in 1..4) outbox.append(alpha, envelope("a$i", "w"))
        val query = OperationalEventOutboxEntryQuery(listOf(alpha), pageSize = 2)

        val first = enumerate(outbox, query)
        outbox.acknowledge(alpha, OperationalEventId("a2"))
        outbox.replay(alpha, OperationalEventId("a2"))
        outbox.acknowledge(alpha, OperationalEventId("a3"))

        val second = enumerate(outbox, query, first.nextCursor)

        assertEquals(listOf("a3", "a4"), second.entries.map { it.entry.envelope.id.value })
        assertTrue(second.entries.first().entry.isAcknowledged)
    }

    @Test
    fun enumerateStopsLoadingScopesOnceThePageIsFull() = runTest {
        val store = InMemoryStore()
        val outbox = DurableOperationalEventOutbox(store, clock)
        outbox.append(alpha, envelope("a1"))
        outbox.append(alpha, envelope("a2"))
        outbox.append(beta, envelope("b1"))
        store.loadedScopes.clear()

        enumerate(outbox, OperationalEventOutboxEntryQuery(listOf(alpha, beta, gamma), pageSize = 1))

        assertEquals(listOf(alpha), store.loadedScopes)
    }

    @Test
    fun enumerateSkipsScopesBeforeTheCursorAndTreatsAMissingScopeAsEmpty() = runTest {
        val store = InMemoryStore()
        val outbox = DurableOperationalEventOutbox(store, clock)
        outbox.append(alpha, envelope("a1"))
        outbox.append(gamma, envelope("g1"))
        store.loadedScopes.clear()

        // beta has never been written; alpha sorts before the cursor's scope.
        val page = enumerate(
            outbox,
            OperationalEventOutboxEntryQuery(listOf(alpha, beta, gamma)),
            OperationalEventOutboxCursor(beta, OperationalEventOrderingKey.Global, 1L),
        )

        assertEquals(listOf("g1"), page.entries.map { it.entry.envelope.id.value })
        assertEquals(listOf(beta, gamma), store.loadedScopes)
    }

    @Test
    fun enumerateFailsTheWholeCallWhenAnyScopeFailsToLoad() = runTest {
        val store = InMemoryStore()
        val outbox = DurableOperationalEventOutbox(store, clock)
        outbox.append(alpha, envelope("a1"))
        outbox.append(beta, envelope("b1"))
        store.failLoadFor = beta

        val result = outbox.enumerate(OperationalEventOutboxEntryQuery(listOf(alpha, beta)))

        assertIs<ProviderOperationResult.Failure>(result)
    }

    @Test
    fun enumerateReportsEachLoadedScopeToTheStateObserver() = runTest {
        val observed = mutableListOf<OperationalEventOutboxScope>()
        val outbox = DurableOperationalEventOutbox(
            InMemoryStore(),
            clock,
            stateObserver = OperationalEventOutboxStateObserver { observed += it.scope },
        )
        outbox.append(alpha, envelope("a1"))
        observed.clear()

        enumerate(outbox, OperationalEventOutboxEntryQuery(listOf(alpha, beta)))

        assertEquals(listOf(alpha, beta), observed)
    }

    @Test
    fun theQueryRejectsAnUnboundedOrMalformedRequest() {
        assertFailsWith<IllegalArgumentException> { OperationalEventOutboxEntryQuery(emptyList()) }
        assertFailsWith<IllegalArgumentException> { OperationalEventOutboxEntryQuery(listOf(alpha, alpha)) }
        assertFailsWith<IllegalArgumentException> { OperationalEventOutboxEntryQuery(listOf(alpha), pageSize = 0) }
        assertFailsWith<IllegalArgumentException> {
            OperationalEventOutboxEntryQuery(listOf(alpha), pageSize = OperationalEventOutboxEntryQuery.MAXIMUM_PAGE_SIZE + 1)
        }
        assertFailsWith<IllegalArgumentException> {
            OperationalEventOutboxEntryQuery((0..OperationalEventOutboxEntryQuery.MAXIMUM_SCOPES).map { OperationalEventOutboxScope("s$it") })
        }
        // The caps themselves are accepted.
        OperationalEventOutboxEntryQuery(
            (1..OperationalEventOutboxEntryQuery.MAXIMUM_SCOPES).map { OperationalEventOutboxScope("s$it") },
            pageSize = OperationalEventOutboxEntryQuery.MAXIMUM_PAGE_SIZE,
        )
    }

    // ---- replayBatch ----------------------------------------------------------------------

    @Test
    fun replayBatchReopensOnlyAcknowledgedEntriesOfTheRequestedWorkflowAcrossScopesAtTheirOriginalSequence() = runTest {
        val outbox = DurableOperationalEventOutbox(InMemoryStore(), clock)
        outbox.append(alpha, envelope("a1", "w1"))
        outbox.append(alpha, envelope("a2", "w2"))
        outbox.append(alpha, envelope("a3", "w1"))
        outbox.append(beta, envelope("b1", "w1"))
        outbox.append(beta, envelope("b2", "w1")) // stays pending: never acknowledged
        for ((scope, id) in listOf(alpha to "a1", alpha to "a2", alpha to "a3", beta to "b1")) {
            outbox.acknowledge(scope, OperationalEventId(id))
        }

        val result = outbox.replayBatch(
            OperationalEventOutboxBatchReplayRequest(listOf(beta, alpha), workflowId = WorkflowId("w1")),
            OperationalEventOutboxReplayAuthorizer { _, _ -> true },
        )

        assertEquals(listOf("a1", "a3", "b1"), result.replayed.map { it.entry.envelope.id.value })
        assertEquals(listOf(alpha, alpha, beta), result.replayed.map { it.scope })
        assertEquals(0, result.denied)
        assertEquals(0, result.notReplayable)
        assertEquals(emptyList(), result.failures)
        assertEquals(false, result.budgetExhausted)
        assertEquals(listOf("a1", "a3"), pendingIds(outbox, alpha))
        assertEquals(listOf("a1" to 1L, "a3" to 2L), sequences(outbox, alpha).filter { it.first != "a2" })
        assertEquals(listOf("a2"), acknowledgedIds(outbox, alpha), "another workflow's tombstone is untouched")
        assertEquals(listOf("b1", "b2"), pendingIds(outbox, beta))
    }

    @Test
    fun replayBatchWithADenyAllAuthorizerChangesNothingAndWritesNothing() = runTest {
        val store = InMemoryStore()
        val outbox = DurableOperationalEventOutbox(store, clock)
        outbox.append(alpha, envelope("a1", "w1"))
        outbox.append(alpha, envelope("a2", "w1"))
        outbox.acknowledge(alpha, OperationalEventId("a1"))
        outbox.acknowledge(alpha, OperationalEventId("a2"))
        val versionBefore = store.version(alpha)

        val result = outbox.replayBatch(
            OperationalEventOutboxBatchReplayRequest(listOf(alpha)),
            OperationalEventOutboxReplayAuthorizer { _, _ -> false },
        )

        assertEquals(emptyList(), result.replayed)
        assertEquals(2, result.denied)
        assertEquals(versionBefore, store.version(alpha))
        assertEquals(listOf("a1", "a2"), acknowledgedIds(outbox, alpha))
    }

    @Test
    fun replayBatchHonoursAPerEntryDecisionAndSeesTheScopeAndEntryItIsAskedAbout() = runTest {
        val outbox = DurableOperationalEventOutbox(InMemoryStore(), clock)
        outbox.append(alpha, envelope("a1", "w1"))
        outbox.append(beta, envelope("b1", "w1"))
        outbox.acknowledge(alpha, OperationalEventId("a1"))
        outbox.acknowledge(beta, OperationalEventId("b1"))
        val asked = mutableListOf<Pair<String, String>>()

        val result = outbox.replayBatch(OperationalEventOutboxBatchReplayRequest(listOf(alpha, beta))) { scope, entry ->
            asked += scope.value to entry.envelope.id.value
            scope == beta
        }

        assertEquals(listOf("alpha" to "a1", "beta" to "b1"), asked)
        assertEquals(listOf("b1"), result.replayed.map { it.entry.envelope.id.value })
        assertEquals(1, result.denied)
        assertEquals(listOf("a1"), acknowledgedIds(outbox, alpha))
        assertEquals(listOf("b1"), pendingIds(outbox, beta))
    }

    @Test
    fun replayBatchTreatsAThrowingAuthorizerAsADenial() = runTest {
        val outbox = DurableOperationalEventOutbox(InMemoryStore(), clock)
        outbox.append(alpha, envelope("a1", "w1"))
        outbox.acknowledge(alpha, OperationalEventId("a1"))

        val result = outbox.replayBatch(OperationalEventOutboxBatchReplayRequest(listOf(alpha))) { _, _ ->
            error("policy backend unavailable")
        }

        assertEquals(emptyList(), result.replayed)
        assertEquals(1, result.denied)
        assertEquals(listOf("a1"), acknowledgedIds(outbox, alpha))
    }

    @Test
    fun replayBatchStopsAtTheBudgetReportsItAndAFollowUpCallContinues() = runTest {
        val outbox = DurableOperationalEventOutbox(InMemoryStore(), clock)
        for (i in 1..3) outbox.append(alpha, envelope("a$i", "w"))
        outbox.append(beta, envelope("b1", "w"))
        for ((scope, id) in listOf(alpha to "a1", alpha to "a2", alpha to "a3", beta to "b1")) {
            outbox.acknowledge(scope, OperationalEventId(id))
        }
        val request = OperationalEventOutboxBatchReplayRequest(listOf(alpha, beta), maximumEntries = 2)
        val allow = OperationalEventOutboxReplayAuthorizer { _, _ -> true }

        val first = outbox.replayBatch(request, allow)
        assertEquals(listOf("a1", "a2"), first.replayed.map { it.entry.envelope.id.value })
        assertTrue(first.budgetExhausted)
        assertEquals(listOf("a3"), acknowledgedIds(outbox, alpha))
        assertEquals(listOf("b1"), acknowledgedIds(outbox, beta))

        val second = outbox.replayBatch(request, allow)
        assertEquals(listOf("a3", "b1"), second.replayed.map { it.entry.envelope.id.value })
        assertEquals(false, second.budgetExhausted, "the budget was met exactly with nothing left unexamined")

        val third = outbox.replayBatch(request, allow)
        assertEquals(emptyList(), third.replayed)
    }

    @Test
    fun deniedEntriesDoNotConsumeTheBudgetSoTheyCannotStarveApprovedOnes() = runTest {
        val outbox = DurableOperationalEventOutbox(InMemoryStore(), clock)
        for (i in 1..3) outbox.append(alpha, envelope("a$i", "w"))
        for (i in 1..3) outbox.acknowledge(alpha, OperationalEventId("a$i"))

        val result = outbox.replayBatch(OperationalEventOutboxBatchReplayRequest(listOf(alpha), maximumEntries = 1)) { _, entry ->
            entry.envelope.id.value == "a3"
        }

        assertEquals(listOf("a3"), result.replayed.map { it.entry.envelope.id.value })
        assertEquals(2, result.denied)
    }

    @Test
    fun anEntryReopenedBetweenAuthorizationAndTheWriteIsReportedNotReplayableAndNotWrittenTwice() = runTest {
        val store = InMemoryStore()
        val outbox = DurableOperationalEventOutbox(store, clock)
        outbox.append(alpha, envelope("a1", "w"))
        outbox.append(alpha, envelope("a2", "w"))
        outbox.acknowledge(alpha, OperationalEventId("a1"))
        outbox.acknowledge(alpha, OperationalEventId("a2"))

        val result = outbox.replayBatch(OperationalEventOutboxBatchReplayRequest(listOf(alpha))) { scope, entry ->
            // A concurrent operator reopens a1 while this batch is still deciding.
            if (entry.envelope.id.value == "a1") outbox.replay(scope, entry.envelope.id)
            true
        }

        assertEquals(listOf("a2"), result.replayed.map { it.entry.envelope.id.value })
        assertEquals(1, result.notReplayable)
        assertEquals(listOf("a1", "a2"), pendingIds(outbox, alpha))
    }

    @Test
    fun aCompareAndSetRaceIsRetriedAndOnlyEntriesThatWereAuthorizedAreReopened() = runTest {
        val store = InMemoryStore()
        val outbox = DurableOperationalEventOutbox(store, clock)
        outbox.append(alpha, envelope("a1", "w"))
        outbox.acknowledge(alpha, OperationalEventId("a1"))
        // A concurrent writer lands between this batch's load and compare-and-set,
        // acknowledging a brand-new entry the authorizer never saw.
        store.beforeNextCompareAndSet = {
            outbox.append(alpha, envelope("a2", "w"))
            outbox.acknowledge(alpha, OperationalEventId("a2"))
        }

        val result = outbox.replayBatch(
            OperationalEventOutboxBatchReplayRequest(listOf(alpha)),
            OperationalEventOutboxReplayAuthorizer { _, _ -> true },
        )

        assertEquals(listOf("a1"), result.replayed.map { it.entry.envelope.id.value })
        assertEquals(listOf("a2"), acknowledgedIds(outbox, alpha), "an unauthorized entry is never swept in by the retry")
        assertEquals(listOf("a1"), pendingIds(outbox, alpha))
    }

    @Test
    fun aScopeThatLosesEveryRaceIsReportedAsContentionAndOtherScopesStillReplay() = runTest {
        val store = InMemoryStore()
        val outbox = DurableOperationalEventOutbox(store, clock, maximumStateUpdateAttempts = 1)
        outbox.append(alpha, envelope("a1", "w"))
        outbox.append(beta, envelope("b1", "w"))
        outbox.acknowledge(alpha, OperationalEventId("a1"))
        outbox.acknowledge(beta, OperationalEventId("b1"))
        store.beforeNextCompareAndSet = { outbox.append(alpha, envelope("a2", "w")) }

        val result = outbox.replayBatch(
            OperationalEventOutboxBatchReplayRequest(listOf(alpha, beta)),
            OperationalEventOutboxReplayAuthorizer { _, _ -> true },
        )

        assertEquals(listOf(OperationalEventOutboxBatchReplayFailure.ContentionLimitReached(alpha)), result.failures)
        assertEquals(listOf("b1"), result.replayed.map { it.entry.envelope.id.value })
        assertEquals(listOf("a1"), acknowledgedIds(outbox, alpha))
    }

    @Test
    fun aScopeThatFailsToLoadIsReportedAndOtherScopesStillReplay() = runTest {
        val store = InMemoryStore()
        val outbox = DurableOperationalEventOutbox(store, clock)
        outbox.append(alpha, envelope("a1", "w"))
        outbox.append(beta, envelope("b1", "w"))
        outbox.acknowledge(alpha, OperationalEventId("a1"))
        outbox.acknowledge(beta, OperationalEventId("b1"))
        store.failLoadFor = alpha

        val result = outbox.replayBatch(
            OperationalEventOutboxBatchReplayRequest(listOf(alpha, beta)),
            OperationalEventOutboxReplayAuthorizer { _, _ -> true },
        )

        assertEquals(1, result.failures.size)
        assertEquals(alpha, assertIs<OperationalEventOutboxBatchReplayFailure.PersistenceFailure>(result.failures.single()).scope)
        assertEquals(listOf("b1"), result.replayed.map { it.entry.envelope.id.value })
    }

    @Test
    fun aScopeThatFailsToWriteIsReportedAndLeavesItsEntriesAcknowledged() = runTest {
        val store = InMemoryStore()
        val outbox = DurableOperationalEventOutbox(store, clock)
        outbox.append(alpha, envelope("a1", "w"))
        outbox.acknowledge(alpha, OperationalEventId("a1"))
        store.failCompareAndSet = true

        val result = outbox.replayBatch(
            OperationalEventOutboxBatchReplayRequest(listOf(alpha)),
            OperationalEventOutboxReplayAuthorizer { _, _ -> true },
        )

        assertIs<OperationalEventOutboxBatchReplayFailure.PersistenceFailure>(result.failures.single())
        assertEquals(emptyList(), result.replayed)
        assertEquals(listOf("a1"), acknowledgedIds(outbox, alpha))
    }

    @Test
    fun replayedEntriesAreProcessedAgainAheadOfLaterAppendsInOriginalSequenceOrder() = runTest {
        val outbox = DurableOperationalEventOutbox(InMemoryStore(), clock)
        outbox.append(alpha, envelope("a1", "w"))
        outbox.append(alpha, envelope("a2", "w"))
        outbox.acknowledge(alpha, OperationalEventId("a1"))
        outbox.acknowledge(alpha, OperationalEventId("a2"))
        outbox.append(alpha, envelope("a3", "w"))

        outbox.replayBatch(
            OperationalEventOutboxBatchReplayRequest(listOf(alpha)),
            OperationalEventOutboxReplayAuthorizer { _, _ -> true },
        )

        assertEquals(listOf("a1" to 1L, "a2" to 2L, "a3" to 3L), sequences(outbox, alpha))
        assertEquals(listOf("a1", "a2", "a3"), pendingIds(outbox, alpha))
    }

    @Test
    fun theBatchRequestRejectsAnUnboundedOrMalformedRequest() {
        assertFailsWith<IllegalArgumentException> { OperationalEventOutboxBatchReplayRequest(emptyList()) }
        assertFailsWith<IllegalArgumentException> { OperationalEventOutboxBatchReplayRequest(listOf(alpha, alpha)) }
        assertFailsWith<IllegalArgumentException> { OperationalEventOutboxBatchReplayRequest(listOf(alpha), maximumEntries = 0) }
        assertFailsWith<IllegalArgumentException> {
            OperationalEventOutboxBatchReplayRequest(
                listOf(alpha),
                maximumEntries = OperationalEventOutboxBatchReplayRequest.MAXIMUM_ENTRIES + 1,
            )
        }
    }

    // ---- Helpers ---------------------------------------------------------------------------

    private suspend fun enumerate(
        outbox: DurableOperationalEventOutbox,
        query: OperationalEventOutboxEntryQuery,
        after: OperationalEventOutboxCursor? = null,
    ): OperationalEventOutboxEntryPage =
        assertIs<ProviderOperationResult.Success<OperationalEventOutboxEntryPage>>(outbox.enumerate(query, after)).value

    private suspend fun pendingIds(outbox: DurableOperationalEventOutbox, scope: OperationalEventOutboxScope): List<String> =
        assertIs<ProviderOperationResult.Success<List<OperationalEventOutboxEntry>>>(outbox.pendingEntries(scope))
            .value.map { it.envelope.id.value }

    private suspend fun acknowledgedIds(outbox: DurableOperationalEventOutbox, scope: OperationalEventOutboxScope): List<String> =
        assertIs<ProviderOperationResult.Success<List<OperationalEventOutboxEntry>>>(outbox.acknowledgedEntries(scope))
            .value.map { it.envelope.id.value }

    private suspend fun sequences(
        outbox: DurableOperationalEventOutbox,
        scope: OperationalEventOutboxScope,
    ): List<Pair<String, Long>> =
        assertIs<ProviderOperationResult.Success<List<OperationalEventOutboxEntry>>>(outbox.pendingEntries(scope))
            .value.map { it.envelope.id.value to it.sequence }

    private fun envelope(id: String, workflow: String? = null): OperationalEventEnvelope = OperationalEventEnvelope(
        id = OperationalEventId(id),
        type = OperationalEventType("dataloom.retry.scheduled"),
        source = OperationalEventSource("dataloom.runtime.retry"),
        category = OperationalEventCategory.TELEMETRY,
        schemaVersion = OperationalSchemaVersion(1),
        occurredAt = DataLoomInstant(1_000L),
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

    /** Version-checked in-memory [DurableStateStore] with the fault-injection hooks these tests need. */
    private class InMemoryStore : DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState> {
        private val records = mutableMapOf<OperationalEventOutboxScope, DurableStateRecord<OperationalEventOutboxState>>()
        val loadedScopes = mutableListOf<OperationalEventOutboxScope>()
        var failLoadFor: OperationalEventOutboxScope? = null
        var failCompareAndSet: Boolean = false

        /** Runs once, just before the next compare-and-set -- a concurrent writer in the load-to-write window. */
        var beforeNextCompareAndSet: (suspend () -> Unit)? = null

        fun version(scope: OperationalEventOutboxScope): Long? = records[scope]?.version

        override suspend fun load(
            scope: OperationalEventOutboxScope,
        ): ProviderOperationResult<DurableStateLoadResult<OperationalEventOutboxState>> {
            loadedScopes += scope
            if (scope == failLoadFor) return ProviderOperationResult.Failure(storeError())
            val record = records[scope]
            return ProviderOperationResult.Success(
                if (record == null) DurableStateLoadResult.Missing else DurableStateLoadResult.Found(record),
            )
        }

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<OperationalEventOutboxScope, OperationalEventOutboxState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<OperationalEventOutboxState>> {
            if (failCompareAndSet) return ProviderOperationResult.Failure(storeError())
            beforeNextCompareAndSet?.let {
                beforeNextCompareAndSet = null
                it()
            }
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
}

private fun storeError(): DataLoomError = EnumerationTestError(
    code = ErrorCode("DURABLE_OPERATIONAL_EVENT_OUTBOX_ENUMERATION_TEST_FAILURE"),
    category = ErrorCategory.STORAGE,
    severity = ErrorSeverity.ERROR,
    recoverability = Recoverability.RECOVERABLE,
    message = "Simulated store failure.",
)

private data class EnumerationTestError(
    override val code: ErrorCode,
    override val category: ErrorCategory,
    override val severity: ErrorSeverity,
    override val recoverability: Recoverability,
    override val message: String,
    override val cause: Throwable? = null,
) : DataLoomError
