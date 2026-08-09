package io.dataloom.api.time

/**
 * Immutable, platform-independent monotonic time reading expressed as
 * nanoseconds since an arbitrary, implementation-defined origin.
 *
 * [DataLoomMonotonicReading] is a pure value type. It does not read the
 * monotonic clock, sleep, delay, or schedule work. [DataLoomMonotonicClock]
 * supplies the [nanoseconds] value.
 *
 * ## Constraints
 *
 * - [nanoseconds] must be zero or greater.
 * - Negative values are rejected at construction.
 * - The origin is arbitrary and implementation-defined. It is not comparable
 *   across different [DataLoomMonotonicClock] instances, processes, or
 *   platforms, and it does not represent wall-clock time.
 * - Construction does not access the system clock.
 *
 * ## Platform independence
 *
 * [DataLoomMonotonicReading] depends only on the Kotlin standard library. It
 * is safe for use in Kotlin Multiplatform common code.
 *
 * ## Equality
 *
 * Equality compares [nanoseconds] by value.
 *
 * @param nanoseconds non-negative nanoseconds since an arbitrary,
 *   implementation-defined origin.
 */
public class DataLoomMonotonicReading(
    /** Non-negative nanoseconds since an arbitrary, implementation-defined origin. */
    public val nanoseconds: Long,
) {
    init {
        require(nanoseconds >= 0L) {
            "DataLoomMonotonicReading nanoseconds must be zero or greater, but was $nanoseconds."
        }
    }

    /**
     * Returns the elapsed nanoseconds between [earlier] and this reading.
     *
     * Both readings must originate from the same [DataLoomMonotonicClock]
     * instance. The result fails closed rather than returning a misleading
     * negative duration when [earlier] is not actually earlier than this
     * reading.
     *
     * @param earlier a reading taken before this one, from the same clock.
     * @return the non-negative elapsed nanoseconds between the two readings.
     * @throws IllegalArgumentException if [earlier] is later than this
     *   reading.
     */
    public fun elapsedNanosecondsSince(earlier: DataLoomMonotonicReading): Long {
        val delta = nanoseconds - earlier.nanoseconds
        require(delta >= 0L) {
            "DataLoomMonotonicReading elapsed duration must be zero or greater, but was $delta. " +
                "Both readings must originate from the same DataLoomMonotonicClock instance."
        }
        return delta
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DataLoomMonotonicReading) return false
        return nanoseconds == other.nanoseconds
    }

    override fun hashCode(): Int = nanoseconds.hashCode()

    override fun toString(): String = "DataLoomMonotonicReading(nanoseconds=$nanoseconds)"
}
