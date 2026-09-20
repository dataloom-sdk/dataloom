package io.dataloom.api.security

/**
 * Incremental (create-update-update-finalize) digest computation over a
 * stream of byte ranges, produced by
 * [DataLoomIncrementalDigestCalculator.newAccumulator].
 *
 * ## Purpose
 *
 * The one-shot [DataLoomDigestCalculator.digest] requires the complete input
 * as a single [ByteArray], so it cannot verify a whole multi-chunk object
 * without holding that object in memory. An accumulator hashes the same bytes
 * in bounded pieces: the digest [finish] returns is bit-for-bit identical to
 * the one-shot digest of the concatenation of every range passed to [update],
 * regardless of how that concatenation was split into calls.
 *
 * ## Lifecycle
 *
 * An accumulator is single-use and single-owner: not safe for concurrent use.
 * It is *open* until [finish] or [close] is called, after which it is
 * *closed*:
 *
 * - [update] and [finish] on a closed accumulator throw
 *   [IllegalStateException].
 * - [finish] closes the accumulator; it can therefore be called at most once.
 * - [close] is idempotent, and is a no-op after [finish].
 *
 * Callers that may abandon an accumulator mid-stream (cancellation, I/O
 * failure) should call [close] (or use [kotlin.use]) so platform resources
 * are released deterministically. On Apple targets the accumulator owns a
 * native hashing context, which [close] and [finish] free; a defensive
 * cleaner frees it if an abandoned accumulator is garbage collected, but
 * relying on that is not deterministic.
 *
 * There is deliberately no snapshot/restore of the running state: the
 * platform primitives (`MessageDigest`, CommonCrypto contexts) do not expose
 * a portable serialisable state, so a whole-object digest cannot be resumed
 * across a process restart. Callers that resume a partially processed object
 * re-hash the already-processed bytes from durable storage instead.
 */
public interface DataLoomDigestAccumulator : AutoCloseable {

    /** The algorithm this accumulator computes. */
    public val algorithm: DigestAlgorithm

    /**
     * Feeds `input[offset until offset + length]` into the running digest.
     *
     * @param input the buffer to read from. Not modified.
     * @param offset index of the first byte to hash. Must be non-negative.
     * @param length number of bytes to hash. Must be non-negative, with
     *   `offset + length <= input.size`. Zero is allowed and is a no-op.
     * @throws IllegalArgumentException if the range is invalid.
     * @throws IllegalStateException if this accumulator is closed.
     */
    public fun update(input: ByteArray, offset: Int = 0, length: Int = input.size - offset)

    /**
     * Completes the computation and returns the digest of every byte fed to
     * [update] so far, then closes this accumulator.
     *
     * @throws IllegalStateException if this accumulator is already closed.
     */
    public fun finish(): DataLoomDigest

    /** Releases platform resources without producing a digest. Idempotent. */
    override fun close()
}

/**
 * A [DataLoomDigestCalculator] that can additionally create
 * [DataLoomDigestAccumulator]s for bounded-memory hashing of large inputs.
 *
 * This is a separate, extending contract — rather than a new method on
 * [DataLoomDigestCalculator] — so existing one-shot implementations (test
 * fakes included) stay valid. The production JVM and Apple implementations
 * both implement this contract, and asset synchronization requires it for
 * whole-object verification (see `docs/adr/ADR-0006-asset-transfer-and-streaming-digest.md`).
 * This reverses the earlier "one-shot only" design of
 * [DataLoomDigestCalculator]; the one-shot method is unchanged.
 */
public interface DataLoomIncrementalDigestCalculator : DataLoomDigestCalculator {

    /**
     * Creates a new open accumulator computing [algorithm].
     *
     * The caller owns the returned accumulator and must
     * [finish][DataLoomDigestAccumulator.finish] or
     * [close][DataLoomDigestAccumulator.close] it.
     */
    public fun newAccumulator(algorithm: DigestAlgorithm): DataLoomDigestAccumulator
}

/**
 * Shared lifecycle and argument validation for platform accumulators, so each
 * platform implements only the three primitive operations.
 */
internal abstract class AbstractDigestAccumulator(
    final override val algorithm: DigestAlgorithm,
) : DataLoomDigestAccumulator {

    private var closed: Boolean = false

    protected abstract fun doUpdate(input: ByteArray, offset: Int, length: Int)

    /** Returns the raw digest bytes. Called at most once. */
    protected abstract fun doFinish(): ByteArray

    /** Frees platform resources. Called at most once. */
    protected abstract fun doRelease()

    final override fun update(input: ByteArray, offset: Int, length: Int) {
        check(!closed) { "DataLoomDigestAccumulator is closed." }
        require(offset >= 0 && length >= 0 && length <= input.size - offset) {
            "Invalid range: offset=$offset, length=$length, input.size=${input.size}."
        }
        if (length == 0) return
        doUpdate(input, offset, length)
    }

    final override fun finish(): DataLoomDigest {
        check(!closed) { "DataLoomDigestAccumulator is closed." }
        closed = true
        try {
            return DataLoomDigest(algorithm, doFinish())
        } finally {
            doRelease()
        }
    }

    final override fun close() {
        if (closed) return
        closed = true
        doRelease()
    }
}
