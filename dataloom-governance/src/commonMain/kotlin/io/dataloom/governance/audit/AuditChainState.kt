package io.dataloom.governance.audit

/**
 * Durable `TState` persisted per [AuditStoreScope]: the entire hash-chained
 * audit trail for that scope, oldest first.
 *
 * ## Why the whole chain is one state value
 *
 * This follows the same shape
 * [io.dataloom.api.operational.OperationalEventOutboxState] already
 * establishes for a growing, durably-persisted, ordered list: one
 * [io.dataloom.api.state.DurableStateStore] scope holds the complete ordered
 * list, rewritten (and re-encoded) in full on every
 * [io.dataloom.api.state.DurableStateStore.compareAndSet]. For the audit
 * chain specifically, keeping every record under one known scope also means
 * [DurableAuditStore.head] and [DurableAuditStore.readAll] are answered
 * directly by [io.dataloom.api.state.DurableStateStore.load] -- no probing
 * across per-record scopes to find the current head, which is exactly the
 * "per-record layout... freezes a layout before retention/overflow/delivery
 * are decided" concern ADR-0010 raised about durable audit persistence.
 *
 * ## Retention: none. A bounded safety limit instead
 *
 * Unlike this codebase's other durable logs (the operational outbox, the
 * conflict quarantine log), [records] is never pruned: an audit trail that
 * silently forgets entries defeats its own purpose, the same reasoning
 * [InMemoryAuditStore] already documents for refusing rather than evicting
 * once its capacity is reached. There is deliberately no age- or
 * count-based eviction here.
 *
 * What bounds growth instead is the same posture every other
 * [io.dataloom.api.state.DurableStateCodec] in this codebase takes toward its
 * own encoded payload: a fixed maximum record count ([MAX_RECORD_COUNT], the
 * same order of magnitude as
 * [io.dataloom.api.operational.OperationalEventOutboxStateCodec.MAX_ENTRY_COUNT])
 * and, transitively through [AuditChainStateCodec], a maximum encoded length.
 * Once either is reached, further appends fail closed
 * ([AuditAppendRejection.CAPACITY_EXCEEDED] from [DurableAuditStore.append])
 * rather than growing without bound or silently dropping history. Raising the
 * limit, rotating to a new [AuditStoreScope], or archiving old chains
 * elsewhere are host decisions this slice does not make for them.
 *
 * ## Invariants
 *
 * Validated on construction, so a corrupt persisted payload fails to decode
 * instead of silently presenting a reordered or gapped chain: [records] must
 * be contiguous starting at sequence `0` (matching list position) and each
 * record's [AuditRecord.previousMac] must equal the preceding record's
 * [AuditRecord.mac] (`null` only for the first). This mirrors the exact
 * extend-the-head check [InMemoryAuditStore.append] already performs, just
 * applied to the whole list at once rather than one append at a time. It is a
 * *structural* check only -- it does not verify any MAC cryptographically;
 * that remains [AuditChainVerifier]'s job.
 */
public data class AuditChainState(
    public val records: List<AuditRecord>,
) {
    init {
        require(records.size <= MAX_RECORD_COUNT) {
            "AuditChainState must not exceed $MAX_RECORD_COUNT records, but had ${records.size}."
        }
        records.forEachIndexed { index, record ->
            require(record.sequence == index.toLong()) {
                "AuditChainState records must be contiguous from sequence 0 with no gaps, reordering, or duplicates " +
                    "(record at list position $index has sequence ${record.sequence})."
            }
            val expectedPreviousMac = records.getOrNull(index - 1)?.mac
            require(record.previousMac == expectedPreviousMac) {
                "AuditChainState record at sequence $index must link to the preceding record's mac."
            }
        }
    }

    /** The newest record, or `null` for an empty chain. */
    public val head: AuditRecord?
        get() = records.lastOrNull()

    public companion object {
        /** The empty chain: no records ever appended. */
        public val Empty: AuditChainState = AuditChainState(emptyList())

        /**
         * Hard ceiling on the number of records one [AuditStoreScope] may hold.
         * A safety valve, not a retention policy -- see this class's "Retention"
         * documentation. Chosen to match
         * [io.dataloom.api.operational.OperationalEventOutboxStateCodec.MAX_ENTRY_COUNT]'s
         * order of magnitude for the same "growing list persisted as one state
         * value" shape.
         */
        public const val MAX_RECORD_COUNT: Int = 10_000
    }
}
