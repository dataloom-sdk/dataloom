package io.dataloom.api.time

/**
 * Production [DataLoomMonotonicClock] backed by [System.nanoTime].
 *
 * This is the default monotonic-time implementation for the JVM target,
 * which also serves native Android applications today because the current
 * Android adapter modules consume this module's JVM target directly. It has
 * no mutable state and may be shared across threads.
 *
 * [System.nanoTime] is specified to be monotonic within one JVM process; its
 * origin is arbitrary and not related to wall-clock time, matching
 * [DataLoomMonotonicReading] semantics exactly.
 */
public class SystemDataLoomMonotonicClock : DataLoomMonotonicClock {

    override fun mark(): DataLoomMonotonicReading {
        val nanoTime = System.nanoTime()
        // System.nanoTime() may itself be negative relative to an arbitrary
        // origin; only differences between readings are meaningful. Normalize
        // to a non-negative process-relative value so DataLoomMonotonicReading
        // construction never fails, while preserving elapsed-duration
        // correctness because the offset is constant for the process
        // lifetime.
        val normalized = nanoTime - baselineNanoTime
        return DataLoomMonotonicReading(nanoseconds = normalized)
    }

    private companion object {
        // Captured once at class initialization so every reading is relative
        // to a fixed, non-negative origin regardless of the raw
        // System.nanoTime() sign or magnitude.
        val baselineNanoTime: Long = System.nanoTime()
    }
}
