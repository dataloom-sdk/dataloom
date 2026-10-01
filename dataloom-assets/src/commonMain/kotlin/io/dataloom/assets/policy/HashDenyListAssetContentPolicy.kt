package io.dataloom.assets.policy

import io.dataloom.api.security.DataLoomDigestCalculator
import io.dataloom.api.security.DigestAlgorithm
import io.dataloom.api.time.DataLoomClock
import io.dataloom.assets.AssetContentPolicy
import io.dataloom.assets.AssetContentPolicyContext
import io.dataloom.assets.AssetContentPolicyDecision

/** What a [AssetContentDenyListEntry] match does to the transfer. */
public enum class AssetContentMatchAction {
    /** Refuse outright: [AssetContentPolicyDecision.Deny]. */
    DENY,

    /** Hold for review: [AssetContentPolicyDecision.Quarantine]. */
    QUARANTINE,
}

/**
 * One known-bad chunk digest and what matching it does.
 *
 * @param digestHex the deny-listed digest's lowercase hex rendering, under
 *   [HashDenyListAssetContentPolicy.algorithm]. Matching is exact (this is a
 *   known-hash blocklist, not a similarity or perceptual match), the same
 *   technique production malware-signature and known-content blocklists
 *   (for example hash-set lookups against `NIST NSRL` or antivirus exact-hash
 *   signatures) use for bytes that are already known bad, rather than
 *   classifying unknown content.
 * @param action what a match does.
 * @param reasonCode becomes [AssetContentPolicyDecision.Deny.reasonCode] or
 *   [AssetContentPolicyDecision.Quarantine.reasonCode], so it is bounded
 *   identically (see that type's own `init` checks).
 */
public data class AssetContentDenyListEntry(
    public val digestHex: String,
    public val action: AssetContentMatchAction,
    public val reasonCode: String,
) {
    init {
        require(digestHex.isNotBlank()) { "AssetContentDenyListEntry digestHex must not be blank." }
        require(reasonCode.isNotBlank()) { "AssetContentDenyListEntry reasonCode must not be blank." }
    }
}

/**
 * Reference, production-usable [AssetContentPolicy]: an exact-match
 * known-bad-hash deny list, evaluated per chunk over the chunk's verified
 * logical bytes exactly as [AssetContentPolicy] is invoked.
 *
 * ## Why per-chunk hashing, not whole-object
 *
 * [AssetContentPolicyContext] carries no "this is the last chunk" or total
 * chunk-count signal, and [AssetContentPolicy]'s own class doc is explicit
 * that accumulating cross-chunk state for a whole-object decision is a
 * policy's own responsibility, not something this engine does bookkeeping
 * for. Without an end-of-object signal, a whole-object exact-hash check
 * cannot honestly be implemented inside one `evaluate` call, so this
 * reference implementation deliberately checks only the digest of the chunk
 * it was actually given -- for a single-chunk asset (common for small
 * files: avatars, documents, thumbnails) that *is* the whole-object digest;
 * for a multi-chunk asset it catches a deny-listed *chunk* (for example a
 * known-bad fixed-size block reused across assets), which is itself a real
 * production technique, not a simplification invented for this slice.
 *
 * ## Building your own policy
 *
 * A real deployment swaps the matching strategy, not the wiring: implement
 * [AssetContentPolicy.evaluate] with a call to a virus scanner or an ML
 * classifier instead of a digest lookup, and, on a decision to quarantine,
 * write the same kind of evidence to a [DurableAssetContentQuarantineLog] (or
 * your own store) that this class does below. Nothing here depends on
 * hash-matching being the detection mechanism -- the deny-list and the
 * quarantine store are two independently swappable pieces.
 *
 * ## Quarantine is recorded, not just decided
 *
 * When an entry's [AssetContentDenyListEntry.action] is
 * [AssetContentMatchAction.QUARANTINE], this policy durably records the
 * match via [quarantineLog] (when one is configured) *before* returning the
 * decision, so the engine's resulting terminal failure is never the only
 * trace the quarantine ever happened. A [quarantineLog] write failure (a
 * [io.dataloom.assets.policy.AssetContentQuarantineRecordOutcome.PersistenceFailure]
 * or [io.dataloom.assets.policy.AssetContentQuarantineRecordOutcome.ContentionLimitReached])
 * does not change the returned decision -- the content is still quarantined
 * either way, fail-closed on the transfer -- it is surfaced only through
 * [lastQuarantineRecordOutcome] for a host that wants to monitor it.
 *
 * @param digests computes each chunk's digest under [algorithm].
 * @param algorithm the digest algorithm [denyList] entries are keyed under.
 * @param denyList known-bad digests, keyed by [AssetContentDenyListEntry.digestHex].
 *   Looked up by the hex digest of each evaluated chunk.
 * @param quarantineLog optional durable store a [AssetContentMatchAction.QUARANTINE]
 *   match is recorded to. `null` (the default) still quarantines the
 *   transfer (the engine still fails it) but records nothing durably --
 *   equivalent to the bare SPI's own deferred scope.
 * @param clock supplies the instant recorded as
 *   [AssetContentQuarantineRecord.quarantinedAt]. Only read when a quarantine
 *   match is actually recorded.
 */
public class HashDenyListAssetContentPolicy(
    private val digests: DataLoomDigestCalculator,
    private val algorithm: DigestAlgorithm = DigestAlgorithm.SHA_256,
    denyList: Collection<AssetContentDenyListEntry>,
    private val quarantineLog: DurableAssetContentQuarantineLog? = null,
    private val clock: DataLoomClock? = null,
) : AssetContentPolicy {

    init {
        require(quarantineLog == null || clock != null) {
            "HashDenyListAssetContentPolicy requires a clock when a quarantineLog is configured."
        }
    }

    private val denyListByDigest: Map<String, AssetContentDenyListEntry> =
        denyList.associateBy { it.digestHex.lowercase() }

    /**
     * The outcome of the most recent [quarantineLog] write this policy made,
     * or `null` if none has happened yet. Observational only -- it does not
     * change [evaluate]'s returned decision; see this class's own class doc.
     */
    public var lastQuarantineRecordOutcome: AssetContentQuarantineRecordOutcome? = null
        private set

    override suspend fun evaluate(context: AssetContentPolicyContext, chunk: ByteArray): AssetContentPolicyDecision {
        val digestHex = digests.digest(algorithm, chunk).toHex()
        val entry = denyListByDigest[digestHex] ?: return AssetContentPolicyDecision.Allow
        return when (entry.action) {
            AssetContentMatchAction.DENY -> AssetContentPolicyDecision.Deny(entry.reasonCode)
            AssetContentMatchAction.QUARANTINE -> {
                recordQuarantine(context, digestHex, entry.reasonCode)
                AssetContentPolicyDecision.Quarantine(entry.reasonCode)
            }
        }
    }

    private suspend fun recordQuarantine(context: AssetContentPolicyContext, digestHex: String, reasonCode: String) {
        val log = quarantineLog ?: return
        val now = checkNotNull(clock) { "unreachable: constructor requires a clock when quarantineLog is set" }.now()
        val record = AssetContentQuarantineRecord(
            sessionId = context.sessionId,
            assetId = context.assetId,
            version = context.version,
            mediaType = context.mediaType,
            direction = context.direction,
            chunkIndex = context.chunkIndex,
            reasonCode = reasonCode,
            matchedDigestHex = digestHex,
            quarantinedAt = now,
        )
        lastQuarantineRecordOutcome = log.record(record)
    }
}
