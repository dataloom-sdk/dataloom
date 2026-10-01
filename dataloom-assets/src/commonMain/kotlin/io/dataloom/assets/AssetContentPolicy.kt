package io.dataloom.assets

import io.dataloom.api.asset.AssetMediaType
import io.dataloom.api.identifier.AssetId

/**
 * Context [AssetContentPolicy.evaluate] is given for one chunk.
 *
 * Carries the already-known manifest facts for the asset the chunk belongs
 * to, not the manifest itself, so a policy implementation does not need to
 * depend on [io.dataloom.api.asset.AssetManifest]'s full shape to make a
 * decision.
 *
 * @param sessionId the transfer session the chunk belongs to.
 * @param assetId the asset being transferred.
 * @param version the asset version being transferred.
 * @param mediaType the asset's declared media type.
 * @param direction [AssetTransferDirection.UPLOAD] or [AssetTransferDirection.DOWNLOAD].
 * @param chunkIndex position of this chunk in [io.dataloom.api.asset.AssetChunkLayout.chunks].
 */
public data class AssetContentPolicyContext(
    public val sessionId: AssetTransferSessionId,
    public val assetId: AssetId,
    public val version: Long,
    public val mediaType: AssetMediaType,
    public val direction: AssetTransferDirection,
    public val chunkIndex: Int,
)

/**
 * Result of evaluating one chunk against an [AssetContentPolicy].
 *
 * Mirrors [io.dataloom.plugin.PluginLifecycleAdministrationAuthorizationDecision]'s
 * shape (a trivial allow case plus one or more bounded-reason-code refusal
 * cases) rather than inventing a new decision vocabulary: this codebase's
 * established convention for a non-trivial policy decision is a synchronous
 * (here, suspendable to allow a real scan to call out) result decided in one
 * call, never a "pending, check back later" third state. A hook that needs
 * time to decide (an external scanning service, for example) is expected to
 * await its own result inside [AssetContentPolicy.evaluate] before
 * returning -- exactly as [io.dataloom.plugin.PluginLifecycleAdministrationAuthorizer.authorize]
 * is `suspend` for the same reason. See that type's KDoc for the convention
 * this follows.
 */
public sealed interface AssetContentPolicyDecision {

    /** The chunk may proceed. */
    public data object Allow : AssetContentPolicyDecision

    /** The chunk is refused outright; the transfer fails and is cleaned up. */
    public data class Deny(public val reasonCode: String) : AssetContentPolicyDecision {
        init {
            require(reasonCode.isNotBlank()) { "AssetContentPolicyDecision.Deny reasonCode must not be blank." }
            require(reasonCode.length <= MAX_REASON_CODE_LENGTH) {
                "AssetContentPolicyDecision.Deny reasonCode must not exceed $MAX_REASON_CODE_LENGTH characters."
            }
        }
    }

    /**
     * The chunk is held for review rather than refused outright; the transfer
     * still fails and is cleaned up exactly as [Deny] does -- this engine has
     * nowhere durable to hold a "pending" asset, so quarantine is recorded as
     * a distinct, terminal [AssetErrorKind] a host can route differently (for
     * example, to a manual-review queue) from an outright [Deny], not as a
     * suspended or retryable state.
     */
    public data class Quarantine(public val reasonCode: String) : AssetContentPolicyDecision {
        init {
            require(reasonCode.isNotBlank()) { "AssetContentPolicyDecision.Quarantine reasonCode must not be blank." }
            require(reasonCode.length <= MAX_REASON_CODE_LENGTH) {
                "AssetContentPolicyDecision.Quarantine reasonCode must not exceed $MAX_REASON_CODE_LENGTH characters."
            }
        }
    }

    private companion object {
        const val MAX_REASON_CODE_LENGTH: Int = 128
    }
}

/**
 * Optional content allow/deny/scan/quarantine hook (FR-ASSET-012), evaluated
 * once per chunk at exactly the point [AssetTransferEngine] already holds the
 * chunk's verified logical (pre-transform, decompressed/decrypted) bytes --
 * the same point its own digest check and [io.dataloom.assets.transform.AssetCompressor]/
 * [io.dataloom.assets.transform.AssetChunkCipher] transforms operate, for the
 * same bounded-memory reason: a chunk's logical bytes, never the compressed
 * or encrypted wire frame a provider might store (ADR-0014, D24 applies here
 * too -- a policy inspects what the asset actually *is*, not its storage
 * encoding).
 *
 * ## Why per chunk, not once per whole object
 *
 * `FR-ASSET-012`'s acceptance criteria call for a decision "before commit or
 * exposure" -- before an upload's chunks are committed by the provider, or
 * before a download's bytes are exposed to the caller. Evaluating per chunk,
 * at the exact point this engine already reads (upload) or decodes
 * (download) one chunk, satisfies that: a [Deny] or [Quarantine] on any
 * chunk stops the transfer before that chunk (and, by extension, the whole
 * object) is ever committed or exposed, and does so without holding the
 * whole object in memory -- the one new per-object alternative this slice
 * deliberately avoids, since every other hook in this engine (digest
 * verification, compression, encryption) is already bounded to chunk-sized
 * memory and a policy that needed the whole object in memory to decide would
 * reintroduce the unbounded-memory problem [AssetTransferEngine] was built to
 * avoid. A policy that genuinely needs whole-object context (for example a
 * running hash across chunks) can accumulate its own bounded state across
 * successive [evaluate] calls for the same [AssetContentPolicyContext.sessionId]
 * -- this interface does not need to do that bookkeeping for it.
 *
 * ## Fail-closed
 *
 * [AssetTransferEngine] treats both [AssetContentPolicyDecision.Deny] and
 * [AssetContentPolicyDecision.Quarantine] as a terminal, non-recoverable
 * failure ([AssetErrorKind.CONTENT_POLICY_DENIED] /
 * [AssetErrorKind.CONTENT_POLICY_QUARANTINED]): the session is failed, and
 * the same cleanup every other terminal failure already runs executes --
 * the provider-side upload is aborted (releasing every chunk committed so
 * far, not only the refused one) or the download sink is discarded. A denied
 * or quarantined transfer never completes and never leaves committed or
 * written bytes behind.
 *
 * ## Scope
 *
 * This hook decides allow/deny/quarantine for bytes this engine already has
 * in hand. It does not implement a scanner, a quarantine *store*, an async
 * scanning pipeline with its own callback/webhook, or any notion of
 * "re-admitting" a quarantined asset later -- those are host or application
 * concerns built on top of this decision point, deliberately out of scope
 * for this slice (see the `#97` content-policy fragment for the full list of
 * what is deferred).
 *
 * `null` (the default [AssetTransferEngine.contentPolicy]) allows every
 * chunk, identical to behaviour before this hook existed.
 */
public interface AssetContentPolicy {

    /**
     * Evaluates [chunk] -- [context.chunkIndex]'s verified logical bytes --
     * and decides whether the transfer may proceed.
     *
     * Implementations must not mutate [chunk]. For an ordinary refusal,
     * return [AssetContentPolicyDecision.Deny] or
     * [AssetContentPolicyDecision.Quarantine] rather than throwing.
     * [AssetTransferEngine] does not catch exceptions from this call -- an
     * uncaught exception propagates to the caller of [AssetTransferEngine.upload]/
     * [AssetTransferEngine.download] exactly as an uncaught exception from
     * [AssetTransferObserver.onOutcome] already does (see that interface's
     * KDoc): isolating its own failures is this implementation's
     * responsibility, not this engine's.
     */
    public suspend fun evaluate(context: AssetContentPolicyContext, chunk: ByteArray): AssetContentPolicyDecision
}
