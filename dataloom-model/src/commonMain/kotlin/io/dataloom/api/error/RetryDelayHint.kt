package io.dataloom.api.error

/**
 * Stable origin of a normalized retry delay hint.
 *
 * Protocol adapters must translate raw transport metadata such as an HTTP
 * `Retry-After` header into a non-negative millisecond delay before creating a
 * [RetryDelayHint]. The shared runtime never parses raw headers or exception
 * messages.
 *
 * Enum names are stable persistence and diagnostic values. Ordinals must not be
 * persisted.
 */
public enum class RetryDelayHintSource {
    /** The remote service or server supplied the retry timing guidance. */
    SERVER,

    /** A configured DataLoom provider supplied the retry timing guidance. */
    PROVIDER,
}

/**
 * Immutable, normalized minimum-delay guidance associated with a canonical
 * [DataLoomError].
 *
 * [delayMilliseconds] is an untrusted provider/server proposal until the retry
 * runtime applies its configured maximum. The model deliberately contains no
 * raw header text, absolute date, payload, credential, provider instance,
 * exception, or arbitrary metadata.
 *
 * @param delayMilliseconds non-negative proposed minimum delay in milliseconds.
 * @param source stable source classification for bounded diagnostics.
 */
public data class RetryDelayHint(
    public val delayMilliseconds: Long,
    public val source: RetryDelayHintSource,
) {
    init {
        require(delayMilliseconds >= 0L) {
            "RetryDelayHint delayMilliseconds must be zero or greater, but was " +
                "$delayMilliseconds."
        }
    }
}

/**
 * Optional capability for a [DataLoomError] that carries normalized retry timing
 * guidance.
 *
 * Error types opt in by implementing both [DataLoomError] and this interface.
 * Reading [retryDelayHint] must be deterministic, non-blocking, side-effect free,
 * and safe for concurrent access.
 */
public interface RetryDelayHintCarrier {
    /** Normalized hint that must still be bounded by runtime configuration. */
    public val retryDelayHint: RetryDelayHint
}
