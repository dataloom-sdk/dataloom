package io.dataloom.api.time

/**
 * Contract for obtaining the current [DataLoomInstant] from a wall-clock
 * source.
 *
 * ## Purpose
 *
 * [DataLoomClock] provides a platform-independent abstraction over
 * wall-clock time. Runtime components use it to obtain the current instant
 * when recording timestamps for queue entries, leases, synchronization
 * events, synchronization results, and other persisted time values.
 *
 * ## Wall-clock semantics
 *
 * [DataLoomClock] represents wall-clock (real-world) time, not a monotonic
 * timer. It is not suitable for measuring elapsed execution time. Use a
 * dedicated monotonic-time abstraction if elapsed time measurement is
 * required.
 *
 * The clock is not guaranteed to be monotonic. Implementations may observe
 * clock skew, adjustments, or non-monotonic behavior depending on the
 * underlying platform or test configuration. Use [DataLoomMonotonicClock] to
 * measure elapsed time instead.
 *
 * ## Injection
 *
 * [DataLoomClock] must be injected into components that require the current
 * time. It must not be accessed through a global singleton. Injecting the
 * clock enables deterministic testing without platform clock dependencies.
 *
 * ## Model-construction boundary
 *
 * DataLoom models receive explicit [DataLoomInstant] values as constructor
 * parameters. Models must not call the clock themselves. The clock is
 * called only at the point in the runtime where an instant is needed, and
 * the resulting [DataLoomInstant] is then passed explicitly to the model.
 *
 * ## Thread safety
 *
 * Implementations shared across threads must be thread-safe. Callers that
 * share a [DataLoomClock] instance across threads are responsible for
 * selecting a thread-safe implementation.
 *
 * ## Expected uses
 *
 * ```
 * Queue entry enqueue time
 * Queue entry available time
 * Queue lease acquisition time
 * Queue lease expiration time
 * Synchronization-event occurrence time
 * Synchronization-result completion time
 * Expired-lease recovery time
 * ```
 *
 * ## Production implementations
 *
 * [SystemDataLoomClock] backs the JVM target (which also currently serves
 * native Android, since Android adapters consume this module's JVM target
 * directly). [AppleDataLoomClock] backs the `iosArm64`, `iosSimulatorArm64`,
 * and `iosX64` targets. An application may also supply its own
 * implementation.
 *
 * Test utilities in `dataloom-testing` provide [DataLoomClock] implementations
 * for deterministic testing.
 */
public interface DataLoomClock {

    /**
     * Returns the current instant from this clock.
     *
     * Each call may return a different value depending on the implementation.
     * Implementations backed by a real wall clock return a value that advances
     * over time. Test implementations may return fixed or controlled values.
     *
     * This method does not sleep, schedule work, or mutate runtime state.
     *
     * @return the current [DataLoomInstant] as observed by this clock.
     */
    public fun now(): DataLoomInstant
}
