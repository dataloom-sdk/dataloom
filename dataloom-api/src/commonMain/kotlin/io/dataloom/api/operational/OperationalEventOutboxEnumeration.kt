package io.dataloom.api.operational

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.identifier.WorkflowId

/** Which population of a [DurableOperationalEventOutbox] scope an entry belongs to. */
public enum class OperationalEventOutboxEntryStatus {
    /** Never acknowledged, or acknowledged and then replayed: [OperationalEventOutboxEntry.isAcknowledged] is `false`. */
    PENDING,

    /** A retained acknowledged tombstone: [OperationalEventOutboxEntry.isAcknowledged] is `true`. */
    ACKNOWLEDGED,
}

/**
 * Validates a caller-supplied scope list shared by [OperationalEventOutboxEntryQuery]
 * and [OperationalEventOutboxBatchReplayRequest].
 */
private fun requireBoundedDistinctScopes(scopes: List<OperationalEventOutboxScope>, what: String) {
    require(scopes.isNotEmpty()) { "$what scopes must not be empty." }
    require(scopes.size <= MAXIMUM_OUTBOX_SCOPES_PER_CALL) {
        "$what scopes must not exceed $MAXIMUM_OUTBOX_SCOPES_PER_CALL, but had ${scopes.size}."
    }
    require(scopes.toSet().size == scopes.size) { "$what scopes must be distinct." }
}

private const val MAXIMUM_OUTBOX_SCOPES_PER_CALL: Int = 100

/**
 * A read-only query across several [OperationalEventOutboxScope]s of one
 * [DurableOperationalEventOutbox], executed page by page through
 * [DurableOperationalEventOutbox.enumerate].
 *
 * The caller names the [scopes]: the outbox's [io.dataloom.api.state.DurableStateStore]
 * contract can only load a scope it is handed and has no way to list which
 * scopes exist, so this is a bounded *fan-out over a declared scope set*, not
 * discovery of unknown scopes.
 *
 * @param scopes the scopes to read, between `1` and [MAXIMUM_SCOPES] distinct
 *   entries. Their order is irrelevant: results are always presented in the
 *   order documented on [DurableOperationalEventOutbox.enumerate].
 * @param workflowId when non-null, only entries whose
 *   [OperationalEventEnvelope.workflowId] equals it are returned.
 * @param status when non-null, only entries in that population are returned;
 *   `null` returns both.
 * @param pageSize the maximum number of entries one call returns, between `1`
 *   and [MAXIMUM_PAGE_SIZE].
 */
public data class OperationalEventOutboxEntryQuery(
    public val scopes: List<OperationalEventOutboxScope>,
    public val workflowId: WorkflowId? = null,
    public val status: OperationalEventOutboxEntryStatus? = null,
    public val pageSize: Int = DEFAULT_PAGE_SIZE,
) {
    init {
        requireBoundedDistinctScopes(scopes, "OperationalEventOutboxEntryQuery")
        require(pageSize in 1..MAXIMUM_PAGE_SIZE) {
            "OperationalEventOutboxEntryQuery pageSize must be between 1 and $MAXIMUM_PAGE_SIZE, but was $pageSize."
        }
    }

    internal fun matches(entry: OperationalEventOutboxEntry): Boolean =
        (workflowId == null || entry.envelope.workflowId == workflowId) &&
            when (status) {
                null -> true
                OperationalEventOutboxEntryStatus.PENDING -> !entry.isAcknowledged
                OperationalEventOutboxEntryStatus.ACKNOWLEDGED -> entry.isAcknowledged
            }

    public companion object {
        /** The most scopes one query may name. */
        public const val MAXIMUM_SCOPES: Int = MAXIMUM_OUTBOX_SCOPES_PER_CALL

        /** The explicit page-size cap: one call never returns more than this many entries. */
        public const val MAXIMUM_PAGE_SIZE: Int = 500

        /** The page size used when a query does not name one. */
        public const val DEFAULT_PAGE_SIZE: Int = 100
    }
}

/**
 * A position in the cross-scope enumeration order: the last entry a page
 * returned. [DurableOperationalEventOutbox.enumerate] resumes strictly after it.
 *
 * It is built only from intrinsic entry identity -- the scope, the entry's
 * [OperationalEventOrderingKey] and its [OperationalEventOutboxEntry.sequence],
 * which is never reused -- and never from a list index or a store version, so it
 * stays valid while the outbox changes between pages: appends, acknowledgements,
 * replays and even retention evicting the very entry the cursor names do not
 * skip or repeat any other entry.
 */
public data class OperationalEventOutboxCursor(
    public val scope: OperationalEventOutboxScope,
    public val orderingKey: OperationalEventOrderingKey,
    public val sequence: Long,
) {
    init {
        require(sequence >= 1L) { "OperationalEventOutboxCursor sequence must be at least 1, but was $sequence." }
    }
}

/** One [entry] together with the [scope] it was read from. */
public data class OperationalEventOutboxScopedEntry(
    public val scope: OperationalEventOutboxScope,
    public val entry: OperationalEventOutboxEntry,
)

/**
 * One page of a cross-scope enumeration.
 *
 * @param entries at most [OperationalEventOutboxEntryQuery.pageSize] entries,
 *   in the order documented on [DurableOperationalEventOutbox.enumerate].
 * @param nextCursor pass it as `after` to read the following page; `null` when
 *   this page was the last one *as of the read* (entries appended later are
 *   picked up only if they sort after the cursor of an in-progress enumeration).
 */
public data class OperationalEventOutboxEntryPage(
    public val entries: List<OperationalEventOutboxScopedEntry>,
    public val nextCursor: OperationalEventOutboxCursor?,
)

/**
 * The host-supplied decision of whether acknowledged entries may be reopened
 * by [DurableOperationalEventOutbox.replayBatch].
 *
 * The outbox is a storage primitive and has no notion of who is calling, so
 * the host supplies one -- typically a closure over the operator's identity
 * and its own policy. [DurableOperationalEventOutbox.replayBatch] takes it as
 * a required parameter with no default: batch replay can never run
 * unauthorized by omission, and the two conscious choices are
 * `OperationalEventOutboxReplayAuthorizer { _, _ -> true }` for a host that
 * has already authorized the operator upstream, and a real per-entry policy.
 *
 * Called once per candidate entry, never from inside the compare-and-set
 * retry loop. An exception thrown by [authorize] (other than cancellation) is
 * treated as a denial, so a faulty authorizer fails closed.
 */
public fun interface OperationalEventOutboxReplayAuthorizer {
    /** `true` if [entry], read from [scope], may be reopened. */
    public suspend fun authorize(scope: OperationalEventOutboxScope, entry: OperationalEventOutboxEntry): Boolean
}

/**
 * Selects the acknowledged entries [DurableOperationalEventOutbox.replayBatch]
 * considers for reopening.
 *
 * There is deliberately no status filter: only [OperationalEventOutboxEntryStatus.ACKNOWLEDGED]
 * entries can be replayed. An entry a consumer skipped or failed is already
 * pending -- the outbox keeps no separate failed or dead-letter state -- and
 * the next processor pass presents it again with no replay at all.
 *
 * @param scopes the scopes to read, between `1` and [MAXIMUM_SCOPES] distinct entries.
 * @param workflowId when non-null, only entries of that workflow are considered.
 * @param maximumEntries the most entries one call reopens, between `1` and
 *   [MAXIMUM_ENTRIES]. Denied entries do not consume it.
 */
public data class OperationalEventOutboxBatchReplayRequest(
    public val scopes: List<OperationalEventOutboxScope>,
    public val workflowId: WorkflowId? = null,
    public val maximumEntries: Int = DEFAULT_MAXIMUM_ENTRIES,
) {
    init {
        requireBoundedDistinctScopes(scopes, "OperationalEventOutboxBatchReplayRequest")
        require(maximumEntries in 1..MAXIMUM_ENTRIES) {
            "OperationalEventOutboxBatchReplayRequest maximumEntries must be between 1 and $MAXIMUM_ENTRIES, " +
                "but was $maximumEntries."
        }
    }

    public companion object {
        /** The most scopes one request may name. */
        public const val MAXIMUM_SCOPES: Int = MAXIMUM_OUTBOX_SCOPES_PER_CALL

        /** The explicit cap on entries one call reopens. */
        public const val MAXIMUM_ENTRIES: Int = 500

        /** The cap used when a request does not name one. */
        public const val DEFAULT_MAXIMUM_ENTRIES: Int = 100
    }
}

/** Why one scope of a [DurableOperationalEventOutbox.replayBatch] call could not be processed. */
public sealed interface OperationalEventOutboxBatchReplayFailure {
    /** The scope that failed; its entries were left exactly as they were. */
    public val scope: OperationalEventOutboxScope

    /** The underlying [io.dataloom.api.state.DurableStateStore] failed to load or write [scope]. */
    public data class PersistenceFailure(
        override val scope: OperationalEventOutboxScope,
        public val error: DataLoomError,
    ) : OperationalEventOutboxBatchReplayFailure

    /** Every compare-and-set attempt for [scope] lost a race; nothing was persisted for it. */
    public data class ContentionLimitReached(
        override val scope: OperationalEventOutboxScope,
    ) : OperationalEventOutboxBatchReplayFailure
}

/**
 * What one [DurableOperationalEventOutbox.replayBatch] call did. Each scope is
 * reopened in one atomic compare-and-set, but scopes are independent: a
 * [failures] entry for one scope never undoes another scope's [replayed].
 * Replay is idempotent, so a caller may simply call again.
 *
 * @param replayed every entry reopened, now pending again at its original
 *   position and sequence, in scope then ordering-key then sequence order.
 * @param denied candidates the authorizer refused (or that threw); left acknowledged.
 * @param notReplayable authorized candidates that were no longer acknowledged
 *   when the write was attempted -- reopened or pruned by someone else in
 *   between. Nothing was written for them.
 * @param failures scopes that could not be processed.
 * @param budgetExhausted `true` if the call stopped because
 *   [OperationalEventOutboxBatchReplayRequest.maximumEntries] was reached
 *   while candidates or scopes were still unexamined; calling again continues,
 *   since replayed entries are no longer candidates.
 */
public data class OperationalEventOutboxBatchReplayResult(
    public val replayed: List<OperationalEventOutboxScopedEntry>,
    public val denied: Int,
    public val notReplayable: Int,
    public val failures: List<OperationalEventOutboxBatchReplayFailure>,
    public val budgetExhausted: Boolean,
)
