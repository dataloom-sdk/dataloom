package io.dataloom.governance.audit

import io.dataloom.api.security.DataLoomHmacCalculator

/** Why an audit chain failed verification. */
public enum class AuditVerificationFailure {
    /**
     * A record's sequence is not its position in the chain. Caused by a deleted
     * record (including a deleted first record), reordered records, or a
     * duplicated record.
     */
    SEQUENCE_MISMATCH,

    /** A record's stored previous MAC is not the preceding record's MAC. */
    PREVIOUS_LINK_MISMATCH,

    /**
     * A record's MAC does not verify under the supplied key. Caused by a
     * modified record or MAC, or by verifying with the wrong key; the two are
     * indistinguishable by design.
     */
    MAC_MISMATCH,

    /**
     * The chain is shorter than the supplied anchor: records at or before the
     * anchor's sequence are missing (tail truncation).
     */
    ANCHOR_TRUNCATED,

    /**
     * The record at the anchor's sequence has a different MAC than the anchor:
     * history before the anchor was rewritten (for example the whole chain
     * was replaced by a validly re-keyed or forked chain).
     */
    ANCHOR_MISMATCH,
}

/** Outcome of [AuditChainVerifier.verify]. */
public sealed interface AuditVerificationResult {

    /**
     * Every record verified.
     *
     * @property recordCount number of records verified.
     * @property head anchor for the last record, or `null` for an empty chain.
     *   Hosts store this externally to detect later truncation.
     * @property anchorVerified `true` only if an anchor was supplied and the
     *   chain contains it intact. When `false` because no anchor was supplied,
     *   tail truncation was NOT checked: a chain with its newest records
     *   removed is still a valid shorter chain.
     */
    public data class Valid(
        public val recordCount: Int,
        public val head: AuditAnchor?,
        public val anchorVerified: Boolean,
    ) : AuditVerificationResult

    /**
     * Verification failed.
     *
     * @property failure the first failure found.
     * @property recordIndex zero-based position in the supplied list where the
     *   failure was detected, or `null` for [AuditVerificationFailure.ANCHOR_TRUNCATED].
     */
    public data class Invalid(
        public val failure: AuditVerificationFailure,
        public val recordIndex: Int?,
    ) : AuditVerificationResult
}

/**
 * Offline verifier for an audit chain. It needs only the records, the key, and
 * (optionally) an external [AuditAnchor]: no store, no network, no clock.
 *
 * ## Checks, in order, for the record at position `i`
 *
 * 1. `sequence == i` (detects deletion, reordering, duplication).
 * 2. `previousMac` equals the MAC of the record at `i - 1` (absent for `i == 0`).
 * 3. The record MAC verifies under the key, via
 *    [DataLoomHmacCalculator.verify] (constant-time), over the canonical
 *    encoding of the record's own fields (detects modification and wrong key).
 *
 * The first failing check is reported. The chain is always verified from
 * sequence 0; verifying a retention-trimmed suffix is out of scope for V1.
 *
 * ## Anchor
 *
 * With an anchor, the chain must additionally contain a record at the anchor's
 * sequence whose MAC equals the anchor's. A chain that is longer than the
 * anchor (records appended since the anchor was taken) is valid; a chain that
 * is shorter is [AuditVerificationFailure.ANCHOR_TRUNCATED]. The anchor
 * comparison is an ordinary equality check: it runs only after every record MAC
 * has verified under the key, and an anchor is not a secret.
 *
 * ## Key handling
 *
 * [verify] takes the key per call and never retains it.
 */
public class AuditChainVerifier(
    private val hmacCalculator: DataLoomHmacCalculator,
) {

    /**
     * Verifies [records] under [key], optionally against [anchor].
     *
     * @throws IllegalArgumentException if [key] is empty.
     */
    public fun verify(
        records: List<AuditRecord>,
        key: ByteArray,
        anchor: AuditAnchor? = null,
    ): AuditVerificationResult {
        require(key.isNotEmpty()) { "key must not be empty." }

        var previous: AuditRecord? = null
        for ((index, record) in records.withIndex()) {
            if (record.sequence != index.toLong()) {
                return AuditVerificationResult.Invalid(AuditVerificationFailure.SEQUENCE_MISMATCH, index)
            }
            // Ordinary equality is fine here: both sides are untrusted, attacker-visible
            // stored values, so there is no secret for a timing side channel to reveal.
            // Authenticity is decided by the constant-time MAC verification below.
            if (record.previousMac != previous?.mac) {
                return AuditVerificationResult.Invalid(AuditVerificationFailure.PREVIOUS_LINK_MISMATCH, index)
            }
            val authenticated = AuditCanonicalEncoding.encode(
                sequence = record.sequence,
                recordedAt = record.recordedAt,
                previousMac = record.previousMac,
                event = record.event,
            )
            if (!hmacCalculator.verify(key, authenticated, record.mac)) {
                return AuditVerificationResult.Invalid(AuditVerificationFailure.MAC_MISMATCH, index)
            }
            previous = record
        }

        var anchorVerified = false
        if (anchor != null) {
            if (anchor.sequence >= records.size.toLong()) {
                return AuditVerificationResult.Invalid(AuditVerificationFailure.ANCHOR_TRUNCATED, null)
            }
            val anchorIndex = anchor.sequence.toInt()
            if (records[anchorIndex].mac != anchor.mac) {
                return AuditVerificationResult.Invalid(AuditVerificationFailure.ANCHOR_MISMATCH, anchorIndex)
            }
            anchorVerified = true
        }

        return AuditVerificationResult.Valid(
            recordCount = records.size,
            head = records.lastOrNull()?.toAnchor(),
            anchorVerified = anchorVerified,
        )
    }
}
