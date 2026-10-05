package io.dataloom.runtime.facade

import io.dataloom.api.security.DataLoomIncrementalDigestCalculator
import io.dataloom.api.security.DigestAlgorithm
import io.dataloom.assets.AssetChunkSizeBounds
import io.dataloom.assets.AssetIntegrityVerifier
import io.dataloom.assets.AssetProvider
import io.dataloom.assets.AssetTransferSessionStore
import io.dataloom.assets.transform.AssetTransferTransforms

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
 * only constructs the engine over [provider]: it performs no provider I/O and
 * reads no session during [DataLoomBuilder.build]. [provider] is itself a
 * [io.dataloom.api.provider.DataLoomProvider] of type
 * [io.dataloom.api.provider.ProviderType.ASSET] (`#97`); it is
 * lifecycle-managed (`initialize`/`health`/`close`) exactly like every other
 * provider type when the same instance is also registered with the builder
 * via `providers(...)`/`provider(...)`. Supplying it only here and never also
 * registering it leaves it un-managed, exactly as before this conformance
 * existed -- the builder never infers registration from this spec.
 *
 * ## Circuit/retry protection
 *
 * [io.dataloom.runtime.facade.DataLoomAssetProviderProtectionSpec] (set via
 * `DataLoomBuilder.assetProviderProtectionConfiguration`) wraps every call
 * [AssetTransferEngine] makes into [provider] with the same
 * [io.dataloom.runtime.retry.CircuitBreakerCoordinator]/
 * [io.dataloom.runtime.retry.CircuitBreakerExecutionGate] machinery already
 * used to protect [io.dataloom.api.storage.StorageProvider] and
 * [io.dataloom.api.transport.TransportProvider] calls (`#94`). Protection is
 * opt-in: without it, a provider failure propagates to the engine unchanged,
 * exactly as before this conformance existed.
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

    /**
     * Compression and encryption applied to uploaded chunks and reversed on
     * download; [AssetTransferTransforms.NONE] (the default) transfers chunks
     * as-is. The host supplies encryption keys through the cipher's
     * [io.dataloom.assets.transform.AssetKeyResolver]; DataLoom never stores them.
     */
    public val transforms: AssetTransferTransforms = AssetTransferTransforms.NONE,
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
            "verifyBufferBytes=$verifyBufferBytes, transforms=$transforms)"
}
