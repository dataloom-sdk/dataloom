package io.dataloom.api.time

/**
 * Contract for obtaining a [DataLoomMonotonicReading] suitable for measuring
 * elapsed execution time.
 *
 * ## Purpose
 *
 * [DataLoomMonotonicClock] is the dedicated monotonic-time abstraction
 * referenced by [DataLoomClock]. Runtime components use it when they need to
 * measure elapsed duration between two code points, such as an execution
 * timeout budget or a benchmark. It must not be used to produce a value
 * suitable for a persisted timestamp; use [DataLoomClock] for that.
 *
 * ## Monotonic semantics
 *
 * [DataLoomMonotonicClock] does not represent wall-clock (real-world) time.
 * Its readings have no meaning outside elapsed-duration comparisons taken
 * from the same clock instance. A correct implementation never observes
 * clock adjustments, time-zone changes, or NTP corrections; two readings
 * from the same instance never move backward relative to each other.
 *
 * ## Injection
 *
 * [DataLoomMonotonicClock] must be injected into components that measure
 * elapsed time. It must not be accessed through a global singleton. Injecting
 * the clock enables deterministic testing without a platform timer
 * dependency.
 *
 * ## Thread safety
 *
 * Implementations shared across threads must be thread-safe.
 *
 * ## Expected uses
 *
 * ```
 * Execution timeout budgets
 * Retry/circuit elapsed-time measurement
 * Benchmark and diagnostic duration measurement
 * ```
 */
public interface DataLoomMonotonicClock {

    /**
     * Returns the current [DataLoomMonotonicReading] from this clock.
     *
     * Each call may return a different value. This method does not sleep,
     * schedule work, or mutate runtime state.
     *
     * @return the current [DataLoomMonotonicReading] as observed by this
     *   clock.
     */
    public fun mark(): DataLoomMonotonicReading
}
