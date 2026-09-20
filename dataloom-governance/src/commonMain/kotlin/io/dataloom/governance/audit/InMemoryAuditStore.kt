package io.dataloom.governance.audit

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Bounded, in-memory [AuditStore]. State is lost when the process ends, so it
 * is suitable for tests, short-lived tools, and as the reference for durable
 * implementations; it is not durable audit storage.
 *
 * When [capacity] records are held, further appends are refused with
 * [AuditAppendRejection.CAPACITY_EXCEEDED]. Nothing is dropped or overwritten:
 * an audit trail that silently forgets is worse than one that fails closed.
 *
 * Thread-safe: all operations are serialized by an internal mutex, and no
 * caller code runs while it is held.
 */
public class InMemoryAuditStore(
    public val capacity: Int = DEFAULT_CAPACITY,
) : AuditStore {

    private val mutex = Mutex()
    private val records = ArrayList<AuditRecord>()

    init {
        require(capacity > 0) { "InMemoryAuditStore capacity must be positive, but was $capacity." }
    }

    override suspend fun head(): AuditRecord? = mutex.withLock { records.lastOrNull() }

    override suspend fun readAll(): List<AuditRecord> = mutex.withLock { records.toList() }

    override suspend fun append(record: AuditRecord) {
        mutex.withLock {
            val head = records.lastOrNull()
            val expectedSequence = if (head == null) 0L else head.sequence + 1L
            if (record.sequence != expectedSequence || record.previousMac != head?.mac) {
                throw AuditAppendRejectedException(
                    AuditAppendRejection.HEAD_CONFLICT,
                    "Audit record does not extend the store head (expected sequence $expectedSequence).",
                )
            }
            if (records.size >= capacity) {
                throw AuditAppendRejectedException(
                    AuditAppendRejection.CAPACITY_EXCEEDED,
                    "Audit store is at its capacity of $capacity records.",
                )
            }
            records.add(record)
        }
    }

    override fun toString(): String = "InMemoryAuditStore(capacity=$capacity)"

    public companion object {
        /** Default bound on the number of records held. */
        public const val DEFAULT_CAPACITY: Int = 10_000
    }
}
