package io.dataloom.api.queue

/**
 * Sealed result returned by [io.dataloom.api.provider.QueueProvider.acquire].
 *
 * A [QueueAcquireResult] is either [NoEntries] when no eligible queue entries
 * are currently available, or [Entries] when the provider atomically acquired
 * one or more entries and assigned them an exclusive lease.
 *
 * ## Atomic acquisition
 *
 * Every returned [QueueEntry] in an [Entries] result was acquired atomically
 * alongside its lease assignment. The provider must not return entries and
 * assign the lease in separate steps.
 *
 * ## Variants
 *
 * - [NoEntries] — no currently eligible queue entries were available for
 *   acquisition.
 * - [Entries] — one or more entries were atomically acquired and leased.
 */
public sealed interface QueueAcquireResult {

    /**
     * Indicates that no currently eligible queue entries were available for
     * acquisition at the time of the request.
     *
     * A consumer may retry acquisition after an appropriate delay or wait for
     * a future scheduling trigger.
     */
    public data object NoEntries : QueueAcquireResult

    /**
     * Indicates that one or more queue entries were atomically acquired and
     * assigned an exclusive lease.
     *
     * ## Lease contract
     *
     * The [lease] is the single exclusive lease assigned to all entries in this
     * acquisition batch. Every entry in [entries] must be in
     * [QueueEntryState.LEASED] state and must carry a [QueueEntry.lease] equal
     * to this result's [lease].
     *
     * The provider constructs the lease from the corresponding
     * [QueueAcquireRequest] fields: [QueueAcquireRequest.leaseId],
     * [QueueAcquireRequest.consumerId], [QueueAcquireRequest.acquiredAt], and
     * [QueueAcquireRequest.leaseExpiresAt].
     *
     * ## Order
     *
     * The [entries] list preserves the order returned by the provider.
     * Priority, fairness, and scheduling policy are owned by the DataLoom
     * runtime, not the provider.
     *
     * ## Constraints
     *
     * - [lease] is required.
     * - [entries] must contain at least one entry.
     * - Empty collections are rejected.
     * - Every entry must be in [QueueEntryState.LEASED] state.
     * - Every entry's [QueueEntry.lease] must equal [lease].
     * - The source collection is defensively copied.
     * - No mutable collection is exposed.
     *
     * @param lease required exclusive lease assigned to all entries in this
     *   batch.
     * @param entries source list of acquired queue entries. Must be non-empty.
     *   All entries must be in [QueueEntryState.LEASED] state and must carry
     *   a [QueueEntry.lease] equal to [lease].
     */
    public class Entries(
        /** Required exclusive lease assigned to all acquired entries. */
        public val lease: QueueLease,
        entries: List<QueueEntry>,
    ) : QueueAcquireResult {

        /** Immutable snapshot of acquired queue entries in provider-returned order. */
        public val entries: List<QueueEntry> = entries.toList()

        init {
            require(this.entries.isNotEmpty()) {
                "QueueAcquireResult.Entries must contain at least one entry."
            }
            this.entries.forEachIndexed { index, entry ->
                require(entry.state == QueueEntryState.LEASED) {
                    "QueueAcquireResult.Entries: entry at index $index must be in LEASED state, " +
                        "but was ${entry.state}."
                }
                require(entry.lease == lease) {
                    "QueueAcquireResult.Entries: entry at index $index must carry the result lease."
                }
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Entries) return false
            return lease == other.lease && entries == other.entries
        }

        override fun hashCode(): Int {
            var result = lease.hashCode()
            result = 31 * result + entries.hashCode()
            return result
        }

        override fun toString(): String = "QueueAcquireResult.Entries(lease=$lease, entries=$entries)"
    }
}
