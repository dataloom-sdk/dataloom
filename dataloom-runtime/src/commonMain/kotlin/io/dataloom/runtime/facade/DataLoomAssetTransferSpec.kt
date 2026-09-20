package io.dataloom.runtime.facade

import io.dataloom.api.security.DataLoomIncrementalDigestCalculator
import io.dataloom.api.security.DigestAlgorithm
import io.dataloom.assets.AssetChunkSizeBounds
import io.dataloom.assets.AssetIntegrityVerifier
import io.dataloom.assets.AssetProvider
import io.dataloom.assets.AssetTransferSessionStore

/**
 * Immutable configuration for the optional asset-transfer capability.
 *
 * Supplying this spec to [DataLoomBuilder.assetTransferConfiguration] makes
 * [DataLoom.assetTransfer] non-null after [DataLoomBuilder.build]. Omitting it
 * leaves [DataLoom] behavior unchanged and [DataLoom.assetTransfer] `null`.
 *
 * ## Ownership
 *
 * Every collaborator is an explicit host or platform dependency. The builder
 * only constructs the engine over them: it performs no provider I/O, reads no
 * session, and does not initialize, health-check, or close [provider].
 * [io.dataloom.assets.AssetProvider] is not yet a
 * [io.dataloom.api.provider.DataLoomProvider] (there is no asset
 * `ProviderType` or lifecycle slot), so the host owns its lifecycle; that
 * follows in a later slice.
 *
 * ## Durability
 *
 * [sessionStore] decides whether an interrupted transfer survives a restart.
 * Pass a [io.dataloom.assets.DurableAssetTransferSessionStore] over a
 * platform durable store for resumable-after-restart behavior, or
 * [io.dataloom.assets.InMemoryAssetTransferSessionStore] for transfers that
 * only need to survive an interruption within one process.
 */
public class DataLoomAssetTransferSpec(
    /** Where assets are uploaded to and downloaded from. */
    public val provider: AssetProvider,

    /** Where transfer-session state is recorded. */
    public val sessionStore: AssetTransferSessionStore,

    /** Digest calculator; must support incremental hashing for whole-object verification. */
    public val digestCalculator: DataLoomIncrementalDigestCalculator,

    /** Requested chunk size for new uploads, clamped into [AssetProvider.chunkSizeBounds]. */
    public val chunkSizeBytes: Int = AssetChunkSizeBounds.DEFAULT_CHUNK_SIZE_BYTES,

    /** Algorithm for the chunk and whole-object digests of new uploads. */
    public val digestAlgorithm: DigestAlgorithm = DigestAlgorithm.SHA_256,

    /** Streaming buffer size for whole-object verification. */
    public val verifyBufferBytes: Int = AssetIntegrityVerifier.DEFAULT_READ_BUFFER_BYTES,
) {
    init {
        require(chunkSizeBytes >= 1) {
            "DataLoomAssetTransferSpec chunkSizeBytes must be at least one."
        }
        require(verifyBufferBytes >= 1) {
            "DataLoomAssetTransferSpec verifyBufferBytes must be at least one."
        }
    }

    /** Avoids rendering collaborator implementation state in diagnostics. */
    override fun toString(): String =
        "DataLoomAssetTransferSpec(chunkSizeBytes=$chunkSizeBytes, digestAlgorithm=$digestAlgorithm, " +
            "verifyBufferBytes=$verifyBufferBytes)"
}
