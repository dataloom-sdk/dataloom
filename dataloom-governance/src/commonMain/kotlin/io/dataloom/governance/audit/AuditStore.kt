package io.dataloom.governance.audit

/** Why an [AuditStore] refused an append. */
public enum class AuditAppendRejection {
    /** The store is at its bounded capacity. Records are never silently dropped or overwritten. */
    CAPACITY_EXCEEDED,

    /**
     * The record does not extend the store's current head: its sequence is not
     * the next one, or its previous MAC is not the head's MAC. A concurrent
     * writer got there first; the caller may re-read the head and retry.
     */
    HEAD_CONFLICT,
}

/** Thrown by [AuditStore.append] when the record is refused. */
public class AuditAppendRejectedException(
    public val rejection: AuditAppendRejection,
    message: String,
) : IllegalStateException(message)

/**
 * Append-only storage port for audit records.
 *
 * The contract is intentionally narrow: there is no update, no delete, and no
 * overwrite. A store never verifies MACs (it has no key); it only enforces that
 * each appended record extends the current head, so two racing writers cannot
 * both succeed. Authenticity is [AuditChainVerifier]'s job.
 *
 * Only an in-memory implementation ([InMemoryAuditStore]) ships in this slice.
 * Durable persistence through the durable-state contract is a later slice.
 */
public interface AuditStore {

    /** The newest record, or `null` when the store is empty. */
    public suspend fun head(): AuditRecord?

    /** All records in sequence order. The returned list is a snapshot. */
    public suspend fun readAll(): List<AuditRecord>

    /**
     * Appends [record], which must extend [head] (sequence `head.sequence + 1`
     * and `previousMac == head.mac`, or sequence 0 with no previous MAC on an
     * empty store).
     *
     * @throws AuditAppendRejectedException if the store is full or [record] does
     *   not extend the head.
     */
    public suspend fun append(record: AuditRecord)
}
