package io.dataloom.assets

/**
 * Random-access, position-based read side of an asset's bytes.
 *
 * Position-based (rather than a forward-only stream) so that a resumed
 * transfer can seek straight to the first uncommitted chunk instead of
 * re-reading everything before it, and so that verification can re-read a
 * staged asset after a restart.
 *
 * Callers never ask an [AssetReadable] for more than one chunk (or one
 * verification buffer) at a time: memory use is bounded by the chunk size,
 * never by the asset size (FR-ASSET-005).
 */
public interface AssetReadable {

    /**
     * Reads up to [length] bytes starting at [position] into
     * `destination[destinationOffset until destinationOffset + length]`.
     *
     * A short read (fewer than [length] bytes, but at least one) is allowed
     * and does not mean end of data; callers loop, or use [readFully].
     *
     * @return the number of bytes read (at least 1), or `-1` when [position]
     *   is at or beyond the end of the available bytes.
     */
    public suspend fun read(position: Long, destination: ByteArray, destinationOffset: Int, length: Int): Int
}

/** A readable asset whose total size is known up front — the input of an upload. */
public interface AssetSource : AssetReadable {

    /** Total size in bytes of the asset this source exposes. */
    public suspend fun sizeBytes(): Long
}

/**
 * A writable, read-back-able staging area for a download's bytes.
 *
 * A sink must support reading back what it holds: whole-object verification
 * and resume-after-restart re-derive the digest from the staged bytes rather
 * than persisting hash state (see [io.dataloom.api.security.DataLoomDigestAccumulator],
 * which cannot be serialised). Atomic promotion of a verified sink to its
 * final location, and private/secure temp-file placement, are later slices
 * (FR-ASSET-009); this slice only defines the write/read/discard contract.
 */
public interface AssetSink : AssetReadable {

    /**
     * Preflight quota check (FR-ASSET-010): called once before any write with
     * the number of bytes the download will need. Implementations that cannot
     * hold [sizeBytes] must throw [AssetSinkFullException]. The default does
     * nothing.
     */
    public suspend fun reserve(sizeBytes: Long) {}

    /**
     * Writes all of `source[sourceOffset until sourceOffset + length]` at
     * [position]. Writing the same range twice with the same bytes must be
     * harmless (idempotent redelivery).
     *
     * @throws AssetSinkFullException if there is no room for the bytes.
     */
    public suspend fun write(position: Long, source: ByteArray, sourceOffset: Int, length: Int)

    /**
     * Discards everything written so far. Called when a download fails
     * integrity verification or is cancelled, so a corrupted or abandoned
     * asset is never left exposed. Must be idempotent.
     */
    public suspend fun discard()
}

/** Thrown by an [AssetSink] that has no room for the bytes it was asked to hold. */
public class AssetSinkFullException(message: String) : RuntimeException(message)

/**
 * Thrown by an [AssetSource] (or by manifest preparation reading one) when the
 * source's content no longer matches what was recorded — it ended early, or a
 * chunk's bytes differ from the manifest's digest.
 */
public class AssetSourceChangedException(message: String) : RuntimeException(message)

/**
 * Reads exactly `length` bytes at [position] into [destination], looping over
 * short reads.
 *
 * @return the number of bytes actually read: `length`, or fewer only if the
 *   end of data was reached first.
 */
public suspend fun AssetReadable.readFully(
    position: Long,
    destination: ByteArray,
    destinationOffset: Int,
    length: Int,
): Int {
    var total = 0
    while (total < length) {
        val read = read(position + total, destination, destinationOffset + total, length - total)
        if (read < 0) break
        check(read > 0) { "AssetReadable.read must return -1 or at least 1 byte." }
        total += read
    }
    return total
}
