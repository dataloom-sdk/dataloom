package io.dataloom.runtime.facade

import io.dataloom.api.lifecycle.AppLifecycleState
import io.dataloom.api.scheduling.SchedulingDelay

/**
 * When and how much a lifecycle-triggered queue drain may do (ADR-0013, D23).
 *
 * The policy is a pure value: construction reads no clock, performs no I/O and
 * starts nothing.
 *
 * ## What a "transition" is
 *
 * [triggers] lists the states whose arrival triggers a drain. The state a
 * collection starts in is not a transition: an app that is already in the
 * background when [DataLoomLifecycleDrain.run] begins does not drain until it
 * changes state (the platform schedulers own the "woken in the background"
 * case). Only [AppLifecycleState.BACKGROUND] and
 * [AppLifecycleState.TERMINATING_SOON] trigger by default; add
 * [AppLifecycleState.FOREGROUND] to also drain on return to the foreground.
 *
 * ## Bounds
 *
 * - [minimumInterval] is measured from the start of the previous drain to the
 *   arrival of the next qualifying transition, on the runtime clock. A
 *   transition inside the interval is dropped, not deferred. A runtime clock
 *   that moves backwards never blocks a drain.
 * - [maxEntriesPerDrain] is the `maxEntries` of the single acquisition a drain
 *   performs, so one drain never processes more than this many entries.
 * - [leaseDuration] is how long the drain's queue lease lives. Keep it short:
 *   a drain the OS interrupts leaves its entries leased until the lease
 *   expires, and only expired-lease recovery returns them to the queue.
 *
 * @param triggers states that trigger a drain; must not be empty.
 * @param minimumInterval minimum time between the starts of two drains.
 * @param maxEntriesPerDrain entries one drain may acquire; at least one.
 * @param leaseDuration lifetime of the drain's queue lease; greater than zero.
 */
public class LifecycleDrainPolicy(
    triggers: Set<AppLifecycleState> = DEFAULT_TRIGGERS,
    public val minimumInterval: SchedulingDelay = SchedulingDelay(DEFAULT_MINIMUM_INTERVAL_MILLIS),
    public val maxEntriesPerDrain: Int = DEFAULT_MAX_ENTRIES_PER_DRAIN,
    public val leaseDuration: SchedulingDelay = SchedulingDelay(DEFAULT_LEASE_DURATION_MILLIS),
) {
    /** Immutable copy of the states that trigger a drain. */
    public val triggers: Set<AppLifecycleState> = triggers.toSet()

    init {
        require(this.triggers.isNotEmpty()) {
            "LifecycleDrainPolicy triggers must contain at least one state."
        }
        require(maxEntriesPerDrain >= 1) {
            "LifecycleDrainPolicy maxEntriesPerDrain must be at least one, but was $maxEntriesPerDrain."
        }
        require(leaseDuration.milliseconds > 0L) {
            "LifecycleDrainPolicy leaseDuration must be greater than zero."
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LifecycleDrainPolicy) return false
        return triggers == other.triggers &&
            minimumInterval == other.minimumInterval &&
            maxEntriesPerDrain == other.maxEntriesPerDrain &&
            leaseDuration == other.leaseDuration
    }

    override fun hashCode(): Int {
        var result = triggers.hashCode()
        result = 31 * result + minimumInterval.hashCode()
        result = 31 * result + maxEntriesPerDrain
        result = 31 * result + leaseDuration.hashCode()
        return result
    }

    override fun toString(): String =
        "LifecycleDrainPolicy(triggers=$triggers, minimumInterval=$minimumInterval, " +
            "maxEntriesPerDrain=$maxEntriesPerDrain, leaseDuration=$leaseDuration)"

    private companion object {
        val DEFAULT_TRIGGERS: Set<AppLifecycleState> =
            setOf(AppLifecycleState.BACKGROUND, AppLifecycleState.TERMINATING_SOON)
        const val DEFAULT_MINIMUM_INTERVAL_MILLIS: Long = 30_000L
        const val DEFAULT_MAX_ENTRIES_PER_DRAIN: Int = 25
        const val DEFAULT_LEASE_DURATION_MILLIS: Long = 60_000L
    }
}
