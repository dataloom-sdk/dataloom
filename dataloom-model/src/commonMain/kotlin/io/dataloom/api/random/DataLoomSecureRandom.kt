package io.dataloom.api.random

/**
 * Contract for obtaining cryptographically secure random bytes.
 *
 * ## Purpose
 *
 * [DataLoomSecureRandom] is the dedicated secure-randomness boundary for
 * components that need unpredictable values suitable for security-sensitive
 * use — for example, future nonce, salt, or token generation. It is distinct
 * from `io.dataloom.runtime.retry.RetryRandomSource`, which is intentionally
 * deterministic and reproducible for retry-jitter scheduling and explicitly
 * documents that it must not be used for security purposes. Use
 * [DataLoomSecureRandom] whenever unpredictability matters; use the retry
 * jitter source whenever reproducibility matters. The two must never be
 * substituted for each other.
 *
 * ## Security semantics
 *
 * Implementations must be backed by a cryptographically secure
 * pseudorandom number generator (CSPRNG) supplied by the host platform, not a
 * general-purpose deterministic generator. Returned bytes must not be
 * reproducible from the request alone, must not depend on a caller-supplied
 * seed, and must not be logged, persisted in diagnostics, or included in
 * `toString()` output by any caller.
 *
 * ## Injection
 *
 * [DataLoomSecureRandom] must be injected into components that need secure
 * randomness. It must not be accessed through a global singleton or a
 * companion-object field.
 *
 * ## Thread safety
 *
 * Implementations shared across threads must be thread-safe.
 */
public interface DataLoomSecureRandom {

    /**
     * Returns a new byte array of exactly [byteCount] cryptographically
     * secure random bytes.
     *
     * This method does not read a clock, block on entropy collection beyond
     * what the underlying platform CSPRNG requires, or mutate caller-visible
     * state beyond returning the new array.
     *
     * @param byteCount the number of random bytes to return. Must be greater
     *   than zero.
     * @return a new [ByteArray] of length [byteCount] containing
     *   cryptographically secure random bytes.
     * @throws IllegalArgumentException if [byteCount] is zero or negative.
     */
    public fun nextBytes(byteCount: Int): ByteArray
}
