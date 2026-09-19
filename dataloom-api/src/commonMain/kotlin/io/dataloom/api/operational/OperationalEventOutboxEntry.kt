package io.dataloom.api.operational

import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.time.DataLoomInstant
import kotlin.jvm.JvmInline

/**
 * The key a [DurableOperationalEventOutbox] assigns durable sequence numbers
 * under (FR-EVENT-003). Entries sharing a key are totally ordered by their
 * [OperationalEventOutboxEntry.sequence]; entries with different keys have no
 * ordering relationship beyond their shared position in the append-ordered
 * retained list.
 *
 * An [OperationalEventEnvelope] with a [OperationalEventEnvelope.workflowId]
 * is keyed by that workflow. An envelope without one is keyed by [Global], the
 * single documented default ordering key -- so every workflow-less event in a
 * scope (administration commands, strategy decisions, ...) is still
 * totally ordered relative to the others, just not relative to any workflow.
 *
 * Workflow keys are namespaced (`workflow:<id>`) so a workflow whose id
 * happens to read `global` can never collide with [Global].
 */
@JvmInline
public value class OperationalEventOrderingKey(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "OperationalEventOrderingKey must not be blank." }
    }

    override fun toString(): String = value

    public companion object {
        /** The default ordering key for envelopes that carry no workflow id. */
        public val Global: OperationalEventOrderingKey = OperationalEventOrderingKey("global")

        /** The ordering key for [workflowId], or [Global] when [workflowId] is `null`. */
        public fun forWorkflow(workflowId: WorkflowId?): OperationalEventOrderingKey =
            if (workflowId == null) Global else OperationalEventOrderingKey("workflow:${workflowId.value}")
    }
}

/**
 * One durably persisted outbox entry: an [OperationalEventEnvelope] plus the
 * outbox-owned metadata that makes ordering and replay possible.
 *
 * @param sequence durable, monotonically increasing position of this entry
 *   among every entry ever appended under the same [orderingKey] in its scope,
 *   assigned by [DurableOperationalEventOutbox.append] inside the same
 *   compare-and-set write that persists the entry. Gaps are possible (an
 *   entry evicted by retention leaves its sequence unused forever); a
 *   sequence is never assigned twice under one key. Starts at `1`.
 * @param envelope the appended event.
 * @param acknowledgedAt when [DurableOperationalEventOutbox.acknowledge]
 *   marked this entry done, or `null` while it is still pending. An
 *   acknowledged entry is a retained tombstone, not a deletion: it stays
 *   readable and replayable until acknowledged-history retention prunes it.
 */
public data class OperationalEventOutboxEntry(
    public val sequence: Long,
    public val envelope: OperationalEventEnvelope,
    public val acknowledgedAt: DataLoomInstant? = null,
) {
    init {
        require(sequence >= 1L) { "OperationalEventOutboxEntry sequence must be at least 1, but was $sequence." }
    }

    /** The key [sequence] is assigned under. */
    public val orderingKey: OperationalEventOrderingKey
        get() = OperationalEventOrderingKey.forWorkflow(envelope.workflowId)

    /** `true` once [DurableOperationalEventOutbox.acknowledge] has marked this entry done and no [DurableOperationalEventOutbox.replay] has reopened it. */
    public val isAcknowledged: Boolean
        get() = acknowledgedAt != null
}

/**
 * Durable `TState` persisted per [OperationalEventOutboxScope]: every retained
 * [OperationalEventOutboxEntry] -- pending and acknowledged -- in append
 * order (oldest first), plus the sequence-assignment bookkeeping that must
 * outlive the entries themselves.
 *
 * ## Invariants
 *
 * Validated on construction, so a corrupt persisted payload fails to decode
 * instead of silently reordering events:
 *
 * - Entry ids are unique.
 * - Within one [OperationalEventOrderingKey], sequences strictly increase in
 *   list order. Together with [DurableOperationalEventOutbox.append] only ever
 *   appending at the end, this makes list order and per-key sequence order
 *   the same thing.
 * - [sequenceHighWaterMarks] holds, for every key with a retained entry, a
 *   value at least as large as that key's largest retained sequence.
 *
 * ## Why the high-water marks are persisted separately
 *
 * Deriving the next sequence from the largest retained one would reuse a
 * sequence whenever retention or acknowledged-history pruning had just
 * removed that key's newest entries. [sequenceHighWaterMarks] remembers the
 * largest sequence ever assigned per key, and [sequenceFloor] bounds that
 * memory: when the marks of keys with no retained entries would exceed the
 * outbox's tracked-key limit they are dropped and [sequenceFloor] is raised
 * to the largest dropped mark. A key with no mark starts above
 * [sequenceFloor], so a dropped key can never be assigned an already-used
 * sequence. The cost of the bound is only a larger initial gap for such a
 * key, which the ordering contract already allows.
 */
public data class OperationalEventOutboxState(
    public val entries: List<OperationalEventOutboxEntry>,
    public val sequenceHighWaterMarks: Map<OperationalEventOrderingKey, Long> = highWaterMarksOf(entries),
    public val sequenceFloor: Long = 0L,
) {
    init {
        require(sequenceFloor >= 0L) { "OperationalEventOutboxState sequenceFloor must not be negative." }
        require(sequenceHighWaterMarks.values.all { it >= 1L }) {
            "OperationalEventOutboxState sequence high-water marks must be at least 1."
        }
        val ids = HashSet<OperationalEventId>()
        val lastSequenceByKey = HashMap<OperationalEventOrderingKey, Long>()
        entries.forEach { entry ->
            require(ids.add(entry.envelope.id)) {
                "OperationalEventOutboxState entries must have unique envelope ids."
            }
            val key = entry.orderingKey
            val previous = lastSequenceByKey.put(key, entry.sequence)
            require(previous == null || previous < entry.sequence) {
                "OperationalEventOutboxState sequences must strictly increase per ordering key."
            }
            val mark = sequenceHighWaterMarks[key]
            require(mark != null && mark >= entry.sequence) {
                "OperationalEventOutboxState high-water mark must cover every retained sequence."
            }
        }
    }
}

/** Largest retained sequence per ordering key -- the default marks for a state built from entries alone. */
internal fun highWaterMarksOf(entries: List<OperationalEventOutboxEntry>): Map<OperationalEventOrderingKey, Long> {
    val marks = LinkedHashMap<OperationalEventOrderingKey, Long>()
    entries.forEach { entry ->
        val key = entry.orderingKey
        val current = marks[key]
        if (current == null || entry.sequence > current) marks[key] = entry.sequence
    }
    return marks
}

/** The next sequence for [key] in [state]: one above its high-water mark, or one above [OperationalEventOutboxState.sequenceFloor] for an untracked key. */
internal fun nextSequenceFor(state: OperationalEventOutboxState, key: OperationalEventOrderingKey): Long =
    (state.sequenceHighWaterMarks[key] ?: state.sequenceFloor) + 1L

/**
 * Bounds [marks] to [maximumTrackedKeys] by dropping the marks of keys with no
 * retained entry in [entries], lowest mark first (ties broken by key value, so
 * the result is deterministic), raising [floor] to the largest dropped mark so
 * no dropped key can be assigned a used sequence again. Returns the inputs
 * unchanged when already within the bound or when no droppable key exists.
 */
internal fun boundSequenceTracking(
    entries: List<OperationalEventOutboxEntry>,
    marks: Map<OperationalEventOrderingKey, Long>,
    floor: Long,
    maximumTrackedKeys: Int,
): Pair<Map<OperationalEventOrderingKey, Long>, Long> {
    if (marks.size <= maximumTrackedKeys) return marks to floor
    val retainedKeys = entries.mapTo(HashSet()) { it.orderingKey }
    val dropped = marks.entries
        .filter { it.key !in retainedKeys }
        .sortedWith(compareBy({ it.value }, { it.key.value }))
        .take(marks.size - maximumTrackedKeys)
    if (dropped.isEmpty()) return marks to floor
    val droppedKeys = dropped.mapTo(HashSet()) { it.key }
    return marks.filterKeys { it !in droppedKeys } to maxOf(floor, dropped.maxOf { it.value })
}
