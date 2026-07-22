package io.dataloom.api.time

/**
 * Immutable, platform-independent representation of an absolute point in time
 * expressed as milliseconds since the Unix epoch (1970-01-01T00:00:00Z).
 *
 * [DataLoomInstant] is a pure value type. It does not read the system clock,
 * represent a duration, sleep, delay, or schedule work. The caller is
 * responsible for supplying the epoch-millisecond value from a clock
 * abstraction or host integration.
 *
 * ## Constraints
 *
 * - [epochMilliseconds] must be zero or greater.
 * - Negative values are rejected at construction.
 * - Construction does not access the system clock.
 *
 * ## Platform independence
 *
 * [DataLoomInstant] depends only on the Kotlin standard library. It does not
 * depend on `java.time`, `kotlinx-datetime`, or any other third-party
 * date-time library, and is safe for use in Kotlin Multiplatform common code.
 *
 * ## Equality
 *
 * Equality compares [epochMilliseconds] by value.
 *
 * @param epochMilliseconds non-negative milliseconds since the Unix epoch
 *   (1970-01-01T00:00:00Z). Zero represents the epoch itself.
 */
public class DataLoomInstant(
    /** Non-negative milliseconds since the Unix epoch (1970-01-01T00:00:00Z). */
    public val epochMilliseconds: Long,
) {
    init {
        require(epochMilliseconds >= 0L) {
            "DataLoomInstant epochMilliseconds must be zero or greater, but was $epochMilliseconds."
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DataLoomInstant) return false
        return epochMilliseconds == other.epochMilliseconds
    }

    override fun hashCode(): Int = epochMilliseconds.hashCode()

    override fun toString(): String = "DataLoomInstant(epochMilliseconds=$epochMilliseconds)"
}
