package io.dataloom.runtime.operational

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.operational.DurableOperationalEventOutbox
import io.dataloom.api.operational.DurableOperationalEventOutboxAcknowledgeOutcome
import io.dataloom.api.operational.OperationalEventEnvelope
import io.dataloom.api.operational.OperationalEventOutboxScope
import io.dataloom.api.provider.ProviderOperationResult

/**
 * Per-entry outcome an [OperationalEventOutboxEntryHandler] reports for one
 * [OperationalEventEnvelope] handed to it by [DurableOperationalEventOutboxProcessor].
 *
 * Deliberately three variants, not a plain `Boolean` -- [Skipped] and [Failed]
 * both leave the entry retained for a later processing pass (see
 * [DurableOperationalEventOutboxProcessor]'s "Acknowledgement" documentation),
 * but they are distinct signals worth preserving in
 * [OperationalEventOutboxProcessingSummary] for a caller's own diagnostics: a
 * handler that deliberately declines an entry (for example, one outside a
 * filter it applies) is a materially different situation from one that
 * attempted the entry and hit a genuine error. This mirrors
 * [io.dataloom.runtime.queue.QueueEntryExecutionOutcome]'s own precedent of a
 * sealed per-entry result type, deliberately without that type's
 * retry-attempt/lease/circuit-breaker fields -- this outbox is a
 * diagnostics/observability sink, not a work queue with retry semantics (see
 * [io.dataloom.api.operational.DurableOperationalEventOutbox]'s own
 * "Acknowledgement" documentation), so [DurableOperationalEventOutboxProcessor]
 * never needs to compute a next-retry time or a failure disposition.
 */
public sealed interface OperationalEventOutboxEntryOutcome {

    /**
     * The handler successfully dealt with the entry.
     * [DurableOperationalEventOutboxProcessor] acknowledges it.
     */
    public data object Processed : OperationalEventOutboxEntryOutcome

    /**
     * The handler deliberately declined to act on this entry. The entry is
     * left retained; it is presented again on a later processing pass.
     */
    public data object Skipped : OperationalEventOutboxEntryOutcome

    /**
     * The handler attempted the entry and it failed. The entry is left
     * retained; it is presented again on a later processing pass.
     *
     * @param error an optional [DataLoomError] describing the failure, for a
     *   caller's own diagnostics. Never required -- a handler that has no
     *   structured error to report may leave this `null`.
     */
    public data class Failed(
        public val error: DataLoomError? = null,
    ) : OperationalEventOutboxEntryOutcome
}

/**
 * Application-level contract for handling one [OperationalEventEnvelope] read
 * from a [DurableOperationalEventOutbox] scope.
 *
 * Mirrors [io.dataloom.runtime.queue.QueueEntryExecutionHandler]'s own shape:
 * a `fun interface` invoked once per entry by
 * [DurableOperationalEventOutboxProcessor.process], returning a structured
 * outcome that decides the entry's fate rather than performing any durable
 * transition itself.
 *
 * Implementations must not throw to signal a business-level failure -- report
 * [OperationalEventOutboxEntryOutcome.Failed] instead.
 * [kotlin.coroutines.cancellation.CancellationException] must not be caught or
 * suppressed; it propagates normally out of [DurableOperationalEventOutboxProcessor.process].
 */
public fun interface OperationalEventOutboxEntryHandler {

    /**
     * Handles [envelope] and returns the outcome that decides whether
     * [DurableOperationalEventOutboxProcessor] acknowledges it.
     */
    public suspend fun handle(envelope: OperationalEventEnvelope): OperationalEventOutboxEntryOutcome
}

/**
 * Immutable counters describing one [DurableOperationalEventOutboxProcessor.process] cycle.
 *
 * @param read the number of entries handed to the handler this cycle -- at
 *   most the `maxEntries` bound passed to [DurableOperationalEventOutboxProcessor.process].
 * @param processed entries whose handler outcome was [OperationalEventOutboxEntryOutcome.Processed].
 * @param skipped entries whose handler outcome was [OperationalEventOutboxEntryOutcome.Skipped].
 * @param failed entries whose handler outcome was [OperationalEventOutboxEntryOutcome.Failed].
 * @param acknowledged entries that were both [processed] and durably
 *   acknowledged -- [DurableOperationalEventOutboxAcknowledgeOutcome.Acknowledged].
 *   A subsequent [DurableOperationalEventOutbox.entries] call for the same
 *   scope no longer includes these.
 * @param acknowledgeRaced entries that were [processed] but whose
 *   acknowledgement reported [DurableOperationalEventOutboxAcknowledgeOutcome.NotFound]
 *   -- another caller (a concurrent [DurableOperationalEventOutboxProcessor.process]
 *   call, an unrelated direct [DurableOperationalEventOutbox.acknowledge] caller, or
 *   retention) already removed the entry first. Not an error; see this
 *   class's "Concurrency" documentation.
 * @param acknowledgeFailed entries that were [processed] but whose
 *   acknowledgement reported [DurableOperationalEventOutboxAcknowledgeOutcome.PersistenceFailure]
 *   or [DurableOperationalEventOutboxAcknowledgeOutcome.ContentionLimitReached].
 *   The entry remains retained and is presented again on a later pass.
 */
public data class OperationalEventOutboxProcessingSummary(
    public val read: Int,
    public val processed: Int,
    public val skipped: Int,
    public val failed: Int,
    public val acknowledged: Int,
    public val acknowledgeRaced: Int,
    public val acknowledgeFailed: Int,
)

/**
 * Sealed result returned by [DurableOperationalEventOutboxProcessor.process].
 */
public sealed interface OperationalEventOutboxProcessingResult {

    /**
     * [DurableOperationalEventOutbox.entries] returned nothing retained for
     * the requested scope. No handler was invoked.
     */
    public data object NoWork : OperationalEventOutboxProcessingResult

    /**
     * The processor read a batch and ran the handler cycle to completion.
     * [summary] reflects the full truthful counts -- including any per-entry
     * acknowledgement failures, which do not stop the cycle (see
     * [DurableOperationalEventOutboxProcessor]'s "Acknowledgement" documentation).
     */
    public data class Processed(
        public val summary: OperationalEventOutboxProcessingSummary,
    ) : OperationalEventOutboxProcessingResult

    /**
     * The initial [DurableOperationalEventOutbox.entries] read for the
     * requested scope failed. No handler was invoked.
     */
    public data class ReadFailure(
        public val error: DataLoomError,
    ) : OperationalEventOutboxProcessingResult
}

/**
 * Bounded, opt-in read-then-consume processing loop over one
 * [DurableOperationalEventOutbox] scope.
 *
 * [DurableOperationalEventOutbox] already durably persists
 * [OperationalEventEnvelope]s ([io.dataloom.api.operational.DurableOperationalEventOutbox.append]),
 * reads back everything currently retained
 * ([io.dataloom.api.operational.DurableOperationalEventOutbox.entries]), and
 * lets a caller remove one entry it has already dealt with
 * ([io.dataloom.api.operational.DurableOperationalEventOutbox.acknowledge]).
 * Every real caller so far only ever appends, purely for operator visibility
 * -- nothing in this codebase yet reads `entries` back and acknowledges as
 * part of one processing step. [DurableOperationalEventOutboxProcessor] is
 * that first, purely additive consumer-side loop: read one bounded batch of
 * currently-retained entries, hand each to a caller-supplied
 * [OperationalEventOutboxEntryHandler], and acknowledge only the ones the
 * handler reports [OperationalEventOutboxEntryOutcome.Processed], leaving
 * every other entry retained for a later pass.
 *
 * ## Why [io.dataloom.api.operational.DurableOperationalEventOutbox] itself is unchanged
 *
 * This class is built entirely out of [outbox]'s already-public `entries`/
 * `acknowledge` API. It needs nothing new from
 * [io.dataloom.api.operational.DurableOperationalEventOutbox] itself, so that
 * class is not modified -- every existing caller of `append`/`entries`/
 * `acknowledge` is completely unaffected by this type's existence.
 *
 * ## Why this lives in `dataloom-runtime`, not `dataloom-api`
 *
 * [io.dataloom.api.operational.DurableOperationalEventOutbox] itself lives in
 * `dataloom-api` because it is a generic, self-contained
 * load-evaluate-compare-and-set loop over a [io.dataloom.api.state.DurableStateStore]
 * -- the same shape [io.dataloom.api.configuration.DurableConfigurationHistory],
 * [io.dataloom.api.policy.DurablePolicyDecisionLog], and
 * [io.dataloom.api.conflict.DurableUnresolvedConflictLog] already establish
 * there, none of which needs a caller-supplied handler or drives a processing
 * cycle. `dataloom-api`'s own module rules are explicit that it "must not
 * contain runtime implementations." A read-then-consume *cycle* that invokes
 * an application-supplied handler and decides per-entry durable transitions
 * from that handler's outcome is exactly a runtime implementation -- this
 * codebase's only existing precedent for that shape,
 * [io.dataloom.runtime.queue.DurableQueueExecutionProcessor], already lives in
 * `dataloom-runtime` for the same reason, even though the
 * [io.dataloom.api.queue.QueueProvider] it drives lives in `dataloom-api`.
 * This class follows that same split. Within `dataloom-runtime`, it lives in
 * `io.dataloom.runtime.operational` alongside
 * [io.dataloom.runtime.operational.OperationalEnvelopeUpcasterRegistry] --
 * this package is already this codebase's home for generic
 * [OperationalEventEnvelope]-processing logic that is not one specific
 * outcome-to-envelope bridge (compare `io.dataloom.runtime.observation.operational`,
 * which holds exactly those bridges).
 *
 * ## Why not [io.dataloom.runtime.queue.QueueEntryExecutionOutcome]'s shape
 *
 * [io.dataloom.runtime.queue.DurableQueueExecutionProcessor] and its
 * [io.dataloom.runtime.queue.QueueEntryExecutionOutcome] exist to drive
 * [io.dataloom.api.queue.QueueProvider]'s lease/acquire/complete/fail/defer/
 * cancel work-item lifecycle, including retry-attempt and dead-letter
 * disposition. [io.dataloom.api.operational.DurableOperationalEventOutbox]'s
 * own "Acknowledgement" documentation is explicit that `acknowledge` is
 * "operator-driven dismissal from view, not work-queue completion" -- there is
 * no lease, no retry attempt, no failure disposition to compute here. Mirroring
 * the queue processor's five-variant outcome and lease-aware transition table
 * would import retry/circuit-breaker complexity this outbox deliberately does
 * not have. [OperationalEventOutboxEntryOutcome] instead keeps only the part
 * of that idiom that still applies: a sealed per-entry result a handler
 * returns, that the loop uses to decide one thing -- acknowledge, or leave
 * retained.
 *
 * ## Ordering
 *
 * [process] reads [outbox]'s `entries(scope)` once per call -- oldest first,
 * exactly as [io.dataloom.api.operational.DurableOperationalEventOutbox.entries]
 * already returns them -- and hands them to [handler] in that same order,
 * sequentially, one at a time. At most `maxEntries` of the retained list are
 * read; any beyond that bound are left for a later [process] call.
 *
 * ## Acknowledgement
 *
 * Only entries whose handler outcome is [OperationalEventOutboxEntryOutcome.Processed]
 * are acknowledged. [OperationalEventOutboxEntryOutcome.Skipped] and
 * [OperationalEventOutboxEntryOutcome.Failed] both leave the entry retained,
 * with no distinction in how they are treated by this loop -- only in what
 * [OperationalEventOutboxProcessingSummary] records for a caller's own
 * diagnostics.
 *
 * A per-entry acknowledgement failure --
 * [DurableOperationalEventOutboxAcknowledgeOutcome.PersistenceFailure] or
 * [DurableOperationalEventOutboxAcknowledgeOutcome.ContentionLimitReached] --
 * does **not** stop the cycle. Unlike
 * [io.dataloom.runtime.queue.DurableQueueExecutionProcessor], which stops a
 * cycle immediately on a provider transition failure because a queue's
 * lease-bound transitions are business-critical, this class's own
 * `acknowledge` calls are each independent, idempotent removals from a
 * diagnostics sink -- one entry's acknowledgement failing durably does not
 * invalidate any other entry's, and the failed entry simply remains for a
 * later pass. [process] instead continues to the next entry and reports the
 * failure through [OperationalEventOutboxProcessingSummary.acknowledgeFailed].
 * This is a deliberate proportionality choice, not an oversight.
 *
 * ## Concurrency
 *
 * [DurableOperationalEventOutbox.entries] is a snapshot read -- a fresh
 * `load` -- not a live view, so two concurrent [process] calls against the
 * *same* [OperationalEventOutboxScope] may both read a batch containing the
 * same entry before either has acknowledged it, and [handler] may therefore
 * be invoked more than once for that entry across the two calls.
 *
 * What concurrent [process] calls on the same scope *do* still guarantee,
 * inherited directly from [io.dataloom.api.operational.DurableOperationalEventOutbox.acknowledge]'s
 * own compare-and-set retry loop:
 * - **No entry is ever lost.** An entry stays retained until some
 *   `acknowledge` call actually wins its compare-and-set; a losing racer
 *   reloads and retries against the new state, exactly as
 *   [io.dataloom.api.operational.DurableOperationalEventOutbox]'s own
 *   "Concurrency" documentation already establishes for `acknowledge` in
 *   general.
 * - **No entry is ever double-acknowledged.** Exactly one of two racing
 *   `acknowledge(scope, id)` calls for the same `id` observes
 *   [DurableOperationalEventOutboxAcknowledgeOutcome.Acknowledged]; the other
 *   necessarily observes [DurableOperationalEventOutboxAcknowledgeOutcome.NotFound]
 *   once it reloads and finds the entry already gone -- counted in
 *   [OperationalEventOutboxProcessingSummary.acknowledgeRaced], not treated as
 *   a failure.
 *
 * What concurrent [process] calls on the same scope do **not** guarantee:
 * exactly-once [handler] invocation. A handler with side effects that are not
 * themselves idempotent must not be invoked concurrently for the same scope
 * -- a caller that needs that guarantee must serialize [process] calls per
 * scope itself (for example, one coroutine owning one scope at a time).
 * [process] calls against *different* scopes never interact, by the same
 * reasoning [io.dataloom.api.operational.DurableOperationalEventOutbox]
 * already documents for `append`/`acknowledge` across scopes.
 *
 * ## Cancellation
 *
 * [kotlin.coroutines.cancellation.CancellationException] from [handler] or
 * from any [outbox] call propagates normally and is never converted into an
 * [OperationalEventOutboxProcessingResult] variant.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 *
 * @param outbox the [DurableOperationalEventOutbox] this processor reads from
 *   and acknowledges into. Required.
 */
public class DurableOperationalEventOutboxProcessor(
    private val outbox: DurableOperationalEventOutbox,
) {

    /**
     * Executes one bounded read-then-consume processing cycle for [scope].
     *
     * @param scope the [OperationalEventOutboxScope] to read and acknowledge
     *   into.
     * @param maxEntries the maximum number of currently-retained entries to
     *   read and hand to [handler] this cycle. Must be at least `1`. Defaults
     *   to [DEFAULT_MAX_ENTRIES]. Entries beyond this bound, if any, are left
     *   for a later [process] call.
     * @param handler invoked once per read entry, sequentially, in the same
     *   oldest-first order [DurableOperationalEventOutbox.entries] returns.
     * @return an [OperationalEventOutboxProcessingResult] describing the
     *   terminal outcome of this cycle.
     */
    public suspend fun process(
        scope: OperationalEventOutboxScope,
        maxEntries: Int = DEFAULT_MAX_ENTRIES,
        handler: OperationalEventOutboxEntryHandler,
    ): OperationalEventOutboxProcessingResult {
        require(maxEntries >= 1) { "maxEntries must be at least 1, but was $maxEntries." }

        val allRetained = when (val result = outbox.entries(scope)) {
            is ProviderOperationResult.Failure -> return OperationalEventOutboxProcessingResult.ReadFailure(result.error)
            is ProviderOperationResult.Success -> result.value
        }
        if (allRetained.isEmpty()) {
            return OperationalEventOutboxProcessingResult.NoWork
        }

        val batch = allRetained.take(maxEntries)
        var processed = 0
        var skipped = 0
        var failed = 0
        var acknowledged = 0
        var acknowledgeRaced = 0
        var acknowledgeFailed = 0

        for (envelope in batch) {
            when (handler.handle(envelope)) {
                is OperationalEventOutboxEntryOutcome.Processed -> {
                    processed++
                    when (outbox.acknowledge(scope, envelope.id)) {
                        is DurableOperationalEventOutboxAcknowledgeOutcome.Acknowledged -> acknowledged++
                        is DurableOperationalEventOutboxAcknowledgeOutcome.NotFound -> acknowledgeRaced++
                        is DurableOperationalEventOutboxAcknowledgeOutcome.PersistenceFailure,
                        is DurableOperationalEventOutboxAcknowledgeOutcome.ContentionLimitReached,
                        -> acknowledgeFailed++
                    }
                }
                is OperationalEventOutboxEntryOutcome.Skipped -> skipped++
                is OperationalEventOutboxEntryOutcome.Failed -> failed++
            }
        }

        return OperationalEventOutboxProcessingResult.Processed(
            OperationalEventOutboxProcessingSummary(
                read = batch.size,
                processed = processed,
                skipped = skipped,
                failed = failed,
                acknowledged = acknowledged,
                acknowledgeRaced = acknowledgeRaced,
                acknowledgeFailed = acknowledgeFailed,
            ),
        )
    }

    private companion object {
        const val DEFAULT_MAX_ENTRIES: Int = 100
    }
}
