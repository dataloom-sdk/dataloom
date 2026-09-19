package io.dataloom.api.security

/**
 * Contract for computing an unkeyed cryptographic digest over a byte array.
 *
 * ## Purpose
 *
 * [DataLoomDigestCalculator] is the integrity primitive future asset-transfer
 * work (chunk and whole-object integrity) is expected to consume: a manifest
 * records a [DataLoomDigest] per chunk, and a receiver recomputes and
 * compares. A digest over a bounded chunk needs only this one-shot method.
 * A *whole-object* digest over the asset's raw bytes (as
 * `AssetManifest.checksum` is defined) cannot be computed one-shot without
 * buffering the whole object, so it uses the incremental
 * [DataLoomIncrementalDigestCalculator] / [DataLoomDigestAccumulator]
 * capability instead.
 *
 * ## Shape
 *
 * This is a single-method, stateless, one-shot (non-streaming) contract,
 * mirroring [io.dataloom.api.random.DataLoomSecureRandom.nextBytes]'s
 * one-call shape. Incremental hashing is a deliberately separate, extending
 * contract ([DataLoomIncrementalDigestCalculator]) so simple one-shot
 * implementations and test fakes remain valid. The algorithm is a per-call
 * parameter, not a constructor parameter, so one injected instance serves
 * every algorithm an implementation supports.
 *
 * ## Injection
 *
 * Must be injected into components that need digest computation. It must
 * not be accessed through a global singleton or a companion-object field.
 *
 * ## Thread safety
 *
 * Implementations shared across threads must be thread-safe.
 */
public interface DataLoomDigestCalculator {

    /**
     * Computes the digest of [input] using [algorithm].
     *
     * This method does not read a clock, perform I/O, or mutate [input].
     *
     * @param algorithm the hash algorithm to compute.
     * @param input the complete content to hash.
     * @return a new [DataLoomDigest] of [input] under [algorithm].
     */
    public fun digest(algorithm: DigestAlgorithm, input: ByteArray): DataLoomDigest
}
