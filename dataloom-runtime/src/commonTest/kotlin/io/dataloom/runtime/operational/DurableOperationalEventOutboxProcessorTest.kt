package io.dataloom.runtime.operational

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.operational.DurableOperationalEventOutbox
import io.dataloom.api.operational.DurableOperationalEventOutboxReplayOutcome
import io.dataloom.api.operational.OperationalEventCategory
import io.dataloom.api.operational.OperationalEventEnvelope
import io.dataloom.api.operational.OperationalEventId
import io.dataloom.api.operational.OperationalEventOutboxEntry
import io.dataloom.api.operational.OperationalEventOutboxScope
import io.dataloom.api.operational.OperationalEventOutboxState
import io.dataloom.api.operational.OperationalEventSource
import io.dataloom.api.operational.OperationalEventType
import io.dataloom.api.operational.OperationalPayloadDescriptor
import io.dataloom.api.operational.OperationalPayloadEncoding
import io.dataloom.api.operational.OperationalPayloadType
import io.dataloom.api.operational.OperationalSchemaVersion
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.security.DataClassification
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateRecord
import io.dataloom.api.state.DurableStateStore
import io.dataloom.api.time.DataLoomInstant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

/**
 * Verifies [DurableOperationalEventOutboxProcessor]'s read-then-consume cycle:
 * only [OperationalEventOutboxEntryOutcome.Processed] entries are acknowledged,
 * an empty outbox is a no-op, [maxEntries] bounds one cycle, and concurrent
 * invocations against the same scope never lose or double-acknowledge an
 * entry -- see that class's own "Concurrency" documentation for exactly what
 * is and is not guaranteed.
 */
class DurableOperationalEventOutboxProcessorTest {

    private val scope = OperationalEventOutboxScope("outbox-processor-1")

    @Test
    fun emptyOutboxIsANoOpAndNeverInvokesTheHandler() = runTest {
        val outbox = DurableOperationalEventOutbox(InMemoryOperationalEventOutboxStore(), outboxTestClock)
        val processor = DurableOperationalEventOutboxProcessor(outbox)
        var invocations = 0

        val result = processor.process(scope) { invocations++; OperationalEventOutboxEntryOutcome.Processed }

        assertIs<OperationalEventOutboxProcessingResult.NoWork>(result)
        assertEquals(0, invocations)
    }

    @Test
    fun mixedOutcomesOnlyAcknowledgeTheProcessedEntriesAndLeaveTheRest() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val outbox = DurableOperationalEventOutbox(store, outboxTestClock)
        val processed = envelope("event-processed")
        val skipped = envelope("event-skipped")
        val failed = envelope("event-failed")
        listOf(processed, skipped, failed).forEach { outbox.append(scope, it) }
        val processor = DurableOperationalEventOutboxProcessor(outbox)

        val result = processor.process(scope) { envelope ->
            when (envelope.id) {
                processed.id -> OperationalEventOutboxEntryOutcome.Processed
                skipped.id -> OperationalEventOutboxEntryOutcome.Skipped
                else -> OperationalEventOutboxEntryOutcome.Failed()
            }
        }

        val summary = assertIs<OperationalEventOutboxProcessingResult.Processed>(result).summary
        assertEquals(3, summary.read)
        assertEquals(0, summary.filteredOut)
        assertEquals(1, summary.processed)
        assertEquals(1, summary.skipped)
        assertEquals(1, summary.failed)
        assertEquals(1, summary.acknowledged)
        assertEquals(0, summary.acknowledgeRaced)
        assertEquals(0, summary.acknowledgeFailed)

        val remaining = assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(outbox.entries(scope))
        assertEquals(listOf(skipped, failed), remaining.value)
    }

    @Test
    fun entriesAreHandedToTheHandlerInOldestFirstOrder() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val outbox = DurableOperationalEventOutbox(store, outboxTestClock)
        val envelopes = (1..4).map { envelope("event-$it") }
        envelopes.forEach { outbox.append(scope, it) }
        val processor = DurableOperationalEventOutboxProcessor(outbox)
        val seen = mutableListOf<OperationalEventId>()

        processor.process(scope) { envelope -> seen.add(envelope.id); OperationalEventOutboxEntryOutcome.Skipped }

        assertEquals(envelopes.map { it.id }, seen)
    }

    @Test
    fun interleavedWorkflowsArePresentedInAppendOrderSoEachWorkflowSeesItsEventsInSequenceOrder() = runTest {
        val outbox = DurableOperationalEventOutbox(InMemoryOperationalEventOutboxStore(), outboxTestClock)
        listOf("a" to "a1", "b" to "b1", "a" to "a2", "b" to "b2", "a" to "a3")
            .forEach { (workflow, id) -> outbox.append(scope, envelope(id, workflow = workflow)) }
        val processor = DurableOperationalEventOutboxProcessor(outbox)
        val seen = mutableListOf<String>()

        processor.process(scope) { seen.add(it.id.value); OperationalEventOutboxEntryOutcome.Processed }

        assertEquals(listOf("a1", "b1", "a2", "b2", "a3"), seen)
        assertEquals(listOf("a1", "a2", "a3"), seen.filter { it.startsWith("a") })
        assertEquals(listOf("b1", "b2"), seen.filter { it.startsWith("b") })
    }

    @Test
    fun neverAcknowledgedSkippedAndFailedEntriesAreRepresentedInSequenceOrderAheadOfLaterAppends() = runTest {
        val outbox = DurableOperationalEventOutbox(InMemoryOperationalEventOutboxStore(), outboxTestClock)
        listOf("a1", "a2", "a3").forEach { outbox.append(scope, envelope(it, workflow = "a")) }
        val processor = DurableOperationalEventOutboxProcessor(outbox)
        processor.process(scope) { current ->
            when (current.id.value) {
                "a1" -> OperationalEventOutboxEntryOutcome.Failed()
                "a2" -> OperationalEventOutboxEntryOutcome.Skipped
                else -> OperationalEventOutboxEntryOutcome.Processed
            }
        }
        outbox.append(scope, envelope("a4", workflow = "a"))
        outbox.append(scope, envelope("b1", workflow = "b"))
        val seen = mutableListOf<String>()

        processor.process(scope) { seen.add(it.id.value); OperationalEventOutboxEntryOutcome.Processed }

        // The retained never-acknowledged entries come back first, in their original
        // sequence order, ahead of everything appended after the first pass.
        assertEquals(listOf("a1", "a2", "a4", "b1"), seen)
        assertEquals(emptyList(), pendingIds(outbox))
    }

    @Test
    fun anAcknowledgedEntryReplayedThroughTheOutboxIsPresentedAgainInSequenceOrder() = runTest {
        val outbox = DurableOperationalEventOutbox(InMemoryOperationalEventOutboxStore(), outboxTestClock)
        listOf("a1", "a2").forEach { outbox.append(scope, envelope(it, workflow = "a")) }
        val processor = DurableOperationalEventOutboxProcessor(outbox)
        processor.process(scope) { OperationalEventOutboxEntryOutcome.Processed }
        outbox.append(scope, envelope("a3", workflow = "a"))
        assertEquals(listOf("a3"), pendingIds(outbox))

        // The operator asks for a1 to be replayed: it becomes pending again at its original
        // position, so the next pass presents it ahead of a3, not after it.
        assertIs<DurableOperationalEventOutboxReplayOutcome.Replayed>(outbox.replay(scope, OperationalEventId("a1")))
        val seen = mutableListOf<String>()
        val result = processor.process(scope) { seen.add(it.id.value); OperationalEventOutboxEntryOutcome.Processed }

        assertEquals(listOf("a1", "a3"), seen)
        val summary = assertIs<OperationalEventOutboxProcessingResult.Processed>(result).summary
        assertEquals(2, summary.acknowledged)
        assertEquals(emptyList(), pendingIds(outbox))
        val history = assertIs<ProviderOperationResult.Success<List<OperationalEventOutboxEntry>>>(outbox.acknowledgedEntries(scope))
        assertEquals(listOf("a1", "a2", "a3"), history.value.map { it.envelope.id.value })
    }

    // ---- head-of-line blocking (opt-in) --------------------------------------------------------------

    private val blocking = OperationalEventOutboxOrderingPolicy.BLOCK_WORKFLOW_ON_UNFINISHED_ENTRY

    private suspend fun seeded(vararg specs: Pair<String, String?>): DurableOperationalEventOutbox {
        val outbox = DurableOperationalEventOutbox(InMemoryOperationalEventOutboxStore(), outboxTestClock)
        specs.forEach { (id, workflow) -> outbox.append(scope, envelope(id, workflow = workflow)) }
        return outbox
    }

    @Test
    fun byDefaultALaterEntryOfAWorkflowIsDeliveredEvenWhenAnEarlierOneWasLeftPending() = runTest {
        val outbox = seeded("a1" to "a", "a2" to "a")
        val seen = mutableListOf<String>()

        val result = DurableOperationalEventOutboxProcessor(outbox).process(scope) { current ->
            seen.add(current.id.value)
            if (current.id.value == "a1") OperationalEventOutboxEntryOutcome.Failed() else OperationalEventOutboxEntryOutcome.Processed
        }

        assertEquals(listOf("a1", "a2"), seen)
        assertEquals(0, assertIs<OperationalEventOutboxProcessingResult.Processed>(result).summary.blocked)
        assertEquals(listOf("a1"), pendingIds(outbox))
    }

    @Test
    fun aFailedEntryBlocksItsWorkflowsLaterEntriesWhileOtherWorkflowsKeepFlowing() = runTest {
        val outbox = seeded("a1" to "a", "b1" to "b", "a2" to "a", "b2" to "b", "a3" to "a")
        val seen = mutableListOf<String>()

        val result = DurableOperationalEventOutboxProcessor(outbox).process(scope, ordering = blocking) { current ->
            seen.add(current.id.value)
            if (current.id.value == "a1") OperationalEventOutboxEntryOutcome.Failed() else OperationalEventOutboxEntryOutcome.Processed
        }

        assertEquals(listOf("a1", "b1", "b2"), seen) // a2 and a3 were never handed to the handler
        val summary = assertIs<OperationalEventOutboxProcessingResult.Processed>(result).summary
        assertEquals(3, summary.read)
        assertEquals(2, summary.blocked)
        assertEquals(1, summary.failed)
        assertEquals(2, summary.acknowledged)
        // The blocked entries stay pending, untouched, in order.
        assertEquals(listOf("a1", "a2", "a3"), pendingIds(outbox))
    }

    @Test
    fun aSkippedEntryBlocksTheSameWay() = runTest {
        val outbox = seeded("a1" to "a", "a2" to "a")
        val seen = mutableListOf<String>()

        val result = DurableOperationalEventOutboxProcessor(outbox).process(scope, ordering = blocking) { current ->
            seen.add(current.id.value)
            OperationalEventOutboxEntryOutcome.Skipped
        }

        assertEquals(listOf("a1"), seen)
        assertEquals(1, assertIs<OperationalEventOutboxProcessingResult.Processed>(result).summary.blocked)
    }

    @Test
    fun theHoldPersistsAcrossCyclesUntilTheBlockerSucceedsThenSuccessorsAreDeliveredInOrderInTheSameCycle() = runTest {
        val outbox = seeded("a1" to "a", "a2" to "a", "a3" to "a", "b1" to "b")
        val processor = DurableOperationalEventOutboxProcessor(outbox)

        repeat(2) { // a1 keeps failing for two cycles; a2/a3 never reach the handler
            val seen = mutableListOf<String>()
            processor.process(scope, ordering = blocking) { current ->
                seen.add(current.id.value)
                if (current.id.value == "a1") OperationalEventOutboxEntryOutcome.Failed() else OperationalEventOutboxEntryOutcome.Processed
            }
            assertEquals(if (it == 0) listOf("a1", "b1") else listOf("a1"), seen)
        }
        assertEquals(listOf("a1", "a2", "a3"), pendingIds(outbox))

        val recovered = mutableListOf<String>()
        val result = processor.process(scope, ordering = blocking) { current ->
            recovered.add(current.id.value)
            OperationalEventOutboxEntryOutcome.Processed
        }

        assertEquals(listOf("a1", "a2", "a3"), recovered)
        assertEquals(0, assertIs<OperationalEventOutboxProcessingResult.Processed>(result).summary.blocked)
        assertEquals(emptyList(), pendingIds(outbox))
    }

    @Test
    fun anEntryAcknowledgedByAnotherCallerReleasesTheWorkflowOnTheNextCycle() = runTest {
        val outbox = seeded("a1" to "a", "a2" to "a")
        val processor = DurableOperationalEventOutboxProcessor(outbox)
        processor.process(scope, ordering = blocking) { OperationalEventOutboxEntryOutcome.Skipped }

        outbox.acknowledge(scope, OperationalEventId("a1")) // an operator dismisses the blocker
        val seen = mutableListOf<String>()
        processor.process(scope, ordering = blocking) { seen.add(it.id.value); OperationalEventOutboxEntryOutcome.Processed }

        assertEquals(listOf("a2"), seen)
    }

    @Test
    fun blockedEntriesDoNotCountAgainstMaxEntries() = runTest {
        val outbox = seeded("a1" to "a", "a2" to "a", "a3" to "a", "b1" to "b", "b2" to "b")
        val seen = mutableListOf<String>()

        val result = DurableOperationalEventOutboxProcessor(outbox).process(scope, maxEntries = 2, ordering = blocking) { current ->
            seen.add(current.id.value)
            if (current.id.value == "a1") OperationalEventOutboxEntryOutcome.Failed() else OperationalEventOutboxEntryOutcome.Processed
        }

        // Two handled (a1, b1); a2 and a3 were skipped over as blocked rather than eating the bound.
        assertEquals(listOf("a1", "b1"), seen)
        val summary = assertIs<OperationalEventOutboxProcessingResult.Processed>(result).summary
        assertEquals(2, summary.read)
        assertEquals(2, summary.blocked)
    }

    @Test
    fun anAcknowledgementFailureLeavesTheEntryPendingSoItBlocksToo() = runTest {
        val record = DurableStateRecord(
            state = OperationalEventOutboxState(
                listOf(
                    OperationalEventOutboxEntry(1L, envelope("a1", workflow = "a")),
                    OperationalEventOutboxEntry(2L, envelope("a2", workflow = "a")),
                ),
            ),
            version = 0L,
            schemaVersion = 2,
        )
        val processor = DurableOperationalEventOutboxProcessor(
            DurableOperationalEventOutbox(FoundStoreWithFailingCompareAndSet(record), outboxTestClock),
        )
        val seen = mutableListOf<String>()

        val result = processor.process(scope, ordering = blocking) { seen.add(it.id.value); OperationalEventOutboxEntryOutcome.Processed }

        assertEquals(listOf("a1"), seen)
        val summary = assertIs<OperationalEventOutboxProcessingResult.Processed>(result).summary
        assertEquals(1, summary.acknowledgeFailed)
        assertEquals(1, summary.blocked)
    }

    @Test
    fun entriesWithoutAWorkflowNeitherBlockNorAreBlocked() = runTest {
        val outbox = seeded("g1" to null, "g2" to null, "a1" to "a")
        val seen = mutableListOf<String>()

        DurableOperationalEventOutboxProcessor(outbox).process(scope, ordering = blocking) { current ->
            seen.add(current.id.value)
            if (current.id.value == "g1") OperationalEventOutboxEntryOutcome.Failed() else OperationalEventOutboxEntryOutcome.Processed
        }

        assertEquals(listOf("g1", "g2", "a1"), seen)
    }

    @Test
    fun anEntryTheFilterRejectsDoesNotBlockItsWorkflow() = runTest {
        val outbox = DurableOperationalEventOutbox(InMemoryOperationalEventOutboxStore(), outboxTestClock)
        val uninteresting = OperationalEventType("dataloom.test.uninteresting")
        outbox.append(scope, envelope("a1", type = uninteresting, workflow = "a"))
        outbox.append(scope, envelope("a2", workflow = "a"))
        val seen = mutableListOf<String>()

        DurableOperationalEventOutboxProcessor(outbox).process(
            scope,
            filter = OperationalEventOutboxEntryFilter { it.type != uninteresting },
            ordering = blocking,
        ) { seen.add(it.id.value); OperationalEventOutboxEntryOutcome.Processed }

        assertEquals(listOf("a2"), seen)
    }

    @Test
    fun blockingIsTrackedAsBlockedInTheSummaryAndTheDefaultPolicyReportsZero() = runTest {
        val outbox = seeded("a1" to "a", "a2" to "a")

        val result = DurableOperationalEventOutboxProcessor(outbox).process(scope) { OperationalEventOutboxEntryOutcome.Processed }

        assertEquals(0, assertIs<OperationalEventOutboxProcessingResult.Processed>(result).summary.blocked)
    }

    @Test
    fun maxEntriesBoundsOneCycleAndLeavesTheRestForALaterPass() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val outbox = DurableOperationalEventOutbox(store, outboxTestClock)
        val envelopes = (1..5).map { envelope("event-$it") }
        envelopes.forEach { outbox.append(scope, it) }
        val processor = DurableOperationalEventOutboxProcessor(outbox)

        val result = processor.process(scope, maxEntries = 2) { OperationalEventOutboxEntryOutcome.Processed }

        val summary = assertIs<OperationalEventOutboxProcessingResult.Processed>(result).summary
        assertEquals(2, summary.read)
        assertEquals(0, summary.filteredOut)
        assertEquals(2, summary.acknowledged)
        val remaining = assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(outbox.entries(scope))
        // The two oldest were acknowledged; the three newest are left for a later pass.
        assertEquals(envelopes.drop(2), remaining.value)
    }

    @Test
    fun unconfiguredFilterAcceptsEveryEntryAndReportsZeroFilteredOut() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val outbox = DurableOperationalEventOutbox(store, outboxTestClock)
        val envelopes = (1..3).map { envelope("event-$it") }
        envelopes.forEach { outbox.append(scope, it) }
        val processor = DurableOperationalEventOutboxProcessor(outbox)

        // No `filter` argument -- exercises the default value directly.
        val result = processor.process(scope) { OperationalEventOutboxEntryOutcome.Processed }

        val summary = assertIs<OperationalEventOutboxProcessingResult.Processed>(result).summary
        assertEquals(3, summary.read)
        assertEquals(0, summary.filteredOut)
        assertEquals(3, summary.acknowledged)
        val remaining = assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(outbox.entries(scope))
        assertEquals(emptyList(), remaining.value)
    }

    @Test
    fun aConfiguredFilterProcessesOnlyMatchingEntriesAndLeavesNonMatchingEntriesRetainedUntouched() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val outbox = DurableOperationalEventOutbox(store, outboxTestClock)
        val matchingType = OperationalEventType("dataloom.test.matching")
        val otherType = OperationalEventType("dataloom.test.other")
        val matchingOne = envelope("event-matching-1", type = matchingType)
        val nonMatching = envelope("event-other", type = otherType)
        val matchingTwo = envelope("event-matching-2", type = matchingType)
        listOf(matchingOne, nonMatching, matchingTwo).forEach { outbox.append(scope, it) }
        val processor = DurableOperationalEventOutboxProcessor(outbox)
        val seen = mutableListOf<OperationalEventId>()

        val result = processor.process(
            scope = scope,
            filter = OperationalEventOutboxEntryFilter { it.type == matchingType },
        ) { current -> seen.add(current.id); OperationalEventOutboxEntryOutcome.Processed }

        val summary = assertIs<OperationalEventOutboxProcessingResult.Processed>(result).summary
        assertEquals(2, summary.read)
        assertEquals(1, summary.filteredOut)
        assertEquals(2, summary.processed)
        assertEquals(2, summary.acknowledged)
        assertEquals(listOf(matchingOne.id, matchingTwo.id), seen)

        // The non-matching entry was never handed to the handler and stays retained, untouched.
        val remaining = assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(outbox.entries(scope))
        assertEquals(listOf(nonMatching), remaining.value)
    }

    @Test
    fun rejectedEntriesDoNotCountAgainstMaxEntriesSoARareFilterIsNotStarvedByCommonEntries() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val outbox = DurableOperationalEventOutbox(store, outboxTestClock)
        val rareType = OperationalEventType("dataloom.test.rare")
        val commonType = OperationalEventType("dataloom.test.common")
        // Five common (non-matching) entries appended first, then two rare (matching) ones.
        val commonEnvelopes = (1..5).map { envelope("event-common-$it", type = commonType) }
        val rareEnvelopes = (1..2).map { envelope("event-rare-$it", type = rareType) }
        (commonEnvelopes + rareEnvelopes).forEach { outbox.append(scope, it) }
        val processor = DurableOperationalEventOutboxProcessor(outbox)

        // maxEntries is smaller than the total retained count, but both rare
        // entries must still be processed since the five common entries
        // ahead of them in the list are filtered out before the bound is applied.
        val result = processor.process(
            scope = scope,
            maxEntries = 2,
            filter = OperationalEventOutboxEntryFilter { it.type == rareType },
        ) { OperationalEventOutboxEntryOutcome.Processed }

        val summary = assertIs<OperationalEventOutboxProcessingResult.Processed>(result).summary
        assertEquals(2, summary.read)
        assertEquals(5, summary.filteredOut)
        assertEquals(2, summary.processed)
        assertEquals(2, summary.acknowledged)
        val remaining = assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(outbox.entries(scope))
        assertEquals(commonEnvelopes, remaining.value)
    }

    @Test
    fun everyRetainedEntryFilteredOutReturnsProcessedWithAnAllZeroSummaryNotNoWork() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val outbox = DurableOperationalEventOutbox(store, outboxTestClock)
        val envelopes = (1..3).map { envelope("event-$it") }
        envelopes.forEach { outbox.append(scope, it) }
        val processor = DurableOperationalEventOutboxProcessor(outbox)
        var invocations = 0

        val result = processor.process(
            scope = scope,
            filter = OperationalEventOutboxEntryFilter { false },
        ) { invocations++; OperationalEventOutboxEntryOutcome.Processed }

        val summary = assertIs<OperationalEventOutboxProcessingResult.Processed>(result).summary
        assertEquals(0, invocations)
        assertEquals(0, summary.read)
        assertEquals(3, summary.filteredOut)
        assertEquals(0, summary.processed)
        assertEquals(0, summary.skipped)
        assertEquals(0, summary.failed)
        assertEquals(0, summary.acknowledged)
        assertEquals(0, summary.acknowledgeRaced)
        assertEquals(0, summary.acknowledgeFailed)
        val remaining = assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(outbox.entries(scope))
        assertEquals(envelopes, remaining.value)
    }

    @Test
    fun maxEntriesBelowOneIsRejected() = runTest {
        val outbox = DurableOperationalEventOutbox(InMemoryOperationalEventOutboxStore(), outboxTestClock)
        val processor = DurableOperationalEventOutboxProcessor(outbox)

        assertFailsWith<IllegalArgumentException> {
            processor.process(scope, maxEntries = 0) { OperationalEventOutboxEntryOutcome.Processed }
        }
    }

    @Test
    fun readFailurePropagatesAsAReadFailureResultAndNeverInvokesTheHandler() = runTest {
        val outbox = DurableOperationalEventOutbox(FailingLoadStore(), outboxTestClock)
        val processor = DurableOperationalEventOutboxProcessor(outbox)
        var invocations = 0

        val result = processor.process(scope) { invocations++; OperationalEventOutboxEntryOutcome.Processed }

        assertIs<OperationalEventOutboxProcessingResult.ReadFailure>(result)
        assertEquals(0, invocations)
    }

    @Test
    fun anAcknowledgementRaceReportedByAnotherCallerIsCountedNotTreatedAsAFailure() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val outbox = DurableOperationalEventOutbox(store, outboxTestClock)
        val other = DurableOperationalEventOutbox(store, outboxTestClock) // a distinct instance over the same durable store
        val envelope = envelope("event-1")
        outbox.append(scope, envelope)
        val processor = DurableOperationalEventOutboxProcessor(outbox)

        val result = processor.process(scope) { current ->
            // Simulate a second, independent caller acknowledging the same
            // entry first -- before this processor's own acknowledge runs.
            other.acknowledge(scope, current.id)
            OperationalEventOutboxEntryOutcome.Processed
        }

        val summary = assertIs<OperationalEventOutboxProcessingResult.Processed>(result).summary
        assertEquals(1, summary.processed)
        assertEquals(0, summary.acknowledged)
        assertEquals(1, summary.acknowledgeRaced)
        assertEquals(0, summary.acknowledgeFailed)
        val remaining = assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(outbox.entries(scope))
        assertEquals(emptyList(), remaining.value)
    }

    @Test
    fun anAcknowledgementPersistenceFailureIsCountedAndTheEntryStaysRetained() = runTest {
        val envelope = envelope("event-1")
        val record = DurableStateRecord(
            state = OperationalEventOutboxState(listOf(OperationalEventOutboxEntry(1L, envelope))),
            version = 0L,
            schemaVersion = 1,
        )
        val outbox = DurableOperationalEventOutbox(FoundStoreWithFailingCompareAndSet(record), outboxTestClock)
        val processor = DurableOperationalEventOutboxProcessor(outbox)

        val result = processor.process(scope) { OperationalEventOutboxEntryOutcome.Processed }

        val summary = assertIs<OperationalEventOutboxProcessingResult.Processed>(result).summary
        assertEquals(1, summary.processed)
        assertEquals(0, summary.acknowledged)
        assertEquals(0, summary.acknowledgeRaced)
        assertEquals(1, summary.acknowledgeFailed)
    }

    @Test
    fun cancellationFromTheHandlerPropagatesRatherThanBecomingAResultVariant() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val outbox = DurableOperationalEventOutbox(store, outboxTestClock)
        outbox.append(scope, envelope("event-1"))
        val processor = DurableOperationalEventOutboxProcessor(outbox)

        assertFailsWith<CancellationException> {
            processor.process(scope) { throw CancellationException("cancelled") }
        }
    }

    /**
     * Proves the guarantees [DurableOperationalEventOutboxProcessor]'s own
     * "Concurrency" documentation makes for two [DurableOperationalEventOutboxProcessor.process]
     * calls racing against the *same* scope over the *same* durable store: no
     * entry is lost, and no entry is double-acknowledged -- both processors'
     * `acknowledged` counts, summed, equal exactly the original entry count,
     * never more. [InterleavingOperationalEventOutboxStore] forces a real
     * suspension point inside both `load` and `compareAndSet`, so the two
     * processor coroutines genuinely interleave through
     * [io.dataloom.api.operational.DurableOperationalEventOutbox.acknowledge]'s
     * own compare-and-set retry loop rather than running one after the other.
     */
    @Test
    fun concurrentProcessCallsAgainstTheSameScopeNeverLoseOrDoubleAcknowledgeAnEntry() = runTest {
        val store = InterleavingOperationalEventOutboxStore()
        val seedOutbox = DurableOperationalEventOutbox(store, outboxTestClock)
        val envelopes = (1..8).map { envelope("event-$it") }
        envelopes.forEach { seedOutbox.append(scope, it) }

        val processorA = DurableOperationalEventOutboxProcessor(DurableOperationalEventOutbox(store, outboxTestClock))
        val processorB = DurableOperationalEventOutboxProcessor(DurableOperationalEventOutbox(store, outboxTestClock))

        val deferredA = async { processorA.process(scope, maxEntries = 8) { OperationalEventOutboxEntryOutcome.Processed } }
        val deferredB = async { processorB.process(scope, maxEntries = 8) { OperationalEventOutboxEntryOutcome.Processed } }
        val summaryA = (deferredA.await() as OperationalEventOutboxProcessingResult.Processed).summary
        val summaryB = (deferredB.await() as OperationalEventOutboxProcessingResult.Processed).summary

        // Nothing lost: every entry ends up either acknowledged by A or by B.
        assertEquals(envelopes.size, summaryA.acknowledged + summaryB.acknowledged)
        // Nothing double-acknowledged: the durable store agrees -- empty.
        val remaining = assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(
            DurableOperationalEventOutbox(store, outboxTestClock).entries(scope),
        )
        assertTrue(remaining.value.isEmpty())
        // Every acknowledge attempt that did not win landed as a counted race,
        // never a silent loss and never a reported failure.
        assertEquals(0, summaryA.acknowledgeFailed)
        assertEquals(0, summaryB.acknowledgeFailed)
    }

    /** The ids of [scope]'s pending entries, in the order [DurableOperationalEventOutbox.entries] presents them. */
    private suspend fun pendingIds(outbox: DurableOperationalEventOutbox): List<String> =
        assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(outbox.entries(scope)).value
            .map { it.id.value }

    private fun envelope(
        id: String,
        correlationId: String = "correlation-1",
        type: OperationalEventType = OperationalEventType("dataloom.test.event"),
        workflow: String? = null,
    ): OperationalEventEnvelope = OperationalEventEnvelope(
        id = OperationalEventId(id),
        type = type,
        workflowId = workflow?.let { WorkflowId(it) },
        source = OperationalEventSource("dataloom.runtime.test"),
        category = OperationalEventCategory.TELEMETRY,
        schemaVersion = OperationalSchemaVersion(1),
        occurredAt = DataLoomInstant(1_000L),
        correlationId = CorrelationId(correlationId),
        payload = OperationalPayloadDescriptor(
            type = OperationalPayloadType("dataloom.test.signal"),
            schemaVersion = OperationalSchemaVersion(1),
            encoding = OperationalPayloadEncoding("application/json"),
            classification = DataClassification.INTERNAL,
        ),
    )

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

    /**
     * Identical to [InMemoryOperationalEventOutboxStore] except both [load]
     * and [compareAndSet] yield once before doing any work, so two coroutines
     * sharing this store genuinely interleave through
     * [io.dataloom.api.operational.DurableOperationalEventOutbox.acknowledge]'s
     * load-evaluate-compare-and-set retry loop under a single-threaded test
     * dispatcher, rather than one running to completion before the other
     * starts.
     */
    private class InterleavingOperationalEventOutboxStore :
        DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState> {
        private val records = mutableMapOf<OperationalEventOutboxScope, DurableStateRecord<OperationalEventOutboxState>>()

        override suspend fun load(
            scope: OperationalEventOutboxScope,
        ): ProviderOperationResult<DurableStateLoadResult<OperationalEventOutboxState>> {
            yield()
            val record = records[scope]
            return ProviderOperationResult.Success(
                if (record == null) DurableStateLoadResult.Missing else DurableStateLoadResult.Found(record),
            )
        }

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<OperationalEventOutboxScope, OperationalEventOutboxState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<OperationalEventOutboxState>> {
            yield()
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
}

private fun testError(): DataLoomError = DurableOperationalEventOutboxProcessorTestError(
    code = ErrorCode("DURABLE_OPERATIONAL_EVENT_OUTBOX_PROCESSOR_TEST_FAILURE"),
    category = ErrorCategory.STORAGE,
    severity = ErrorSeverity.ERROR,
    recoverability = Recoverability.RECOVERABLE,
    message = "Simulated store failure.",
)

private data class DurableOperationalEventOutboxProcessorTestError(
    override val code: ErrorCode,
    override val category: ErrorCategory,
    override val severity: ErrorSeverity,
    override val recoverability: Recoverability,
    override val message: String,
    override val cause: Throwable? = null,
) : DataLoomError
