package io.dataloom.governance.audit

import io.dataloom.api.security.DataLoomHmacCalculator
import io.dataloom.api.security.DataLoomMac
import io.dataloom.api.security.HmacAlgorithm
import io.dataloom.api.time.DataLoomClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Append-only, tamper-evident audit log: an [AuditStore] plus the HMAC chain
 * that makes it verifiable.
 *
 * Each [append] reads the current head, stamps the record from the injected
 * [clock], computes the record MAC over the canonical encoding (which includes
 * the head's MAC), and appends. Appends within one [AuditLog] are serialized;
 * a second writer on the same store loses cleanly through
 * [AuditAppendRejection.HEAD_CONFLICT].
 *
 * ## Key custody
 *
 * The [key] is host-supplied and host-owned, exactly like the underlying
 * [DataLoomHmacCalculator] contract: DataLoom does no key management, rotation,
 * or storage. The log keeps a private copy in memory for its own lifetime,
 * never renders it, and never persists it. Anyone holding the key can forge a
 * consistent chain, which is why an [AuditAnchor] kept outside the store (and
 * ideally outside the key holder's control) is the defense against wholesale
 * replacement and tail truncation.
 *
 * ## What is and is not detected
 *
 * With only the records and the key, verification detects modification of any
 * record, reordering, deletion of any record except the newest ones, and use of
 * the wrong key. Deletion of the newest records (tail truncation) is detected
 * only when an anchor is supplied to [verify].
 */
public class AuditLog(
    private val store: AuditStore,
    private val hmacCalculator: DataLoomHmacCalculator,
    private val clock: DataLoomClock,
    key: ByteArray,
) {
    private val key: ByteArray = key.copyOf()
    private val verifier = AuditChainVerifier(hmacCalculator)
    private val appendMutex = Mutex()

    init {
        require(key.isNotEmpty()) { "key must not be empty." }
    }

    /**
     * Appends [event] and returns the committed record.
     *
     * @throws AuditAppendRejectedException if the store is full or another
     *   writer advanced the head first. Nothing is recorded in that case, so
     *   callers whose action must be audited should fail closed.
     */
    public suspend fun append(event: AuditEvent): AuditRecord = appendMutex.withLock {
        val head = store.head()
        val sequence = if (head == null) 0L else head.sequence + 1L
        val previousMac = head?.mac
        val recordedAt = clock.now()
        val mac = hmacCalculator.hmac(
            MAC_ALGORITHM,
            key,
            AuditCanonicalEncoding.encode(sequence, recordedAt, previousMac, event),
        )
        val record = AuditRecord(sequence, recordedAt, previousMac, event, mac)
        store.append(record)
        record
    }

    /** Anchor for the current head, or `null` if the log is empty. */
    public suspend fun anchor(): AuditAnchor? = store.head()?.toAnchor()

    /**
     * Verifies the whole stored chain with this log's key, optionally against
     * an externally held [anchor]. See [AuditChainVerifier].
     */
    public suspend fun verify(anchor: AuditAnchor? = null): AuditVerificationResult =
        verifier.verify(store.readAll(), key, anchor)

    override fun toString(): String = "AuditLog(store=$store)"

    public companion object {
        /** The only MAC algorithm the V1 audit chain uses. */
        public val MAC_ALGORITHM: HmacAlgorithm = HmacAlgorithm.HMAC_SHA_256
    }
}
