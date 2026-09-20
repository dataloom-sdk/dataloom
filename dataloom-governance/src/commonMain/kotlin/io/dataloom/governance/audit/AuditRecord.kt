package io.dataloom.governance.audit

import io.dataloom.api.security.DataLoomMac
import io.dataloom.api.time.DataLoomInstant

/**
 * One immutable link in the tamper-evident audit chain.
 *
 * [mac] is `HMAC-SHA256(key, canonical(sequence, recordedAt, previousMac, event))`.
 * Because [previousMac] is inside the authenticated bytes, every record commits
 * to the whole history before it: changing, reordering, or removing any earlier
 * record changes every later MAC. Without the key an attacker cannot recompute
 * the chain after editing it.
 *
 * The first record has `sequence == 0` and no [previousMac]; every later record
 * has `sequence > 0` and a non-null [previousMac]. Both MACs use
 * [AuditLog.MAC_ALGORITHM].
 *
 * Constructing a record does not verify it. Only [AuditChainVerifier] (which
 * needs the key) decides whether a sequence of records is authentic.
 */
public data class AuditRecord(
    public val sequence: Long,
    public val recordedAt: DataLoomInstant,
    public val previousMac: DataLoomMac?,
    public val event: AuditEvent,
    public val mac: DataLoomMac,
) {
    init {
        require(sequence >= 0L) { "AuditRecord sequence must be zero or greater, but was $sequence." }
        require((sequence == 0L) == (previousMac == null)) {
            "AuditRecord previousMac must be absent exactly for the first record (sequence 0)."
        }
        require(mac.algorithm == AuditLog.MAC_ALGORITHM) {
            "AuditRecord mac must use ${AuditLog.MAC_ALGORITHM}."
        }
        require(previousMac == null || previousMac.algorithm == AuditLog.MAC_ALGORITHM) {
            "AuditRecord previousMac must use ${AuditLog.MAC_ALGORITHM}."
        }
    }

    /** The anchor that commits to this record and, through it, all earlier records. */
    public fun toAnchor(): AuditAnchor = AuditAnchor(sequence, mac)
}

/**
 * An externally held commitment to the audit chain's head at some point in
 * time: the [sequence] and [mac] of one record.
 *
 * Hosts persist anchors outside the store that holds the records (a different
 * device, a server, a signed log). An anchor is what makes tail truncation
 * detectable: without one, a chain with its newest records removed is still a
 * perfectly valid shorter chain. It is not secret (a MAC tag is safe to
 * store and render) but must be kept somewhere an attacker who can edit the
 * record store cannot also edit.
 */
public data class AuditAnchor(
    public val sequence: Long,
    public val mac: DataLoomMac,
) {
    init {
        require(sequence >= 0L) { "AuditAnchor sequence must be zero or greater, but was $sequence." }
        require(mac.algorithm == AuditLog.MAC_ALGORITHM) { "AuditAnchor mac must use ${AuditLog.MAC_ALGORITHM}." }
    }
}
