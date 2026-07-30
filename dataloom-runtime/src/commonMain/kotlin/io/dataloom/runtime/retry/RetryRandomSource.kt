package io.dataloom.runtime.retry

import io.dataloom.api.error.ErrorCode
import io.dataloom.api.identifier.RetryPolicyId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.retry.RetryOperation

/**
 * Immutable, payload-free input for one bounded retry-jitter sample.
 *
 * The request intentionally contains only stable identifiers and the inclusive
 * upper bound required by the jitter algorithm. It never contains application
 * payloads, credentials, tokens, provider instances, exception messages, or
 * arbitrary metadata.
 *
 * @param policyId retry policy requesting the sample.
 * @param workflowId workflow being retried.
 * @param sessionId synchronization session being retried.
 * @param operation logical retry operation.
 * @param errorCode canonical sanitized error code.
 * @param attempt current retry attempt.
 * @param maximumInclusive largest permitted sample value. Must be non-negative.
 */
public data class RetryRandomRequest(
    /** Retry policy requesting the sample. */
    public val policyId: RetryPolicyId,

    /** Workflow being retried. */
    public val workflowId: WorkflowId,

    /** Synchronization session being retried. */
    public val sessionId: SynchronizationSessionId,

    /** Logical operation being retried. */
    public val operation: RetryOperation,

    /** Canonical sanitized error code. */
    public val errorCode: ErrorCode,

    /** Current retry attempt. */
    public val attempt: RetryAttempt,

    /** Inclusive non-negative upper bound for the returned sample. */
    public val maximumInclusive: Long,
) {
    init {
        require(maximumInclusive >= 0L) {
            "maximumInclusive must be zero or greater, but was $maximumInclusive."
        }
    }
}

/**
 * Injected source of deterministic, bounded retry-jitter samples.
 *
 * Implementations must return the same value for equal [RetryRandomRequest]
 * values, must return a value in `0..request.maximumInclusive`, and must remain
 * non-blocking, side-effect free, and safe for concurrent use. Implementations
 * must not perform I/O, read application payloads, log request identifiers, or
 * use this boundary for cryptographic key generation.
 *
 * DataLoom validates the returned range before using the sample. A contract
 * violation fails evaluation rather than silently clamping a faulty source.
 */
public fun interface RetryRandomSource {

    /**
     * Returns one deterministic value in `0..request.maximumInclusive`.
     *
     * @param request immutable payload-free sample input.
     * @return a deterministic value inside the inclusive requested range.
     */
    public fun sample(request: RetryRandomRequest): Long
}

/**
 * Stateless seeded [RetryRandomSource] for reproducible common-code jitter.
 *
 * The same [seed] and equal [RetryRandomRequest] always produce the same result
 * across JVM, Android, and Kotlin/Native. The implementation hashes only the
 * request's stable identifiers, error code, attempt, and bound; it never reads a
 * clock or global random state. This makes retry timing reproducible after
 * restart when the same seed and durable request identity are restored.
 *
 * The source is thread-safe because it has no mutable state. It is not a
 * cryptographic random-number generator and [seed] must not contain secret key
 * material.
 *
 * @param seed application- or policy-supplied non-secret deterministic seed.
 */
public class SeededRetryRandomSource(
    /** Non-secret deterministic seed used for every sample. */
    public val seed: Long,
) : RetryRandomSource {

    /**
     * Produces a stable bounded sample using versioned FNV-1a input hashing,
     * SplitMix64 finalization, and rejection sampling.
     */
    override public fun sample(request: RetryRandomRequest): Long {
        if (request.maximumInclusive == 0L) {
            return 0L
        }

        var hash = FNV_OFFSET_BASIS
        hash = hashString(hash, RANDOM_DOMAIN)
        hash = hashLong(hash, seed.toULong())
        hash = hashString(hash, request.policyId.value)
        hash = hashString(hash, request.workflowId.value)
        hash = hashString(hash, request.sessionId.value)
        hash = hashString(hash, request.operation.value)
        hash = hashString(hash, request.errorCode.value)
        hash = hashLong(hash, request.attempt.number.toULong())
        hash = hashLong(hash, request.maximumInclusive.toULong())

        return boundedSample(
            initial = hash,
            maximumInclusive = request.maximumInclusive,
        )
    }
}

private const val RANDOM_DOMAIN: String = "io.dataloom.retry.jitter.v1"
private const val FNV_OFFSET_BASIS: ULong = 0xCBF29CE484222325uL
private const val FNV_PRIME: ULong = 0x100000001B3uL
private const val GOLDEN_GAMMA: ULong = 0x9E3779B97F4A7C15uL
private const val MIX_MULTIPLIER_ONE: ULong = 0xBF58476D1CE4E5B9uL
private const val MIX_MULTIPLIER_TWO: ULong = 0x94D049BB133111EBuL

private fun hashString(initial: ULong, value: String): ULong {
    var hash = hashLong(initial, value.length.toULong())
    value.forEach { character ->
        hash = hashByte(hash, character.code and 0xFF)
        hash = hashByte(hash, character.code ushr 8)
    }
    return hash
}

private fun hashLong(initial: ULong, value: ULong): ULong {
    var hash = initial
    repeat(Long.SIZE_BYTES) { byteIndex ->
        val byte = ((value shr (byteIndex * Byte.SIZE_BITS)) and 0xFFuL).toInt()
        hash = hashByte(hash, byte)
    }
    return hash
}

private fun hashByte(initial: ULong, value: Int): ULong =
    (initial xor value.toULong()) * FNV_PRIME

private fun boundedSample(
    initial: ULong,
    maximumInclusive: Long,
): Long {
    val bound = maximumInclusive.toULong() + 1uL
    val rejectionThreshold = (0uL - bound) % bound
    var sequence = 0uL

    while (true) {
        val candidate = avalanche(initial + (sequence * GOLDEN_GAMMA))
        if (candidate >= rejectionThreshold) {
            return (candidate % bound).toLong()
        }
        sequence++
    }
}

private fun avalanche(value: ULong): ULong {
    var mixed = value
    mixed = (mixed xor (mixed shr 30)) * MIX_MULTIPLIER_ONE
    mixed = (mixed xor (mixed shr 27)) * MIX_MULTIPLIER_TWO
    return mixed xor (mixed shr 31)
}
